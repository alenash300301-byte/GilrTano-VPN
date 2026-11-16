package com.gilrtano.vpn

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Requests a fresh AmneziaWG profile from the Cyberportal generator.
 *
 * Cyberportal exposes GET /api/warp and returns the generated profile as
 * content.importText. It is not the unrelated Nellimonix POST /api/generate API.
 */
object WarpGeneratorClient {
    private val API_URLS = listOf(
        "https://warp-gen.cyb-portal.org/api/warp",
        "https://warp-gen-vip.vercel.app/api/warp",
        "https://portalwg.vercel.app/api/warp"
    )

    fun generateAmneziaProfile(): String {
        val failures = mutableListOf<String>()
        for (apiUrl in API_URLS) {
            try {
                return requestConfig(apiUrl)
            } catch (e: Exception) {
                val host = runCatching { URL(apiUrl).host }.getOrDefault(apiUrl)
                failures += "$host: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        error("Все генераторы WARP недоступны. " + failures.joinToString(" | "))
    }

    private fun requestConfig(apiUrl: String): String {
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            // Cyberportal's documented client integration uses GET /api/warp.
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WARP-Generator/2.0")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code" + response.take(180).takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
            }
            val json = runCatching { JSONObject(response) }.getOrElse {
                error("сервер вернул не JSON (HTTP $code)")
            }
            val content = json.optJSONObject("content")
                ?: error(json.optString("message").ifBlank { "в ответе отсутствует content" })

            // Cyberportal returns a ready-to-import configuration in importText.
            val importText = content.optString("importText").trim()
            if (importText.isNotBlank()) return validateConfig(importText)

            // Keep compatibility with deployments that expose the generic
            // base64 response envelope, but prefer Cyberportal's native field.
            val encoded = content.optString("configBase64")
            if (encoded.isNotBlank()) {
                val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                return validateConfig(decoded)
            }
            error("в ответе отсутствует content.importText")
        } finally {
            connection.disconnect()
        }
    }

    private fun validateConfig(raw: String): String {
        val config = raw.trim()
        require(config.contains("[Interface]") && config.contains("[Peer]")) {
            "генератор вернул не WireGuard/AmneziaWG конфигурацию"
        }
        require(Regex("(?im)^\\s*PrivateKey\\s*=").containsMatchIn(config)) {
            "в конфигурации отсутствует PrivateKey"
        }
        require(Regex("(?im)^\\s*PublicKey\\s*=").containsMatchIn(config)) {
            "в конфигурации отсутствует PublicKey"
        }
        require(Regex("(?im)^\\s*Endpoint\\s*=").containsMatchIn(config)) {
            "в конфигурации отсутствует Endpoint"
        }
        // Reserved is optional in Cyberportal's importText response. If supplied,
        // the importer and AWG engine preserve it; do not reject valid profiles
        // merely because this generator version omitted the field.
        return config
    }
}
