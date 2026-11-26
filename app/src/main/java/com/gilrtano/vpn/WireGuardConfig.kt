package com.gilrtano.vpn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal wg-quick parser and sing-box 1.14 config builder for one-peer WireGuard profiles.
 * Stored profiles contain the original config encoded as URL-safe Base64; private keys are
 * never put into a URL query string or written to logs.
 */
internal object WireGuardConfig {
    private const val PREFIX = "wgconf://"

    data class Profile(
        val endpointHost: String,
        val endpointPort: Int,
        val privateKey: String,
        val addresses: List<String>,
        val publicKey: String,
        val presharedKey: String?,
        val allowedIps: List<String>,
        val keepalive: Int?,
        val mtu: Int?,
        val name: String
    )

    fun isStored(value: String): Boolean = value.startsWith(PREFIX, ignoreCase = true)

    fun storeConf(text: String): String {
        val profile = parse(text)
        val encoded = Base64.encodeToString(
            text.trim().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return PREFIX + encoded
    }

    fun profileFromStored(value: String): Profile {
        require(isStored(value)) { "Это не сохранённый профиль WireGuard" }
        val encoded = value.substring(PREFIX.length)
        val raw = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
        return parse(String(raw, Charsets.UTF_8))
    }

    fun parse(text: String): Profile {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').substringBefore(';').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                val name = line.substring(1, line.length - 1).trim().lowercase()
                require(name == "interface" || name == "peer") { "Неподдерживаемая секция [$name]" }
                current = sections.getOrPut(name) { linkedMapOf() }
                return@forEach
            }
            val section = current ?: return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            if (key == "address" || key == "allowedips") {
                section[key] = listOfNotNull(section[key], value).filter { it.isNotBlank() }.joinToString(",")
            } else {
                section[key] = value
            }
        }

        val iface = sections["interface"] ?: error("В файле отсутствует [Interface]")
        val peer = sections["peer"] ?: error("В файле отсутствует [Peer]")
        require(sections.keys.count { it == "interface" } == 1) { "Поддерживается одна секция [Interface]" }
        require(sections.keys.count { it == "peer" } == 1) { "Поддерживается один [Peer]" }
        val privateKey = required(iface, "privatekey", "PrivateKey")
        val publicKey = required(peer, "publickey", "PublicKey")
        validateKey(privateKey, "PrivateKey")
        validateKey(publicKey, "PublicKey")
        val endpoint = required(peer, "endpoint", "Endpoint")
        val endpointMatch = Regex("^\\[(.+)]:(\\d+)$").matchEntire(endpoint)
            ?: Regex("^([^:]+):(\\d+)$").matchEntire(endpoint)
            ?: error("Неверный Endpoint: $endpoint")
        val host = endpointMatch.groupValues[1]
        val port = endpointMatch.groupValues[2].toIntOrNull()
            ?: error("Неверный порт Endpoint")
        require(port in 1..65535) { "Порт Endpoint должен быть от 1 до 65535" }
        val addresses = csv(iface["address"])
        require(addresses.isNotEmpty()) { "В [Interface] отсутствует Address" }
        val allowed = csv(peer["allowedips"]).ifEmpty { listOf("0.0.0.0/0", "::/0") }
        val keepalive = peer["persistentkeepalive"]?.substringBefore('-')?.toIntOrNull()
        val mtu = iface["mtu"]?.toIntOrNull()
        if (mtu != null) require(mtu in 576..9000) { "MTU должен быть от 576 до 9000" }
        return Profile(host, port, privateKey, addresses, publicKey,
            peer["presharedkey"]?.takeIf { it.isNotBlank() }, allowed, keepalive, mtu,
            "WireGuard · $host")
    }

    fun toSingBoxJson(profile: Profile): String {
        val peer = JSONObject()
            .put("address", profile.endpointHost)
            .put("port", profile.endpointPort)
            .put("public_key", profile.publicKey)
            .put("allowed_ips", JSONArray(profile.allowedIps))
        profile.presharedKey?.let { peer.put("pre_shared_key", it) }
        profile.keepalive?.let { peer.put("persistent_keepalive_interval", it) }

        val endpoint = JSONObject()
            .put("type", "wireguard")
            .put("tag", "proxy")
            .put("address", JSONArray(profile.addresses))
            .put("private_key", profile.privateKey)
            .put("peers", JSONArray().put(peer))
        profile.mtu?.let { endpoint.put("mtu", it) }

        val inbound = JSONObject()
            .put("type", "mixed")
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("listen_port", 10808)

        val dns = JSONObject()
            .put("servers", JSONArray().put(
                JSONObject().put("type", "udp").put("tag", "dns-main")
                    .put("server", "1.1.1.1").put("server_port", 53)
            ))
            .put("final", "dns-main")
            .put("strategy", "prefer_ipv4")

        val route = JSONObject()
            .put("auto_detect_interface", true)
            .put("final", "proxy")
        return JSONObject()
            .put("log", JSONObject().put("level", "warn").put("timestamp", false))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(inbound))
            .put("endpoints", JSONArray().put(endpoint))
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", route)
            .toString()
    }

    private fun required(values: Map<String, String>, key: String, label: String): String =
        values[key]?.takeIf { it.isNotBlank() } ?: error("В конфиге отсутствует $label")

    private fun validateKey(value: String, label: String) {
        val decoded = runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull()
        require(decoded?.size == 32) { "$label должен быть корректным ключом WireGuard (32 байта Base64)" }
    }

    private fun csv(value: String?): List<String> =
        value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
