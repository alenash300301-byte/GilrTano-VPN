package com.gilrtano.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.madeye.meow.core.MeowCore
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.UUID

class GilrTanoVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var stopping = false
    @Volatile private var paused = false
    @Volatile private var monitorRunning = false
    @Volatile private var automaticMode = false
    @Volatile private var usingFallback = false
    @Volatile private var consecutiveProbeFailures = 0
    @Volatile private var lastSwitchAt = 0L
    @Volatile private var activeConfig: String? = null
    @Volatile private var fallbackConfig: String? = null
    @Volatile private var preferredConfig: String? = null

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastUnderlyingNetwork: Network? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_PAUSE -> pauseTunnel()
            ACTION_RESUME -> {
                val config = activeConfig
                if (paused && !config.isNullOrBlank()) {
                    startTunnel(config, fallbackConfig, preferredConfig, automaticMode, true)
                }
            }
            ACTION_SWITCH -> {
                automaticMode = false
                fallbackConfig = null
                preferredConfig = null
                usingFallback = false
                readConfigExtra(intent, EXTRA_CONFIG)?.takeIf { it.isNotBlank() }?.let {
                    startTunnel(it, null, null, false, false)
                } ?: stopTunnel()
            }
            ACTION_START, null -> {
                val config = intent?.let { readConfigExtra(it, EXTRA_CONFIG) }
                if (config.isNullOrBlank()) {
                    broadcastStatus("Добавьте VLESS-ссылку или Clash YAML")
                    stopSelf()
                } else {
                    automaticMode = intent.getBooleanExtra(EXTRA_AUTO, false)
                    fallbackConfig = readConfigExtra(intent, EXTRA_FALLBACK_CONFIG)
                    preferredConfig = readConfigExtra(intent, EXTRA_PREFERRED_CONFIG)
                    usingFallback = false
                    consecutiveProbeFailures = 0
                    startTunnel(config, fallbackConfig, preferredConfig, automaticMode, false)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(
        config: String,
        fallback: String?,
        preferred: String?,
        automatic: Boolean,
        resumed: Boolean,
    ) {
        stopping = false
        paused = false
        monitorRunning = false
        activeConfig = config
        fallbackConfig = fallback
        preferredConfig = preferred
        automaticMode = automatic
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Запуск Meow…"))
        Thread({
            try {
                synchronized(this) { stopEngineAndTun() }
                if (stopping) return@Thread

                val home = File(noBackupFilesDir, "meow").apply { mkdirs() }
                seedGeoAssets(home)
                File(home, "config.yaml").writeText(prepareYaml(config))
                MeowCore.nativeSetHomeDir(home.absolutePath)

                val controllerSecret = UUID.randomUUID().toString()
                File(home, "controller.secret").apply {
                    writeText(controllerSecret)
                    setReadable(false, false)
                    setReadable(true, true)
                    setWritable(false, false)
                    setWritable(true, true)
                }
                val engineResult = MeowCore.nativeStartEngine(
                    "127.0.0.1:9090",
                    controllerSecret,
                    "",
                    false,
                )
                if (engineResult != 0) {
                    throw IllegalStateException("Meow: ${MeowCore.nativeGetLastError()}")
                }
                registerNetworkCallback()
                if (stopping) return@Thread

                val builder = Builder()
                    .setSession("GilrTano VPN")
                    .setMtu(1500)
                    .addAddress("172.19.0.1", 30)
                    .addDnsServer("172.19.0.2")
                    .addRoute("0.0.0.0", 0)
                if (MeowCore.nativeTunIpv6()) {
                    builder.addAddress("fdfe:dcba:9876::1", 126)
                        .addRoute("::", 0)
                }
                val pfd = builder.establish()
                    ?: throw IllegalStateException("Android не создал VPN-интерфейс")
                tun = pfd

                val tunResult = MeowCore.nativeStartTun2Socks(this, pfd.fd, DNS_PORT)
                if (tunResult != 0) {
                    throw IllegalStateException("TUN Meow: ${MeowCore.nativeGetLastError()}")
                }
                if (!MeowCore.nativeIsRunning()) {
                    throw IllegalStateException("Движок Meow остановился во время запуска")
                }

                val latency = probeTunnel()
                if (latency < 0L) {
                    throw IllegalStateException("Туннель запущен, но интернет через него не отвечает")
                }
                if (stopping) return@Thread

                val label = when { automaticMode && usingFallback -> "бесплатный резерв"; automaticMode -> "Quattro · Meow"; else -> "Meow" }
                broadcastStatus(if (resumed) "Возобновлено · $label" else "Подключено · $label")
                updateNotification("Подключено · защищённый трафик")
                if (automaticMode) startConnectionMonitor()
                Log.i(TAG, "Meow connected, latency=${latency}ms, ipv6=${MeowCore.nativeTunIpv6()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Meow VPN", e)
                if (!stopping && automaticMode && !usingFallback && !fallbackConfig.isNullOrBlank()) {
                    usingFallback = true
                    lastSwitchAt = System.currentTimeMillis()
                    broadcastStatus("Quattro недоступен. Запускаю резерв…")
                    startTunnel(fallbackConfig!!, fallbackConfig, preferredConfig, true, false)
                } else if (!stopping) {
                    broadcastStatus("Ошибка запуска: ${e.message ?: "неизвестная ошибка"}")
                    stopTunnel()
                }
            }
        }, "GilrTano-Meow-start").start()
    }

    private fun readConfigExtra(intent: Intent, key: String): String? {
        val value = intent.getStringExtra(key)?.takeIf { it.isNotBlank() } ?: return null
        val file = File(value)
        return if (file.isFile) file.readText() else value
    }

    private fun prepareYaml(raw: String): String {
        var yaml = raw.trim()
        require(yaml.isNotBlank()) { "Пустая конфигурация" }
        yaml = yaml.replace(
            Regex("(?m)^subscriptions:.*?(?=^[a-zA-Z][\\w-]*:|\\z)", RegexOption.DOT_MATCHES_ALL),
            "",
        ).trim()
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml)
        require(root is MutableMap<*, *>) { "Конфигурация должна быть Clash/Mihomo YAML" }
        @Suppress("UNCHECKED_CAST")
        val config = root as MutableMap<Any?, Any?>
        require(config.containsKey("proxies") || config.containsKey("proxy-providers")) {
            "В YAML не найдены proxies или proxy-providers"
        }
        val dns = LinkedHashMap<Any?, Any?>()
        (config["dns"] as? Map<*, *>)?.forEach { (key, value) -> dns[key] = value }
        fun hasValue(value: Any?): Boolean = when (value) {
            is Collection<*> -> value.isNotEmpty()
            is String -> value.isNotBlank()
            null -> false
            else -> true
        }
        if (!hasValue(dns["nameserver"])) {
            dns["nameserver"] = listOf("https://cloudflare-dns.com/dns-query", "https://dns.google/dns-query")
        }
        if (!hasValue(dns["default-nameserver"])) {
            dns["default-nameserver"] = listOf("1.1.1.1", "8.8.8.8")
        }
        dns["enable"] = true
        config["dns"] = dns
        return Yaml().dump(config)
    }

    private fun seedGeoAssets(home: File) {
        for (name in listOf("country.mmdb", "GeoLite2-ASN.mmdb")) {
            val target = File(home, if (name == "country.mmdb") "Country.mmdb" else name)
            if (target.exists() && target.length() > 0) continue
            try {
                assets.open("geox/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Geo database not bundled: geox/$name", e)
            }
        }
    }

    private fun probeTunnel(): Long {
        val endpoints = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://www.msftconnecttest.com/connecttest.txt",
        )
        for (endpoint in endpoints) {
            val started = System.nanoTime()
            var connection: HttpURLConnection? = null
            try {
                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.connectTimeout = PROBE_TIMEOUT_MS
                connection.readTimeout = PROBE_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                val code = connection.responseCode
                if (code in 200..399) return (System.nanoTime() - started) / 1_000_000L
            } catch (_: Exception) {
                // Try the next independent connectivity endpoint.
            } finally {
                connection?.disconnect()
            }
        }
        return -1L
    }

    private fun startConnectionMonitor() {
        if (monitorRunning || !automaticMode) return
        monitorRunning = true
        Thread({
            while (monitorRunning && automaticMode && !stopping && !paused) {
                try {
                    Thread.sleep(MONITOR_INTERVAL_MS)
                    if (!monitorRunning || stopping || paused) break
                    if (probeTunnel() < 0L) consecutiveProbeFailures++ else consecutiveProbeFailures = 0
                    if (consecutiveProbeFailures >= FAILURE_LIMIT &&
                        System.currentTimeMillis() - lastSwitchAt >= SWITCH_COOLDOWN_MS) {
                        if (!usingFallback && !fallbackConfig.isNullOrBlank()) {
                            usingFallback = true
                            lastSwitchAt = System.currentTimeMillis()
                            consecutiveProbeFailures = 0
                            broadcastStatus("Связь ухудшилась. Переключаю на резерв…")
                            startTunnel(fallbackConfig!!, fallbackConfig, preferredConfig, true, false)
                            break
                        }
                    }
                    if (usingFallback && !preferredConfig.isNullOrBlank() &&
                        System.currentTimeMillis() - lastSwitchAt >= RECOVERY_INTERVAL_MS) {
                        usingFallback = false
                        lastSwitchAt = System.currentTimeMillis()
                        consecutiveProbeFailures = 0
                        broadcastStatus("Проверяю восстановление Quattro…")
                        startTunnel(preferredConfig!!, fallbackConfig, preferredConfig, true, false)
                        break
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
            monitorRunning = false
        }, "GilrTano-Meow-monitor").apply { isDaemon = true }.start()
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = lastUnderlyingNetwork
                if (previous != null && previous != network) {
                    runCatching { MeowCore.nativeResetOutbound(network.toString()) }
                }
                lastUnderlyingNetwork = network
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val label = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    else -> "OTHER"
                }
                runCatching { MeowCore.nativeSetTrafficInterface(label) }
            }
        }
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { Log.w(TAG, "Could not register network callback", it) }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        lastUnderlyingNetwork = null
    }

    private fun pauseTunnel() {
        if (stopping || paused || tun == null) return
        paused = true
        monitorRunning = false
        broadcastStatus("Приостанавливаю VPN…")
        Thread({
            synchronized(this) { stopEngineAndTun() }
            if (paused && !stopping) {
                updateNotification("VPN приостановлен · трафик идёт напрямую")
                broadcastStatus("Приостановлено")
            }
        }, "GilrTano-Meow-pause").start()
    }

    private fun stopTunnel() {
        if (stopping) return
        stopping = true
        paused = false
        monitorRunning = false
        automaticMode = false
        broadcastStatus("Отключаю VPN…")
        Thread({
            synchronized(this) { stopEngineAndTun() }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            broadcastStatus("Отключено")
            stopSelf()
        }, "GilrTano-Meow-stop").start()
    }

    private fun stopEngineAndTun() {
        unregisterNetworkCallback()
        runCatching { MeowCore.nativeStopEngine() }
        runCatching { tun?.close() }
        tun = null
    }

    @Suppress("unused")
    fun connectionOwnerUid(
        protocol: Int,
        localAddr: ByteArray,
        localPort: Int,
        remoteAddr: ByteArray,
        remotePort: Int,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return try {
            connectivity.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(InetAddress.getByAddress(localAddr), localPort),
                InetSocketAddress(InetAddress.getByAddress(remoteAddr), remotePort),
            )
        } catch (_: Exception) {
            -1
        }
    }

    @Suppress("unused")
    fun packageForUid(uid: Int): String? = packageManager.getPackagesForUid(uid)?.firstOrNull()

    private fun broadcastStatus(value: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, value))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GilrTano VPN", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 2,
            Intent(this, GilrTanoVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GilrTano VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", disconnect)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        monitorRunning = false
        Thread({ synchronized(this) { stopEngineAndTun() } }, "GilrTano-Meow-destroy").start()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gilrtano.vpn.START"
        const val ACTION_SWITCH = "com.gilrtano.vpn.SWITCH"
        const val ACTION_STOP = "com.gilrtano.vpn.STOP"
        const val ACTION_PAUSE = "com.gilrtano.vpn.PAUSE"
        const val ACTION_RESUME = "com.gilrtano.vpn.RESUME"
        const val ACTION_STATUS = "com.gilrtano.vpn.STATUS"
        const val EXTRA_CONFIG = "xray_config"
        const val EXTRA_STATUS = "status"
        const val EXTRA_AUTO = "automatic_mode"
        const val EXTRA_FALLBACK_CONFIG = "fallback_config"
        const val EXTRA_PREFERRED_CONFIG = "preferred_config"

        private const val TAG = "GilrTanoVPN"
        private const val CHANNEL_ID = "gilrtano_vpn"
        private const val NOTIFICATION_ID = 76
        private const val DNS_PORT = 1053
        private const val PROBE_TIMEOUT_MS = 5000
        private const val MONITOR_INTERVAL_MS = 15_000L
        private const val FAILURE_LIMIT = 2
        private const val SWITCH_COOLDOWN_MS = 60_000L
        private const val RECOVERY_INTERVAL_MS = 5 * 60_000L

        fun meowConfigForNode(node: ImportedNode): String {
            node.yamlConfig?.takeIf { it.isNotBlank() }?.let { return it }
            val parsed = android.net.Uri.parse(node.uri.trim())
            require(parsed.scheme == "vless" && !parsed.host.isNullOrBlank()) {
                "Нужна VLESS-ссылка или Clash/Mihomo YAML"
            }
            val uuid = parsed.userInfo ?: parsed.encodedUserInfo ?: error("В ссылке нет UUID")
            val security = parsed.getQueryParameter("security") ?: "none"
            val network = parsed.getQueryParameter("type") ?: "tcp"
            require(security in setOf("reality", "tls", "none")) { "Неизвестный тип защиты VLESS: $security" }
            require(network in setOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) {
                "Неподдерживаемый транспорт VLESS: $network"
            }

            val proxy = linkedMapOf<String, Any>(
                "name" to "GilrTanoProxy",
                "type" to "vless",
                "server" to parsed.host!!,
                "port" to (parsed.port.takeIf { it > 0 } ?: 443),
                "uuid" to uuid,
                "network" to network,
            )
            parsed.getQueryParameter("flow")?.takeIf { it.isNotBlank() }?.let { proxy["flow"] = it }
            if (security != "none") {
                proxy["tls"] = true
                proxy["servername"] = parsed.getQueryParameter("sni")
                    ?: parsed.getQueryParameter("serverName") ?: parsed.host!!
                parsed.getQueryParameter("fp")?.takeIf { it.isNotBlank() }?.let {
                    proxy["client-fingerprint"] = it
                }
            }
            if (security == "reality") {
                val publicKey = parsed.getQueryParameter("pbk")
                    ?: error("В VLESS REALITY-ссылке отсутствует public key")
                val reality = linkedMapOf<String, Any>("public-key" to publicKey)
                parsed.getQueryParameter("sid")?.let { reality["short-id"] = it }
                proxy["reality-opts"] = reality
            }
            when (network) {
                "ws", "httpupgrade" -> {
                    val options = linkedMapOf<String, Any>()
                    parsed.getQueryParameter("path")?.let { options["path"] = it }
                    parsed.getQueryParameter("host")?.let { options["headers"] = mapOf("Host" to it) }
                    proxy[if (network == "ws") "ws-opts" else "http-upgrade-opts"] = options
                }
                "grpc" -> parsed.getQueryParameter("serviceName")?.let {
                    proxy["grpc-opts"] = mapOf("grpc-service-name" to it)
                }
                "xhttp" -> {
                    val options = linkedMapOf<String, Any>()
                    parsed.getQueryParameter("path")?.let { options["path"] = it }
                    parsed.getQueryParameter("mode")?.let { options["mode"] = it }
                    proxy["xhttp-opts"] = options
                }
            }
            val proxyName = "GilrTanoProxy"
            val config = linkedMapOf<String, Any>(
                "mode" to "rule",
                "proxies" to listOf(proxy),
                "rules" to listOf("MATCH,$proxyName"),
                "dns" to linkedMapOf(
                    "enable" to true,
                    "enhanced-mode" to "fake-ip",
                    "nameserver" to listOf("https://cloudflare-dns.com/dns-query", "https://dns.google/dns-query"),
                    "default-nameserver" to listOf("1.1.1.1", "8.8.8.8"),
                ),
            )
            return Yaml().dump(config)
        }
    }
}
