package com.gilrtano.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.follow.clashx.core.Core
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android runtime adapter for the FlClashX v0.5.7 gomobile/Mihomo core.
 * The config is kept intact: proxy groups, rules, providers and subscription
 * metadata are parsed by Mihomo itself, not rewritten by the app.
 */
internal object FlClashXEngine {
    private const val TAG = "GilrTano-FlClashX"
    private const val IPV4_ADDRESS = "198.18.0.1"
    private const val IPV4_PREFIX = 30
    private const val DNS = "198.18.0.2"
    private const val TUN_STACK = "gvisor"
    private const val TUN_MTU = 9000

    @Volatile private var tunStarted = false

    fun start(service: VpnService, rawConfig: String) {
        require(rawConfig.isNotBlank()) { "Пустая подписка" }
        val home = File(service.noBackupFilesDir, "flclashx").apply { mkdirs() }
        File(home, "config.yaml").writeText(rawConfig.trim() + "\n")

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
            "FlClashX не завершил инициализацию за 90 секунд"
        }
        check(initError == null) { "FlClashX: ${initError ?: "ошибка инициализации"}" }

        val pfd = VpnService.Builder(service)
            .setSession("GilrTano VPN · FlClashX")
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
            error("FlClashX отклонил TUN-дескриптор")
        }
        tunStarted = true
        Log.i(TAG, "FlClashX v0.5.7 TUN started; config preserved at ${File(home, "config.yaml")}")
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
