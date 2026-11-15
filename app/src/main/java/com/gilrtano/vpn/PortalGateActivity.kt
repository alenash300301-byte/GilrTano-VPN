package com.gilrtano.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** PORTAL GATE profile picker. Profiles remain in memory; private keys are not written to preferences. */
class PortalGateActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var picker: Spinner
    private val requestCode = 7314
    private var pendingConfig: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(0xff050507.toInt())
        }
        root.addView(TextView(this).apply {
            text = "PORTAL GATE"
            textSize = 25f
            setTextColor(0xfff4f1ed.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "AmneziaWG · импорт серверного архива"
            textSize = 13f
            setTextColor(0xffaaaaaa.toInt())
            setPadding(0, 8, 0, 20)
        })
        status = TextView(this).apply {
            text = if (PortalGateSession.profiles.isEmpty()) "Архив ещё не импортирован" else "Загружено серверов: ${PortalGateSession.profiles.size}"
            setTextColor(0xffbdbdbd.toInt())
            setPadding(0, 8, 0, 12)
        }
        root.addView(status)
        val import = Button(this).apply {
            text = "ИМПОРТИРОВАТЬ ZIP"
            setOnClickListener {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                }
                startActivityForResult(pick, IMPORT_REQUEST)
            }
        }
        root.addView(import)
        picker = Spinner(this)
        root.addView(picker)
        val connect = Button(this).apply {
            text = "ПОДКЛЮЧИТЬ PORTAL GATE"
            setOnClickListener { connectSelected() }
        }
        root.addView(connect)
        root.addView(Button(this).apply {
            text = "НАЗАД"
            setOnClickListener { finish() }
        })
        setContentView(root)
        refreshProfiles()
    }

    private fun refreshProfiles() {
        val profiles = PortalGateSession.profiles
        picker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            profiles.map { "${it.country} · ${it.name} · ${it.endpoint}" })
        status.text = if (profiles.isEmpty()) "Архив ещё не импортирован" else "Загружено серверов: ${profiles.size}"
    }

    @Deprecated("Legacy document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMPORT_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                contentResolver.openInputStream(uri)?.use { PortalGateImporter.parseZip(it) }
                    ?: error("Не удалось открыть архив")
            }.onSuccess {
                PortalGateSession.profiles = it
                refreshProfiles()
                Toast.makeText(this, "Импортировано серверов: ${it.size}", Toast.LENGTH_LONG).show()
            }.onFailure {
                status.text = "Ошибка импорта: ${it.message}"
            }
        } else if (requestCode == requestCode && resultCode == RESULT_OK) {
            pendingConfig?.let { startPortal(it) }
            pendingConfig = null
        }
    }

    private fun connectSelected() {
        val profile = PortalGateSession.profiles.getOrNull(picker.selectedItemPosition)
        if (profile == null) {
            Toast.makeText(this, "Сначала импортируй ZIP-архив PORTAL GATE", Toast.LENGTH_LONG).show()
            return
        }
        pendingConfig = profile.config
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission, this.requestCode)
        else startPortal(profile.config)
    }

    private fun startPortal(config: String) {
        startForegroundService(Intent(this, GilrTanoVpnService::class.java)
            .setAction(GilrTanoVpnService.ACTION_START_PORTAL)
            .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config))
        status.text = "Запускаю PORTAL GATE…"
        Toast.makeText(this, "Запуск PORTAL GATE", Toast.LENGTH_SHORT).show()
    }

    companion object { private const val IMPORT_REQUEST = 7313 }
}

object PortalGateSession {
    @Volatile var profiles: List<PortalGateProfile> = emptyList()
}
