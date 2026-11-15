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
    private var pendingVpnIntent: Intent? = null
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
            text = "Единый режим AmneziaWG + MASQUE"
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
            text = "ПОДКЛЮЧИТЬ ВЫБРАННЫЙ СЕРВЕР"
            setOnClickListener { connectSelected() }
        }
        root.addView(connect)
        root.addView(Button(this).apply {
            text = "АВТОПОДКЛЮЧЕНИЕ · AWG / MASQUE"
            setOnClickListener { connectAutomatically() }
        })
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
            else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
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
        val prefs = getSharedPreferences("portal_gate", MODE_PRIVATE)
        val savedUris = listOfNotNull(
            prefs.getString("awg_archive_uri", null),
            prefs.getString("masque_archive_uri", null),
            prefs.getString("archive_uri", null)
        ).distinct()
        val restored = mutableListOf<PortalGateProfile>()
        // The APK ships with the base PORTAL AmneziaWG archive. Load it first so
        // PORTAL is ready immediately after installation, without a manual import.
        runCatching {
            assets.open("portal_gate.zip").use { PortalGateImporter.parseZip(it) }
        }.onSuccess { restored += it }
            .onFailure { status.text = "Не удалось открыть встроенный архив PORTAL: ${it.message}" }
        savedUris.forEach { saved ->
            runCatching { loadProfiles(Uri.parse(saved)) }
                .onSuccess { restored += it }
                .onFailure { status.text = "Не удалось восстановить источник PORTAL: ${it.message}" }
        }
        PortalGateSession.profiles = restored.distinctBy { it.config }
    }

    private fun loadProfiles(uri: Uri): List<PortalGateProfile> {
        val displayName = contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }.orEmpty()
        return contentResolver.openInputStream(uri)?.use { stream ->
            if (displayName.endsWith(".yaml", true) || displayName.endsWith(".yml", true)) {
                PortalGateMasqueImporter.parseYaml(stream)
            } else {
                PortalGateImporter.parseZip(stream)
            }
        } ?: error("Файл PORTAL недоступен")
    }

    private fun isMasque(config: String): Boolean = runCatching {
        org.json.JSONObject(config).optJSONArray("outbounds")
            ?.optJSONObject(0)?.optString("protocol") == "masque"
    }.getOrDefault(false)

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
                val isYaml = displayName.endsWith(".yaml", true) || displayName.endsWith(".yml", true)
                if (!isYaml && !displayName.endsWith(".zip", true) && displayName.isNotBlank()) {
                    error("Поддерживаются ZIP с AmneziaWG и YAML с MASQUE")
                }
                contentResolver.openInputStream(uri)?.use { stream ->
                    if (isYaml) PortalGateMasqueImporter.parseYaml(stream)
                    else PortalGateImporter.parseZip(stream)
                } ?: error("Не удалось открыть файл")
            }.onSuccess { imported ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val key = if (displayName.endsWith(".yaml", true) || displayName.endsWith(".yml", true)) {
                    "masque_archive_uri"
                } else {
                    "awg_archive_uri"
                }
                getSharedPreferences("portal_gate", MODE_PRIVATE).edit()
                    .putString(key, uri.toString())
                    .remove("archive_uri")
                    .apply()
                val keepOtherProtocol = PortalGateSession.profiles.filter {
                    isMasque(it.config) != key.startsWith("masque")
                }
                PortalGateSession.profiles = (keepOtherProtocol + imported).distinctBy { it.config }
                refreshProfiles()
                Toast.makeText(this, "Добавлено серверов: ${imported.size}", Toast.LENGTH_LONG).show()
            }.onFailure {
                status.text = "Ошибка импорта: ${it.message}"
            }
        } else if (requestCode == this.requestCode && resultCode == RESULT_OK) {
            startPendingVpn()
        }
    }

    private fun connectSelected() {
        val profile = PortalGateSession.profiles.getOrNull(picker.selectedItemPosition)
        if (profile == null) {
            Toast.makeText(this, "Сначала добавь источник серверов PORTAL", Toast.LENGTH_LONG).show()
            return
        }
        requestVpnPermissionOrStart(buildPortalIntent(profile.config, automatic = false))
    }

    private fun connectAutomatically() {
        val profiles = PortalGateSession.profiles
        if (profiles.isEmpty()) {
            Toast.makeText(this, "Источники серверов PORTAL ещё не загружены", Toast.LENGTH_LONG).show()
            return
        }
        val awg = profiles.firstOrNull { !isMasque(it.config) }
        val masque = profiles.firstOrNull { isMasque(it.config) }
        val primary = awg ?: masque ?: profiles.first()
        val fallback = if (awg != null && masque != null) masque else null
        val preferred = if (fallback != null) awg else null
        requestVpnPermissionOrStart(
            buildPortalIntent(
                primary.config,
                automatic = true,
                fallbackConfig = fallback?.config,
                fallbackBoxMode = false,
                preferredConfig = preferred?.config,
                preferredBoxMode = true
            )
        )
        status.text = when {
            awg != null && masque != null -> "Автовыбор: AmneziaWG с резервом MASQUE"
            awg != null -> "Автовыбор: AmneziaWG"
            else -> "Автовыбор: MASQUE"
        }
    }

    private fun buildPortalIntent(
        config: String,
        automatic: Boolean,
        fallbackConfig: String? = null,
        fallbackBoxMode: Boolean = false,
        preferredConfig: String? = null,
        preferredBoxMode: Boolean = false
    ): Intent {
        val useBox = !isMasque(config)
        return Intent(this, GilrTanoVpnService::class.java)
            .setAction(GilrTanoVpnService.ACTION_START)
            .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config)
            .putExtra(GilrTanoVpnService.EXTRA_ENGINE_BOX, useBox)
            .putExtra(GilrTanoVpnService.EXTRA_PORTAL, true)
            .putExtra(GilrTanoVpnService.EXTRA_AUTO, automatic)
            .putExtra(GilrTanoVpnService.EXTRA_FALLBACK_CONFIG, fallbackConfig)
            .putExtra(GilrTanoVpnService.EXTRA_FALLBACK_ENGINE_BOX, fallbackBoxMode)
            .putExtra(GilrTanoVpnService.EXTRA_PREFERRED_CONFIG, preferredConfig)
            .putExtra(GilrTanoVpnService.EXTRA_PREFERRED_ENGINE_BOX, preferredBoxMode)
    }

    private fun requestVpnPermissionOrStart(intent: Intent) {
        pendingVpnIntent = intent
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission, this.requestCode)
        else startPendingVpn()
    }

    private fun startPendingVpn() {
        val intent = pendingVpnIntent ?: return
        pendingVpnIntent = null
        startForegroundService(intent)
    }

    companion object { private const val IMPORT_REQUEST = 7313 }
}

object PortalGateSession {
    @Volatile var profiles: List<PortalGateProfile> = emptyList()
}
