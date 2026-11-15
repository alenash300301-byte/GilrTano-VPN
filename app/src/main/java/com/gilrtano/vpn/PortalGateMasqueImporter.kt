package com.gilrtano.vpn

import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.InputStream
import java.util.Locale

/**
 * Imports Mihomo-style PORTAL MASQUE/WARP proxy entries and converts each
 * endpoint into the Xray-core MASQUE outbound schema shipped with this app.
 * Credentials are held only in the in-memory profile and are never logged.
 */
object PortalGateMasqueImporter {
    @Suppress("UNCHECKED_CAST")
    fun parseYaml(input: InputStream): List<PortalGateProfile> {
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(input) as? Map<String, Any?>
            ?: error("Некорректный YAML PORTAL GATE")
        val proxies = root["proxies"] as? List<*>
            ?: error("В YAML не найден список proxies")
        val masque = proxies.mapNotNull { raw ->
            val p = raw as? Map<String, Any?> ?: return@mapNotNull null
            if (p["type"]?.toString()?.lowercase(Locale.ROOT) != "masque") return@mapNotNull null
            p
        }
        require(masque.isNotEmpty()) { "В YAML не найдены серверы type: masque" }
        return masque.map { p -> buildProfile(p) }
    }

    private fun buildProfile(p: Map<String, Any?>): PortalGateProfile {
        fun value(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key -> p[key]?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        val name = value("name") ?: "PORTAL MASQUE"
        val server = value("server") ?: error("$name: отсутствует server")
        val port = value("port")?.toIntOrNull() ?: error("$name: некорректный port")
        require(port in 1..65535) { "$name: порт вне диапазона" }
        val sni = value("sni") ?: error("$name: отсутствует SNI")
        val privateKey = value("private-key", "private_key", "privateKey")
            ?: error("$name: отсутствует private-key")
        val publicKey = value("public-key", "public_key", "publicKey")
            ?: error("$name: отсутствует public-key")
        val address = value("ip", "address") ?: error("$name: отсутствует IP-адрес туннеля")
        val addressList = address.split(',').map(String::trim).filter(String::isNotEmpty)
        require(addressList.isNotEmpty()) { "$name: некорректный IP-адрес" }

        val masqueSettings = JSONObject()
            .put("host", value("masque-host", "masque_host") ?: "cloudflareaccess.com")
            .put("path", value("path") ?: "/")
            .put("warp", JSONObject()
                .put("privateKey", privateKey)
                .put("publicKey", publicKey)
                .put("address", JSONArray(addressList)))
        val stream = JSONObject()
            .put("network", "masque")
            .put("security", "tls")
            .put("tlsSettings", JSONObject()
                .put("serverName", sni)
                .put("alpn", JSONArray().put("h3")))
            .put("masqueSettings", masqueSettings)

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "masque")
            .put("settings", JSONObject()
                .put("address", server)
                .put("port", port)
                .put("remoteDNS", JSONArray().put("8.8.8.8").put("8.8.4.4")))
            .put("streamSettings", stream)

        val inbound = JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", 10808)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true))

        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray()
                .put(outbound)
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
            .put("routing", JSONObject().put("rules", JSONArray().put(
                JSONObject().put("type", "field")
                    .put("inboundTag", JSONArray().put("socks-in"))
                    .put("outboundTag", "proxy")
            )))
            .toString()

        val country = when {
            name.contains("NL", true) || server.startsWith("162.159.") -> "Cloudflare WARP"
            else -> "PORTAL GATE"
        }
        return PortalGateProfile(name, country, "$server:$port · $sni", config)
    }
}
