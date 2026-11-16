package com.gilrtano.vpn

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Requests a fresh AmneziaWG profile from the Cyberportal generator.
 *
 * Cyberportal exposes GET /api/warp and returns the generated profile as
 * content.importText. The client deliberately logs metadata only: never log
 * private keys or complete generated profiles.
 */
object WarpGeneratorClient {
    private const val TAG = "WarpGenerator"
    private const val TOTAL_TIMEOUT_MS = 35_000L
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_RESPONSE_CHARS = 1_000_000

    private val apiUrls = listOf(
        "https://warp-gen.cyb-portal.org/api/warp",
        "https://warp-gen-vip.vercel.app/api/warp",
        "https://portalwg.vercel.app/api/warp"
    )

    fun generateAmneziaProfile(): String {
        val startedAt = System.nanoTime()
        val deadline = startedAt + TOTAL_TIMEOUT_MS * 1_000_000L
        val failures = mutableListOf<String>()
        Log.i(TAG, "Generation started; endpoints=${apiUrls.size}")

        for ((index, apiUrl) in apiUrls.withIndex()) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) {
                failures += "общий лимит времени исчерпан"
                break
            }
            val host = URL(apiUrl).host
            val endpointStarted = System.nanoTime()
            Log.i(TAG, "Endpoint ${index + 1}/${apiUrls.size} started: $host")
            try {
                val config = requestConfig(apiUrl, remainingMs)
                val elapsed = (System.nanoTime() - endpointStarted) / 1_000_000L
                Log.i(TAG, "Endpoint succeeded: $host; elapsed=${elapsed}ms; configChars=${config.length}")
                Log.i(TAG, "Generation succeeded; totalElapsed=${(System.nanoTime() - startedAt) / 1_000_000L}ms")
                return config
            } catch (e: Exception) {
                val elapsed = (System.nanoTime() - endpointStarted) / 1_000_000L
                val reason = e.message?.take(240) ?: e.javaClass.simpleName
                failures += "$host: $reason"
                Log.e(TAG, "Endpoint failed: $host; elapsed=${elapsed}ms; type=${e.javaClass.simpleName}; reason=$reason")
            }
        }

        val totalElapsed = (System.nanoTime() - startedAt) / 1_000_000L
        val message = "Не удалось получить WARP-конфигурацию за ${totalElapsed}мс. " +
            failures.joinToString(" | ")
        Log.e(TAG, "Generation failed: $message")
        error(message)
    }

    private fun requestConfig(apiUrl: String, remainingMs: Long): String {
        val host = URL(apiUrl).host
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = minOf(CONNECT_TIMEOUT_MS.toLong(), remainingMs).toInt().coerceAtLeast(1)
            readTimeout = minOf(READ_TIMEOUT_MS.toLong(), remainingMs).toInt().coerceAtLeast(1)
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "GilrTano-WARP-Generator/2.1")
        }
        try {
            val code = connection.responseCode
            Log.i(TAG, "HTTP response: host=$host code=$code")
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (out.length + count > MAX_RESPONSE_CHARS) {
                        error("ответ сервера слишком большой")
                    }
                    out.append(buffer, 0, count)
                }
                out.toString()
            }.orEmpty()

            if (code !in 200..299) {
                val safeBody = response.replace(Regex("\\s+"), " ").take(180)
                error("HTTP $code" + safeBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
            }

            val json = runCatching { JSONObject(response) }.getOrElse {
                error("сервер вернул некорректный JSON (HTTP $code)")
            }
            val content = json.optJSONObject("content")
                ?: error(json.optString("message").takeIf { it.isNotBlank() && it != "null" }
                    ?: "в ответе отсутствует объект content")

            val importText = content.optString("importText").takeUnless { it == "null" }?.trim().orEmpty()
            if (importText.isNotBlank()) {
                Log.i(TAG, "Response payload: content.importText; chars=${importText.length}")
                return validateConfig(importText)
            }

            val encoded = content.optString("configBase64").takeUnless { it == "null" }.orEmpty()
            if (encoded.isNotBlank()) {
                Log.i(TAG, "Response payload: content.configBase64; chars=${encoded.length}")
                val decoded = try {
                    String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    error("поле configBase64 содержит некорректные данные")
                }
                return validateConfig(decoded)
            }

            error("в ответе отсутствуют content.importText и content.configBase64")
        } finally {
            connection.disconnect()
        }
    }

    private fun validateConfig(raw: String): String {
        val config = raw.trim()
        require(config.contains("[Interface]") && config.contains("[Peer]")) {
            "ответ получен, но в нём нет секций [Interface] и [Peer]"
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
        return config
    }
}
