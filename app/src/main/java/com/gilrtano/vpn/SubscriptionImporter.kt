package com.gilrtano.vpn

import android.net.Uri
import android.util.Base64
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.HttpURLConnection
import java.net.URL

data class ImportedNode(
    val name: String,
    val uri: String,
    /** Full Clash/Mihomo YAML is kept intact and passed to the Meow engine. */
    val yamlConfig: String? = null,
)

object SubscriptionImporter {
    private const val MAX_NODES = 600
    private const val MAX_BYTES = 25 * 1024 * 1024

    fun load(source: String): List<ImportedNode> {
        val trimmed = source.trim()
        val content = when {
            trimmed.startsWith("vless://", ignoreCase = true) -> trimmed
            looksLikeClashYaml(trimmed) -> trimmed
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> download(trimmed)
            else -> error("Ожидается VLESS-ссылка, HTTPS-подписка или Clash/Mihomo YAML")
        }
        return parse(content)
    }

    private fun looksLikeClashYaml(value: String): Boolean =
        Regex("(?m)^(proxies|proxy-groups|proxy-providers|rules)\\s*:").containsMatchIn(value)

    private fun download(source: String): String {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(source).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 25000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 GilrTanoVPN/0.10.0")
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
                val content = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").trim()
                require(content.isNotBlank()) { "Сервер вернул пустую подписку" }
                return content
            } catch (e: Exception) {
                lastError = e
                if (e is IllegalArgumentException || (e is java.io.IOException && e.message?.contains("HTTP ") == true)) {
                    throw e
                }
                if (attempt < 2) Thread.sleep(700L * (attempt + 1))
            } finally {
                connection?.disconnect()
            }
        }
        throw java.io.IOException(
            "Не удалось загрузить подписку после 3 попыток: ${lastError?.message ?: "соединение закрыто"}",
            lastError,
        )
    }

    fun parse(raw: String): List<ImportedNode> {
        val text = raw.trim()
        if (text.startsWith("vless://", ignoreCase = true)) {
            return listOf(ImportedNode("VLESS · сервер", text))
        }
        if (looksLikeClashYaml(text)) return parseClashProfile(text)

        val decoded = if (text.contains("://")) text else decodeSubscription(text)
        if (looksLikeClashYaml(decoded)) return parseClashProfile(decoded)
        val seen = LinkedHashMap<String, ImportedNode>()
        decoded.lineSequence().map { it.trim() }
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .take(MAX_NODES * 4)
            .forEach { link ->
                runCatching {
                    val uri = Uri.parse(link)
                    require(uri.scheme.equals("vless", ignoreCase = true) && !uri.host.isNullOrBlank())
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
            val decoded = runCatching { String(Base64.decode(compact, flags), Charsets.UTF_8) }.getOrNull()
            if (decoded?.contains("://") == true || decoded?.let(::looksLikeClashYaml) == true) return decoded
        }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseClashProfile(text: String): List<ImportedNode> {
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        val root = yaml.load<Any?>(text) as? Map<String, Any?>
            ?: error("Не удалось прочитать Clash/Mihomo YAML")
        val proxies = root["proxies"] as? List<*>
        val providers = root["proxy-providers"] as? Map<*, *>
        require(!proxies.isNullOrEmpty() || !providers.isNullOrEmpty()) {
            "В YAML не найдены proxies или proxy-providers"
        }
        val firstProxyName = proxies?.firstNotNullOfOrNull { item ->
            (item as? Map<*, *>)?.get("name")?.toString()?.takeIf { it.isNotBlank() }
        }
        val name = root["profile-name"]?.toString()?.takeIf { it.isNotBlank() }
            ?: root["name"]?.toString()?.takeIf { it.isNotBlank() }
            ?: firstProxyName?.let { "Clash · $it" }
            ?: "Clash/Mihomo профиль"
        // Keep the complete profile: proxy groups, all protocols, rules,
        // providers and DNS are interpreted by Meow, not flattened to VLESS.
        return listOf(ImportedNode(name, "", text))
    }
}
