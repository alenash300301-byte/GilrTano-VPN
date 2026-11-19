package com.gilrtano.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.follow.clashx.core.Core
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * First-party Android lifecycle adapter for the pinned FlClashX Mihomo bridge.
 * Xray remains available until the app's connection path explicitly selects this engine.
 */
class MihomoRuntime(private val service: VpnService) {
    private var tun: ParcelFileDescriptor? = null
    private var started = false

    fun start(xrayConfig: String): String {
        check(!started) { "Mihomo уже запущен" }
        Core.load()

        val home = File(service.filesDir, "mihomo/runtime").apply { mkdirs() }
        val configFile = File(home, "config.yaml")
        configFile.writeText(buildConfig(xrayConfig), Charsets.UTF_8)

        val setupDone = CountDownLatch(1)
        var setupError: String? = null
        val init = JSONObject()
            .put("home-dir", home.absolutePath)
            .put("version", android.os.Build.VERSION.SDK_INT)
        val setup = JSONObject()
            .put("selected-map", JSONObject())
            .put("test-url", "https://www.gstatic.com/generate_204")
            .put("skip-cert-verify", false)

        Core.quickSetup(init.toString(), setup.toString()) { result ->
            setupError = result?.takeIf { it.isNotBlank() }
            setupDone.countDown()
        }
        check(setupDone.await(45, TimeUnit.SECONDS)) { "Тайм-аут инициализации Mihomo" }
        check(setupError == null) { "Mihomo: $setupError" }

        val descriptor = service.Builder()
            .setSession("GilrTano VPN · Mihomo")
            .setMtu(1280)
            .addAddress("10.77.0.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .establish() ?: error("Android не создал VPN-интерфейс")
        tun = descriptor

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val ok = Core.startTun(
            descriptor.fd,
            { fd -> service.protect(fd) },
            { protocol, source, target ->
                runCatching {
                    connectivity.getConnectionOwnerUid(protocol, source, target)
                }.getOrDefault(-1)
            },
            { uid ->
                service.packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
            },
            "gvisor",
            "10.77.0.2/30",
            "1.1.1.1,8.8.8.8",
            """{"mtu":1280}"""
        )
        check(ok) { "Mihomo не смог запустить TUN" }
        started = true
        return "Mihomo и TUN запущены"
    }

    fun stop() {
        runCatching { Core.stopTun() }
        runCatching { tun?.close() }
        tun = null
        started = false
    }

    private fun buildConfig(xrayConfig: String): String {
        val root = JSONObject(xrayConfig)
        val outbounds = root.optJSONArray("outbounds") ?: error("В Xray-профиле нет outbounds")
        val outbound = (0 until outbounds.length())
            .map { outbounds.getJSONObject(it) }
            .firstOrNull { it.optString("protocol") == "vless" }
            ?: error("Профиль не содержит VLESS outbound")
        val vnext = outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        val user = vnext.getJSONArray("users").getJSONObject(0)
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
        val security = stream.optString("security", "none")
        val network = stream.optString("network", "tcp")

        val proxy = linkedMapOf<String, Any>(
            "name" to "GilrTano-Active",
            "type" to "vless",
            "server" to vnext.getString("address"),
            "port" to vnext.optInt("port", 443),
            "uuid" to user.getString("id"),
            "network" to network,
            "udp" to true
        )
        user.optString("flow").takeIf { it.isNotBlank() }?.let { proxy["flow"] = it }

        if (security == "tls" || security == "reality") {
            proxy["tls"] = true
            val tls = stream.optJSONObject("tlsSettings")
            val reality = stream.optJSONObject("realitySettings")
            val serverName = when {
                reality != null -> reality.optString("serverName")
                tls != null -> tls.optString("serverName")
                else -> ""
            }
            if (serverName.isNotBlank()) proxy["servername"] = serverName
            val fingerprint = reality?.optString("fingerprint")
                ?: tls?.optString("fingerprint")
            if (!fingerprint.isNullOrBlank()) proxy["client-fingerprint"] = fingerprint
            if (reality != null) {
                proxy["reality-opts"] = linkedMapOf(
                    "public-key" to reality.optString("publicKey"),
                    "short-id" to reality.optString("shortId")
                ).filterValues { it.isNotBlank() }
            }
            val alpn = tls?.optJSONArray("alpn")
            if (alpn != null) proxy["alpn"] = (0 until alpn.length()).map { alpn.getString(it) }
        }

        when (network) {
            "ws" -> {
                val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
                val opts = linkedMapOf<String, Any>("path" to ws.optString("path", "/"))
                val headers = ws.optJSONObject("headers")
                headers?.optString("Host")?.takeIf { it.isNotBlank() }?.let { opts["headers"] = mapOf("Host" to it) }
                proxy["ws-opts"] = opts
            }
            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                proxy["grpc-opts"] = mapOf("grpc-service-name" to grpc.optString("serviceName"))
            }
            "httpupgrade" -> {
                val opts = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                proxy["http-upgrade-opts"] = mapOf("path" to opts.optString("path", "/"))
            }
            "xhttp" -> {
                val opts = stream.optJSONObject("xhttpSettings") ?: JSONObject()
                proxy["network"] = "xhttp"
                proxy["xhttp-opts"] = mapOf(
                    "path" to opts.optString("path", "/"),
                    "mode" to opts.optString("mode", "auto")
                )
            }
        }

        val config = linkedMapOf<String, Any>(
            "mixed-port" to 10808,
            "allow-lan" to false,
            "mode" to "rule",
            "log-level" to "info",
            "ipv6" to false,
            "proxies" to listOf(proxy),
            "proxy-groups" to listOf(
                mapOf("name" to "PROXY", "type" to "select", "proxies" to listOf("GilrTano-Active"))
            ),
            "rules" to listOf("MATCH,PROXY")
        )
        return Yaml().dump(config)
    }
}
