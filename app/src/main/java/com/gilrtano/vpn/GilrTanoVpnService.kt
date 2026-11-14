package com.gilrtano.vpn

import android.net.VpnService
import android.content.Intent
import android.os.ParcelFileDescriptor

/** Lifecycle shell only. It deliberately refuses to establish a tunnel until a real dataplane is integrated. */
class GilrTanoVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START -> {
                // Do not create a black-hole VPN. The native engine and Xray runtime
                // must be integrated and verified before establishing the TUN.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { tun?.close() }
        tun = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gilrtano.vpn.START"
        const val ACTION_STOP = "com.gilrtano.vpn.STOP"
    }
}
