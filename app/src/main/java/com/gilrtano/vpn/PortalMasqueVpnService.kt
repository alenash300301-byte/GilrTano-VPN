package com.gilrtano.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject

/**
 * PORTAL runs in :portal so its libXray Go runtime never shares a process with
 * the legacy teapod-core Go runtime used by Quattro/free modes.
 */
class PortalMasqueVpnService : VpnService() {
    @Volatile private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var stopping = false
    @Volatile private var started = false

    private val controller = object : DialerController {
        override fun protectFd(fd: Long): Boolean = protect(fd.toInt())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopPortal()
            ACTION_START -> startPortal(intent.getIntExtra(EXTRA_ENDPOINT, 0))
        }
        return START_NOT_STICKY
    }

    private fun startPortal(endpointIndex: Int) {
        if (started) return
        if (endpointIndex !in PortalMasqueConfig.endpoints.indices) {
            sendStatus("Ошибка PORTAL: неизвестный сервер")
            stopSelf()
            return
        }
        started = true
        stopping = false
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Запуск PORTAL MASQUE…"))

        Thread({
            try {
                val credentials = PortalCredentialStore.load(this)
                    ?: throw IllegalStateException("Сначала добавьте ключи PORTAL")
                LibXray.registerDialerController(controller)
                LibXray.registerListenerController(controller)

                val pfd = Builder()
                    .setSession("GilrTano PORTAL")
                    .setMtu(1280)
                    .addAddress("10.77.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .establish() ?: throw IllegalStateException("Android не создал VPN-интерфейс")
                tunnel = pfd
                if (stopping) return@Thread

                // The resolver's own sockets must also be protected from the VPN.
                LibXray.setDNS(controller, "1.1.1.1:53")
                val config = PortalMasqueConfig.build(
                    PortalMasqueConfig.endpoints[endpointIndex],
                    credentials,
                    pfd.fd
                )
                val request = JSONObject()
                    .put("apiVersion", 3)
                    .put("method", "runXray")
                    .put("payload", JSONObject().put("xrayJson", config))
                val response = JSONObject(LibXray.invoke(request.toString()))
                if (!response.optBoolean("success", false)) {
                    throw IllegalStateException(response.optString("error", "libXray не запустился"))
                }
                if (stopping) return@Thread
                sendStatus("Подключено · PORTAL")
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Подключено · PORTAL MASQUE"))
            } catch (e: Exception) {
                Log.e(TAG, "PORTAL startup failed", e)
                sendStatus("Ошибка PORTAL: ${e.message ?: "неизвестная ошибка"}")
                stopPortal()
            }
        }, "GilrTano-Portal-start").start()
    }

    private fun stopPortal() {
        stopping = true
        Thread({
            runCatching {
                val request = JSONObject().put("apiVersion", 3).put("method", "stopXray")
                LibXray.invoke(request.toString())
            }
            runCatching { LibXray.resetDNS() }
            runCatching { tunnel?.close() }
            tunnel = null
            started = false
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            sendStatus("Отключено")
            stopSelf()
        }, "GilrTano-Portal-stop").start()
    }

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GilrTano PORTAL", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 81, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnect = PendingIntent.getService(
            this, 82, Intent(this, PortalMasqueVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("GilrTano VPN")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", disconnect)
            .build()
    }

    override fun onRevoke() {
        stopPortal()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (started && !stopping) stopPortal()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gilrtano.vpn.PORTAL_START"
        const val ACTION_STOP = "com.gilrtano.vpn.PORTAL_STOP"
        const val ACTION_STATUS = "com.gilrtano.vpn.PORTAL_STATUS"
        const val EXTRA_ENDPOINT = "portal_endpoint"
        const val EXTRA_STATUS = "portal_status"
        private const val TAG = "GilrTanoPortal"
        private const val CHANNEL_ID = "gilrtano_portal"
        private const val NOTIFICATION_ID = 77
    }
}
