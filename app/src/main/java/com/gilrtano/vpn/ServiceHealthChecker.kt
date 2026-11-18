package com.gilrtano.vpn

import android.util.Log
import io.github.madeye.meow.core.MeowCore
import org.json.JSONObject
import java.util.Locale
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-proxy service checks. Each request is sent by Meow's native HTTP probe
 * through the named outbound, never through Android's default network.
 *
 * The catalogue mirrors FlClashX v0.5.8's 17 service targets. The Meow probe
 * exposes GET and the service-specific POST handshake (1 MiB body cap, 5 s native timeout),
 * so checks use lightweight public endpoints and classify what those
 * responses can prove. They do not claim account-login or playback success.
 */
object ServiceHealthChecker {
    private const val TAG = "GilrTanoServiceCheck"
    private const val NATIVE_KIND = "http-get"
    private const val MAX_PARALLEL_SERVICES = 4
    private const val FUTURE_TIMEOUT_SECONDS = 7L
    private val ids = AtomicLong(System.currentTimeMillis())

    enum class Status(val id: String) {
        AVAILABLE("available"),
        UNAVAILABLE("unavailable"),
        RESTRICTED("restricted"),
        DISALLOWED_ISP("disallowed-isp"),
        BLOCKED("blocked"),
        UNSUPPORTED_REGION("unsupported-region"),
        ORIGINALS_ONLY("originals-only"),
        COMING_SOON("coming-soon"),
        TIMEOUT("timeout"),
        FAILED("failed"),
    }

    data class Target(
        val id: String,
        val label: String,
        val url: String,
        val rule: Rule = Rule.REACHABLE,
    )

    enum class Rule {
        REACHABLE, YOUTUBE, CHATGPT, CLAUDE, GEMINI, NETFLIX,
        DISNEY, PRIME_VIDEO, SPOTIFY, TIKTOK, DISCORD
    }

    data class Result(
        val id: String,
        val label: String,
        val status: Status,
        val delayMs: Int?,
        val region: String? = null,
        val detail: String? = null,
    )

    data class ProxyResult(
        val proxyName: String,
        val services: List<Result>,
        val averageDelayMs: Int?,
        val eligible: Boolean,
    ) {
        val failedServices: List<String>
            get() = services.filter { it.status != Status.AVAILABLE }.map { it.label }
    }

    val targets = listOf(
        Target("google", "Google", "https://www.google.com/generate_204"),
        Target("github", "GitHub", "https://github.com/"),
        Target("youtube", "YouTube", "https://www.youtube.com/premium?hl=en", Rule.YOUTUBE),
        Target("chatgpt", "ChatGPT", "https://ios.chat.openai.com/", Rule.CHATGPT),
        Target("claude", "Claude", "https://claude.ai/cdn-cgi/trace", Rule.CLAUDE),
        Target("gemini", "Gemini", "https://gemini.google.com/", Rule.GEMINI),
        Target("netflix", "Netflix", "https://api.fast.com/netflix/speedtest/v2?https=true&token=YXNkZmFzZGxmbnNkYWZoYXNkZmhrYWxm&urlCount=5", Rule.NETFLIX),
        Target("disney-plus", "Disney+", "https://www.disneyplus.com/", Rule.DISNEY),
        Target("prime-video", "Prime Video", "https://www.primevideo.com/", Rule.PRIME_VIDEO),
        Target("spotify", "Spotify", "https://www.spotify.com/api/content/v1/country-selector?platform=web&format=json", Rule.SPOTIFY),
        Target("tiktok", "TikTok", "https://www.tiktok.com/", Rule.TIKTOK),
        Target("discord", "Discord", "https://discord.com/api/v9/auth/location-metadata", Rule.DISCORD),
        Target("gosuslugi", "Госуслуги", "https://www.gosuslugi.ru/"),
        Target("sberbank", "Сбербанк", "https://online.sberbank.ru/CSAFront/index.do"),
        Target("vk", "VK", "https://vk.com/"),
        Target("yandex", "Яндекс", "https://ya.ru/"),
        Target("kinopoisk", "Кинопоиск", "https://www.kinopoisk.ru/"),
    )

    fun checkProxy(proxyName: String): ProxyResult {
        val pool = Executors.newFixedThreadPool(MAX_PARALLEL_SERVICES)
        val futures = ArrayList<Future<Result>>(targets.size)
        try {
            targets.forEach { target ->
                futures += pool.submit(Callable { checkOne(proxyName, target) })
            }
            val results = futures.mapIndexed { index, future ->
                try {
                    future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    val target = targets[index]
                    Result(
                        target.id,
                        target.label,
                        if (e is java.util.concurrent.TimeoutException) Status.TIMEOUT else Status.FAILED,
                        null,
                        detail = e.message,
                    )
                }
            }
            val eligible = results.size == targets.size && results.all { it.status == Status.AVAILABLE }
            val average = if (eligible) results.mapNotNull { it.delayMs }.average().toInt() else null
            if (!eligible) {
                Log.i(TAG, "$proxyName excluded: " + results.filter { it.status != Status.AVAILABLE }
                    .joinToString { "${it.label}=${it.status.id}" })
            } else {
                Log.i(TAG, "$proxyName passed 17/17 services, mean=${average}ms")
            }
            return ProxyResult(proxyName, results, average, eligible)
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
            pool.shutdownNow()
        }
    }

    private fun checkOne(proxyName: String, target: Target): Result {
        if (target.rule == Rule.DISNEY) return checkDisney(proxyName, target)
        if (target.rule == Rule.CHATGPT) return checkChatGpt(proxyName, target)
        if (target.rule == Rule.NETFLIX) return checkNetflix(proxyName, target)
        val started = System.nanoTime()
        val raw = try {
            MeowCore.nativeProbeProxy(
                ids.incrementAndGet(),
                proxyName,
                NATIVE_KIND,
                target.url,
            )
        } catch (e: Throwable) {
            return Result(target.id, target.label, Status.FAILED, null, detail = e.message)
        }
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L).toInt()
        if (raw.isNullOrBlank()) {
            return Result(target.id, target.label, Status.FAILED, null, detail = "Пустой ответ Meow")
        }
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Result(target.id, target.label, Status.FAILED, null, detail = "Некорректный ответ Meow")
        if (json.has("error")) {
            val code = json.optString("code")
            val status = if (code == "timeout") Status.TIMEOUT else Status.FAILED
            return Result(target.id, target.label, status, null, detail = json.optString("error"))
        }
        val code = json.optInt("status", -1)
        val body = json.optString("body", "")
        val lower = body.lowercase(Locale.ROOT)
        val base = when {
            code in 200..399 -> Status.AVAILABLE
            code == 401 || code == 403 || code == 429 || code == 451 -> Status.RESTRICTED
            code in 100..599 -> Status.UNAVAILABLE
            else -> Status.FAILED
        }
        val (status, region, detail) = classify(target.rule, code, body, lower, base)
        return Result(target.id, target.label, status, elapsed, region, detail)
    }

    private fun nativeGet(proxyName: String, url: String): JSONObject? {
        val raw = runCatching {
            MeowCore.nativeProbeProxy(ids.incrementAndGet(), proxyName, NATIVE_KIND, url)
        }.getOrNull() ?: return null
        val response = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (response.has("error")) return null
        return response
    }

    private fun checkChatGpt(proxyName: String, target: Target): Result {
        val started = System.nanoTime()
        val ios = nativeGet(proxyName, "https://ios.chat.openai.com/")
            ?: return Result(target.id, target.label, Status.FAILED, null, detail = "ChatGPT iOS endpoint did not answer")
        val body = ios.optString("body", "").lowercase(Locale.ROOT)
        val status = when {
            body.contains("you may be connected to a disallowed isp") -> Status.DISALLOWED_ISP
            body.contains("sorry, you have been blocked") -> Status.BLOCKED
            body.contains("request is not allowed. please try again later.") -> Status.AVAILABLE
            else -> Status.FAILED
        }
        val trace = nativeGet(proxyName, "https://chat.openai.com/cdn-cgi/trace")
        val region = trace?.optString("body", "")?.let {
            Regex("(?m)^loc=([A-Za-z]{2,3})\\s*$").find(it)?.groupValues?.getOrNull(1)
        }?.let(::normalizeRegion)
        val web = nativeGet(proxyName, "https://api.openai.com/compliance/cookie_requirements")
        val finalStatus = if (web?.optString("body", "")?.contains("unsupported_country", true) == true) {
            Status.UNSUPPORTED_REGION
        } else status
        return Result(target.id, target.label, finalStatus, elapsed(started), region)
    }

    private fun checkNetflix(proxyName: String, target: Target): Result {
        val started = System.nanoTime()
        val cdn = nativeGet(
            proxyName,
            "https://api.fast.com/netflix/speedtest/v2?https=true&token=YXNkZmFzZGxmbnNkYWZoYXNkZmhrYWxm&urlCount=5",
        )
        if (cdn != null) {
            val code = cdn.optInt("status", -1)
            if (code == 403) return Result(target.id, target.label, Status.BLOCKED, elapsed(started))
            val country = runCatching {
                val body = JSONObject(cdn.optString("body", ""))
                normalizeRegion(
                    body.optJSONArray("targets")?.optJSONObject(0)
                        ?.optJSONObject("location")?.optString("country"),
                )
            }.getOrNull()
            if (country != null) {
                return Result(target.id, target.label, Status.AVAILABLE, elapsed(started), country)
            }
        }

        val first = nativeGet(proxyName, "https://www.netflix.com/title/81280792")
            ?: return Result(target.id, target.label, Status.FAILED, null, detail = "Netflix title probe failed")
        val second = nativeGet(proxyName, "https://www.netflix.com/title/70143836")
            ?: return Result(target.id, target.label, Status.FAILED, null, detail = "Netflix fallback probe failed")
        val firstCode = first.optInt("status", -1)
        val secondCode = second.optInt("status", -1)
        val status = when {
            firstCode == 404 && secondCode == 404 -> Status.ORIGINALS_ONLY
            firstCode == 403 || secondCode == 403 -> Status.UNSUPPORTED_REGION
            firstCode in 200..399 || secondCode in 200..399 -> Status.AVAILABLE
            else -> statusFromHttp(firstCode)
        }
        return Result(target.id, target.label, status, elapsed(started))
    }

    private fun checkDisney(proxyName: String, target: Target): Result {
        val started = System.nanoTime()
        fun post(url: String, headers: Map<String, String>, body: String): JSONObject? {
            val args = JSONObject()
                .put("url", url)
                .put("headers", JSONObject(headers))
                .put("body", body)
                .toString()
            val raw = runCatching {
                MeowCore.nativeProbeProxy(ids.incrementAndGet(), proxyName, "http-post", args)
            }.getOrNull() ?: return null
            val response = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (response.has("error")) return null
            return response
        }

        val auth = "Bearer ZGlzbmV5JmJyb3dzZXImMS4wLjA.Cu56AgSfBTDag5NiRA81oLHkDZfu5L3CKadnefEAY84"
        val device = post(
            "https://disney.api.edge.bamgrid.com/devices",
            mapOf(
                "authorization" to auth,
                "content-type" to "application/json; charset=UTF-8",
            ),
            """{"deviceFamily":"browser","applicationRuntime":"chrome","deviceProfile":"windows","attributes":{}}""",
        ) ?: return disneyFailure(target, started, "Disney device handshake failed")
        val deviceCode = device.optInt("status", -1)
        val deviceBody = device.optString("body", "")
        if (deviceCode == 403) return Result(target.id, target.label, Status.BLOCKED, elapsed(started))
        if (deviceCode !in 200..299) {
            return Result(target.id, target.label, statusFromHttp(deviceCode), elapsed(started))
        }
        val assertion = runCatching { JSONObject(deviceBody).optString("assertion") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return disneyFailure(target, started, "Disney did not return a device assertion")

        val form = "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" +
            "&latitude=0&longitude=0&platform=browser" +
            "&subject_token=" + URLEncoder.encode(assertion, "UTF-8") +
            "&subject_token_type=urn:bamtech:params:oauth:token-type:device"
        val token = post(
            "https://disney.api.edge.bamgrid.com/token",
            mapOf(
                "authorization" to auth,
                "content-type" to "application/x-www-form-urlencoded",
            ),
            form,
        ) ?: return disneyFailure(target, started, "Disney token exchange failed")
        val tokenCode = token.optInt("status", -1)
        val tokenBody = token.optString("body", "")
        if (tokenCode == 403 || tokenBody.contains("forbidden-location", true) ||
            tokenBody.contains("403 ERROR", true)) {
            return Result(target.id, target.label, Status.BLOCKED, elapsed(started))
        }
        if (tokenCode !in 200..299) {
            return Result(target.id, target.label, statusFromHttp(tokenCode), elapsed(started))
        }
        val refresh = runCatching { JSONObject(tokenBody).optString("refresh_token") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return disneyFailure(target, started, "Disney did not return a refresh token")

        val query = JSONObject()
            .put(
                "query",
                "mutation refreshToken(\$input: RefreshTokenInput!) { refreshToken(refreshToken: \$input) { activeSession { sessionId } } }",
            )
            .put(
                "variables",
                JSONObject().put("input", JSONObject().put("refreshToken", refresh)),
            )
            .toString()
        val session = post(
            "https://disney.api.edge.bamgrid.com/graph/v1/device/graphql",
            mapOf(
                "authorization" to auth,
                "content-type" to "application/json",
            ),
            query,
        ) ?: return disneyFailure(target, started, "Disney session request failed")
        val sessionCode = session.optInt("status", -1)
        val sessionBody = session.optString("body", "")
        if (sessionCode !in 200..299 || sessionBody.isBlank()) {
            return disneyFailure(target, started, "Disney returned no active session")
        }
        val country = Regex("\"countryCode\"\\s*:\\s*\"([^\"]+)\"")
            .find(sessionBody)?.groupValues?.getOrNull(1)?.let(::normalizeRegion)
            ?: return Result(target.id, target.label, Status.UNAVAILABLE, elapsed(started))
        val supported = Regex("\"inSupportedLocation\"\\s*:\\s*(true|false)")
            .find(sessionBody)?.groupValues?.getOrNull(1)
        val status = when (supported) {
            "true" -> Status.AVAILABLE
            "false" -> Status.COMING_SOON
            else -> Status.FAILED
        }
        return Result(target.id, target.label, status, elapsed(started), country)
    }

    private fun disneyFailure(target: Target, started: Long, detail: String) =
        Result(target.id, target.label, Status.FAILED, null, detail = detail)

    private fun elapsed(started: Long): Int =
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L).toInt()

    private fun statusFromHttp(code: Int): Status = when {
        code == 401 || code == 403 || code == 429 || code == 451 -> Status.RESTRICTED
        code in 100..599 -> Status.UNAVAILABLE
        else -> Status.FAILED
    }

    private data class Classification(val status: Status, val region: String?, val detail: String?)

    private fun classify(
        rule: Rule,
        code: Int,
        body: String,
        lower: String,
        base: Status,
    ): Classification {
        fun region(vararg patterns: Regex): String? {
            for (pattern in patterns) {
                val match = pattern.find(body)?.groupValues?.getOrNull(1)
                normalizeRegion(match)?.let { return it }
            }
            return null
        }
        return when (rule) {
            Rule.REACHABLE -> Classification(base, null, null)
            Rule.CLAUDE -> {
                val loc = Regex("(?m)^loc=([A-Za-z]{2,3})\\s*$").find(body)?.groupValues?.getOrNull(1)
                val country = normalizeRegion(loc)
                val blocked = setOf("AF", "BY", "CN", "CU", "HK", "IR", "KP", "MO", "RU", "SY")
                if (base != Status.AVAILABLE) Classification(base, null, null)
                else if (country == null) Classification(Status.FAILED, null, "Claude не вернул код региона")
                else Classification(if (country in blocked) Status.UNSUPPORTED_REGION else Status.AVAILABLE, country, null)
            }
            Rule.YOUTUBE -> {
                val country = region(
                    Regex("(?i)id=[\"']country-code[\"'][^>]*>\\s*([A-Za-z]{2,3})\\s*<"),
                    Regex("\"GL\"\\s*:\\s*\"([A-Za-z]{2})\""),
                    Regex("\"countryCode\"\\s*:\\s*\"([A-Za-z]{2})\""),
                    Regex("\"country_code\"\\s*:\\s*\"([A-Za-z]{2})\""),
                )
                val unavailable = lower.contains("youtube premium is not available in your country") ||
                    lower.contains("premium is not available in your region")
                val looksLikePremium = lower.contains("youtube premium") || lower.contains("ad-free") ||
                    lower.contains("\"browseid\":\"spunlimited\"")
                val status = when {
                    unavailable -> Status.UNSUPPORTED_REGION
                    code in 200..299 && looksLikePremium -> Status.AVAILABLE
                    base != Status.AVAILABLE -> base
                    else -> Status.AVAILABLE
                }
                Classification(status, country, null)
            }
            Rule.CHATGPT -> {
                val status = when {
                    lower.contains("you may be connected to a disallowed isp") -> Status.DISALLOWED_ISP
                    lower.contains("sorry, you have been blocked") -> Status.BLOCKED
                    lower.contains("request is not allowed. please try again later.") -> Status.AVAILABLE
                    base == Status.AVAILABLE -> Status.AVAILABLE
                    else -> base
                }
                Classification(status, null, null)
            }
            Rule.GEMINI -> {
                val marker = ",2,1,200,\""
                val index = body.indexOf(marker)
                val code3 = if (index >= 0 && index + marker.length + 3 <= body.length)
                    body.substring(index + marker.length, index + marker.length + 3) else null
                val country = normalizeRegion(code3)
                val blocked = setOf("CN", "RU", "BY", "CU", "IR", "KP", "SY", "HK", "MO")
                val status = when {
                    base != Status.AVAILABLE -> base
                    country != null && country in blocked -> Status.UNSUPPORTED_REGION
                    else -> Status.AVAILABLE
                }
                Classification(status, country, null)
            }
            Rule.NETFLIX -> {
                val country = runCatching {
                    val json = JSONObject(body)
                    normalizeRegion(json.optJSONArray("targets")?.optJSONObject(0)
                        ?.optJSONObject("location")?.optString("country"))
                }.getOrNull()
                val status = when {
                    code == 403 -> Status.BLOCKED
                    base != Status.AVAILABLE -> base
                    body.isBlank() -> Status.FAILED
                    else -> Status.AVAILABLE
                }
                Classification(status, country, null)
            }
            Rule.DISNEY -> {
                val status = when {
                    code == 403 -> Status.BLOCKED
                    base != Status.AVAILABLE -> base
                    lower.contains("not available in your region") -> Status.UNSUPPORTED_REGION
                    else -> Status.AVAILABLE
                }
                Classification(status, null, "Проверка доступности сайта; полный Disney device/token handshake требует POST-пробника")
            }
            Rule.PRIME_VIDEO -> {
                val country = region(Regex("\"currentTerritory\":\"([^\"]+)\""))
                val status = when {
                    lower.contains("isservicerestricted") -> Status.UNSUPPORTED_REGION
                    base != Status.AVAILABLE -> base
                    else -> Status.AVAILABLE
                }
                Classification(status, country, null)
            }
            Rule.SPOTIFY -> {
                val country = region(Regex("\"countryCode\"\\s*:\\s*\"([^\"]+)\""))
                val status = when {
                    code == 403 || code == 451 || lower.contains("not available in your country") -> Status.UNSUPPORTED_REGION
                    base != Status.AVAILABLE -> base
                    else -> Status.AVAILABLE
                }
                Classification(status, country, null)
            }
            Rule.TIKTOK -> {
                val country = region(Regex("\"webapp\\.app-context\"\\s*:\\s*\\{[^{}]*?\"region\"\\s*:\\s*\"([^\"]*)\""))
                val blocked = lower.contains("access denied") || lower.contains("not available in your region") ||
                    lower.contains("tiktok is not available")
                val status = when {
                    code == 403 || code == 451 || blocked -> Status.UNSUPPORTED_REGION
                    base != Status.AVAILABLE -> base
                    else -> Status.AVAILABLE
                }
                Classification(status, country, null)
            }
            Rule.DISCORD -> {
                val country = region(Regex("\"country_code\"\\s*:\\s*\"([^\"]+)\""))
                Classification(base, country, null)
            }
        }.let { Classification(it.status, it.region, it.detail) }
    }

    private fun normalizeRegion(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val raw = value.trim().uppercase(Locale.ROOT)
        val aliases = mapOf("CHN" to "CN", "HKG" to "HK", "MAC" to "MO", "USA" to "US")
        val code = aliases[raw] ?: raw
        return code.takeIf { it.matches(Regex("[A-Z]{2}")) }
    }
}
