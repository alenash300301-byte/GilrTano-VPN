package com.gilrtano.vpn

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/** Converts share links into the Xray JSON dialect bundled in Teapodcore. */
object ProtocolConfig {
    fun toXrayConfig(raw: String): String {
        val value = raw.trim()
        return when {
            value.startsWith("vless://", true) -> withXhttpExtra(value, GilrTanoVpnService.xrayConfigFromVless(value))
            value.startsWith("vmess://", true) -> vmess(value)
            value.startsWith("ss://", true) -> shadowsocks(value)
            else -> error("Поддерживаются VLESS, VMess и Shadowsocks для Xray")
        }
    }

    private fun baseConfig(outbound: JSONObject): String {
        val inbound = JSONObject().put("tag", "socks-in").put("listen", "127.0.0.1")
            .put("port", 10808).put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
        return JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound).put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
            .put("routing", JSONObject().put("rules", JSONArray().put(
                JSONObject().put("type", "field").put("inboundTag", JSONArray().put("socks-in")).put("outboundTag", "proxy")
            ))).toString()
    }

    private fun withXhttpExtra(uri: String, config: String): String {
        val parsed = Uri.parse(uri)
        val rawExtra = parsed.getQueryParameter("extra") ?: return config
        val extra = runCatching { JSONObject(rawExtra) }.getOrElse {
            error("Параметр XHTTP extra содержит некорректный JSON")
        }
        val root = JSONObject(config)
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        val stream = outbound.getJSONObject("streamSettings")
        val settings = stream.optJSONObject("xhttpSettings") ?: JSONObject().also { stream.put("xhttpSettings", it) }
        settings.put("extra", extra)
        // Preserve XHTTP-specific knobs used by newer Xray share links.
        listOf("mode", "path").forEach { key ->
            parsed.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let { settings.put(key, it) }
        }
        return root.toString()
    }

    private fun vmess(link: String): String {
        val payload = link.substringAfter("vmess://").substringBefore('#').trim()
        val jsonText = decodeBase64(payload) ?: error("Не удалось декодировать VMess-ссылку")
        val data = JSONObject(jsonText)
        val host = data.optString("add").ifBlank { data.optString("address") }
        val port = data.optString("port").toIntOrNull() ?: 443
        val id = data.optString("id")
        require(host.isNotBlank() && id.isNotBlank()) { "В VMess-профиле отсутствует адрес или UUID" }
        val security = data.optString("tls").lowercase()
        val network = data.optString("net", "tcp").lowercase()
        val user = JSONObject().put("id", id).put("alterId", data.optInt("aid", 0))
            .put("security", data.optString("scy").ifBlank { "auto" })
        val stream = JSONObject().put("network", network)
            .put("security", if (security == "tls" || security == "reality") security else "none")
        val sni = data.optString("sni").ifBlank { data.optString("host") }
        if (security == "tls") {
            stream.put("tlsSettings", JSONObject().put("serverName", sni.ifBlank { host }))
        }
        when (network) {
            "ws" -> {
                val ws = JSONObject().put("path", data.optString("path", "/"))
                if (data.optString("host").isNotBlank()) ws.put("headers", JSONObject().put("Host", data.optString("host")))
                stream.put("wsSettings", ws)
            }
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", data.optString("path")))
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().put("path", data.optString("path", "/")))
            "xhttp" -> stream.put("xhttpSettings", JSONObject().put("path", data.optString("path", "/")).put("mode", data.optString("mode", "auto")))
        }
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user))
            ))).put("streamSettings", stream)
        return baseConfig(outbound)
    }

    private fun shadowsocks(link: String): String {
        val raw = link.substringAfter("ss://").substringBefore('#')
        val authority = raw.substringBefore('?')
        val at = authority.lastIndexOf('@')
        val decodedWhole = if (at < 0) decodeBase64(authority) else null
        val credentialsAndServer = decodedWhole ?: authority
        val splitAt = credentialsAndServer.lastIndexOf('@')
        require(splitAt > 0) { "Неверная Shadowsocks-ссылка: отсутствует method:password@server" }
        val credentials = credentialsAndServer.substring(0, splitAt)
        val serverPart = credentialsAndServer.substring(splitAt + 1)
        val methodPassword = if (credentials.contains(':')) credentials else decodeBase64(credentials)
            ?: error("Не удалось декодировать параметры Shadowsocks")
        val colon = methodPassword.indexOf(':')
        require(colon > 0) { "В Shadowsocks-профиле отсутствует метод шифрования или пароль" }
        val method = methodPassword.substring(0, colon)
        val password = methodPassword.substring(colon + 1)
        val parsed = Uri.parse("ss://$serverPart")
        val host = parsed.host ?: error("В Shadowsocks-профиле отсутствует сервер")
        val port = parsed.port.takeIf { it in 1..65535 } ?: error("В Shadowsocks-профиле некорректный порт")
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("method", method).put("password", password)
            )))
        return baseConfig(outbound)
    }

    private fun decodeBase64(value: String): String? {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
            ?: runCatching { String(Base64.decode(padded, Base64.URL_SAFE or Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
    }
}
