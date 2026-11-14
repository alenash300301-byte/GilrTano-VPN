package com.gilrtano.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Parsed VLESS + REALITY share link. Secrets are kept in memory and never logged. */
data class VlessRealityServer(
    val id: String,
    val address: String,
    val port: Int,
    val name: String,
    val serverName: String,
    val publicKey: String,
    val shortId: String,
    val fingerprint: String,
    val flow: String,
    val network: String = "tcp"
)

object VlessRealityParser {
    fun parse(link: String): VlessRealityServer {
        val uri = URI(link.trim())
        require(uri.scheme.equals("vless", ignoreCase = true)) { "Поддерживаются только ссылки vless://" }
        val userInfo = requireNotNull(uri.rawUserInfo) { "В ссылке отсутствует UUID" }
        val id = decode(userInfo.substringAfterLast(':'))
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "Некорректный UUID VLESS" }
        val host = requireNotNull(uri.host) { "Не указан адрес сервера" }
        val port = uri.port
        require(port in 1..65535) { "Некорректный порт сервера" }
        val q = parseQuery(uri.rawQuery.orEmpty())
        require(q["security"] == "reality") { "Ссылка должна использовать REALITY" }
        val sni = q["sni"].orEmpty()
        val pbk = q["pbk"].orEmpty()
        val sid = q["sid"].orEmpty()
        require(sni.isNotBlank()) { "В ссылке отсутствует SNI" }
        require(pbk.isNotBlank()) { "В ссылке отсутствует REALITY public key (pbk)" }
        require(sid.isNotBlank()) { "В ссылке отсутствует REALITY short ID (sid)" }
        val network = q["type"].orEmpty().ifBlank { "tcp" }
        require(network == "tcp") { "В этой сборке генератор пока принимает только VLESS REALITY over TCP" }
        return VlessRealityServer(
            id = id,
            address = host,
            port = port,
            name = decode(uri.rawFragment.orEmpty()).ifBlank { host },
            serverName = sni,
            publicKey = pbk,
            shortId = sid,
            fingerprint = q["fp"].orEmpty().ifBlank { "chrome" },
            flow = q["flow"].orEmpty(),
            network = network
        )
    }

    private fun parseQuery(raw: String): Map<String, String> = raw.split('&')
        .filter { it.isNotBlank() }
        .associate { part ->
            val key = decode(part.substringBefore('='))
            key to decode(part.substringAfter('=', ""))
        }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

/** Generates an Xray JSON config: local SOCKS5 inbound for tun2socks + VLESS/REALITY outbound. */
object XrayConfig {
    fun build(server: VlessRealityServer, socksPort: Int = 10808): String {
        require(socksPort in 1..65535)
        val user = JSONObject()
            .put("id", server.id)
            .put("encryption", "none")
        if (server.flow.isNotBlank()) user.put("flow", server.flow)

        val reality = JSONObject()
            .put("serverName", server.serverName)
            .put("fingerprint", server.fingerprint)
            .put("publicKey", server.publicKey)
            .put("shortId", server.shortId)
            .put("spiderX", "")

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject()
                    .put("address", server.address)
                    .put("port", server.port)
                    .put("users", JSONArray().put(user))
            )))
            .put("streamSettings", JSONObject()
                .put("network", server.network)
                .put("security", "reality")
                .put("realitySettings", reality))

        val socks = JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("userLevel", 0))
            .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic")))

        val direct = JSONObject().put("tag", "direct").put("protocol", "freedom").put("settings", JSONObject())
        val block = JSONObject().put("tag", "block").put("protocol", "blackhole").put("settings", JSONObject())

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", JSONObject().put("servers", JSONArray().put("https+local://1.1.1.1/dns-query").put("https+local://8.8.8.8/dns-query")))
            .put("inbounds", JSONArray().put(socks))
            .put("outbounds", JSONArray().put(outbound).put(direct).put(block))
            .put("routing", JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", JSONArray().put(JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("socks-in"))
                    .put("outboundTag", "proxy"))))
            .toString(2)
    }
}
