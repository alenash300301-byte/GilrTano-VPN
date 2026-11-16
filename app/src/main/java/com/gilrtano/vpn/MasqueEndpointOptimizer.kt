package com.gilrtano.vpn

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Ranks only MASQUE endpoints actually present in the imported profile.
 *
 * A TCP TLS/H2 handshake is only a reachability pre-check, not proof that
 * CONNECT-IP works. The VPN service performs the required end-to-end probe
 * after starting the selected outbound. H3/QUIC endpoints are deliberately
 * left untouched because a TCP TLS probe cannot measure QUIC correctly.
 */
object MasqueEndpointOptimizer {
    private data class Candidate(
        val tag: String,
        val address: String,
        val port: Int,
        val sni: String
    )

    fun optimize(sourceConfig: String): String {
        val root = JSONObject(sourceConfig)
        val outbounds = root.optJSONArray("outbounds")
            ?: error("В MASQUE-конфигурации нет outbounds")

        val candidates = (0 until outbounds.length()).mapNotNull { index ->
            val outbound = outbounds.optJSONObject(index) ?: return@mapNotNull null
            if (outbound.optString("protocol") != "masque") return@mapNotNull null
            val settings = outbound.optJSONObject("settings") ?: return@mapNotNull null
            val stream = outbound.optJSONObject("streamSettings") ?: return@mapNotNull null
            val tls = stream.optJSONObject("tlsSettings") ?: return@mapNotNull null
            val alpn = tls.optJSONArray("alpn") ?: return@mapNotNull null
            val supportsH2 = (0 until alpn.length()).any { alpn.optString(it) == "h2" }
            if (!supportsH2) return@mapNotNull null

            val tag = outbound.optString("tag").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val address = settings.optString("address").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val port = settings.optInt("port").takeIf { it in 1..65535 }
                ?: return@mapNotNull null
            val sni = tls.optString("serverName").takeIf { it.isNotBlank() }
                ?: address
            Candidate(tag, address, port, sni)
        }

        require(candidates.isNotEmpty()) {
            "В профиле нет MASQUE endpoint с поддержкой HTTP/2; исходный профиль будет использован без изменения"
        }

        val ranked = candidates.mapNotNull { candidate ->
            probe(candidate)?.let { candidate to it }
        }.sortedBy { it.second }

        val best = ranked.firstOrNull()?.first
            ?: error("Не удалось установить TLS/HTTP2-соединение ни с одним MASQUE endpoint из профиля")

        val routing = root.optJSONObject("routing") ?: error("В MASQUE-конфигурации нет routing")
        val rules = routing.optJSONArray("rules") ?: error("В MASQUE-конфигурации нет routing.rules")
        var changed = false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val currentTag = rule.optString("outboundTag")
            if (candidates.any { it.tag == currentTag }) {
                rule.put("outboundTag", best.tag)
                changed = true
            }
        }
        require(changed) { "Не найдено правило маршрутизации на MASQUE outbound" }
        return root.toString()
    }

    private fun probe(candidate: Candidate): Long? {
        val raw = Socket()
        var tls: SSLSocket? = null
        val started = System.nanoTime()
        return try {
            raw.connect(InetSocketAddress(candidate.address, candidate.port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, candidate.sni, candidate.port, true) as SSLSocket
            tls.soTimeout = READ_TIMEOUT_MS
            val params = tls.sslParameters
            params.serverNames = listOf(SNIHostName(candidate.sni))
            params.applicationProtocols = arrayOf("h2")
            tls.sslParameters = params
            tls.startHandshake()
            if (tls.applicationProtocol != "h2") return null
            (System.nanoTime() - started) / 1_000_000L
        } catch (_: Exception) {
            null
        } finally {
            runCatching { tls?.close() }
            runCatching { raw.close() }
        }
    }

    private const val CONNECT_TIMEOUT_MS = 1800
    private const val READ_TIMEOUT_MS = 1800
}
