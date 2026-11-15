package com.gilrtano.vpn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds Xray WARP-over-MASQUE profiles for the isolated libXray migration.
 * Credentials are supplied at runtime and must never be committed to source control.
 *
 * This is the endpoint-level generator only. It does not yet reproduce the
 * original PORTAL Clash rule providers and category routing.
 */
data class PortalWarpCredentials(
    val privateKey: String,
    val publicKey: String,
    val ipv4: String,
    val ipv6: String
)

data class PortalMasqueEndpoint(
    val name: String,
    val address: String,
    val port: Int = 443,
    val serverName: String,
    val alpn: List<String> = listOf("h2")
)

object PortalMasqueConfig {
    val endpoints = listOf(
        PortalMasqueEndpoint("КИБЕРПОРТАЛ", "162.159.198.2", serverName = "4pda.to"),
        PortalMasqueEndpoint("КИБЕРПОРТАЛ 2", "162.159.199.2", serverName = "mail.ru")
    )

    /**
     * Builds a single-endpoint config. Android VpnService's TUN descriptor is
     * passed directly to Xray through the documented root env field.
     */
    fun build(
        endpoint: PortalMasqueEndpoint,
        credentials: PortalWarpCredentials,
        tunFd: Int
    ): String {
        require(tunFd >= 0) { "Некорректный TUN file descriptor" }
        require(endpoint.port in 1..65535) { "Некорректный порт MASQUE" }
        require(endpoint.address.isNotBlank()) { "Не указан MASQUE endpoint" }
        require(endpoint.serverName.isNotBlank()) { "Не указан TLS SNI" }
        require(endpoint.alpn.isNotEmpty()) { "Не указан ALPN" }

        val privateKey = credentials.privateKey.trim()
        val publicKey = credentials.publicKey.trim()
        require(privateKey.isNotEmpty()) { "Не задан WARP private key" }
        require(publicKey.isNotEmpty()) { "Не задан WARP public key" }
        require(credentials.ipv4.isNotBlank() && credentials.ipv6.isNotBlank()) {
            "Не заданы адреса WARP IPv4/IPv6"
        }
        // Catch common copy/paste errors without logging or exposing key material.
        val encoded = privateKey.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .trim()
        require(runCatching { Base64.decode(encoded, Base64.DEFAULT).isNotEmpty() }.getOrDefault(false)) {
            "WARP private key должен быть PEM или Base64 DER"
        }

        val warp = JSONObject()
            .put("privateKey", privateKey)
            .put("publicKey", publicKey)
            .put("address", JSONArray()
                .put(if (credentials.ipv4.contains("/")) credentials.ipv4 else "${credentials.ipv4}/32")
                .put(if (credentials.ipv6.contains("/")) credentials.ipv6 else "${credentials.ipv6}/128"))

        val masqueOutbound = JSONObject()
            .put("tag", "portal-warp")
            .put("protocol", "masque")
            .put("settings", JSONObject()
                .put("address", endpoint.address)
                .put("port", endpoint.port))
            .put("streamSettings", JSONObject()
                .put("method", "masque")
                .put("security", "tls")
                .put("tlsSettings", JSONObject()
                    .put("serverName", endpoint.serverName)
                    .put("alpn", JSONArray(endpoint.alpn)))
                .put("masqueSettings", JSONObject().put("warp", warp)))

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("env", JSONObject().put("xray.tun.fd", tunFd.toString()))
            .put("inbounds", JSONArray().put(
                JSONObject()
                    .put("tag", "portal-tun")
                    .put("protocol", "tun")
                    .put("settings", JSONObject()
                        .put("name", "gilrtano-portal")
                        .put("mtu", 1280)
                        .put("gateway", JSONArray().put("10.77.0.1/30").put("fd00:77::1/126"))
                        .put("dns", JSONArray().put("1.1.1.1").put("2606:4700:4700::1111")))
            ))
            .put("outbounds", JSONArray()
                .put(masqueOutbound)
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom").put("settings", JSONObject()))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole").put("settings", JSONObject())))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray().put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("portal-tun"))
                    .put("outboundTag", "portal-warp")
            )))
            .toString(2)
    }
}
