package com.gilrtano.vpn

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Portal profile conversion to sing-box JSON. The generated config always exposes
 * one loopback SOCKS inbound consumed by the existing TUN bridge.
 */
object PortalConfigBuilder {
    private const val SOCKS_PORT = 10808

    fun fromLinks(raw: String): List<ImportedNode> {
        val decoded = decode(raw)
        val links = decoded.lineSequence().map { it.trim() }
            .filter { it.startsWith("vless://", true) || it.startsWith("trojan://", true) ||
                it.startsWith("ss://", true) }
            .distinct().take(600).toList()
        return links.mapIndexedNotNull { index, link ->
            runCatching {
                val uri = Uri.parse(link)
                val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") }
                    ?.takeIf { it.isNotBlank() } ?: "${uri.host ?: "Portal"} #${index + 1}"
                ImportedNode(name, link)
            }.getOrNull()
        }
    }

    fun build(link: String): String {
        val uri = Uri.parse(link.trim())
        val outbound = when (uri.scheme?.lowercase()) {
            "vless" -> vless(uri)
            "trojan" -> trojan(uri)
            "ss" -> shadowsocks(uri)
            else -> error("Portal: поддерживаются VLESS, Trojan и Shadowsocks")
        }
        val inbound = JSONObject()
            .put("type", "socks").put("tag", "socks-in")
            .put("listen", "127.0.0.1").put("listen_port", SOCKS_PORT)
            .put("users", JSONArray()).put("udp", true)
        val direct = JSONObject().put("type", "direct").put("tag", "direct")
        val route = JSONObject().put("final", "proxy").put("auto_detect_interface", true)
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound).put(direct))
            .put("route", route)
            .toString()
    }

    private fun vless(u: Uri): JSONObject {
        val host = requireNotNull(u.host) { "VLESS: отсутствует сервер" }
        val uuid = u.userInfo ?: error("VLESS: отсутствует UUID")
        val port = if (u.port > 0) u.port else 443
        val out = JSONObject().put("type", "vless").put("tag", "proxy")
            .put("server", host).put("server_port", port).put("uuid", uuid)
        u.getQueryParameter("flow")?.takeIf { it.isNotBlank() }?.let { out.put("flow", it) }
        val security = u.getQueryParameter("security") ?: "none"
        if (security == "tls" || security == "reality") {
            val tls = JSONObject().put("enabled", true)
                .put("server_name", u.getQueryParameter("sni") ?: host)
                .put("alpn", JSONArray().put("h2").put("http/1.1"))
            u.getQueryParameter("fp")?.takeIf { it.isNotBlank() }?.let {
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
            }
            if (security == "reality") {
                val key = u.getQueryParameter("pbk") ?: error("REALITY: отсутствует public key")
                tls.put("reality", JSONObject().put("enabled", true)
                    .put("public_key", key).put("short_id", u.getQueryParameter("sid") ?: ""))
            }
            out.put("tls", tls)
        } else require(security == "none") { "VLESS: неизвестный тип защиты $security" }
        addTransport(out, u)
        return out
    }

    private fun trojan(u: Uri): JSONObject {
        val host = requireNotNull(u.host) { "Trojan: отсутствует сервер" }
        val password = u.userInfo ?: error("Trojan: отсутствует пароль")
        val out = JSONObject().put("type", "trojan").put("tag", "proxy")
            .put("server", host).put("server_port", if (u.port > 0) u.port else 443)
            .put("password", password)
            .put("tls", JSONObject().put("enabled", true)
                .put("server_name", u.getQueryParameter("sni") ?: host))
        u.getQueryParameter("fp")?.takeIf { it.isNotBlank() }?.let {
            out.getJSONObject("tls").put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
        }
        addTransport(out, u)
        return out
    }

    private fun shadowsocks(u: Uri): JSONObject {
        var userInfo = u.encodedUserInfo ?: error("Shadowsocks: отсутствуют method/password")
        if (!userInfo.contains(":")) {
            userInfo = String(Base64.decode(userInfo, Base64.DEFAULT), Charsets.UTF_8)
        }
        val decoded = URLDecoder.decode(userInfo, "UTF-8")
        val split = decoded.indexOf(':')
        require(split > 0 && split < decoded.length - 1) { "Shadowsocks: неверный method/password" }
        return JSONObject().put("type", "shadowsocks").put("tag", "proxy")
            .put("server", requireNotNull(u.host) { "Shadowsocks: отсутствует сервер" })
            .put("server_port", if (u.port > 0) u.port else 8388)
            .put("method", decoded.substring(0, split))
            .put("password", decoded.substring(split + 1))
    }

    private fun addTransport(out: JSONObject, u: Uri) {
        when (u.getQueryParameter("type") ?: "tcp") {
            "ws" -> {
                val headers = JSONObject()
                u.getQueryParameter("host")?.let { headers.put("Host", it) }
                out.put("transport", JSONObject().put("type", "ws")
                    .put("path", u.getQueryParameter("path") ?: "/").put("headers", headers))
            }
            "grpc" -> out.put("transport", JSONObject().put("type", "grpc")
                .put("service_name", u.getQueryParameter("serviceName") ?: ""))
            "tcp", "none" -> Unit
            else -> error("Portal: транспорт пока не поддержан")
        }
    }

    private fun decode(value: String): String {
        val text = value.trim()
        if (text.contains("://")) return text
        return runCatching { String(Base64.decode(text, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrDefault(text)
    }
}
