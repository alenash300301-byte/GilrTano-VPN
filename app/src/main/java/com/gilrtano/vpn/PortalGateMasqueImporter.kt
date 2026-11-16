package com.gilrtano.vpn

import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.InputStream
import java.util.Locale

/** Imports Mihomo-style Cloudflare WARP-over-MASQUE entries into the bundled Xray MASQUE schema. */
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
        return proxies.mapIndexed { i, p -> buildProfile(p, proxies, tags, i) }
    }

    private fun buildProfile(
        selected: Map<String, Any?>,
        proxies: List<Map<String, Any?>>,
        tags: Map<String, String>,
        selectedIndex: Int
    ): PortalGateProfile {
        fun field(p: Map<String, Any?>, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> p[k]?.toString()?.trim()?.takeIf(String::isNotEmpty) }

        fun stringList(value: Any?): List<String> = when (value) {
            is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> value.split(',').map(String::trim).filter(String::isNotEmpty)
            else -> emptyList()
        }

        val outbounds = JSONArray()
        proxies.forEachIndexed { index, p ->
            val name = field(p, "name") ?: "PORTAL MASQUE $index"
            val tag = tags[name] ?: error("$name: не удалось назначить тег")
            val server = field(p, "server") ?: error("$name: отсутствует server")
            val port = field(p, "port")?.toIntOrNull() ?: error("$name: некорректный port")
            require(port in 1..65535) { "$name: порт вне диапазона" }
            val sni = field(p, "sni", "server-name", "servername", "server_name")
                ?: error("$name: отсутствует SNI")
            val privateKey = field(p, "private-key", "private_key", "privateKey")
                ?: error("$name: отсутствует private-key")
            val publicKey = field(p, "public-key", "public_key", "publicKey")
                ?: error("$name: отсутствует public-key")
            val rawAddresses = stringList(p["ip"] ?: p["address"])
            require(rawAddresses.isNotEmpty()) { "$name: отсутствует IP-адрес WARP" }
            // Xray's WARP schema requires CIDR prefixes. Some Mihomo exports
            // contain bare interface addresses, so normalize those explicitly.
            val addresses = rawAddresses.map { raw ->
                val value = raw.trim()
                val address = value.substringBefore('/')
                require('%' !in address && address.isNotBlank()) {
                    "$name: некорректный IP-адрес WARP '$value'"
                }
                val parsed = if (':' in address) {
                    runCatching { java.net.InetAddress.getByName(address) }
                        .getOrNull()?.takeIf { it is java.net.Inet6Address }
                } else {
                    val octets = address.split('.')
                    if (octets.size == 4 && octets.all {
                        it.isNotEmpty() && it.all(Char::isDigit) &&
                            (it.length == 1 || it[0] != '0') && it.toIntOrNull()?.let { n -> n in 0..255 } == true
                    }) runCatching { java.net.InetAddress.getByName(address) }
                        .getOrNull()?.takeIf { it is java.net.Inet4Address }
                    else null
                } ?: error("$name: некорректный IP-адрес WARP '$value'")
                val prefix = if ('/' in value) value.substringAfter('/').toIntOrNull()
                    ?: error("$name: некорректная маска WARP '$value'")
                else parsed.address.size * 8
                require(prefix in 0..(parsed.address.size * 8)) {
                    "$name: маска WARP вне диапазона '$value'"
                }
                "$address/$prefix"
            }.distinct()

            // Mihomo's "network" controls the MASQUE HTTP version. Xray selects
            // HTTP/2 vs HTTP/3 from TLS ALPN; forcing h3 breaks every masque-h2 profile.
            val network = field(p, "network")?.lowercase(Locale.ROOT) ?: "quic"
            val alpn = when (network) {
                "h2" -> "h2"
                "h3", "quic" -> "h3"
                else -> error("$name: неподдерживаемая сеть MASQUE '$network' (ожидается h2 или quic)")
            }

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
                .put("tlsSettings", JSONObject().put("serverName", sni).put("alpn", JSONArray().put(alpn)))
                .put("masqueSettings", masque)
            field(p, "dialer-proxy", "dialer_proxy")?.let { parent ->
                val parentTag = tags[parent] ?: error("$name: dialer-proxy '$parent' не найден")
                require(parentTag != tag) { "$name: циклическая цепочка dialer-proxy" }
                stream.put("sockopt", JSONObject().put("dialerProxy", parentTag))
            }
            // Kiberportal-style WARP profiles may expose the same resolver list
            // under different YAML keys. Xray's MASQUE client accepts IP literals
            // only; never silently discard an invalid user-supplied resolver.
            val rawDns = listOf("dns", "remote-dns", "remote_dns", "nameserver", "name-server")
                .firstNotNullOfOrNull { key -> p[key] }
            val suppliedDns = stringList(rawDns).distinct()
            fun isIpLiteral(value: String): Boolean = if (':' in value) {
                '%' !in value && runCatching {
                    java.net.InetAddress.getByName(value) is java.net.Inet6Address
                }.getOrDefault(false)
            } else {
                val octets = value.split('.')
                octets.size == 4 && octets.all {
                    it.isNotEmpty() && it.all(Char::isDigit) &&
                        (it.length == 1 || it[0] != '0') && it.toIntOrNull()?.let { n -> n in 0..255 } == true
                }
            }
            val dns = if (suppliedDns.isEmpty()) {
                // Match Xray's documented Cloudflare resolver set so IPv4- and
                // IPv6-assigned WARP profiles both have a same-family resolver.
                listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
            } else {
                suppliedDns.also { servers ->
                    require(servers.all(::isIpLiteral)) {
                        "$name: DNS должен содержать IP-адреса, а не доменные имена"
                    }
                }
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
        val endpoint = "${field(selected, "server")}:${field(selected, "port")} · ${field(selected, "sni")} · ${field(selected, "network") ?: "quic"}"
        return PortalGateProfile(selectedName, "MASQUE / WARP", endpoint, config)
    }
}
