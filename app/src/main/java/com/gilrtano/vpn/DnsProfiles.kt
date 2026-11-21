package com.gilrtano.vpn

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * DNS presets supplied by the user. Endpoints are kept explicit so we never
 * silently convert an informational website into a resolver URL.
 */
internal object DnsProfiles {
    data class Profile(
        val id: String,
        val title: String,
        val resolver: String,
        val protocol: String,
        val note: String,
    )

    val profiles = listOf(
        Profile("auto", "Автоматически · DNS профиля", "", "Авто", "Не переопределять DNS из конфигурации подписки"),
        Profile("webshift", "WebShift VPN · DoH", "https://d.webshiftvpn.ru/dns-query", "DoH", "Адрес указан пользователем"),
        Profile("bezmezhau", "Bezmezhau · DoH", "https://sync.bezmezhau.com/dns-query", "DoH", "Адрес указан пользователем"),
        Profile("cloudflare_gateway", "Cloudflare Gateway · DoH", "https://ys39tfku2v.cloudflare-gateway.com/dns-query", "DoH", "Персональный Gateway URL из списка пользователя"),
        Profile("comss_router", "Comss Router · DoH", "https://router.comss.one/dns-query", "DoH", "Формат endpoint подтверждён документацией AdGuard DNS"),
        Profile("quicbest", "DNS.quic.best · DoQ", "quic://dns.quic.best", "DoQ", "Схема предполагается по названию; требуется проверка провайдера"),
        Profile("tamaev", "Tamaev Gifts · DoT", "tls://dns.tamaev-gifts.xyz", "DoT", "Хост указан пользователем; протокол требует подтверждения"),
        Profile("controld", "ControlD Comss · DoT", "tls://comss.dns.controld.com", "DoT", "Хост указан пользователем; протокол требует подтверждения"),
        Profile("webshift_dot", "WebShift · DoT", "tls://dns.webshiftvpn.ru", "DoT", "Хост указан пользователем; протокол требует подтверждения"),
        Profile("nullsproxy", "NullsProxy · DoT", "tls://dns.nullsproxy.com", "DoT", "Хост указан пользователем; протокол требует подтверждения"),
        Profile("xbox_supercell", "Xbox / Supercell · DoT", "tls://supercell.xbox-dns.ru", "DoT", "Хост указан пользователем; назначение не подтверждено"),
        Profile("commonnet", "Commonnet · DoT", "tls://dns.commonnet.work", "DoT", "Хост указан пользователем; протокол требует подтверждения"),
    )

    fun selected(context: Context): Profile {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, "auto")
        return profiles.firstOrNull { it.id == id } ?: profiles.first()
    }

    fun save(context: Context, id: String) {
        require(profiles.any { it.id == id })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, id).apply()
    }

    /** Apply selected resolver to a parsed Clash/Mihomo root map. */
    fun applyToConfig(context: Context, config: MutableMap<Any?, Any?>) {
        val profile = selected(context)
        if (profile.id == "auto") return
        val dns = LinkedHashMap<Any?, Any?>()
        (config["dns"] as? Map<*, *>)?.forEach { (key, value) -> dns[key] = value }
        dns["enable"] = true
        dns["nameserver"] = listOf(profile.resolver)
        if (dns["default-nameserver"] == null) {
            dns["default-nameserver"] = listOf("1.1.1.1", "8.8.8.8")
        }
        config["dns"] = dns
    }

    /**
     * Reachability probe for HTTPS DoH endpoints only. HTTP 400/405/415 still
     * proves the HTTPS server answered; it does not prove DNS answers are correct.
     */
    fun probe(profile: Profile): String {
        if (!profile.resolver.startsWith("https://")) {
            return when (profile.protocol) {
                "DoT" -> "Проверка DoT ещё не поддерживается; адрес сохранён"
                "DoQ" -> "Проверка DoQ ещё не поддерживается; адрес сохранён"
                else -> "Для этого типа DNS нет HTTPS-проверки"
            }
        }
        val connection = (URL(profile.resolver).openConnection() as HttpURLConnection)
        return try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/dns-message, application/json")
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code in 200..499) "HTTPS-сервер отвечает (HTTP $code); корректность DNS-ответов отдельно не подтверждена"
            else "Сервер ответил HTTP $code"
        } finally {
            connection.disconnect()
        }
    }

    const val PREFS = "gilrtano_dns"
    const val KEY_SELECTED = "subscription_dns_profile"
}
