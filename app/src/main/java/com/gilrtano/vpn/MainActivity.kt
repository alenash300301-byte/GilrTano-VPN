package com.gilrtano.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.animation.ArgbEvaluator
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Chronometer
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.net.TrafficStats
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var powerOrb: TextView
    private lateinit var tigerFlames: TigerFlameOverlayView
    private val powerOrbDrawable = PowerOrbDrawable()
    private lateinit var connectionTimer: Chronometer
    private lateinit var serverCardTitle: TextView
    private lateinit var serverCardSubtitle: TextView
    private lateinit var serverCardPing: TextView
    private lateinit var settingsHeader: TextView
    private lateinit var pingStatus: TextView
    private lateinit var primarySource: EditText
    private lateinit var freeSource: EditText
    private lateinit var servers: Spinner
    private lateinit var quattroTab: Button
    private lateinit var freeTab: Button
    private lateinit var portalGateButton: Button
    private lateinit var importPrimary: Button
    private lateinit var importDocx: Button
    private lateinit var importFree: Button
    private lateinit var autoTab: Button
    private var autoMode = true
    private var activeMode: String = "auto"
    private lateinit var bestFreeButton: Button
    private lateinit var checkPing: Button
    private lateinit var modeSelectorRow: LinearLayout
    private lateinit var pingMetric: TextView
    private lateinit var downloadMetric: TextView
    private lateinit var uploadMetric: TextView
    private val trafficHandler = Handler(Looper.getMainLooper())
    private var lastRxBytes = TrafficStats.getTotalRxBytes()
    private var lastTxBytes = TrafficStats.getTotalTxBytes()
    private var lastTrafficSample = SystemClock.elapsedRealtime()
    private lateinit var connectButton: Button
    private val fireDrawable = FireButtonDrawable()
    private var fireAnimator: ValueAnimator? = null
    @Volatile private var scanningFree = false
    private var activeFree = false
    private var settingsExpanded = false
    private var quattroNodes: List<ImportedNode> = emptyList()
    private var freeNodes: List<ImportedNode> = emptyList()
    private var aetrisFallbackNodes: List<ImportedNode> = emptyList()
    private var pendingConfig: String? = null
    private var pendingFallbackConfig: String? = null
    private var pendingFallbackChainJson: String? = null
    private var pendingPreferredConfig: String? = null
    private var pendingAutomatic = false
    private var pendingPortalAwg = false
    private var pendingPortalGenerate = false
    private var pendingEngineBox = false
    private val requestCode = 4101
    private val docxRequestCode = 4102

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private val activeNodes: List<ImportedNode>
        get() = if (activeFree) freeNodes else quattroNodes

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(GilrTanoVpnService.EXTRA_STATUS) ?: "—"
            status.text = when {
                message.startsWith("Подключено") -> "●  Подключено"
                message == "Отключено" -> "●  Отключено"
                else -> message
            }
            if (message.startsWith("Подключено")) {
                if (message.contains("бесплатный резерв")) activeFree = true
                else if (message.contains("Quattro")) activeFree = false
                refreshMode()
                status.setTextColor(Color.rgb(36, 255, 72))
                updatePowerOrb(true)
                if (::connectionTimer.isInitialized) {
                    connectionTimer.base = SystemClock.elapsedRealtime()
                    connectionTimer.visibility = View.VISIBLE
                    connectionTimer.start()
                }
                updateServerCard(true)
                igniteConnectButton()
            } else if (message == "Отключено" || message.startsWith("Ошибка")) {
                status.setTextColor(Color.LTGRAY)
                updatePowerOrb(false)
                if (::connectionTimer.isInitialized) {
                    connectionTimer.stop()
                    connectionTimer.visibility = View.GONE
                }
                updateServerCard(false)
                resetConnectButtonGlow()
            } else if (message.startsWith("Подключаю") || message.startsWith("Запускаю") || message.startsWith("Запуск Xray") || message.startsWith("Переключаю")) {
                status.setTextColor(Color.rgb(255, 166, 55))
                // Keep the ignition animation visible while the tunnel is starting.
            }
            val busy = message.startsWith("Подключаю") || message.startsWith("Запускаю") || message.startsWith("Запуск Xray") || message.startsWith("Переключаю")
            // The single power button remains usable while connecting so it can cancel,
            // and while connected so it can disconnect.
            connectButton.isEnabled = true
            setPowerButtonLabel(
                if (message.startsWith("Подключено") || message.startsWith("Отключаю")) "ОТКЛЮЧИТЬ"
                else if (message.startsWith("Переключаю")) "ПЕРЕКЛЮЧЕНИЕ…"
                else if (busy) "ПОДКЛЮЧЕНИЕ…"
                else "ПОДКЛЮЧИТЬ"
            )
            connectButton.contentDescription = when {
                message.startsWith("Подключено") -> "Отключить VPN"
                busy -> "Отменить подключение"
                else -> "Подключить VPN"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        quattroNodes = restoreNodes("quattro_nodes")
        freeNodes = restoreNodes("free_nodes")
        aetrisFallbackNodes = restoreNodes("aetris_fallback_nodes")

        status = TextView(this).apply { text = "Отключено"; textSize = 14f; maxLines = 2; gravity = android.view.Gravity.CENTER; setPadding(dp(18), dp(4), dp(18), dp(4)) }
        pingStatus = TextView(this).apply { text = "Пинг: —"; textSize = 15f }
        primarySource = EditText(this).apply {
            hint = "Ссылка Quattro или Clash YAML"
            minLines = 2
            maxLines = 5
            setText(prefs.getString("subscription_url", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        freeSource = EditText(this).apply {
            hint = "Ссылка бесплатного резерва"
            minLines = 2
            maxLines = 3
            setText(prefs.getString("free_subscription_url", "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/vless.txt"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        servers = Spinner(this).apply {
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val key = if (activeFree) "free_selected" else "quattro_selected"
                    prefs.edit().putInt(key, position).apply()
                    pingStatus.text = "Пинг: —"
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        quattroTab = Button(this).apply {
            text = "QUATTRO"
            setOnClickListener { switchMode(false) }
        }
        freeTab = Button(this).apply {
            text = "PORTAL GATE"
            setOnClickListener {
                autoMode = false
                activeMode = "portal"
                prefs.edit().putString("connection_mode", activeMode).apply()
                refreshMode()
            }
        }
        autoTab = Button(this).apply {
            text = "АВТО"
            setOnClickListener { switchToAuto() }
        }

        portalGateButton = Button(this).apply {
            text = "ОТКРЫТЬ PORTAL GATE"
            setOnClickListener { startActivity(Intent(this@MainActivity, PortalGateActivity::class.java)) }
        }
        importPrimary = Button(this).apply {
            text = "Импортировать Quattro"
            setOnClickListener { importFrom(primarySource.text.toString().trim(), false) }
        }
        importDocx = Button(this).apply {
            text = "Выбрать DOCX Quattro"
            setOnClickListener {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                }
                startActivityForResult(pick, docxRequestCode)
            }
        }
        importFree = Button(this).apply {
            text = "Импортировать бесплатные серверы"
            setOnClickListener { importFrom(freeSource.text.toString().trim(), true) }
        }
        connectButton = Button(this).apply {
            text = "⏻\nПОДКЛЮЧИТЬ"
            setOnClickListener {
                val currentStatus = status.text.toString()
                val isConnected = currentStatus.contains("Подключено")
                val isConnecting = currentStatus.startsWith("Подключаю") ||
                    currentStatus.startsWith("Запускаю") || currentStatus.startsWith("Запуск Xray") || currentStatus.startsWith("Переключаю")
                if (isConnected || isConnecting) {
                    pendingConfig = null
                    status.text = if (isConnected) "Отключаю VPN…" else "Отменяю подключение…"
                    startService(Intent(this@MainActivity, GilrTanoVpnService::class.java)
                        .setAction(GilrTanoVpnService.ACTION_STOP))
                    return@setOnClickListener
                }
                val permission = VpnService.prepare(this@MainActivity)
                when (activeMode) {
                    "quattro" -> {
                        val node = quattroNodes.getOrNull(prefs.getInt("quattro_selected", 0).coerceIn(0, (quattroNodes.size - 1).coerceAtLeast(0)))
                        if (node == null) {
                            Toast.makeText(this@MainActivity, "Сначала добавь QUATTRO в настройках", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        val (config, engineBox) = configForNode(node)
                        pendingConfig = config
                        pendingFallbackConfig = null
                        pendingPreferredConfig = config
                        pendingAutomatic = false
                        pendingPortalAwg = false
                        pendingEngineBox = engineBox
                        status.text = "Запускаю QUATTRO…"
                        if (permission != null) startActivityForResult(permission, requestCode)
                        else {
                            startVpn(config, preferredConfig = config, engineBox = engineBox)
                            pendingConfig = null
                            pendingPreferredConfig = null
                        }
                    }
                    "auto" -> {
                        activeFree = quattroNodes.isEmpty()
                        if (activeNodes.isNotEmpty()) {
                            val key = if (activeFree) "free_selected" else "quattro_selected"
                            val index = prefs.getInt(key, 0).coerceIn(0, activeNodes.lastIndex)
                            servers.setSelection(index)
                            connectAutomaticallySelected()
                        } else startBundledPortal(permission)
                    }
                    else -> startBundledPortal(permission)
                }
            }
        }
        bestFreeButton = Button(this).apply {
            text = "Найти лучший бесплатный сервер (проверка интернета)"
            setOnClickListener { refreshFreeServersAndFindBest() }
        }
        checkPing = Button(this).apply {
            text = "Проверить пинг"
            setOnClickListener { measureSelectedPing() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 25, 29))
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(dp(18), dp(12), dp(18), dp(18))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = "☰"; textSize = 27f; setTextColor(Color.WHITE)
                        setPadding(dp(4), 0, dp(10), 0)
                        setOnClickListener { openSettingsScreen() }
                    })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER
                        val title = TextView(this@MainActivity).apply {
                            text = "GilrTano"; textSize = 25f
                            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD_ITALIC)
                            setTextColor(Color.rgb(245, 242, 238))
                            setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), Color.rgb(130, 18, 18))
                        }
                        val vpn = TextView(this@MainActivity).apply {
                            text = " VPN"; textSize = 25f
                            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD_ITALIC)
                            setTextColor(Color.rgb(230, 38, 38))
                            setShadowLayer(dp(6).toFloat(), 0f, 0f, Color.rgb(170, 0, 0))
                        }
                        addView(title); addView(vpn)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "⚙"; textSize = 25f; setTextColor(Color.WHITE)
                        setOnClickListener { openSettingsScreen() }
                    })
                })
                // The uploaded GIF is the complete hero composition: tiger, realistic fire and orb.
                val tigerScene = FrameLayout(this@MainActivity).apply {
                    setBackgroundColor(Color.BLACK)
                    tigerFlames = TigerFlameOverlayView(this@MainActivity).apply {
                        visibility = View.VISIBLE
                        isClickable = false
                        isFocusable = false
                        contentDescription = "Тигр с анимированным огнём"
                    }
                    addView(tigerFlames, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                }
                addView(tigerScene, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(410)
                ).apply { topMargin = dp(0); bottomMargin = dp(0) })

                // Transparent hit target sits over the power symbol already rendered in the GIF.
                powerOrb = PowerOrbButtonView(this@MainActivity).apply {
                    text = ""
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.TRANSPARENT)
                    background = null
                    alpha = 0f
                    elevation = dp(14).toFloat()
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
                powerOrb.setOnClickListener { connectButton.performClick() }
                // Keep the transparent hit target passive: no infinite animator on touch.
                powerOrb.isClickable = true
                powerOrb.contentDescription = "Кнопка питания VPN"
                tigerScene.addView(powerOrb, FrameLayout.LayoutParams(dp(132), dp(132)).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    topMargin = dp(242)
                })
                addView(status.apply {
                    text = "●  Отключено"; textSize = 17f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.LTGRAY)
                    setPadding(dp(8), dp(5), dp(8), dp(0))
                })
                connectionTimer = Chronometer(this@MainActivity).apply {
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.WHITE)
                    format = "%s"
                    visibility = View.GONE
                }
                addView(connectionTimer, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(22)
                ))
                // The central glowing orb is the primary connect/disconnect control.
                // Keep the accessible action button as the click target, but remove its
                // duplicate pill from the visual layout to match the approved reference.
                connectButton.visibility = View.GONE
                addView(connectButton, LinearLayout.LayoutParams(1, 1))
                // Server details and traffic diagnostics are intentionally hidden from Home.
                addView(servers, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                servers.visibility = View.GONE
                addView(pingStatus.apply { gravity = android.view.Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                pingStatus.visibility = View.GONE
                addView(bestFreeButton, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                bestFreeButton.visibility = View.GONE
                addView(checkPing, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                checkPing.visibility = View.GONE
                modeSelectorRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    addView(autoTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
                    addView(quattroTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
                    addView(freeTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
                }
                settingsHeader = TextView(this@MainActivity).apply {
                    text = "Настройки и импорт  ›"; textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); setPadding(dp(16), dp(14), dp(12), dp(12))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(35, 39, 51)); cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.rgb(76, 132, 235))
                    }
                    visibility = View.GONE
                    setOnClickListener {
                        settingsExpanded = !settingsExpanded
                        text = if (settingsExpanded) "Настройки и импорт  ⌄" else "Настройки и импорт  ›"
                        findImportButtonsVisibility()
                    }
                }
                addView(settingsHeader)
                portalGateButton.visibility = View.GONE
                addView(portalGateButton)
                addView(modeSelectorRow, LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
                addView(primarySource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(importPrimary)
                addView(importDocx)
                addView(freeSource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(importFree)
                addView(TextView(this@MainActivity).apply {
                    text = "Бесплатные серверы предоставляются сторонними операторами."
                    textSize = 10f; setTextColor(Color.GRAY); gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(8))
                })
            })
        }
        // Respect Android system bars so the brand header and bottom navigation
        // never sit underneath the clock or gesture handle.
        window.statusBarColor = Color.rgb(24, 25, 29)
        window.navigationBarColor = Color.rgb(24, 25, 29)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars())
            } else {
                android.graphics.Insets.of(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val homeContent = scroll.getChildAt(0) as? ViewGroup
        homeContent?.let { page ->
            // Keep only the brand header, tiger/power control, status and mode selector on Home.
            for (index in 5..12) if (index < page.childCount) page.getChildAt(index).visibility = View.GONE
            modeSelectorRow.visibility = View.VISIBLE
            settingsHeader.visibility = View.GONE
            portalGateButton.visibility = View.GONE
            primarySource.visibility = View.GONE
            freeSource.visibility = View.GONE
            importPrimary.visibility = View.GONE
            importDocx.visibility = View.GONE
            importFree.visibility = View.GONE
        }
        setContentView(root)
        // Traffic widgets were removed from Home; do not poll TrafficStats every second.
        trafficHandler.removeCallbacks(trafficSample)
        styleDarkInterface()
        installButtonFeedback()
        setPowerButtonLabel("ПОДКЛЮЧИТЬ")
        connectButton.apply {
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = fireDrawable
            elevation = 12f
            // Subtle fire pulse only while the user presses the power button.
            setOnTouchListener { view, event ->
                val pressed = event.actionMasked == MotionEvent.ACTION_DOWN
                val released = event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                if (pressed || released) {
                    if (pressed && !status.text.toString().contains("Подключено")) {
                        if (::tigerFlames.isInitialized) tigerFlames.setActive(true)
                        fireDrawable.setPressed(true)
                        if (fireAnimator?.isRunning != true) {
                            fireAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
                                duration = 900L
                                repeatCount = ValueAnimator.INFINITE
                                addUpdateListener {
                val phase = it.animatedValue as Float
                fireDrawable.setPhase(phase)
                powerOrbDrawable.setPhase(phase)
            }
                                start()
                            }
                        }
                    } else if (released && !status.text.toString().startsWith("Подключено")) {
                        fireAnimator?.cancel()
                        fireAnimator = null
                        fireDrawable.setPressed(false)
                        fireDrawable.setPhase(0f)
                    }
                    val targetScale = if (pressed) 0.975f else 1f
                    val targetElevation = if (pressed) dp(18).toFloat() else dp(12).toFloat()
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(view, View.SCALE_X, targetScale),
                            ObjectAnimator.ofFloat(view, View.SCALE_Y, targetScale),
                            ObjectAnimator.ofFloat(view, View.TRANSLATION_Z, if (pressed) dp(6).toFloat() else 0f)
                        )
                        duration = if (pressed) 85L else 150L
                        interpolator = DecelerateInterpolator()
                        start()
                    }
                    fireDrawable.invalidateSelf()
                    view.elevation = targetElevation
                }
                false
            }
        }
        activeMode = prefs.getString("connection_mode", "portal") ?: "portal"
        if (activeMode !in setOf("auto", "quattro", "portal")) activeMode = "portal"
        autoMode = activeMode == "auto"
        activeFree = false
        refreshMode()
        updatePowerOrb(false)
        updateServerCard(false)
    }

    private fun showServerPicker() {
        val nodes = activeNodes
        if (nodes.isEmpty()) {
            settingsExpanded = true
            findImportButtonsVisibility()
            val content = findViewById<ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0) as? ViewGroup
            val scroll = root?.getChildAt(0) as? android.widget.ScrollView
            scroll?.post { scroll.smoothScrollTo(0, settingsHeader.top) }
            Toast.makeText(this, "Добавь сервер в настройках", Toast.LENGTH_LONG).show()
            return
        }
        val names = nodes.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выбор сервера")
            .setSingleChoiceItems(names, servers.selectedItemPosition) { dialog, which ->
                servers.setSelection(which)
                val key = if (activeFree) "free_selected" else "quattro_selected"
                prefs.edit().putInt(key, which).apply()
                serverCardTitle.text = nodes[which].name
                serverCardSubtitle.text = if (activeFree) "Бесплатный" else "Quattro"
                serverCardPing.text = ""
                pingStatus.text = "Пинг: —"
                dialog.dismiss()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun updateServerCard(connected: Boolean) {
        if (!::serverCardTitle.isInitialized) return
        val node = activeNodes.getOrNull(servers.selectedItemPosition)
        if (connected && node != null) {
            serverCardTitle.text = node.name
            serverCardSubtitle.text = if (activeFree) "Бесплатный резерв" else "Подключено"
            val ping = Regex("(\\d+)\\s*мс").find(pingStatus.text.toString())?.groupValues?.get(1)
            serverCardPing.text = ping?.let { "$it мс" } ?: ""
        } else {
            serverCardTitle.text = if (quattroNodes.isEmpty() && freeNodes.isEmpty()) "Сервер не добавлен" else "Автоматический сервер"
            serverCardSubtitle.text = if (quattroNodes.isEmpty() && freeNodes.isEmpty()) "Добавьте подписку в настройках" else "Автовыбор"
            serverCardPing.text = ""
        }
    }

    private fun updatePowerOrb(connected: Boolean) {
        if (!::powerOrb.isInitialized) return
        powerOrbDrawable.setConnected(connected)
        (powerOrb as? PowerOrbButtonView)?.setConnected(connected)
        if (::tigerFlames.isInitialized) tigerFlames.setActive(connected)
        powerOrb.setTextColor(if (connected) Color.rgb(0, 255, 48) else Color.WHITE)
        powerOrb.elevation = dp(if (connected) 20 else 12).toFloat()
        powerOrb.invalidate()
    }

    private fun setPowerButtonLabel(label: String) {
        val full = "⏻   $label"
        val styled = SpannableString(full)
        styled.setSpan(
            ForegroundColorSpan(Color.rgb(95, 150, 255)),
            0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        connectButton.text = styled
    }

    // Flame tongues move continuously only while VPN is genuinely connected.
    private fun igniteConnectButton() {
        fireDrawable.setPressed(false)
        fireDrawable.setConnected(true)
        if (fireAnimator?.isRunning == true) return
        fireAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 1250L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val phase = it.animatedValue as Float
                fireDrawable.setPhase(phase)
                powerOrbDrawable.setPhase(phase)
                }
            start()
        }
        connectButton.elevation = dp(16).toFloat()
    }

    private fun resetConnectButtonGlow() {
        fireAnimator?.cancel()
        fireAnimator = null
        fireDrawable.setPressed(false)
        fireDrawable.setConnected(false)
        fireDrawable.setPhase(0f)
        powerOrbDrawable.setPhase(0f)
        if (::tigerFlames.isInitialized) tigerFlames.setPhase(0f)
        connectButton.animate().cancel()
        connectButton.scaleX = 1f
        connectButton.scaleY = 1f
        connectButton.elevation = dp(10).toFloat()
    }

    private fun installButtonFeedback() {
        fun visit(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is Button && child !== connectButton) {
                    child.setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                view.animate().cancel()
                                view.animate().scaleX(0.965f).scaleY(0.965f)
                                    .translationZ(dp(4).toFloat()).setDuration(75L)
                                    .setInterpolator(DecelerateInterpolator()).start()
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                view.animate().scaleX(1f).scaleY(1f).translationZ(0f)
                                    .setDuration(170L).setInterpolator(DecelerateInterpolator()).start()
                            }
                        }
                        false
                    }
                }
                if (child is ViewGroup) visit(child)
            }
        }
        visit(findViewById(android.R.id.content))
    }

    override fun onDestroy() {
        trafficHandler.removeCallbacks(trafficSample)
        fireAnimator?.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun styleDarkInterface() {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? LinearLayout ?: return
        root.setBackgroundColor(Color.rgb(24, 25, 29))
        fun style(view: android.view.View) {
            when (view) {
                is Button -> {
                    view.setTextColor(Color.WHITE)
                    view.textSize = 13f
                    view.isAllCaps = false
                    view.background = GradientDrawable().apply {
                        setColor(Color.rgb(35, 39, 51))
                        cornerRadius = 18f
                        setStroke(2, Color.rgb(76, 132, 235))
                    }
                    view.setPadding(16, 10, 16, 10)
                    val lp = view.layoutParams
                    if (lp != null) { lp.height = (44 * resources.displayMetrics.density).toInt(); view.layoutParams = lp }
                }
                is EditText -> {
                    view.setTextColor(Color.WHITE)
                    view.setHintTextColor(Color.GRAY)
                    view.textSize = 14f
                    view.setPadding(14, 10, 14, 10)
                    view.background = GradientDrawable().apply {
                        setColor(Color.rgb(34, 37, 46))
                        cornerRadius = 14f
                        setStroke(1, Color.rgb(75, 110, 190))
                    }
                }
                is TextView -> {
                    if (view.currentTextColor != Color.WHITE) view.setTextColor(Color.LTGRAY)
                }
                is Spinner -> {
                    view.setBackgroundColor(Color.rgb(40, 43, 53))
                }
                is ViewGroup -> for (i in 0 until view.childCount) style(view.getChildAt(i))
            }
        }
        style(root)
    }

    private fun switchToAuto() {
        autoMode = true
        activeMode = "auto"
        prefs.edit().putString("connection_mode", "auto").apply()
        activeFree = quattroNodes.isEmpty() && freeNodes.isNotEmpty()
        refreshMode()
        Toast.makeText(this, "Автовыбор включён: Quattro в приоритете, бесплатный резерв", Toast.LENGTH_SHORT).show()
    }

    private fun switchMode(free: Boolean) {
        autoMode = false
        activeMode = if (free) "free" else "quattro"
        prefs.edit().putString("connection_mode", activeMode).apply()
        if (activeFree == free) { refreshMode(); return }
        val targetNodes = if (free) freeNodes else quattroNodes
        if (targetNodes.isEmpty()) {
            Toast.makeText(
                this,
                if (free) "Сначала найди бесплатные серверы" else "Сначала импортируй Quattro",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val wasConnected = status.text.toString().contains("Подключено")
        activeFree = free
        prefs.edit().putBoolean("active_free_mode", free).apply()
        refreshMode()
        updateServerCard(wasConnected)

        if (wasConnected) {
            val selectedKey = if (free) "free_selected" else "quattro_selected"
            val index = prefs.getInt(selectedKey, 0).coerceIn(0, targetNodes.lastIndex)
            servers.setSelection(index)
            val node = targetNodes[index]
            val config = runCatching { configForNode(node).first }.getOrElse {
                Toast.makeText(this, "Не удалось подготовить сервер: ${it.message}", Toast.LENGTH_LONG).show()
                return
            }
            status.text = "Переключаю на ${if (free) "бесплатный сервер" else "Quattro"}…"
            startService(Intent(this, GilrTanoVpnService::class.java)
                .setAction(GilrTanoVpnService.ACTION_SWITCH)
                .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config)
                .putExtra(GilrTanoVpnService.EXTRA_ENGINE_BOX, configForNode(node).second))
        } else {
            Toast.makeText(this, if (free) "Выбран бесплатный резерв" else "Выбран Quattro", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshMode() {
        autoTab.isEnabled = activeMode != "auto"
        quattroTab.isEnabled = activeMode != "quattro"
        freeTab.isEnabled = activeMode != "portal"
        autoTab.alpha = if (activeMode == "auto") 1f else 0.72f
        quattroTab.alpha = if (activeMode == "quattro") 1f else 0.72f
        freeTab.alpha = if (activeMode == "portal") 1f else 0.72f
        fun modeBackground(selected: Boolean) = GradientDrawable().apply {
            setColor(if (selected) Color.rgb(39, 70, 130) else Color.rgb(32, 34, 42))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(if (selected) 2 else 1),
                if (selected) Color.rgb(95, 150, 255) else Color.rgb(75, 82, 105))
        }
        quattroTab.background = modeBackground(activeMode == "quattro")
        freeTab.background = modeBackground(activeMode == "portal")
        autoTab.background = modeBackground(activeMode == "auto")
        quattroTab.setTextColor(if (activeMode == "quattro") Color.WHITE else Color.LTGRAY)
        freeTab.setTextColor(if (activeMode == "portal") Color.WHITE else Color.LTGRAY)
        autoTab.setTextColor(if (activeMode == "auto") Color.WHITE else Color.LTGRAY)
        primarySource.visibility = if (activeFree) ViewGroup.GONE else ViewGroup.VISIBLE
        freeSource.visibility = if (activeFree) ViewGroup.VISIBLE else ViewGroup.GONE
        findImportButtonsVisibility()
        val list = activeNodes
        servers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list.map { it.name })
        val selectedKey = if (activeFree) "free_selected" else "quattro_selected"
        val selected = prefs.getInt(selectedKey, 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (list.isNotEmpty()) servers.setSelection(selected)
        pingStatus.text = "Пинг: —"
        if (!status.text.toString().contains("Подключено")) {
            status.text = "●  Отключено"
            status.setTextColor(Color.LTGRAY)
        }
    }


    private fun openSettingsScreen() {
        val dialog = android.app.Dialog(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundColor(Color.rgb(24, 25, 29))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 34f; setTextColor(Color.WHITE)
            setPadding(0, 0, dp(18), 0)
            setOnClickListener { dialog.dismiss() }
        })
        header.addView(TextView(this).apply {
            text = "Настройки"; textSize = 23f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        page.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, dp(54))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(20))
        }
        val settingsScroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            addView(content)
        }
        fun section(label: String) {
            content.addView(TextView(this).apply {
                text = label
                textSize = 12f
                letterSpacing = 0.12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(115, 155, 245))
                setPadding(dp(2), dp(20), 0, dp(9))
            })
        }
        fun action(label: String, primary: Boolean = false, callback: () -> Unit) {
            val button = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                setTextColor(if (primary) Color.WHITE else Color.rgb(225, 225, 230))
                background = GradientDrawable().apply {
                    setColor(if (primary) Color.rgb(42, 91, 170) else Color.rgb(35, 38, 47))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), if (primary) Color.rgb(90, 145, 245) else Color.rgb(65, 70, 88))
                }
                setOnClickListener { callback() }
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            params.bottomMargin = dp(8)
            content.addView(button, params)
        }
        fun input(hintText: String, value: String, lines: Int): EditText {
            return EditText(this).apply {
                hint = hintText
                setText(value)
                minLines = 1
                maxLines = lines
                textSize = 14f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(34, 37, 46))
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), Color.rgb(75, 82, 105))
                }
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
        }

        section("QUATTRO")
        val quattroLink = input("Ссылка или Clash YAML", prefs.getString("subscription_url", "") ?: "", 3)
        content.addView(quattroLink, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        action("Импортировать ссылку / YAML", true) {
            val value = quattroLink.text.toString().trim()
            prefs.edit().putString("subscription_url", value).apply()
            importFrom(value, false)
            dialog.dismiss()
        }
        action("Выбрать DOCX-файл") {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            }
            startActivityForResult(pick, docxRequestCode)
            dialog.dismiss()
        }

        section("СЕРВЕРЫ")
        action("Выбрать сервер") { showServerPicker(); dialog.dismiss() }
        action("Проверить соединение") { measureSelectedPing() }

        section("PORTAL GATE")
        action("Открыть Portal Gate", true) {
            startActivity(Intent(this, PortalGateActivity::class.java))
            dialog.dismiss()
        }
        page.addView(settingsScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(page)
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun findImportButtonsVisibility() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as? LinearLayout ?: return
        val scroll = root.getChildAt(0) as? android.widget.ScrollView ?: return
        val page = scroll.getChildAt(0) as? ViewGroup ?: return
        settingsHeader.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        portalGateButton.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        modeSelectorRow.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        primarySource.visibility = if (settingsExpanded && !activeFree) View.VISIBLE else View.GONE
        freeSource.visibility = View.GONE
        bestFreeButton.visibility = View.GONE
        checkPing.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        for (i in 0 until page.childCount) {
            val child = page.getChildAt(i)
            if (child is Button) {
                val label = child.text.toString()
                val show = settingsExpanded && (
                    (!activeFree && (label == "Импортировать Quattro" || label == "Выбрать DOCX Quattro")) ||
                    (label == "ОТКРЫТЬ PORTAL GATE")
                )
                if (label.startsWith("Импортировать") || label == "Выбрать DOCX Quattro") {
                    child.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
        }
        pingStatus.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
    }

    private val trafficSample = object : Runnable {
        override fun run() {
            if (::downloadMetric.isInitialized && ::uploadMetric.isInitialized) {
                val now = SystemClock.elapsedRealtime()
                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                val seconds = ((now - lastTrafficSample).coerceAtLeast(1L)) / 1000.0
                val down = if (rx >= 0 && lastRxBytes >= 0) ((rx - lastRxBytes).coerceAtLeast(0L) * 8.0 / seconds / 1_000_000.0) else 0.0
                val up = if (tx >= 0 && lastTxBytes >= 0) ((tx - lastTxBytes).coerceAtLeast(0L) * 8.0 / seconds / 1_000_000.0) else 0.0
                val connected = ::status.isInitialized && status.text.toString().contains("Подключено")
                downloadMetric.text = String.format(java.util.Locale.US, "%.1f Мбит/с", if (connected) down else 0.0)
                uploadMetric.text = String.format(java.util.Locale.US, "%.1f Мбит/с", if (connected) up else 0.0)
                lastRxBytes = rx
                lastTxBytes = tx
                lastTrafficSample = now
            }
            trafficHandler.postDelayed(this, 1000L)
        }
    }

    private fun configForNode(node: ImportedNode): Pair<String, Boolean> =
        when {
            node.uri.startsWith("hy2://", true) || node.uri.startsWith("hysteria2://", true) ->
                Hysteria2Config.fromUri(node.uri) to true
            else -> ProtocolConfig.toXrayConfig(node.uri) to false
        }

    private fun importFrom(source: String, isFree: Boolean) {
        if (source.isBlank()) {
            Toast.makeText(this, "Укажи ссылку или конфигурацию", Toast.LENGTH_LONG).show()
            return
        }
        status.text = if (isFree) "Загружаю резерв…" else "Загружаю Quattro…"
        Thread {
            try {
                val parsedNodes = SubscriptionImporter.load(source)
                val imported = parsedNodes.filter { node -> runCatching { configForNode(node) }.isSuccess }
                runOnUiThread {
                    if (imported.isEmpty()) {
                        status.text = "Не найдено пригодных серверов: проверь формат подписки и параметры доступа"
                        return@runOnUiThread
                    }
                    if (isFree) {
                        freeNodes = imported
                        prefs.edit().putString("free_subscription_url", source).apply()
                        saveNodes("free_nodes", freeNodes)
                    } else {
                        val isAetris = source.contains("AetrisVPN", true) || source.contains("#profile-title", true)
                        if (isAetris && quattroNodes.isNotEmpty()) {
                            aetrisFallbackNodes = quattroNodes
                            saveNodes("aetris_fallback_nodes", aetrisFallbackNodes)
                        }
                        quattroNodes = imported
                        prefs.edit().putString("subscription_url", source).putBoolean("aetris_subscription", isAetris).apply()
                        saveNodes("quattro_nodes", quattroNodes)
                    }
                    activeFree = isFree
                    prefs.edit().putBoolean("active_free_mode", activeFree).apply()
                    refreshMode()
                    settingsExpanded = false
                    settingsHeader.text = "Настройки"
                    findImportButtonsVisibility()
                    status.text = "Добавлено: ${imported.size} серверов из ${parsedNodes.size}; неподходящих: ${parsedNodes.size - imported.size}"
                    if (isFree) findBestFreeServers() else measureSelectedPing()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка импорта: ${e.message}" }
            }
        }.start()
    }

    private fun saveNodes(key: String, nodes: List<ImportedNode>) {
        val array = JSONArray()
        nodes.forEach { array.put(JSONObject().put("name", it.name).put("uri", it.uri)) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun restoreNodes(key: String): List<ImportedNode> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                ImportedNode(item.getString("name"), item.getString("uri"))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Always refresh public nodes before measuring them. Saved free nodes are only
     * a fallback cache; public endpoints expire and must not be trusted as current.
     */
    private fun refreshFreeServersAndFindBest(autoConnectWhenReady: Boolean = false, retryRound: Int = 0) {
        if (scanningFree) return
        scanningFree = true
        bestFreeButton.isEnabled = false
        status.text = "Обновляю бесплатные серверы…"
        val configured = freeSource.text.toString().trim().ifBlank {
            "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/vless.txt"
        }
        val primaryVless = "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/vless.txt"
        val legacyDefault = "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/best.txt"
        val sources = if (configured.isBlank() || configured == legacyDefault) {
            listOf(primaryVless, "https://radarhub.top/v2ray.txt",
                "https://cdn.jsdelivr.net/gh/morpheusadam/v2ray-config@main/subs/bundles/vless.txt")
        } else {
            listOf(configured, primaryVless, "https://radarhub.top/v2ray.txt",
                "https://cdn.jsdelivr.net/gh/morpheusadam/v2ray-config@main/subs/bundles/vless.txt")
        }.distinct()
        Thread {
            var imported: List<ImportedNode> = emptyList()
            var successfulSource: String? = null
            // Fetch mirrors simultaneously: a slow/dead feed must not block the
            // other public lists one after another.
            val fetchPool = Executors.newFixedThreadPool(sources.size.coerceAtMost(4))
            val completion = java.util.concurrent.ExecutorCompletionService<Pair<String, List<ImportedNode>>>(fetchPool)
            try {
                sources.forEach { source ->
                    completion.submit(Callable {
                        source to SubscriptionImporter.load(source)
                    })
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(16)
                var finished = 0
                while (finished < sources.size && System.nanoTime() < deadline && imported.isEmpty()) {
                    val remaining = deadline - System.nanoTime()
                    val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                    finished++
                    val result = runCatching { future.get() }.getOrNull() ?: continue
                    if (result.second.isNotEmpty()) {
                        successfulSource = result.first
                        imported = result.second
                    }
                }
            } finally {
                fetchPool.shutdownNow()
            }
            runOnUiThread {
                scanningFree = false
                bestFreeButton.isEnabled = true
                if (imported.isEmpty()) {
                    val cached = freeNodes
                    if (cached.isEmpty()) {
                        if (autoConnectWhenReady && retryRound < 5) {
                            status.text = "Источники временно недоступны. Повторяю поиск…"
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!isFinishing && !isDestroyed) refreshFreeServersAndFindBest(true, retryRound + 1)
                            }, 2500L)
                        } else {
                            status.text = "Не удалось обновить серверы. Попробуй позже."
                        }
                    } else {
                        status.text = "Источники недоступны. Проверяю сохранённые серверы…"
                        findBestFreeServers(autoConnectWhenReady, retryRound)
                    }
                    return@runOnUiThread
                }
                freeNodes = imported
                activeFree = true
                prefs.edit()
                    .putBoolean("active_free_mode", true)
                    .putString("free_subscription_url", successfulSource)
                    .putInt("free_selected", 0)
                    .apply()
                saveNodes("free_nodes", freeNodes)
                freeSource.setText(successfulSource)
                refreshMode()
                status.text = "Обновлено ${imported.size} серверов. Быстро проверяю доступ…"
                findBestFreeServers(autoConnectWhenReady, retryRound)
            }
        }.start()
    }

    private fun findBestFreeServers(autoConnectWhenReady: Boolean = false, retryRound: Int = 0) {
        if (scanningFree) return
        if (freeNodes.isEmpty()) {
            status.text = "Сначала обнови бесплатный список серверов"
            return
        }
        scanningFree = true
        bestFreeButton.isEnabled = false
        status.text = "Быстрый поиск: проверяю лучшие серверы…"
        // Public free nodes expire frequently. Check a much larger portion of the
        // imported catalogue and allow slow handshakes, rather than rejecting all
        // candidates after one short 25-second window.
        val candidates = freeNodes.take(80)
        Thread {
            val pool = Executors.newFixedThreadPool(8)
            val completion = java.util.concurrent.ExecutorCompletionService<Pair<ImportedNode, Long>?>(pool)
            try {
                candidates.forEach { node ->
                    completion.submit(Callable<Pair<ImportedNode, Long>?> {
                        val config = runCatching { configForNode(node).first }
                            .getOrNull() ?: return@Callable null
                        runCatching {
                            val delayMs = teapodcore.Teapodcore.measureOutboundDelay(
                                config, "https://www.gstatic.com/generate_204"
                            )
                            if (delayMs <= 0L) null else node to delayMs
                        }.getOrNull()
                    })
                }
                val checked = mutableListOf<Pair<ImportedNode, Long>>()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18)
                var completed = 0
                while (completed < candidates.size && System.nanoTime() < deadline && checked.size < 2) {
                    val remaining = deadline - System.nanoTime()
                    val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                    completed++
                    runCatching { future.get() }.getOrNull()?.let { checked.add(it) }
                    if (completed % 20 == 0) {
                        val done = completed
                        runOnUiThread {
                            if (scanningFree) status.text = "Автопоиск: проверено $done из ${candidates.size}…"
                        }
                    }
                    if (checked.size >= 2) break
                }
                val fastest = checked.sortedBy { it.second }
                runOnUiThread {
                    if (fastest.isEmpty()) {
                        // A failed scan is never a connected state: restore every
                        // visual control so the screen cannot remain falsely green.
                        if (autoConnectWhenReady && retryRound < 5) {
                            status.text = "Рабочий сервер пока не найден. Повторяю поиск…"
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!isFinishing && !isDestroyed) refreshFreeServersAndFindBest(true, retryRound + 1)
                            }, 2500L)
                        } else {
                            status.text = "Проверено серверов: $completed из ${candidates.size}. Рабочие пока не ответили."
                        }
                        status.setTextColor(Color.LTGRAY)
                        updatePowerOrb(false)
                        if (::tigerFlames.isInitialized) tigerFlames.setActive(false)
                        if (::connectionTimer.isInitialized) {
                            connectionTimer.stop()
                            connectionTimer.visibility = View.GONE
                        }
                        updateServerCard(false)
                        resetConnectButtonGlow()
                        setPowerButtonLabel("ПОДКЛЮЧИТЬ")
                        connectButton.isEnabled = true
                    } else {
                        freeNodes = fastest.map { it.first }
                        saveNodes("free_nodes", freeNodes)
                        prefs.edit().putInt("free_selected", 0).apply()
                        val labels = fastest.map { pair -> "${pair.first.name} · ${pair.second} мс" }
                        servers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                        servers.setSelection(0)
                        pingStatus.text = "Проверка через Xray: ${fastest.first().second} мс"
                        status.text = "Найдено рабочих серверов: ${fastest.size}. Лучший выбран."
                        if (autoConnectWhenReady) {
                            activeFree = true
                            autoMode = true
                            activeMode = "auto"
                            prefs.edit().putString("connection_mode", "auto").putBoolean("active_free_mode", true).apply()
                            refreshMode()
                            connectAutomaticallySelected()
                        }
                    }
                    scanningFree = false
                    bestFreeButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    scanningFree = false
                    bestFreeButton.isEnabled = true
                    status.text = "Не удалось проверить серверы: ${e.message ?: "ошибка проверки"}"
                }
            } finally {
                pool.shutdownNow()
            }
        }.start()
    }
    private fun connectAutomaticallySelected() {
        val node = activeNodes.getOrNull(servers.selectedItemPosition) ?: return
        try {
            val (config, engineBox) = configForNode(node)
            val fallbackNodes = (if (!activeFree && aetrisFallbackNodes.isNotEmpty()) aetrisFallbackNodes else
                if (activeFree) freeNodes.drop(1) else freeNodes).distinctBy { it.uri }
            val fallbackEntries = JSONArray()
            fallbackNodes.forEach { fallbackNode ->
                runCatching { configForNode(fallbackNode) }.getOrNull()?.let { pair ->
                    fallbackEntries.put(JSONObject().put("config", pair.first).put("engineBox", pair.second).put("portal", false))
                }
            }
            val fallback = fallbackEntries.optJSONObject(0)?.optString("config")
            pendingConfig = config
            pendingFallbackConfig = fallback
            pendingPreferredConfig = config
            pendingAutomatic = true
            pendingEngineBox = engineBox
            pendingFallbackChainJson = fallbackEntries.toString()
            status.text = "Подключаю лучший доступный сервер…"
            val permission = VpnService.prepare(this)
            if (permission != null) startActivityForResult(permission, requestCode)
            else startVpn(config, fallback, config, true, engineBox)
        } catch (e: Exception) {
            status.text = "Не удалось подготовить сервер: ${e.message}"
        }
    }

    private fun measureSelectedPing() {
        val node = activeNodes.getOrNull(servers.selectedItemPosition)
        if (node == null) {
            pingStatus.text = "Пинг: нет сервера"
            return
        }
        val parsed = Uri.parse(node.uri)
        val host = parsed.host
        val port = if (parsed.port > 0) parsed.port else 443
        if (host.isNullOrBlank()) {
            pingStatus.text = "Пинг: адрес не распознан"
            return
        }
        pingStatus.text = "Пинг: проверяю…"
        Thread {
            val started = System.nanoTime()
            val result = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 5000)
                }
                ((System.nanoTime() - started) / 1_000_000L)
            }
            runOnUiThread {
                if (result.isSuccess) {
                    val ms = result.getOrThrow()
                    pingStatus.text = "Пинг до сервера: ${ms} мс (TCP)"
                    if (::pingMetric.isInitialized) pingMetric.text = "$ms мс"
                    if (::serverCardPing.isInitialized) serverCardPing.text = "$ms мс"
                } else {
                    pingStatus.text = "Сервер не отвечает на проверку"
                    if (::pingMetric.isInitialized) pingMetric.text = "— мс"
                }
            }
        }.start()
    }

    private fun startBundledPortal(permission: Intent?) {
        val bundledPortal = GilrTanoVpnService.BUNDLED_PORTAL_CONFIG
        val fallback = quattroNodes.firstOrNull()?.let { runCatching { configForNode(it).first }.getOrNull() }
        pendingConfig = bundledPortal
        pendingFallbackConfig = fallback
        pendingPreferredConfig = null
        pendingAutomatic = true
        pendingPortalAwg = true
        pendingPortalGenerate = true
        status.text = "Подготавливаю PORTAL GATE: создаю персональную конфигурацию…"
        if (permission != null) startActivityForResult(permission, requestCode)
        else { startPortalGeneration(fallback); clearPendingPortalRequest() }
    }

    private fun clearPendingPortalRequest() {
        pendingConfig = null; pendingFallbackConfig = null; pendingFallbackChainJson = null; pendingPreferredConfig = null
        pendingAutomatic = false; pendingPortalAwg = false; pendingPortalGenerate = false; pendingEngineBox = false
    }

    private fun startPortalGeneration(fallbackQuattro: String?) {
        connectButton.isEnabled = false
        status.text = "Создаю конфигурацию PORTAL GATE…"
        Thread({
            val generated = runCatching {
                val conf = WarpGeneratorClient.generateAmneziaProfile()
                PortalGateImporter.parseConf(conf, "PORTAL-GENERATED")
                conf
            }
            runOnUiThread {
                connectButton.isEnabled = true
                if (generated.isSuccess) {
                    status.text = "Конфигурация создана. Запускаю PORTAL GATE…"
                    startWarpVpn(generated.getOrThrow(), fallbackQuattro)
                } else {
                    val reason = generated.exceptionOrNull()?.message?.take(140).orEmpty()
                    status.text = "Генератор недоступен${if (reason.isNotBlank()) ": $reason" else ""}. Перехожу на встроенные профили…"
                    startWarpVpn(GilrTanoVpnService.BUNDLED_PORTAL_CONFIG, fallbackQuattro)
                }
            }
        }, "GilrTano-PORTAL-generator").start()
    }
    private fun startWarpVpn(config: String, fallbackQuattro: String?) {
        val intent = Intent(this, GilrTanoVpnService::class.java)
            .setAction(GilrTanoVpnService.ACTION_START_PORTAL)
            .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config)
            .putExtra(GilrTanoVpnService.EXTRA_ENGINE_BOX, true)
            .putExtra(GilrTanoVpnService.EXTRA_PORTAL, true)
            .putExtra(GilrTanoVpnService.EXTRA_AUTO, true)
            .putExtra(GilrTanoVpnService.EXTRA_FALLBACK_CONFIG, fallbackQuattro)
            .putExtra(GilrTanoVpnService.EXTRA_FALLBACK_ENGINE_BOX, false)
        startForegroundService(intent)
        status.text = "Запускаю встроенный PORTAL GATE. Проверяю серверы и резервные профили…"
    }

    private fun startVpn(
        config: String,
        fallbackConfig: String? = null,
        preferredConfig: String? = null,
        automatic: Boolean = false,
        engineBox: Boolean = false
    ) {
        connectButton.isEnabled = true
        val intent = Intent(this, GilrTanoVpnService::class.java)
            .setAction(GilrTanoVpnService.ACTION_START)
            .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config)
            .putExtra(GilrTanoVpnService.EXTRA_AUTO, automatic)
            .putExtra(GilrTanoVpnService.EXTRA_ENGINE_BOX, engineBox)
            .putExtra(GilrTanoVpnService.EXTRA_PREFERRED_ENGINE_BOX, engineBox)
        fallbackConfig?.let { intent.putExtra(GilrTanoVpnService.EXTRA_FALLBACK_CONFIG, it) }
        pendingFallbackChainJson?.let { intent.putExtra(GilrTanoVpnService.EXTRA_FALLBACK_CHAIN, it) }
        preferredConfig?.let { intent.putExtra(GilrTanoVpnService.EXTRA_PREFERRED_CONFIG, it) }
        startForegroundService(intent)
        status.text = "Запускаю VPN…"
    }

    @Deprecated("Deprecated by Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK) {
            pendingConfig?.let {
                val fallback = pendingFallbackConfig
                if (pendingPortalAwg && pendingPortalGenerate) startPortalGeneration(fallback)
                else if (pendingPortalAwg) startWarpVpn(it, fallback)
                else startVpn(it, fallback, pendingPreferredConfig, pendingAutomatic, pendingEngineBox)
                clearPendingPortalRequest()
            } ?: run { status.text = "Подключение отменено" }
        } else if (requestCode == this.requestCode) {
            clearPendingPortalRequest()
            connectButton.isEnabled = true
            status.text = "Разрешение VPN не предоставлено"
        } else if (requestCode == docxRequestCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            status.text = "Читаю DOCX локально…"
            Thread {
                try {
                    val yaml = DocxTextExtractor.read(this, uri)
                    val imported = SubscriptionImporter.parse(yaml)
                    runOnUiThread {
                        if (imported.isEmpty()) {
                            status.text = "В DOCX не найдены поддерживаемые VLESS/Hysteria2-профили"
                        } else {
                            quattroNodes = imported
                            saveNodes("quattro_nodes", quattroNodes)
                            activeFree = false
                            prefs.edit().putBoolean("active_free_mode", false).apply()
                            refreshMode()
                            status.text = "Quattro сохранён: ${imported.size} серверов VLESS/Hysteria2. Импортировать снова не нужно."
                            measureSelectedPing()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Ошибка чтения DOCX: ${e.message}" }
                }
            }.start()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(GilrTanoVpnService.ACTION_STATUS)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }
}