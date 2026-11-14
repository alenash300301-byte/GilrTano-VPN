package com.gilrtano.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val requestCode = 4101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "GilrTano VPN · dataplane not configured"
            textSize = 18f
        }

        val connect = Button(this).apply {
            text = "Подключить"
            setOnClickListener {
                requestVpnPermission()
            }
        }

        val disconnect = Button(this).apply {
            text = "Отключить"
            setOnClickListener {
                startService(
                    Intent(
                        this@MainActivity,
                        GilrTanoVpnService::class.java
                    ).setAction(GilrTanoVpnService.ACTION_STOP)
                )
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 72, 36, 36)
            addView(status)
            addView(connect)
            addView(disconnect)
        })
    }

    private fun requestVpnPermission() {
        val prepare = VpnService.prepare(this)

        if (prepare != null) {
            startActivityForResult(prepare, requestCode)
        } else {
            onActivityResult(requestCode, RESULT_OK, null)
        }
    }

    @Deprecated("Deprecated by Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == this.requestCode && resultCode == RESULT_OK) {
            status.text = "VPN permission granted · native dataplane not yet integrated"

            startForegroundService(
                Intent(this, GilrTanoVpnService::class.java)
                    .setAction(GilrTanoVpnService.ACTION_START)
            )
        }
    }
}