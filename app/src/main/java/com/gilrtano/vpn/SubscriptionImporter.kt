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
    private const val MAX_NODES = 150
    private const val MAX_BYTES = 5 * 1024 * 1024

    fun load(source: String): List<ImportedNode> {
        val trimmed = source.trim()
        val content = when {
            trimmed.startsWith("vless://") -> trimmed
            trimmed.contains("proxies:") -> trimmed
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> download(trimmed)
            else -> error("Ожидается VLESS-ссылка, HTTPS-подписка или Clash YAML с блоком proxies:")
        }
        return parse(content)
    }

    private fun download(source: String): String {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 25000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "GilrTanoVPN/0.7.7")
            setRequestProperty("Accept", "text/plain, application/yaml, */*")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "Сервер подписки ответил HTTP $code" }
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "Подписка слишком большая (лимит 5 МБ)" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8).trim()
        } finally {
            connection.disconnect()
        }
    }

    fun parse(raw: String): List<ImportedNode> {
        val text = raw.trim()
        if (text.startsWith("vless://")) return listOf(ImportedNode("VLESS · сервер", text))
        if (text.contains("proxies:")) return parseClash(text)

        val decoded = if (text.contains("://")) text else decodeSubscription(text)
        val seen = LinkedHashMap<String, ImportedNode>()
        decoded.lineSequence().map { it.trim() }
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .take(MAX_NODES * 4)
            .forEach { link ->
                runCatching {
                    val uri = Uri.parse(link)
                    require(uri.scheme == "vless" && !uri.host.isNullOrBlank())
                    val label = uri.fragment?.takeIf { it.isNotBlank() } ?: uri.host.orEmpty()
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
            if (p["type"]?.toString()?.lowercase() != "vless") continue
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
