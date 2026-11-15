package com.gilrtano.vpn

import android.app.Activity
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.net.VpnService
import android.net.Uri
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
    private var receiverRegistered = false
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra(GilrTanoVpnService.EXTRA_STATUS) ?: status.text
        }
    }

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
            text = "AmneziaWG / MASQUE · импорт ZIP или YAML"
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
            text = "ИМПОРТИРОВАТЬ ZIP / YAML"
            setOnClickListener {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
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
        restoreArchive()
        refreshProfiles()
    }


    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            val filter = IntentFilter(GilrTanoVpnService.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {\n                @Suppress("DEPRECATION")\n                registerReceiver(statusReceiver, filter)\n            }
            receiverRegistered = true
        }
    }

    override fun onPause() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(statusReceiver) }
            receiverRegistered = false
        }
        super.onPause()
    }


    private fun restoreArchive() {
        val saved = getSharedPreferences("portal_gate", MODE_PRIVATE).getString("archive_uri", null) ?: return
        val savedUri = Uri.parse(saved)
        val displayName = runCatching {
            contentResolver.query(savedUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" } ?: ""
        }.getOrDefault("")
        runCatching {
            contentResolver.openInputStream(savedUri)?.use { stream ->
                when {
                    displayName.endsWith(".yaml", true) || displayName.endsWith(".yml", true) ->
                        PortalGateMasqueImporter.parseYaml(stream)
                    else -> PortalGateImporter.parseZip(stream)
                }
            } ?: error("Файл PORTAL GATE недоступен")
        }.onSuccess { PortalGateSession.profiles = it }
            .onFailure { status.text = "Повторно выбери файл PORTAL GATE: ${it.message}" }
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
            val displayName = runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" } ?: ""
            }.getOrDefault("")
            runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    when {
                        displayName.endsWith(".yaml", true) || displayName.endsWith(".yml", true) ->
                            PortalGateMasqueImporter.parseYaml(stream)
                        displayName.endsWith(".zip", true) || displayName.isBlank() ->
                            PortalGateImporter.parseZip(stream)
                        else -> error("Поддерживаются ZIP с AmneziaWG и YAML с MASQUE")
                    }
                } ?: error("Не удалось открыть файл")
            }.onSuccess {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                getSharedPreferences("portal_gate", MODE_PRIVATE).edit().putString("archive_uri", uri.toString()).apply()
                PortalGateSession.profiles = it
                refreshProfiles()
                Toast.makeText(this, "Импортировано серверов: ${it.size}", Toast.LENGTH_LONG).show()
            }.onFailure {
                status.text = "Ошибка импорта: ${it.message}"
            }
        } else if (requestCode == this.requestCode && resultCode == RESULT_OK) {
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
        val isMasque = config.contains("\\"protocol\\":\\"masque\\"")
        val action = if (isMasque) GilrTanoVpnService.ACTION_START else GilrTanoVpnService.ACTION_START_PORTAL
        startForegroundService(Intent(this, GilrTanoVpnService::class.java)
            .setAction(action)
            .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config))
        status.text = if (isMasque) "Запускаю PORTAL MASQUE…" else "Запускаю PORTAL GATE…"
        Toast.makeText(this, if (isMasque) "Запуск PORTAL MASQUE" else "Запуск PORTAL GATE", Toast.LENGTH_SHORT).show()
    }

    companion object { private const val IMPORT_REQUEST = 7313 }
}

object PortalGateSession {
    @Volatile var profiles: List<PortalGateProfile> = emptyList()
}
