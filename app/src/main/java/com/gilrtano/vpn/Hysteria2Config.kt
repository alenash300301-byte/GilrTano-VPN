package com.gilrtano.vpn

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Builds a sing-box outbound configuration for a Hysteria2 share URI. */
object Hysteria2Config {
    fun fromUri(raw: String): String {
        val uri = Uri.parse(raw.trim())
        require(uri.scheme == "hy2" || uri.scheme == "hysteria2") { "Нужна ссылка hy2:// или hysteria2://" }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: error("В Hysteria2-профиле отсутствует сервер")
        val port = uri.port.takeIf { it in 1..65535 } ?: error("В Hysteria2-профиле некорректный порт")
        val password = uri.userInfo?.takeIf { it.isNotEmpty() } ?: error("В Hysteria2-профиле отсутствует пароль")
        val tls = JSONObject().put("enabled", true)
        uri.getQueryParameter("sni")?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
        tls.put("insecure", uri.getQueryParameter("insecure")?.toBooleanStrictOrNull() ?: false)
        uri.getQueryParameter("alpn")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }?.let { tls.put("alpn", JSONArray(it)) }
        val outbound = JSONObject()
            .put("type", "hysteria2")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("password", password)
            .put("tls", tls)
        val inbound = JSONObject()
            .put("type", "socks")
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("listen_port", 10808)
            .put("udp", true)
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound)
                .put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("final", "proxy").put("auto_detect_interface", true))
            .toString()
    }
}
