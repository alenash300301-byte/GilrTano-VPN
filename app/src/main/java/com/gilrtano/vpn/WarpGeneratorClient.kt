package com.gilrtano.vpn

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Requests a fresh AmneziaWG profile from Cyberportal.
 * The primary host may temporarily return 502 during overload, so try the
 * project's published mirrors before reporting generator failure.
 */
object WarpGeneratorClient {
    // Use the generator and mirror currently published by Cyberportal first.
    // The old custom cyb-portal hostname is kept last as a compatibility fallback.
    private val API_URLS = listOf(
        "https://warp-gen-vip.vercel.app/api/generate",
        "https://portalwg.vercel.app/api/generate",
        "https://warp-gen.cyb-portal.org/api/generate"
    )

    // Matches the WARP UDP endpoint observed in the user's working PORTAL profile.
    // Cloudflare's stable endpoint hostname avoids pinning a single anycast IP.
    // Port 2408 is one of the currently documented WARP UDP ports.
    private const val WARP_ENDPOINT = "engage.cloudflareclient.com:2408"

    fun generateAmneziaProfile(): String {
        val payload = JSONObject()
            .put("selectedServices", org.json.JSONArray())
            .put("siteMode", "all")
            .put("deviceType", "awg15")
            .put("endpoint", WARP_ENDPOINT)

        val failures = mutableListOf<String>()
        for (apiUrl in API_URLS) {
            try {
                val config = requestConfig(apiUrl, payload)
                return config
            } catch (e: Exception) {
                val host = runCatching { URL(apiUrl).host }.getOrDefault(apiUrl)
                failures += "$host: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        error("Все генераторы WARP недоступны. " + failures.joinToString(" | "))
    }

    private fun requestConfig(apiUrl: String, payload: JSONObject): String {
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // Keep each mirror bounded so a dead first host cannot leave the UI
            // stuck for several minutes while the remaining mirrors are healthy.
            connectTimeout = 8_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "GilrTanoVPN/1.1 Android")
        }
        try {
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code" + response.take(180).takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
            }
            val json = runCatching { JSONObject(response) }.getOrElse {
                error("сервер вернул не JSON (HTTP $code)")
            }
            if (!json.optBoolean("success", false)) {
                error(json.optString("message").ifBlank { "сервер отклонил генерацию" })
            }
            val content = json.optJSONObject("content")
                ?: error("в ответе отсутствует content")
            val encoded = content.optString("configBase64")
            if (encoded.isBlank()) error("сервер не вернул configBase64")
            val config = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            require(config.contains("[Interface]") && config.contains("[Peer]")) {
                "получен неподдерживаемый формат конфигурации"
            }
            val hasCloudflareClientId = config.lineSequence().any { line ->
                line.trim().removePrefix("#").trim()
                    .startsWith("Reserved", ignoreCase = true) && line.contains('=')
            }
            require(hasCloudflareClientId) {
                "генератор не вернул Cloudflare Client ID (Reserved): такой WARP-профиль может поднять handshake, но не пропускать трафик"
            }
            return config
        } finally {
            connection.disconnect()
        }
    }
}
