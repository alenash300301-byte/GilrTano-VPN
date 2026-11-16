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
    private lateinit var autoTab: Button
    private var autoMode = true
    private var activeMode: String = "auto"
    private lateinit var bestFreeButton: Button
    private lateinit var checkPing: Button
    private lateinit var modeSelectorRow: LinearLayout
    private lateinit var pingMetric: TextView
    private lateinit var downloadMetric: TextView
    private lateinit var uploadMetric: TextView
    private lateinit var sessionMetric: TextView
    private lateinit var trafficTotalMetric: TextView
    private lateinit var routeMetric: TextView
    private var sessionRxStart = TrafficStats.getTotalRxBytes()
    private var sessionTxStart = TrafficStats.getTotalTxBytes()
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
    private var pendingConfig: String? = null
    private var pendingFallbackConfig: String? = null
    private var pendingPreferredConfig: String? = null
    private var pendingAutomatic = false
    private var pendingPortalAwg = false
    private var pendingFallbackChain: List<String> = emptyList()
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
                sessionRxStart = TrafficStats.getTotalRxBytes()
                sessionTxStart = TrafficStats.getTotalTxBytes()
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
            } else if (message.startsWith("Подключаю") || message.startsWith("Запускаю") || (message.startsWith("Запуск Xray") || message.startsWith("Запуск PORTAL") || message.startsWith("Запуск Quattro")) || message.startsWith("Переключаю") || message.startsWith("Переключаюсь") || message.startsWith("Проверяю восстановление")) {
                status.setTextColor(Color.rgb(255, 166, 55))
                // Keep the ignition animation visible while the tunnel is starting.
            }
            val busy = message.startsWith("Подключаю") || message.startsWith("Запускаю") || (message.startsWith("Запуск Xray") || message.startsWith("Запуск PORTAL") || message.startsWith("Запуск Quattro")) || message.startsWith("Переключаю") || message.startsWith("Переключаюсь") || message.startsWith("Проверяю восстановление")
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
            setOnClickListener { startActivity(Intent(this@MainActivity, PortalGateActivity::class.java)) }
        }
        autoTab = Button(this).apply {
            text = "АВТО PORTAL"
            setOnClickListener { switchToAuto() }
        }

        portalGateButton = Button(this).apply {
            text = "ОТКРЫТЬ PORTAL GATE"
            setOnClickListener { startActivity(Intent(this@MainActivity, PortalGateActivity::class.java)) }
        }
        val importPrimary = Button(this).apply {
            text = "Импортировать Quattro"
            setOnClickListener { importFrom(primarySource.text.toString().trim(), false) }
        }
        val importDocx = Button(this).apply {
            text = "Выбрать DOCX Quattro"
            setOnClickListener {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                }
                startActivityForResult(pick, docxRequestCode)
            }
        }
        val importFree = Button(this).apply {
            text = "Импортировать бесплатные серверы"
            setOnClickListener { importFrom(freeSource.text.toString().trim(), true) }
        }
        connectButton = Button(this).apply {
            text = "⏻\nПОДКЛЮЧИТЬ"
            setOnClickListener {
                val currentStatus = status.text.toString()
                val isConnected = currentStatus.contains("Подключено")
                val isConnecting = currentStatus.startsWith("Подключаю") ||
                    currentStatus.startsWith("Запускаю") || currentStatus.startsWith("Запуск Xray") ||
                    currentStatus.startsWith("Запуск PORTAL") || currentStatus.startsWith("Запуск Quattro") ||
                    currentStatus.startsWith("Переключаю") || currentStatus.startsWith("Переключаюсь") ||
                    currentStatus.startsWith("Проверяю восстановление")
                if (isConnected || isConnecting) {
                    pendingConfig = null
                    status.text = if (isConnected) "Отключаю VPN…" else "Отменяю подключение…"
                    startService(Intent(this@MainActivity, GilrTanoVpnService::class.java)
                        .setAction(GilrTanoVpnService.ACTION_STOP))
                    return@setOnClickListener
                }
                // Explicit Quattro mode uses the selected Quattro profile.
                // Automatic mode below uses PORTAL first and Quattro as fallback.
                if (!autoMode) {
                    val node = quattroNodes.getOrNull(servers.selectedItemPosition)
                    if (node == null) {
                        status.text = "Сначала импортируй профиль Quattro в настройках"
                        return@setOnClickListener
                    }
                    val config = runCatching { GilrTanoVpnService.xrayConfigFromVless(node.uri) }
                        .getOrElse {
                            status.text = "Ошибка профиля Quattro: ${it.message}"
                            return@setOnClickListener
                        }
                    pendingConfig = config
                    pendingFallbackConfig = null
                    pendingPreferredConfig = null
                    pendingAutomatic = false
                    pendingPortalAwg = false
                    status.text = "Запрашиваю разрешение VPN для Quattro…"
                    val permission = VpnService.prepare(this@MainActivity)
                    if (permission != null) startActivityForResult(permission, requestCode)
                    else startVpn(config)
                    return@setOnClickListener
                }

                // One-tap automatic mode: fresh PORTAL/WARP first, Quattro if the generator is unavailable.
                status.text = "Получаю новую конфигурацию WARP…"
                connectButton.isEnabled = false
                Thread {
                    try {
                        // Refresh the saved Quattro subscription when configured, then
                        // test real outbound latency and keep the fastest working nodes.
                        val quattroSource = prefs.getString("subscription_url", "").orEmpty().trim()
                        val discoveredQuattro = if (quattroSource.isNotBlank()) {
                            runCatching { SubscriptionImporter.load(quattroSource) }
                                .getOrElse { error ->
                                    android.util.Log.w("QuattroAuto", "Subscription refresh failed; using cached nodes", error)
                                    quattroNodes
                                }
                        } else quattroNodes
                        val rankedQuattro = scanQuattroCandidates(discoveredQuattro)
                        val selectedQuattro = rankedQuattro.firstOrNull()?.first ?: discoveredQuattro.firstOrNull()
                        if (rankedQuattro.isNotEmpty()) {
                            quattroNodes = rankedQuattro.map { it.first }
                            saveNodes("quattro_nodes", quattroNodes)
                            prefs.edit().putInt("quattro_selected", 0).apply()
                        } else if (discoveredQuattro.isNotEmpty()) {
                            quattroNodes = discoveredQuattro
                        }
                        val quattro = selectedQuattro
                            ?.let { runCatching { GilrTanoVpnService.xrayConfigFromVless(it.uri) }.getOrNull() }
                        val generated = WarpGeneratorClient.generateAmneziaProfile()
                        val profile = PortalGateImporter.parseConf(generated, "WARP-Cyberportal")
                        runOnUiThread {
                            pendingConfig = profile.config
                            pendingFallbackConfig = quattro
                            pendingPreferredConfig = null
                            pendingAutomatic = true
                            pendingPortalAwg = true
                            pendingFallbackChain = (if (rankedQuattro.isNotEmpty()) rankedQuattro.map { it.first } else discoveredQuattro)
                                .mapNotNull { node -> runCatching { GilrTanoVpnService.xrayConfigFromVless(node.uri) }.getOrNull() }
                            connectButton.isEnabled = true
                            status.text = "WARP создан. Запрашиваю разрешение VPN…"
                            val permission = VpnService.prepare(this@MainActivity)
                            if (permission != null) startActivityForResult(permission, requestCode)
                            else startWarpVpn(profile.config, quattro)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("WarpGenerator", "WARP generation/import pipeline failed", e)
                        // Generator outage: use the bundled PORTAL archive as the
                        // primary profile. The service loads the rest as failover.
                        val bundled = runCatching {
                            assets.open("portal_gate.zip").use { input ->
                                PortalGateImporter.parseZip(input).firstOrNull { profile ->
                                    runCatching {
                                        org.json.JSONObject(profile.config).optJSONArray("outbounds")
                                            ?.optJSONObject(0)?.optString("protocol") != "masque"
                                    }.getOrDefault(true)
                                }
                            }
                        }.getOrNull()
                        val quattroFallback = quattroNodes.firstOrNull()
                            ?.let { runCatching { GilrTanoVpnService.xrayConfigFromVless(it.uri) }.getOrNull() }
                        runOnUiThread {
                            connectButton.isEnabled = true
                            if (bundled != null) {
                                pendingConfig = bundled.config
                                pendingFallbackConfig = quattroFallback
                                pendingPreferredConfig = null
                                pendingAutomatic = true
                                pendingPortalAwg = true
                                pendingFallbackChain = quattroNodes.mapNotNull { node -> runCatching { GilrTanoVpnService.xrayConfigFromVless(node.uri) }.getOrNull() }
                                status.text = "Генератор WARP недоступен. Запускаю резервный PORTAL…"
                                val permission = VpnService.prepare(this@MainActivity)
                                if (permission != null) startActivityForResult(permission, requestCode)
                                else startWarpVpn(bundled.config, quattroFallback)
                            } else if (quattroFallback != null) {
                                // No public profile archive is bundled. If PORTAL generation
                                // is down, keep the paid Quattro path usable instead of failing.
                                pendingConfig = quattroFallback
                                pendingFallbackConfig = null
                                pendingPreferredConfig = null
                                pendingAutomatic = false
                                pendingPortalAwg = false
                                status.text = "Генератор PORTAL недоступен. Подключаю Quattro…"
                                val permission = VpnService.prepare(this@MainActivity)
                                if (permission != null) startActivityForResult(permission, requestCode)
                                else startVpn(quattroFallback)
                            } else {
                                status.text = "PORTAL недоступен, а профиль Quattro не импортирован: ${e.message ?: "ошибка генератора"}"
                                resetConnectButtonGlow()
                            }
                        }
                    }
                }.start()
            }
        }
        bestFreeButton = Button(this).apply {
            text = "Найти лучший бесплатный сервер (проверка интернета)"
            setOnClickListener { refreshFreeServersAndFindBest() }
        }
        checkPing = Button(this).apply {
            text = "ПРОВЕРИТЬ ЗАДЕРЖКУ"
            setOnClickListener { measureSelectedPing() }
        }

        // FlClashX-inspired settings surfaces: restrained glass-like cards,
        // compact typography and subtle borders. Existing actions remain unchanged.
        fun stylePanelButton(button: Button, accent: Boolean = false) {
            button.isAllCaps = false
            button.textSize = 15f
            button.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            button.setTextColor(Color.rgb(238, 240, 245))
            button.minimumHeight = dp(54)
            button.setPadding(dp(18), dp(12), dp(18), dp(12))
            button.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    if (accent) Color.rgb(48, 28, 30) else Color.rgb(29, 31, 37),
                    if (accent) Color.rgb(27, 19, 22) else Color.rgb(19, 21, 26)
                )
            ).apply {
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), if (accent) Color.rgb(126, 49, 53) else Color.rgb(61, 65, 74))
            }
            if (android.os.Build.VERSION.SDK_INT >= 21) button.elevation = dp(2).toFloat()
        }
        stylePanelButton(checkPing)
        stylePanelButton(portalGateButton, true)
        primarySource.apply {
            textSize = 14f
            setTextColor(Color.rgb(240, 242, 247))
            setHintTextColor(Color.rgb(137, 143, 154))
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 22, 27))
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), Color.rgb(58, 63, 73))
            }
        }
        freeSource.apply {
            textSize = 14f
            setTextColor(Color.rgb(240, 242, 247))
            setHintTextColor(Color.rgb(137, 143, 154))
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 22, 27))
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), Color.rgb(58, 63, 73))
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 5, 7))
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
                        setOnClickListener {
                            settingsExpanded = !settingsExpanded
                            settingsHeader.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
                            findImportButtonsVisibility()
                        }
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
                        setOnClickListener {
                            settingsExpanded = true
                            settingsHeader.visibility = View.VISIBLE
                            findImportButtonsVisibility()
                            root.post { (root.parent as? android.widget.ScrollView)?.smoothScrollTo(0, root.height) }
                        }
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
                powerOrb.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                        !status.text.toString().contains("Подключено")) {
                        tigerFlames.setActive(true)
                        if (fireAnimator?.isRunning != true) {
                            fireAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
                                duration = 900L
                                repeatCount = ValueAnimator.INFINITE
                                addUpdateListener {
                                    val phase = it.animatedValue as Float
                                    powerOrbDrawable.setPhase(phase)
                                    tigerFlames.setPhase(phase)
                                }
                                start()
                            }
                        }
                    }
                    false
                }
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
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(10, 10, 12)); cornerRadius = dp(28).toFloat()
                        setStroke(dp(1), Color.rgb(45, 45, 48))
                    }
                    setPadding(dp(16), dp(10), dp(14), dp(10))
                    setOnClickListener { showServerPicker() }
                    addView(TextView(this@MainActivity).apply {
                        text = "🌐"; textSize = 25f; setTextColor(Color.WHITE)
                        setPadding(0, 0, dp(14), 0)
                    })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        serverCardTitle = TextView(this@MainActivity).apply {
                            text = "Автоматический сервер"; textSize = 16f
                            setTextColor(Color.WHITE)
                        }
                        serverCardSubtitle = TextView(this@MainActivity).apply {
                            text = "Автовыбор"; textSize = 12f
                            setTextColor(Color.LTGRAY)
                        }
                        addView(serverCardTitle)
                        addView(serverCardSubtitle)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        serverCardPing = TextView(this@MainActivity).apply {
                            text = ""; textSize = 13f; setTextColor(Color.rgb(0, 230, 55))
                        }
                        addView(serverCardPing)
                        addView(TextView(this@MainActivity).apply {
                            text = "  ›"; textSize = 27f; setTextColor(Color.WHITE)
                        })
                    })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)).apply {
                    topMargin = dp(8); bottomMargin = dp(8)
                })
                // Branded mode control: automatic failover or a directly selected Quattro profile.
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(10, 10, 13))
                        cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.rgb(54, 54, 60))
                    }
                    setPadding(dp(13), dp(10), dp(13), dp(12))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(TextView(this@MainActivity).apply {
                            text = "РЕЖИМ ПОДКЛЮЧЕНИЯ"
                            textSize = 11f
                            letterSpacing = 0.12f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(Color.rgb(190, 190, 198))
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(TextView(this@MainActivity).apply {
                            text = "● LIVE"
                            textSize = 9f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(Color.rgb(40, 230, 85))
                        })
                    })
                    modeSelectorRow = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, dp(9), 0, dp(7))
                        addView(autoTab, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(5) })
                        addView(quattroTab, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(5) })
                    }
                    addView(modeSelectorRow)
                    addView(TextView(this@MainActivity).apply {
                        text = "Авто: PORTAL → Quattro при сбое  ·  Quattro: выбранный профиль"
                        textSize = 10f
                        gravity = android.view.Gravity.CENTER
                        setTextColor(Color.rgb(135, 135, 145))
                    })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                })
                // Three compact live-metric cards and the neon traffic trace from the approved reference.
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    fun metric(title: String, value: String, unit: String = ""): LinearLayout {
                        return LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = android.view.Gravity.CENTER
                            background = GradientDrawable().apply {
                                setColor(Color.rgb(12, 12, 15))
                                cornerRadius = dp(16).toFloat()
                                setStroke(dp(1), Color.rgb(48, 48, 52))
                            }
                            addView(TextView(this@MainActivity).apply {
                                text = title; textSize = 11f
                                setTextColor(Color.LTGRAY)
                            })
                            addView(TextView(this@MainActivity).apply {
                                text = value + unit; textSize = 14f
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                setTextColor(Color.rgb(30, 238, 65))
                                setPadding(0, dp(3), 0, 0)
                                if (title == "Пинг") pingMetric = this
                                if (title == "Входящий") downloadMetric = this
                                if (title == "Исходящий") uploadMetric = this
                            })
                        }
                    }
                    addView(metric("Пинг", "—", " мс"), LinearLayout.LayoutParams(0, dp(62), 1f).apply { rightMargin = dp(6) })
                    addView(metric("Входящий", "—", " Мбит/с"), LinearLayout.LayoutParams(0, dp(62), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
                    addView(metric("Исходящий", "—", " Мбит/с"), LinearLayout.LayoutParams(0, dp(62), 1f).apply { leftMargin = dp(6) })
                }, LinearLayout.LayoutParams.MATCH_PARENT, dp(62))
                // Second dashboard strip: session duration, total traffic and active route.
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(12, 12, 15))
                        cornerRadius = dp(17).toFloat()
                        setStroke(dp(1), Color.rgb(82, 35, 37))
                    }
                    setPadding(dp(8), dp(9), dp(8), dp(9))
                    fun detail(label: String, initial: String): LinearLayout {
                        return LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = android.view.Gravity.CENTER
                            addView(TextView(this@MainActivity).apply {
                                text = label; textSize = 10f
                                setTextColor(Color.rgb(155, 158, 166))
                            })
                            addView(TextView(this@MainActivity).apply {
                                text = initial; textSize = 13f
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                setTextColor(Color.WHITE)
                                setPadding(0, dp(3), 0, 0)
                                if (label == "СЕССИЯ") sessionMetric = this
                                if (label == "ТРАФИК") trafficTotalMetric = this
                                if (label == "МАРШРУТ") routeMetric = this
                            })
                        }
                    }
                    addView(detail("СЕССИЯ", "00:00"), LinearLayout.LayoutParams(0, dp(43), 1f))
                    addView(View(this@MainActivity).apply { setBackgroundColor(Color.rgb(58, 58, 64)) },
                        LinearLayout.LayoutParams(dp(1), dp(30)))
                    addView(detail("ТРАФИК", "—"), LinearLayout.LayoutParams(0, dp(43), 1f))
                    addView(View(this@MainActivity).apply { setBackgroundColor(Color.rgb(58, 58, 64)) },
                        LinearLayout.LayoutParams(dp(1), dp(30)))
                    addView(detail("МАРШРУТ", if (autoMode) "PORTAL → QUATTRO" else "QUATTRO"), LinearLayout.LayoutParams(0, dp(43), 1.2f))
                }, LinearLayout.LayoutParams.MATCH_PARENT, dp(62)).apply {
                    topMargin = dp(8)
                }
                addView(TextView(this@MainActivity).apply {
                    text = "Скорость сети"
                    textSize = 10f
                    setTextColor(Color.GRAY)
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(3), 0, 0)
                }, LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
                addView(TrafficWaveView(this@MainActivity), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
                ).apply { topMargin = dp(8); bottomMargin = dp(8) })
                addView(servers, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                servers.visibility = View.GONE
                addView(pingStatus.apply { gravity = android.view.Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                pingStatus.visibility = View.GONE
                addView(bestFreeButton, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                bestFreeButton.visibility = View.GONE
                addView(checkPing, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                checkPing.visibility = View.GONE
                settingsHeader = TextView(this@MainActivity).apply {
                    text = "Настройки и импорт     ⌄"; textSize = 17f
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setTextColor(Color.rgb(245, 246, 250)); setPadding(dp(18), dp(17), dp(16), dp(17))
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.rgb(34, 36, 43), Color.rgb(20, 22, 27))
                    ).apply {
                        cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.rgb(66, 70, 80))
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 21) elevation = dp(3).toFloat()
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
                // Mode selector is already attached inside the home-screen mode card above.
                addView(primarySource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(importPrimary)
                addView(importDocx)
                addView(freeSource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(importFree)
                addView(TextView(this@MainActivity).apply {
                    text = "Бесплатные серверы предоставляются сторонними операторами"
                    textSize = 11f; setTextColor(Color.rgb(139, 145, 156)); gravity = android.view.Gravity.CENTER
                    setPadding(dp(8), dp(16), dp(8), dp(12))
                })
            })
        }
        // Respect Android system bars so the brand header and bottom navigation
        // never sit underneath the clock or gesture handle.
        window.statusBarColor = Color.rgb(5, 5, 7)
        window.navigationBarColor = Color.rgb(5, 5, 6)
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

        // Compact FlClashX-inspired navigation dock with clear selected state.
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 17, 21))
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), Color.rgb(48, 52, 60))
            }
            if (android.os.Build.VERSION.SDK_INT >= 21) elevation = dp(8).toFloat()
        }
        val navViews = mutableListOf<TextView>()
        fun updateNavSelection(selected: Int) {
            navViews.forEachIndexed { index, item ->
                val active = index == selected
                item.setTextColor(if (active) Color.rgb(242, 245, 250) else Color.rgb(151, 157, 168))
                item.background = GradientDrawable().apply {
                    setColor(if (active) Color.rgb(43, 47, 56) else Color.TRANSPARENT)
                    cornerRadius = dp(18).toFloat()
                    if (active) setStroke(dp(1), Color.rgb(73, 79, 91))
                }
                item.alpha = if (active) 1f else 0.88f
            }
        }
        fun navItem(symbol: String, label: String, index: Int, action: () -> Unit): TextView =
            TextView(this).apply {
                text = "$symbol\n$label"
                textSize = 11f
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL
                )
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
                maxLines = 2
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(3), dp(8), dp(3), dp(8))
                isClickable = true
                isFocusable = true
                contentDescription = label
                layoutParams = LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                navViews.add(this)
                setOnClickListener {
                    updateNavSelection(index)
                    action()
                }
            }
        bottomNav.addView(navItem("⌂", "Главная", 0) { scroll.smoothScrollTo(0, 0) })
        bottomNav.addView(navItem("◈", "Серверы", 1) { showServerPicker() })
        bottomNav.addView(navItem("⇄", "Трафик", 2) { showConnectionDetails() })
        bottomNav.addView(navItem("⚙", "Настройки", 3) {
            settingsExpanded = true
            settingsHeader.visibility = View.VISIBLE
            settingsHeader.text = "Настройки и импорт  ⌄"
            findImportButtonsVisibility()
            scroll.post { scroll.smoothScrollTo(0, settingsHeader.top) }
        })
        updateNavSelection(0)
        root.addView(LinearLayout(this).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(bottomNav, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        trafficHandler.removeCallbacks(trafficSample)
        trafficHandler.post(trafficSample)
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
                if (::tigerFlames.isInitialized) tigerFlames.setPhase(phase)
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
        activeMode = prefs.getString("connection_mode", "auto") ?: "auto"
        if (::routeMetric.isInitialized) routeMetric.text = if (activeMode == "auto") "PORTAL → QUATTRO" else "QUATTRO"
        // Fresh installs start in the one-tap PORTAL → Quattro mode.
        // Migrate any old public/free mode preference back to automatic mode.
        if (activeMode == "free") activeMode = "auto"
        autoMode = activeMode == "auto"
        activeFree = false
        prefs.edit().putString("connection_mode", activeMode)
            .putBoolean("active_free_mode", false).apply()
        refreshMode()
        updatePowerOrb(false)
        updateServerCard(false)
    }

    private fun showServerPicker() {
        val initialNodes = activeNodes
        if (initialNodes.isEmpty()) {
            settingsExpanded = true
            findImportButtonsVisibility()
            val content = findViewById<ViewGroup>(android.R.id.content)
            val root = content?.getChildAt(0) as? ViewGroup
            val scroll = root?.getChildAt(0) as? android.widget.ScrollView
            scroll?.post { scroll.smoothScrollTo(0, settingsHeader.top) }
            Toast.makeText(this, "Добавь сервер в настройках", Toast.LENGTH_LONG).show()
            return
        }

        // Full-screen server catalogue: keeps the tiger dashboard intact and gives
        // server management its own usable space on phones.
        val dialog = android.app.Dialog(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(Color.rgb(8, 8, 10))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "СЕРВЕРЫ  ·  ${initialNodes.size}"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.LTGRAY)
            isClickable = true
            setPadding(dp(14), dp(8), dp(8), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        val search = EditText(this).apply {
            hint = "Поиск сервера или страны"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 24, 28))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(64, 64, 72))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        fun actionButton(label: String) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 30, 35))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(58, 58, 66))
            }
        }
        val sortButton = actionButton("По названию")
        val testButton = actionButton("Проверить все")
        actions.addView(sortButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(5) })
        actions.addView(testButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(5) })

        val list = android.widget.ListView(this).apply {
            divider = GradientDrawable().apply { setColor(Color.rgb(38, 38, 44)); setSize(1, dp(1)) }
            dividerHeight = dp(1)
            cacheColorHint = Color.TRANSPARENT
            setBackgroundColor(Color.TRANSPARENT)
        }
        var orderedNodes = initialNodes.toList()
        var visibleNodes = orderedNodes
        val measuredLatency = mutableMapOf<String, Long>()
        fun refreshList() {
            val query = search.text.toString().trim()
            visibleNodes = orderedNodes.filter { it.name.contains(query, ignoreCase = true) }
            val labels = visibleNodes.map { node ->
                val latency = measuredLatency[node.uri]
                if (latency != null) "●  ${node.name}     ·  ${latency} мс" else "○  ${node.name}"
            }
            list.adapter = object : android.widget.BaseAdapter() {
                override fun getCount(): Int = visibleNodes.size
                override fun getItem(position: Int): Any = visibleNodes[position]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val node = visibleNodes[position]
                    val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(11), dp(14), dp(11))
                        background = GradientDrawable().apply {
                            setColor(Color.rgb(20, 20, 24))
                            cornerRadius = dp(14).toFloat()
                            setStroke(dp(1), Color.rgb(48, 48, 56))
                        }
                    }
                    row.removeAllViews()
                    val dot = TextView(this@MainActivity).apply {
                        text = if (measuredLatency.containsKey(node.uri)) "●" else "○"
                        textSize = 18f
                        setTextColor(if (measuredLatency.containsKey(node.uri)) Color.rgb(55, 225, 100) else Color.GRAY)
                        gravity = android.view.Gravity.CENTER
                    }
                    val nameColumn = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, dp(8), 0)
                    }
                    val name = TextView(this@MainActivity).apply {
                        text = node.name
                        textSize = 15f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                    }
                    val subtitle = TextView(this@MainActivity).apply {
                        text = if (measuredLatency.containsKey(node.uri)) "Проверен · доступность подтверждена" else "Нажми, чтобы подключиться"
                        textSize = 11f
                        setTextColor(Color.GRAY)
                        setPadding(0, dp(3), 0, 0)
                    }
                    nameColumn.addView(name)
                    nameColumn.addView(subtitle)
                    val latency = TextView(this@MainActivity).apply {
                        text = measuredLatency[node.uri]?.let { "$it мс" } ?: "—"
                        textSize = 13f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(if (measuredLatency.containsKey(node.uri)) Color.rgb(70, 230, 110) else Color.LTGRAY)
                        gravity = android.view.Gravity.CENTER
                    }
                    row.addView(dot, LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT))
                    row.addView(nameColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(latency, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
                    return row
                }
            }
        }
        refreshList()
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshList() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        sortButton.setOnClickListener {
            orderedNodes = if (sortButton.text == "По названию") {
                sortButton.text = "Исходный порядок"
                orderedNodes.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
            } else {
                sortButton.text = "По названию"
                initialNodes
            }
            refreshList()
        }
        testButton.setOnClickListener {
            testButton.isEnabled = false
            sortButton.isEnabled = false
            testButton.text = "Проверка…"
            val sourceNodes = orderedNodes
            Thread {
                val ranked = scanQuattroCandidates(sourceNodes)
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (ranked.isNotEmpty()) {
                        val rankedNodes = ranked.map { it.first }
                        measuredLatency.clear()
                        ranked.forEach { measuredLatency[it.first.uri] = it.second }
                        val rest = sourceNodes.filterNot { node -> rankedNodes.any { it.uri == node.uri } }
                        orderedNodes = rankedNodes + rest
                        if (activeFree) {
                            freeNodes = orderedNodes
                            saveNodes("free_nodes", freeNodes)
                        } else {
                            quattroNodes = orderedNodes
                            saveNodes("quattro_nodes", quattroNodes)
                        }
                        title.text = "СЕРВЕРЫ  ·  ${orderedNodes.size}"
                        sortButton.text = "Лучшие по пингу"
                        Toast.makeText(this@MainActivity, "Рабочих серверов найдено: ${ranked.size}", Toast.LENGTH_LONG).show()
                        refreshList()
                    } else {
                        Toast.makeText(this@MainActivity, "Рабочие серверы не найдены", Toast.LENGTH_LONG).show()
                    }
                    testButton.isEnabled = true
                    sortButton.isEnabled = true
                    testButton.text = "Проверить все"
                }
            }.start()
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val selected = visibleNodes.getOrNull(position) ?: return@setOnItemClickListener
            val currentIndex = activeNodes.indexOfFirst { it.uri == selected.uri }
            if (currentIndex >= 0) {
                servers.setSelection(currentIndex)
                val key = if (activeFree) "free_selected" else "quattro_selected"
                prefs.edit().putInt(key, currentIndex).apply()
                serverCardTitle.text = selected.name
                serverCardSubtitle.text = if (activeFree) "Бесплатный резерв" else "Quattro"
                serverCardPing.text = measuredLatency[selected.uri]?.let { "${it} мс" } ?: ""
                pingStatus.text = measuredLatency[selected.uri]?.let { "Пинг: ${it} мс" } ?: "Пинг: —"
            }
            dialog.dismiss()
        }
        page.addView(header)
        page.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8); bottomMargin = dp(10) })
        page.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(8) })
        page.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(page)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun showConnectionDetails() {
        val dialog = android.app.Dialog(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            setBackgroundColor(Color.rgb(8, 8, 10))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val heading = TextView(this).apply {
            text = "СТАТИСТИКА И СОЕДИНЕНИЕ"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.LTGRAY)
            isClickable = true
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(heading, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        val connectedNow = status.text.toString().contains("Подключено")
        val state = TextView(this).apply {
            text = if (connectedNow) "●  ЗАЩИЩЁННОЕ СОЕДИНЕНИЕ" else "○  VPN ОТКЛЮЧЁН"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (connectedNow) Color.rgb(42, 235, 92) else Color.LTGRAY)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 22, 19))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), if (connectedNow) Color.rgb(35, 105, 55) else Color.rgb(55, 55, 60))
            }
        }
        fun infoCard(label: String, value: String): Pair<LinearLayout, TextView> {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(20, 20, 24))
                    cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), Color.rgb(48, 48, 55))
                }
            }
            val caption = TextView(this).apply {
                text = label.uppercase(java.util.Locale.ROOT)
                textSize = 10f
                setTextColor(Color.GRAY)
            }
            val number = TextView(this).apply {
                text = value
                textSize = 19f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, dp(5), 0, 0)
                maxLines = 2
            }
            card.addView(caption)
            card.addView(number)
            return card to number
        }
        val downCard = infoCard("ВХОДЯЩАЯ СКОРОСТЬ", if (::downloadMetric.isInitialized) downloadMetric.text.toString() else "0.0 Мбит/с")
        val upCard = infoCard("ИСХОДЯЩАЯ СКОРОСТЬ", if (::uploadMetric.isInitialized) uploadMetric.text.toString() else "0.0 Мбит/с")
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        grid.addView(downCard.first, LinearLayout.LayoutParams(0, dp(94), 1f).apply { rightMargin = dp(5) })
        grid.addView(upCard.first, LinearLayout.LayoutParams(0, dp(94), 1f).apply { leftMargin = dp(5) })
        val currentNode = activeNodes.getOrNull(servers.selectedItemPosition)?.name ?: "Автовыбор"
        val serverCard = infoCard("ТЕКУЩИЙ СЕРВЕР", currentNode)
        val modeCard = infoCard("РЕЖИМ РАБОТЫ", if (autoMode) "Автоматический · PORTAL → Quattro" else if (activeFree) "Резервный" else "Quattro")
        val pingCard = infoCard("ЗАДЕРЖКА", if (::pingMetric.isInitialized) pingMetric.text.toString() else "— мс")
        val scroll = android.widget.ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        body.addView(state, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        val trafficNote = TextView(this).apply {
            text = "Скорость показывает общий сетевой трафик устройства"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(dp(3), 0, dp(3), dp(8))
        }
        body.addView(trafficNote)
        body.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(94)).apply { bottomMargin = dp(10) })
        body.addView(serverCard.first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) })
        body.addView(modeCard.first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) })
        body.addView(pingCard.first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        val pingButton = Button(this).apply {
            text = "ПРОВЕРИТЬ ЗАДЕРЖКУ"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 38, 27))
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), Color.rgb(45, 130, 65))
            }
            setOnClickListener { measureSelectedPing() }
        }
        body.addView(pingButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        scroll.addView(body)
        page.addView(header)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(page)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val liveUpdate = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                downCard.second.text = if (::downloadMetric.isInitialized) downloadMetric.text else "0.0 Мбит/с"
                upCard.second.text = if (::uploadMetric.isInitialized) uploadMetric.text else "0.0 Мбит/с"
                pingCard.second.text = if (::pingMetric.isInitialized) pingMetric.text else "— мс"
                val nowConnected = ::status.isInitialized && status.text.toString().contains("Подключено")
                state.text = if (nowConnected) "●  ЗАЩИЩЁННОЕ СОЕДИНЕНИЕ" else "○  VPN ОТКЛЮЧЁН"
                state.setTextColor(if (nowConnected) Color.rgb(42, 235, 92) else Color.LTGRAY)
                state.background = GradientDrawable().apply {
                    setColor(Color.rgb(18, 22, 19))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), if (nowConnected) Color.rgb(35, 105, 55) else Color.rgb(55, 55, 60))
                }
                serverCard.second.text = activeNodes.getOrNull(servers.selectedItemPosition)?.name ?: "Автовыбор"
                modeCard.second.text = if (autoMode) "Автоматический · PORTAL → Quattro" else if (activeFree) "Резервный" else "Quattro"
                trafficHandler.postDelayed(this, 1000L)
            }
        }
        dialog.setOnDismissListener { trafficHandler.removeCallbacks(liveUpdate) }
        trafficHandler.post(liveUpdate)
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
            ForegroundColorSpan(Color.rgb(220, 32, 32)),
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
                if (::tigerFlames.isInitialized) tigerFlames.setPhase(phase)
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
        root.setBackgroundColor(Color.rgb(8, 8, 10))
        fun style(view: android.view.View) {
            when (view) {
                is Button -> {
                    view.setTextColor(Color.WHITE)
                    view.textSize = 13f
                    view.isAllCaps = false
                    view.background = GradientDrawable().apply {
                        setColor(Color.rgb(14, 8, 9))
                        cornerRadius = 18f
                        setStroke(2, Color.rgb(150, 24, 24))
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
                        setColor(Color.rgb(18, 12, 10))
                        cornerRadius = 14f
                        setStroke(1, Color.rgb(120, 48, 18))
                    }
                }
                is TextView -> {
                    if (view.currentTextColor != Color.WHITE) view.setTextColor(Color.LTGRAY)
                }
                is Spinner -> {
                    view.setBackgroundColor(Color.rgb(24, 25, 28))
                }
                is ViewGroup -> for (i in 0 until view.childCount) style(view.getChildAt(i))
            }
        }
        style(root)
    }

    private fun switchToAuto() {
        // Automatic mode is PORTAL GATE first, with Quattro as the final fallback.
        // Do not silently switch to the rejected public/free-server mode.
        autoMode = true
        activeMode = "auto"
        activeFree = false
        prefs.edit().putString("connection_mode", "auto")
            .putBoolean("active_free_mode", false).apply()
        refreshMode()
        Toast.makeText(this, "Автовыбор включён: PORTAL GATE → Quattro", Toast.LENGTH_SHORT).show()
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
            val config = runCatching { GilrTanoVpnService.xrayConfigFromVless(node.uri) }.getOrElse {
                Toast.makeText(this, "Не удалось подготовить сервер: ${it.message}", Toast.LENGTH_LONG).show()
                return
            }
            status.text = "Переключаю на ${if (free) "бесплатный сервер" else "Quattro"}…"
            startService(Intent(this, GilrTanoVpnService::class.java)
                .setAction(GilrTanoVpnService.ACTION_SWITCH)
                .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config))
        } else {
            Toast.makeText(this, if (free) "Выбран бесплатный резерв" else "Выбран Quattro", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshMode() {
        if (::routeMetric.isInitialized) routeMetric.text = if (autoMode) "PORTAL → QUATTRO" else "QUATTRO"
        autoTab.isEnabled = !autoMode
        quattroTab.isEnabled = autoMode
        autoTab.alpha = if (autoMode) 1f else 0.72f
        quattroTab.alpha = if (!autoMode && !activeFree) 1f else 0.72f
        fun modeBackground(selected: Boolean) = GradientDrawable().apply {
            setColor(if (selected) Color.rgb(54, 24, 12) else Color.rgb(13, 13, 15))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(if (selected) 2 else 1),
                if (selected) Color.rgb(220, 32, 32) else Color.rgb(58, 58, 62))
        }
        quattroTab.background = modeBackground(!autoMode && !activeFree)
        autoTab.background = modeBackground(autoMode)
        quattroTab.setTextColor(if (!autoMode && !activeFree) Color.WHITE else Color.LTGRAY)
        autoTab.setTextColor(if (autoMode) Color.WHITE else Color.LTGRAY)
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

    private fun findImportButtonsVisibility() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as? LinearLayout ?: return
        val scroll = root.getChildAt(0) as? android.widget.ScrollView ?: return
        val page = scroll.getChildAt(0) as? ViewGroup ?: return
        settingsHeader.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        portalGateButton.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        modeSelectorRow.visibility = View.VISIBLE
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
                if (::sessionMetric.isInitialized) {
                    sessionMetric.text = if (connected && ::connectionTimer.isInitialized) connectionTimer.text.toString() else "00:00"
                }
                if (::trafficTotalMetric.isInitialized) {
                    val total = if (connected && rx >= 0 && tx >= 0) {
                        (rx - sessionRxStart).coerceAtLeast(0L) + (tx - sessionTxStart).coerceAtLeast(0L)
                    } else 0L
                    trafficTotalMetric.text = if (total >= 1_073_741_824L)
                        String.format(java.util.Locale.US, "%.2f ГБ", total / 1_073_741_824.0)
                    else String.format(java.util.Locale.US, "%.0f МБ", total / 1_048_576.0)
                }
                lastRxBytes = rx
                lastTxBytes = tx
                lastTrafficSample = now
            }
            trafficHandler.postDelayed(this, 1000L)
        }
    }

    private fun importFrom(source: String, isFree: Boolean) {
        if (source.isBlank()) {
            Toast.makeText(this, "Укажи ссылку или конфигурацию", Toast.LENGTH_LONG).show()
            return
        }
        status.text = if (isFree) "Загружаю резерв…" else "Загружаю Quattro…"
        Thread {
            try {
                val imported = SubscriptionImporter.load(source)
                runOnUiThread {
                    if (imported.isEmpty()) {
                        status.text = "Не найдено поддерживаемых VLESS-серверов"
                        return@runOnUiThread
                    }
                    if (isFree) {
                        freeNodes = imported
                        prefs.edit().putString("free_subscription_url", source).apply()
                        saveNodes("free_nodes", freeNodes)
                    } else {
                        quattroNodes = imported
                        prefs.edit().putString("subscription_url", source).apply()
                        saveNodes("quattro_nodes", quattroNodes)
                    }
                    activeFree = isFree
                    prefs.edit().putBoolean("active_free_mode", activeFree).apply()
                    refreshMode()
                    settingsExpanded = false
                    settingsHeader.text = "Настройки"
                    findImportButtonsVisibility()
                    status.text = "Серверы добавлены"
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
                        val config = runCatching { GilrTanoVpnService.xrayConfigFromVless(node.uri) }
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
    /**
     * Tests Quattro nodes through the actual proxy outbound, not just TCP port reachability.
     * Returns up to three working nodes sorted by measured HTTPS latency.
     */
    private fun scanQuattroCandidates(nodes: List<ImportedNode>): List<Pair<ImportedNode, Long>> {
        if (nodes.isEmpty()) return emptyList()
        val candidates = nodes.take(80)
        val pool = Executors.newFixedThreadPool(8)
        val completion = java.util.concurrent.ExecutorCompletionService<Pair<ImportedNode, Long>?>(pool)
        try {
            candidates.forEach { node ->
                completion.submit(Callable<Pair<ImportedNode, Long>?> {
                    val config = runCatching { GilrTanoVpnService.xrayConfigFromVless(node.uri) }
                        .getOrNull() ?: return@Callable null
                    runCatching {
                        val latency = teapodcore.Teapodcore.measureOutboundDelay(
                            config, "https://www.gstatic.com/generate_204"
                        )
                        if (latency > 0L) node to latency else null
                    }.getOrNull()
                })
            }
            val results = mutableListOf<Pair<ImportedNode, Long>>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18)
            var completed = 0
            while (completed < candidates.size && System.nanoTime() < deadline && results.size < 3) {
                val remaining = deadline - System.nanoTime()
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                completed++
                runCatching { future.get() }.getOrNull()?.let(results::add)
            }
            return results.sortedBy { it.second }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun connectAutomaticallySelected() {
        val node = activeNodes.getOrNull(servers.selectedItemPosition) ?: return
        try {
            val config = GilrTanoVpnService.xrayConfigFromVless(node.uri)
            // Public/free nodes are intentionally excluded; Quattro remains the only Xray fallback.
            val fallback: String? = null
            pendingConfig = config
            pendingFallbackConfig = fallback
            pendingPreferredConfig = config
            pendingAutomatic = true
            status.text = "Подключаю лучший доступный сервер…"
            val permission = VpnService.prepare(this)
            if (permission != null) startActivityForResult(permission, requestCode)
            else startVpn(config, fallback, config, true)
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

    private fun startWarpVpn(config: String, fallbackQuattro: String?, fallbackChain: List<String> = emptyList()) {
        val intent = Intent(this, GilrTanoVpnService::class.java)
            .setAction(GilrTanoVpnService.ACTION_START_PORTAL)
            .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config)
            .putExtra(GilrTanoVpnService.EXTRA_ENGINE_BOX, true)
            .putExtra(GilrTanoVpnService.EXTRA_PORTAL, true)
            .putExtra(GilrTanoVpnService.EXTRA_AUTO, true)
            .putExtra(GilrTanoVpnService.EXTRA_FALLBACK_CONFIG, fallbackQuattro)
            .putExtra(GilrTanoVpnService.EXTRA_FALLBACK_ENGINE_BOX, false)
            .putExtra(GilrTanoVpnService.EXTRA_FALLBACK_CHAIN, JSONArray(fallbackChain).toString())
        startForegroundService(intent)
        status.text = "Запускаю сгенерированный WARP. Резервный архив загружает VPN-служба…"
    }

    private fun startVpn(
        config: String,
        fallbackConfig: String? = null,
        preferredConfig: String? = null,
        automatic: Boolean = false
    ) {
        connectButton.isEnabled = true
        val intent = Intent(this, GilrTanoVpnService::class.java)
            .setAction(GilrTanoVpnService.ACTION_START)
            .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config)
            .putExtra(GilrTanoVpnService.EXTRA_AUTO, automatic)
        fallbackConfig?.let { intent.putExtra(GilrTanoVpnService.EXTRA_FALLBACK_CONFIG, it) }
        preferredConfig?.let { intent.putExtra(GilrTanoVpnService.EXTRA_PREFERRED_CONFIG, it) }
        startForegroundService(intent)
        status.text = "Запускаю VPN…"
    }

    @Deprecated("Deprecated by Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK) {
            pendingConfig?.let {
                if (pendingPortalAwg) startWarpVpn(it, pendingFallbackConfig, pendingFallbackChain)
                else startVpn(it, pendingFallbackConfig, pendingPreferredConfig, pendingAutomatic)
                pendingConfig = null
                pendingFallbackConfig = null
                pendingPreferredConfig = null
                pendingAutomatic = false
                pendingPortalAwg = false
                pendingFallbackChain = emptyList()
            } ?: run { status.text = "Подключение отменено" }
        } else if (requestCode == this.requestCode) {
            pendingConfig = null
            pendingFallbackConfig = null
            pendingPreferredConfig = null
            pendingPortalAwg = false
            pendingFallbackChain = emptyList()
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
                            status.text = "В DOCX не найдены поддерживаемые VLESS-профили"
                        } else {
                            quattroNodes = imported
                            saveNodes("quattro_nodes", quattroNodes)
                            activeFree = false
                            prefs.edit().putBoolean("active_free_mode", false).apply()
                            refreshMode()
                            status.text = "Quattro сохранён: ${imported.size} серверов. Импортировать снова не нужно."
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