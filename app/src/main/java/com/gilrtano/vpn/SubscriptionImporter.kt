package com.gilrtano.vpn

import android.net.Uri
import android.util.Base64
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ImportedNode(val name: String, val uri: String)

object SubscriptionImporter {
    private const val MAX_NODES = 600
    private const val MAX_BYTES = 25 * 1024 * 1024

    fun load(source: String): List<ImportedNode> {
        val trimmed = source.trim()
        if (trimmed.contains("[Interface]", ignoreCase = true) && trimmed.contains("[Peer]", ignoreCase = true)) {
            val profile = PortalGateImporter.parseConf(trimmed, "AmneziaWG.conf")
            val encoded = Base64.encodeToString(trimmed.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            return listOf(ImportedNode(profile.name, "awgconf://$encoded"))
        }
        val content = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> download(trimmed)
            trimmed.startsWith("vless://") || trimmed.startsWith("vmess://") || trimmed.startsWith("ss://") ||
                trimmed.startsWith("hy2://") || trimmed.startsWith("hysteria2://") ||
                trimmed.contains("://") || trimmed.contains("proxies:") || trimmed.startsWith("#profile-") -> trimmed
            trimmed.lineSequence().any { it.trim().startsWith("#profile-") || it.trim().startsWith("vless://") ||
                it.trim().startsWith("vmess://") || it.trim().startsWith("ss://") } -> trimmed
            else -> error("Вставь ссылку подписки, текст Aetris или Clash YAML")
        }
        return parse(content)
    }

    private fun download(source: String): String {
        var lastError: Exception? = null
        // Some subscription hosts close the first TLS/socket attempt before sending
        // headers. Retry with a fresh connection instead of failing the import.
        repeat(3) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(source).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 25000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 GilrTanoVPN/0.9.3")
                    setRequestProperty("Accept", "text/plain, application/yaml, application/octet-stream, */*")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Connection", "close")
                    instanceFollowRedirects = true
                    useCaches = false
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = runCatching {
                        (connection.errorStream ?: connection.inputStream)
                            .bufferedReader(Charsets.UTF_8).use { it.readText().take(300) }
                    }.getOrNull().orEmpty()
                    throw java.io.IOException(
                        if (detail.isBlank()) "Сервер подписки ответил HTTP $code"
                        else "Сервер подписки ответил HTTP $code: $detail"
                    )
                }
                val bytes = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BYTES) { "Подписка слишком большая (лимит 25 МБ)" }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                val content = String(bytes, Charsets.UTF_8)
                    .removePrefix("\uFEFF")
                    .trim()
                require(content.isNotBlank()) { "Сервер вернул пустую подписку" }
                return content
            } catch (e: Exception) {
                lastError = e
                // HTTP client errors and validation errors will not improve on retry.
                if (e is IllegalArgumentException || (e is java.io.IOException &&
                    e.message?.contains("HTTP ") == true)) throw e
                if (attempt < 2) Thread.sleep(700L * (attempt + 1))
            } finally {
                connection?.disconnect()
            }
        }
        throw java.io.IOException(
            "Не удалось загрузить подписку после 3 попыток: ${lastError?.message ?: "соединение закрыто"}",
            lastError
        )
    }

    fun parse(raw: String): List<ImportedNode> {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.startsWith("proxies:")) return parseClash(text)
        val supported = Regex("^(vless|vmess|ss|hy2|hysteria2)://", RegexOption.IGNORE_CASE)
        val hasLinks = text.lineSequence().any { supported.containsMatchIn(it.trim()) }
        val decoded = if (hasLinks) text else decodeSubscription(text)
        val seen = LinkedHashMap<String, ImportedNode>()
        decoded.lineSequence().map { it.trim() }
            .filter { supported.containsMatchIn(it) }
            .take(MAX_NODES * 4)
            .forEach { link ->
                runCatching {
                    val scheme = link.substringBefore("://").lowercase()
                    val uri = Uri.parse(link)
                    if (scheme != "vmess") require(!uri.host.isNullOrBlank())
                    val label = uri.fragment?.takeIf { it.isNotBlank() }
                        ?: uri.host?.takeIf { it.isNotBlank() }
                        ?: when (scheme) { "ss" -> "Shadowsocks"; "vmess" -> "VMess"; else -> scheme.uppercase() }
                    seen.putIfAbsent(link, ImportedNode(label, link))
                }
            }
        return seen.values.take(MAX_NODES)
    }

    private fun decodeSubscription(value: String): String {
        val compact = value.replace(Regex("\\s+"), "")
        val candidates = listOf(Base64.DEFAULT, Base64.NO_WRAP, Base64.URL_SAFE or Base64.NO_WRAP)
        for (flags in candidates) {
            val decoded = runCatching {
                String(Base64.decode(compact, flags), Charsets.UTF_8)
            }.getOrNull()
            if (decoded?.contains("://") == true) return decoded
        }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseClash(text: String): List<ImportedNode> {
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        val root = yaml.load<Any?>(text) as? Map<String, Any?>
            ?: error("Не удалось прочитать YAML/Clash конфигурацию")
        val proxies = root["proxies"] as? List<*> ?: error("В YAML не найден список proxies")
        val result = LinkedHashMap<String, ImportedNode>()
        for (item in proxies) {
            val p = item as? Map<String, Any?> ?: continue
            val type = p["type"]?.toString()?.lowercase()
            if (type == "hysteria2") {
                val name = p["name"]?.toString()?.takeIf { it.isNotBlank() } ?: "Hysteria2"
                val server = p["server"]?.toString() ?: continue
                val port = p["port"]?.toString()?.toIntOrNull() ?: continue
                val password = p["password"]?.toString() ?: continue
                val query = Uri.Builder()
                p["sni"]?.toString()?.takeIf { it.isNotBlank() }?.let { query.appendQueryParameter("sni", it) }
                p["skip-cert-verify"]?.toString()?.toBooleanStrictOrNull()?.let { query.appendQueryParameter("insecure", it.toString()) }
                (p["alpn"] as? List<*>)?.mapNotNull { it?.toString() }?.takeIf { it.isNotEmpty() }
                    ?.let { query.appendQueryParameter("alpn", it.joinToString(",")) }
                p["client-fingerprint"]?.toString()?.takeIf { it.isNotBlank() }?.let { query.appendQueryParameter("fp", it) }
                val link = Uri.Builder().scheme("hy2").encodedAuthority("${Uri.encode(password)}@${server}:$port")
                    .encodedQuery(query.build().encodedQuery).fragment(name).build().toString()
                result.putIfAbsent(link, ImportedNode(name, link))
                if (result.size >= MAX_NODES) break
                continue
            }
            if (type != "vless") continue
            val name = p["name"]?.toString()?.takeIf { it.isNotBlank() } ?: "VLESS"
            val server = p["server"]?.toString() ?: continue
            val port = p["port"]?.toString()?.toIntOrNull() ?: continue
            val uuid = p["uuid"]?.toString() ?: continue
            val network = p["network"]?.toString() ?: "tcp"
            val reality = p["reality-opts"] as? Map<String, Any?>
            val tls = p["tls"]?.toString()?.toBooleanStrictOrNull() ?: (reality != null)
            val security = when {
                reality != null -> "reality"
                tls -> "tls"
                else -> "none"
            }
            val query = Uri.Builder()
                .appendQueryParameter("encryption", "none")
                .appendQueryParameter("security", security)
                .appendQueryParameter("type", network)
            val sni = p["servername"]?.toString()
            if (!sni.isNullOrBlank()) query.appendQueryParameter("sni", sni)
            val fp = p["client-fingerprint"]?.toString()
            if (!fp.isNullOrBlank()) query.appendQueryParameter("fp", fp)
            val flow = p["flow"]?.toString()
            if (!flow.isNullOrBlank()) query.appendQueryParameter("flow", flow)
            if (reality != null) {
                reality["public-key"]?.toString()?.let { query.appendQueryParameter("pbk", it) }
                reality["short-id"]?.toString()?.let { query.appendQueryParameter("sid", it) }
            }
            if (network == "grpc") {
                val grpc = p["grpc-opts"] as? Map<String, Any?>
                grpc?.get("grpc-service-name")?.toString()?.let {
                    query.appendQueryParameter("serviceName", it)
                }
            } else if (network == "ws") {
                val ws = p["ws-opts"] as? Map<String, Any?>
                ws?.get("path")?.toString()?.let { query.appendQueryParameter("path", it) }
            }
            val link = "vless://$uuid@$server:$port?${query.build().encodedQuery}#${URLEncoder.encode(name, "UTF-8")}"
            result.putIfAbsent(link, ImportedNode(name, link))
            if (result.size >= MAX_NODES) break
        }
        return result.values.toList()
    }
}