package com.gilrtano.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.follow.clashx.core.Core
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android runtime adapter for the FlClashX v0.5.7 gomobile/Mihomo core.
 * Subscription rules and proxy groups remain intact; only GeoIP/GeoSite
 * database endpoints are pinned to the selected Russia V2Ray rules release.
 */
internal object FlClashXEngine {
    private const val TAG = "GilrTano-FlClashX"
    private const val IPV4_ADDRESS = "198.18.0.1"
    private const val IPV4_PREFIX = 30
    private const val DNS = "198.18.0.2"
    private const val TUN_STACK = "gvisor"
    private const val TUN_MTU = 9000

    private const val GEOIP_URL =
        "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/geoip.dat"
    private const val GEOSITE_URL =
        "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/geosite.dat"

    @Volatile private var tunStarted = false

    /**
     * Updates only geoip/geosite in the top-level geox-url block.
     * Other subscription settings (including mmdb/asn) are preserved.
     */
    private fun withRussiaGeoDatabases(rawConfig: String): String {
        val lines = rawConfig.trim().lines().toMutableList()
        val sectionStart = lines.indexOfFirst {
            it == it.trimStart() && it.startsWith("geox-url:")
        }

        if (sectionStart < 0) {
            lines.add("")
            lines.add("geox-url:")
            lines.add("  geoip: \"$GEOIP_URL\"")
            lines.add("  geosite: \"$GEOSITE_URL\"")
            return lines.joinToString("\n") + "\n"
        }

        var sectionEnd = lines.size
        for (i in sectionStart + 1 until lines.size) {
            val line = lines[i]
            if (line.isNotBlank() && !line.trimStart().startsWith("#") &&
                line == line.trimStart() && line.contains(":")) {
                sectionEnd = i
                break
            }
        }

        fun setEntry(key: String, value: String) {
            val entry = Regex("^(\\s*)${Regex.escape(key)}\\s*:.*$")
            val existing = (sectionStart + 1 until sectionEnd).firstOrNull {
                entry.matches(lines[it])
            }
            val replacement = "  $key: \"$value\""
            if (existing != null) {
                lines[existing] = replacement
            } else {
                lines.add(sectionEnd, replacement)
                sectionEnd++
            }
        }

        setEntry("geoip", GEOIP_URL)
        setEntry("geosite", GEOSITE_URL)
        return lines.joinToString("\n") + "\n"
    }

    fun start(service: VpnService, rawConfig: String) {
        require(rawConfig.isNotBlank()) { "Пустая подписка" }
        val home = File(service.noBackupFilesDir, "flclashx").apply { mkdirs() }
        val configFile = File(home, "config.yaml")
        configFile.writeText(DnsProfiles.applyToYaml(service, withRussiaGeoDatabases(rawConfig)))

        Core.load()
        val initialized = CountDownLatch(1)
        var initError: String? = null
        val initParams = JSONObject()
            .put("home-dir", home.absolutePath)
            .put("version", 1)
        val setupParams = JSONObject()
            .put("selected-map", JSONObject())
            .put("test-url", "https://www.gstatic.com/generate_204")
            .put("skip-cert-verify", false)

        Core.quickSetup(initParams.toString(), setupParams.toString()) { result ->
            initError = result?.takeIf { it.isNotBlank() }
            initialized.countDown()
        }
        check(initialized.await(90, TimeUnit.SECONDS)) {
            "Ядро подписки не завершило инициализацию за 90 секунд"
        }
        check(initError == null) { "Ошибка запуска подписки: ${initError ?: "ошибка инициализации"}" }

        val pfd = service.Builder()
            .setSession("GilrTano VPN")
            .setMtu(TUN_MTU)
            .addAddress(IPV4_ADDRESS, IPV4_PREFIX)
            .addDnsServer(DNS)
            .addRoute("0.0.0.0", 0)
            .establish() ?: error("Android не создал VPN-интерфейс")

        val fd = pfd.detachFd()
        val started = runCatching {
            Core.startTun(
                fd = fd,
                protect = { socketFd -> service.protect(socketFd) },
                resolveUid = { protocol, source, target ->
                    if (android.os.Build.VERSION.SDK_INT < 29) -1
                    else runCatching {
                        service.getSystemService(android.net.ConnectivityManager::class.java)
                            .getConnectionOwnerUid(protocol, source, target)
                    }.getOrDefault(-1)
                },
                resolvePackage = { uid ->
                    service.packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
                },
                stack = TUN_STACK,
                address = "$IPV4_ADDRESS/$IPV4_PREFIX",
                dns = DNS,
                options = JSONObject().put("mtu", TUN_MTU).toString(),
            )
        }.getOrElse { error ->
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            throw error
        }

        if (!started) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            error("Ядро подписки отклонило VPN-интерфейс")
        }
        tunStarted = true
        Log.i(TAG, "FlClashX v0.5.7 TUN started; GeoIP/GeoSite configured in ${configFile.absolutePath}")
    }

    private fun invokeCoreMethod(method: String, arguments: JSONObject? = null): JSONObject {
        val completed = CountDownLatch(1)
        var response: JSONObject? = null
        val request = JSONObject().put("method", method)
        if (arguments != null) request.put("arguments", arguments)
        Core.invokeMethod(request.toString()) { bytes ->
            response = runCatching { JSONObject(bytes?.decodeToString().orEmpty()) }.getOrNull()
            completed.countDown()
        }
        check(completed.await(15, TimeUnit.SECONDS)) { "Ядро не ответило на запрос: $method" }
        val result = response ?: error("Пустой ответ ядра: $method")
        val error = result.optJSONObject("error")
        check(error == null) { error?.optString("message") ?: "Ошибка ядра: $method" }
        return result.optJSONObject("result") ?: JSONObject()
    }

    // Subscription server browsing uses the same live core API as the native client.
    /** Returns the live proxy groups and their members from the running Mihomo core. */
    fun getProxyGroups(): JSONObject {
        return invokeCoreMethod("getProxies")
    }

    /** Selects a member in a real subscription proxy group. */
    fun selectProxy(groupName: String, proxyName: String): String {
        val result = invokeCoreMethod(
            "changeProxy",
            JSONObject().put("group-name", groupName).put("proxy-name", proxyName),
        )
        return result.optString("message")
    }

    /** Tests one proxy through the core's own delay probe. */
    fun testProxyDelay(proxyName: String): Int {
        val result = invokeCoreMethod(
            "asyncTestDelay",
            JSONObject()
                .put("proxy-name", proxyName)
                .put("test-url", "https://www.gstatic.com/generate_204")
                .put("timeout", 5000),
        )
        return result.optInt("value", -1)
    }

    fun stop() {
        if (tunStarted) {
            runCatching { Core.stopTun() }
            tunStarted = false
        } else {
            runCatching { Core.stopTun() }
        }
        runCatching { Core.stopListener() }
    }
}
