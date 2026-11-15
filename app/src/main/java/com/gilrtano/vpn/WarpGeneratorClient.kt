package com.gilrtano.vpn

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the public Cyberportal WARP generator API.
 * The request schema mirrors the generator's own web client.
 * Generated private configuration is held in memory only.
 */
object WarpGeneratorClient {
    private const val API_URL = "https://warp-gen.cyb-portal.org/api/warp"

    fun generateAmneziaProfile(): String {
        val payload = JSONObject()
            .put("selectedServices", org.json.JSONArray())
            .put("siteMode", "all")
            .put("deviceType", "awg15")
            .put("endpoint", "162.159.195.1:500")
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 75_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "GilrTanoVPN/1.0 Android")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(response) }.getOrElse {
                error("Генератор вернул некорректный ответ (HTTP $code)")
            }
            if (code !in 200..299 || !json.optBoolean("success", false)) {
                error(json.optString("message").ifBlank { "Генератор недоступен (HTTP $code)" })
            }
            val content = json.optJSONObject("content")
                ?: error("В ответе генератора отсутствует content")
            val encoded = content.optString("configBase64")
            if (encoded.isBlank()) error("Генератор не вернул конфигурацию")
            val config = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            require(config.contains("[Interface]") && config.contains("[Peer]")) {
                "Генератор вернул неподдерживаемый формат конфигурации"
            }
            return config
        } finally {
            connection.disconnect()
        }
    }
}
