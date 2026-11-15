package com.gilrtano.vpn

import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.InputStream
import java.util.Locale

/** Imports Mihomo-style PORTAL MASQUE/WARP entries into the bundled Xray MASQUE schema. */
object PortalGateMasqueImporter {
    @Suppress("UNCHECKED_CAST")
    fun parseYaml(input: InputStream): List<PortalGateProfile> {
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(input) as? Map<String, Any?>
            ?: error("Некорректный YAML PORTAL GATE")
        val raw = root["proxies"] as? List<*> ?: error("В YAML не найден список proxies")
        val proxies = raw.mapNotNull { item ->
            val p = item as? Map<String, Any?> ?: return@mapNotNull null
            if (p["type"]?.toString()?.lowercase(Locale.ROOT) == "masque") p else null
        }
        require(proxies.isNotEmpty()) { "В YAML не найдены серверы type: masque" }
        val names = proxies.mapIndexed { i, p ->
            (p["name"]?.toString()?.takeIf(String::isNotBlank) ?: "PORTAL MASQUE $i") to i
        }.toMap()
        require(names.size == proxies.size) { "В YAML есть повторяющиеся имена MASQUE-серверов" }
        val tags = names.mapValues { (_, i) -> "masque-$i" }
        val dns = parseDns(root["dns"])
        return proxies.mapIndexed { i, p -> buildProfile(p, proxies, tags, i, dns) }
    }

    private fun parseDns(raw: Any?): List<String> {
        val dns = raw as? Map<*, *> ?: return listOf("8.8.8.8", "8.8.4.4")
        val values = dns["nameserver"] as? List<*> ?: return listOf("8.8.8.8", "8.8.4.4")
        val parsed = values.mapNotNull { it?.toString()?.trim() }
            .filter { it.matches(Regex("[0-9a-fA-F:.]+")) }.distinct()
        return parsed.ifEmpty { listOf("8.8.8.8", "8.8.4.4") }
    }

    private fun buildProfile(
        selected: Map<String, Any?>,
        proxies: List<Map<String, Any?>>,
        tags: Map<String, String>,
        selectedIndex: Int,
        dns: List<String>
    ): PortalGateProfile {
        fun field(p: Map<String, Any?>, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> p[k]?.toString()?.trim()?.takeIf(String::isNotEmpty) }

        val outbounds = JSONArray()
        proxies.forEachIndexed { index, p ->
            val name = field(p, "name") ?: "PORTAL MASQUE $index"
            val tag = tags[name] ?: error("$name: не удалось назначить тег")
            val server = field(p, "server") ?: error("$name: отсутствует server")
            val port = field(p, "port")?.toIntOrNull() ?: error("$name: некорректный port")
            require(port in 1..65535) { "$name: порт вне диапазона" }
            val sni = field(p, "sni") ?: error("$name: отсутствует SNI")
            val privateKey = field(p, "private-key", "private_key", "privateKey")
                ?: error("$name: отсутствует private-key")
            val publicKey = field(p, "public-key", "public_key", "publicKey")
                ?: error("$name: отсутствует public-key")
            val address = field(p, "ip", "address") ?: error("$name: отсутствует IP-адрес")
            val addresses = address.split(',').map(String::trim).filter(String::isNotEmpty)
            require(addresses.isNotEmpty()) { "$name: некорректный IP-адрес" }

            val masque = JSONObject()
                .put("host", field(p, "masque-host", "masque_host") ?: "cloudflareaccess.com")
                .put("path", field(p, "path") ?: "/")
                .put("warp", JSONObject()
                    .put("privateKey", privateKey)
                    .put("publicKey", publicKey)
                    .put("address", JSONArray(addresses)))
            val stream = JSONObject()
                .put("network", "masque")
                .put("security", "tls")
                .put("tlsSettings", JSONObject().put("serverName", sni).put("alpn", JSONArray().put("h3")))
                .put("masqueSettings", masque)
            field(p, "dialer-proxy", "dialer_proxy")?.let { parent ->
                val parentTag = tags[parent] ?: error("$name: dialer-proxy '$parent' не найден")
                require(parentTag != tag) { "$name: циклическая цепочка dialer-proxy" }
                stream.put("sockopt", JSONObject().put("dialerProxy", parentTag))
            }
            val outbound = JSONObject()
                .put("tag", tag)
                .put("protocol", "masque")
                .put("settings", JSONObject().put("address", server).put("port", port).put("remoteDNS", JSONArray(dns)))
                .put("streamSettings", stream)
            outbounds.put(outbound)
        }

        val selectedName = field(selected, "name") ?: "PORTAL MASQUE $selectedIndex"
        val selectedTag = tags[selectedName] ?: error("Выбранный MASQUE-сервер не найден")
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        val inbound = JSONObject().put("tag", "socks-in").put("listen", "127.0.0.1")
            .put("port", 10808).put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
        val config = JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("rules", JSONArray().put(
                JSONObject().put("type", "field").put("inboundTag", JSONArray().put("socks-in"))
                    .put("outboundTag", selectedTag)
            ))).toString()
        val endpoint = "${field(selected, "server")}:${field(selected, "port")} · ${field(selected, "sni")}"
        return PortalGateProfile(selectedName, "MASQUE / WARP", endpoint, config)
    }
}
