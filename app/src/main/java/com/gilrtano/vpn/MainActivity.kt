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
    private lateinit var autoTab: Button
    private lateinit var portalTab: Button
    private lateinit var portalSource: EditText
    private lateinit var importPortalButton: Button
    private lateinit var portalYamlButton: Button
    private var portalNodes: List<ImportedNode> = emptyList()
    private var activePortal = false
    private var pendingEngine = "xray"
    private var autoMode = true
    private var activeMode: String = "auto"
    private lateinit var bestFreeButton: Button
    private lateinit var checkPing: Button
    private lateinit var modeSelectorRow: LinearLayout
    private lateinit var serverCard: View
    private lateinit var navigationDock: FlClashNavigationDock
    private lateinit var homePage: LinearLayout
    private lateinit var proxyPage: LinearLayout
    private lateinit var profilesPage: LinearLayout
    private lateinit var settingsPage: LinearLayout
    private lateinit var navigationPages: List<LinearLayout>
    private lateinit var contentScrollView: android.widget.ScrollView
    private var currentPageIndex = 0
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
    private val requestCode = 4101
    private val docxRequestCode = 4102
    private val portalYamlRequestCode = 4103

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private val activeNodes: List<ImportedNode>
        get() = when { activePortal -> portalNodes; activeFree -> freeNodes; else -> quattroNodes }

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
        portalNodes = restoreNodes("portal_nodes")
        activePortal = prefs.getString("connection_mode", "") == "portal"

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
        portalSource = EditText(this).apply {
            hint = "Ссылка Portal (HTTPS или VLESS / Trojan / SS)"
            minLines = 2; maxLines = 4
            setText(prefs.getString("portal_subscription_url", ""))
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
            text = "БЕСПЛАТНЫЙ"
            setOnClickListener { switchMode(true) }
        }
        autoTab = Button(this).apply {
            text = "АВТО"
            setOnClickListener { switchToAuto() }
        }

        portalTab = Button(this).apply {
            text = "PORTAL"
            setOnClickListener { switchToPortal() }
        }
        importPortalButton = Button(this).apply {
            text = "Импортировать Portal"
            setOnClickListener { importPortal(portalSource.text.toString().trim()) }
        }
        portalYamlButton = Button(this).apply {
            text = "Выбрать WARP YAML"
            setOnClickListener {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(pick, portalYamlRequestCode)
            }
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
                    currentStatus.startsWith("Запускаю") || currentStatus.startsWith("Запуск Xray") || currentStatus.startsWith("Переключаю")
                if (isConnected || isConnecting) {
                    pendingConfig = null
                    status.text = if (isConnected) "Отключаю VPN…" else "Отменяю подключение…"
                    startService(Intent(this@MainActivity, GilrTanoVpnService::class.java)
                        .setAction(GilrTanoVpnService.ACTION_STOP))
                    return@setOnClickListener
                }
                if (autoMode && !activePortal) {
                    activeFree = quattroNodes.isEmpty() && freeNodes.isNotEmpty()
                    prefs.edit().putBoolean("active_free_mode", activeFree).apply()
                    refreshMode()
                    if (quattroNodes.isEmpty() && freeNodes.isEmpty()) {
                        status.text = "Ищу бесплатный резерв…"
                        refreshFreeServersAndFindBest(autoConnectWhenReady = true)
                        return@setOnClickListener
                    }
                }
                val node = activeNodes.getOrNull(servers.selectedItemPosition)
                if (node == null) {
                    Toast.makeText(this@MainActivity, "В этом режиме пока нет серверов", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        val isMihomoProfile = activePortal && node.uri.startsWith("mihomo://")
                        val config = if (isMihomoProfile) {
                            prefs.getString("portal_mihomo_profile", null) ?: error("Сначала выбери WARP YAML")
                        } else if (activePortal) PortalConfigBuilder.build(node.uri) else GilrTanoVpnService.xrayConfigFromVless(node.uri)
                        pendingEngine = if (isMihomoProfile) "mihomo" else if (activePortal) "singbox" else "xray"
                        val fallbackConfig = if (autoMode && !activePortal) {
                            (if (activeFree) quattroNodes.firstOrNull() else freeNodes.firstOrNull())
                                ?.let { runCatching { GilrTanoVpnService.xrayConfigFromVless(it.uri) }.getOrNull() }
                        } else null
                        val preferredConfig = if (autoMode && !activePortal) {
                            quattroNodes.firstOrNull()?.let { runCatching { GilrTanoVpnService.xrayConfigFromVless(it.uri) }.getOrNull() }
                        } else null
                        pendingConfig = config
                        pendingFallbackConfig = fallbackConfig
                        pendingPreferredConfig = preferredConfig
                        pendingAutomatic = autoMode
                        connectButton.isEnabled = true
                        status.text = "Подключаю: ${node.name}…"
                        val permission = VpnService.prepare(this@MainActivity)
                        if (permission != null) startActivityForResult(permission, requestCode)
                        else startVpn(config, fallbackConfig, preferredConfig, autoMode)
                    } catch (e: Exception) {
                        pendingConfig = null
                        connectButton.isEnabled = true
                        status.text = "Профиль не поддерживается: ${e.message}"
                    }
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
            setBackgroundColor(Color.rgb(5, 5, 7))
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(FrameLayout(this@MainActivity).apply {
                clipChildren = false
                clipToPadding = false
                setPadding(dp(18), dp(12), dp(18), dp(18))
                homePage = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL }
                proxyPage = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
                profilesPage = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
                settingsPage = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
                navigationPages = listOf(homePage, proxyPage, profilesPage, settingsPage)
                addView(homePage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP))
                addView(proxyPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP))
                addView(profilesPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP))
                addView(settingsPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP))
                proxyPage.addView(TextView(this@MainActivity).apply { text = "ПРОКСИ"; textSize = 23f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(18)) })
                profilesPage.addView(TextView(this@MainActivity).apply { text = "ПРОФИЛИ"; textSize = 23f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(8)) })
                profilesPage.addView(TextView(this@MainActivity).apply { text = "Выбери источник подключения"; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, dp(16)) })
                settingsPage.addView(TextView(this@MainActivity).apply { text = "НАСТРОЙКИ"; textSize = 23f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(18)) })
                homePage.addView(LinearLayout(this@MainActivity).apply {
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
                        setOnClickListener { showSettingsDialog() }
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
                homePage.addView(tigerScene, LinearLayout.LayoutParams(
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
                homePage.addView(status.apply {
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
                homePage.addView(connectionTimer, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(22)
                ))
                // The central glowing orb is the primary connect/disconnect control.
                // Keep the accessible action button as the click target, but remove its
                // duplicate pill from the visual layout to match the approved reference.
                connectButton.visibility = View.GONE
                homePage.addView(connectButton, LinearLayout.LayoutParams(1, 1))
                serverCard = LinearLayout(this@MainActivity).apply {
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
                }
                proxyPage.addView(serverCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)).apply {
                    topMargin = dp(8); bottomMargin = dp(14)
                })
                proxyPage.addView(servers, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                servers.visibility = View.GONE
                proxyPage.addView(pingStatus.apply { gravity = android.view.Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
                pingStatus.visibility = View.GONE
                proxyPage.addView(bestFreeButton, LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
                bestFreeButton.visibility = View.GONE
                proxyPage.addView(checkPing, LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
                checkPing.visibility = View.GONE
                modeSelectorRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    addView(quattroTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
                    addView(freeTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
                    addView(autoTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
                    addView(portalTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
                    visibility = View.GONE
                }
                settingsHeader = TextView(this@MainActivity).apply {
                    text = "Настройки и импорт  ›"; textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); setPadding(dp(16), dp(14), dp(12), dp(12))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(14, 8, 9)); cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.rgb(150, 24, 24))
                    }
                    visibility = View.GONE
                    setOnClickListener {
                        settingsExpanded = !settingsExpanded
                        text = if (settingsExpanded) "Настройки и импорт  ⌄" else "Настройки и импорт  ›"
                        findImportButtonsVisibility()
                    }
                }
                settingsPage.addView(settingsHeader)
                profilesPage.addView(modeSelectorRow, LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
                settingsPage.addView(primarySource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                settingsPage.addView(importPrimary)
                settingsPage.addView(importDocx)
                settingsPage.addView(freeSource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                settingsPage.addView(importFree)
                settingsPage.addView(portalSource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                settingsPage.addView(importPortalButton)
                settingsPage.addView(portalYamlButton)
                settingsPage.addView(TextView(this@MainActivity).apply {
                    text = "Бесплатные серверы предоставляются сторонними операторами."
                    textSize = 10f; setTextColor(Color.GRAY); gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(8))
                })
            })
            contentScrollView = this
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
        root.addView(View(this).apply {
            setBackgroundColor(Color.rgb(28, 28, 30))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        navigationDock = FlClashNavigationDock(this) { destination ->
            if (destination == 3) settingsExpanded = true
            showNavigationPage(destination)
        }
        root.addView(navigationDock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(74)
        ).apply { leftMargin = dp(12); rightMargin = dp(12) })
        setContentView(root)
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
        autoMode = activeMode == "auto"
        activeFree = prefs.getBoolean("active_free_mode", false)
        refreshMode()
        updatePowerOrb(false)
        updateServerCard(false)
    }

    private fun showNavigationPage(destination: Int) {
        if (!::navigationPages.isInitialized || destination !in navigationPages.indices) return
        if (destination == currentPageIndex) {
            if (destination == 3) {
                settingsExpanded = true
                findImportButtonsVisibility()
            }
            return
        }
        val outgoing = navigationPages[currentPageIndex]
        val incoming = navigationPages[destination]
        navigationPages.forEach { page ->
            page.animate().cancel()
            page.translationX = 0f
            page.alpha = 1f
        }
        val direction = if (destination > currentPageIndex) 1f else -1f
        val distance = resources.displayMetrics.widthPixels * 0.25f
        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f
        incoming.translationX = direction * distance
        outgoing.animate().alpha(0f).translationX(-direction * distance)
            .setDuration(450L)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
            .withEndAction {
                if (currentPageIndex == destination) {
                    outgoing.visibility = View.GONE
                    outgoing.translationX = 0f
                    outgoing.alpha = 1f
                }
            }.start()
        incoming.animate().alpha(1f).translationX(0f)
            .setDuration(450L)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
        currentPageIndex = destination
        contentScrollView.scrollTo(0, 0)
        navigationDock.setSelectedIndex(destination, false)
        findImportButtonsVisibility()
    }

    private fun showServerPicker() {
        val nodes = activeNodes
        if (nodes.isEmpty()) {
            settingsExpanded = true
            showNavigationPage(3)
            findImportButtonsVisibility()
            Toast.makeText(this, "Добавь сервер в настройках", Toast.LENGTH_LONG).show()
            return
        }
        val regions = arrayOf("Все регионы", "🇪🇺 Европа", "🌏 Азия", "🇺🇸 США")
        AlertDialog.Builder(this)
            .setTitle("Выбери регион")
            .setItems(regions) { _, regionIndex ->
                val region = regions[regionIndex]
                val indexed = nodes.mapIndexed { index, node -> index to node }
                    .filter { regionIndex == 0 || freeRegion(it.second.name) == region }
                if (indexed.isEmpty()) {
                    Toast.makeText(this, "В этом регионе серверов пока нет", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                val names = indexed.map { (index, node) ->
                    node.name + if (regionIndex == 0) freeRegion(node.name)?.let { " · $it" }.orEmpty() else ""
                }.toTypedArray()
                val current = indexed.indexOfFirst { it.first == servers.selectedItemPosition }.coerceAtLeast(0)
                AlertDialog.Builder(this)
                    .setTitle("Сервер · $region")
                    .setSingleChoiceItems(names, current) { dialog, which ->
                        val actualIndex = indexed[which].first
                        servers.setSelection(actualIndex)
                        val key = when {
                            activePortal -> "portal_selected"
                            activeFree -> "free_selected"
                            else -> "quattro_selected"
                        }
                        prefs.edit().putInt(key, actualIndex).putString("selected_region", region).apply()
                        serverCardTitle.text = indexed[which].second.name
                        serverCardSubtitle.text = if (activePortal) "Portal" else if (activeFree) "Free" else "Quattro"
                        serverCardPing.text = ""
                        pingStatus.text = "Пинг: —"
                        dialog.dismiss()
                    }
                    .setNegativeButton("Назад", null)
                    .show()
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

    private fun switchToPortal() {
        activePortal = true
        activeFree = false
        autoMode = false
        activeMode = "portal"
        prefs.edit().putString("connection_mode", "portal").putBoolean("active_free_mode", false).apply()
        refreshMode()
        updateServerCard(status.text.toString().contains("Подключено"))
        Toast.makeText(this, "Режим Portal · Mihomo / sing-box", Toast.LENGTH_SHORT).show()
    }

    private fun importPortal(source: String) {
        if (source.isBlank()) { Toast.makeText(this, "Укажи ссылку Portal или подписку", Toast.LENGTH_LONG).show(); return }
        status.text = "Загружаю Portal…"
        Thread {
            try {
                val imported = if (source.startsWith("http://") || source.startsWith("https://")) {
                    val connection = (java.net.URL(source).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 15000; readTimeout = 25000
                    }
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    PortalConfigBuilder.fromLinks(text)
                } else PortalConfigBuilder.fromLinks(source)
                runOnUiThread {
                    if (imported.isEmpty()) { status.text = "В Portal не найдены VLESS / Trojan / Shadowsocks"; return@runOnUiThread }
                    portalNodes = imported
                    saveNodes("portal_nodes", portalNodes)
                    prefs.edit().putString("portal_subscription_url", source).apply()
                    switchToPortal()
                    servers.setSelection(0)
                    settingsExpanded = false
                    findImportButtonsVisibility()
                    status.text = "Portal: сохранено серверов — ${imported.size}"
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Ошибка Portal: ${e.message}" } }
        }.start()
    }

    private fun switchToAuto() {
        activePortal = false
        autoMode = true
        activeMode = "auto"
        prefs.edit().putString("connection_mode", "auto").apply()
        activeFree = quattroNodes.isEmpty() && freeNodes.isNotEmpty()
        refreshMode()
        Toast.makeText(this, "Автовыбор включён: Quattro в приоритете, бесплатный резерв", Toast.LENGTH_SHORT).show()
    }

    private fun switchMode(free: Boolean) {
        activePortal = false
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
        autoTab.isEnabled = !autoMode
        quattroTab.isEnabled = autoMode || activeFree
        freeTab.isEnabled = autoMode || !activeFree
        autoTab.alpha = if (autoMode) 1f else 0.72f
        quattroTab.alpha = if (!autoMode && !activeFree) 1f else 0.72f
        freeTab.alpha = if (!autoMode && activeFree) 1f else 0.72f
        fun modeBackground(selected: Boolean) = GradientDrawable().apply {
            setColor(if (selected) Color.rgb(54, 24, 12) else Color.rgb(13, 13, 15))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(if (selected) 2 else 1),
                if (selected) Color.rgb(220, 32, 32) else Color.rgb(58, 58, 62))
        }
        quattroTab.background = modeBackground(!autoMode && !activeFree)
        freeTab.background = modeBackground(!autoMode && activeFree)
        autoTab.background = modeBackground(autoMode)
        quattroTab.setTextColor(if (!autoMode && !activeFree) Color.WHITE else Color.LTGRAY)
        freeTab.setTextColor(if (!autoMode && activeFree) Color.WHITE else Color.LTGRAY)
        autoTab.setTextColor(if (autoMode && !activePortal) Color.WHITE else Color.LTGRAY)
        portalTab.alpha = if (activePortal) 1f else 0.72f
        portalTab.background = modeBackground(activePortal)
        portalTab.setTextColor(if (activePortal) Color.WHITE else Color.LTGRAY)
        primarySource.visibility = if (activeFree || activePortal) ViewGroup.GONE else ViewGroup.VISIBLE
        freeSource.visibility = if (activeFree && !activePortal) ViewGroup.VISIBLE else ViewGroup.GONE
        portalSource.visibility = if (activePortal) ViewGroup.VISIBLE else ViewGroup.GONE
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

    private fun showSettingsDialog() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(Color.rgb(7, 7, 9))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Настройки"
                textSize = 23f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "✕"; textSize = 24f; setTextColor(Color.LTGRAY)
                setPadding(dp(12), dp(4), dp(4), dp(4))
                setOnClickListener { (tag as? AlertDialog)?.dismiss() }
            })
        }
        panel.addView(heading)
        fun section(title: String) {
            panel.addView(TextView(this).apply {
                text = title; textSize = 17f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(230, 230, 235))
                setPadding(0, dp(18), 0, dp(6))
            })
        }
        fun sourceField(hintText: String, saved: String): EditText =
            EditText(this).apply {
                hint = hintText
                setText(saved)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setSingleLine(false)
                minLines = 1
                maxLines = 3
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(20, 20, 23))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.rgb(55, 55, 60))
                }
            }
        fun actionButton(label: String, action: () -> Unit) {
            panel.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })
        }

        section("QUATTRO · Платный сервис")
        val quattroUrl = sourceField("Ссылка на подписку Quattro", prefs.getString("subscription_url", "") ?: "")
        panel.addView(quattroUrl, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        actionButton("Добавить Quattro по ссылке") { importFrom(quattroUrl.text.toString().trim(), false) }
        actionButton("Добавить Quattro через DOCX") {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            }
            startActivityForResult(pick, docxRequestCode)
        }

        section("PORTAL")
        val portalUrl = sourceField("Ссылка Portal", prefs.getString("portal_subscription_url", "") ?: "")
        panel.addView(portalUrl, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        actionButton("Добавить Portal по ссылке") { importPortal(portalUrl.text.toString().trim()) }
        actionButton("Выбрать WARP YAML") {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(pick, portalYamlRequestCode)
        }

        section("FREE")
        val freeUrl = sourceField("Ссылка на бесплатные серверы", prefs.getString("free_subscription_url", "") ?: "")
        panel.addView(freeUrl, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        actionButton("Добавить бесплатные серверы") { importFrom(freeUrl.text.toString().trim(), true) }

        val scrollPanel = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(panel)
        }
        val dialog = AlertDialog.Builder(this).setView(scrollPanel).create()
        (heading.getChildAt(1) as? TextView)?.tag = dialog
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun findImportButtonsVisibility() {
        if (!::settingsHeader.isInitialized || !::modeSelectorRow.isInitialized) return
        settingsHeader.visibility = if (currentPageIndex == 3) View.VISIBLE else View.GONE
        modeSelectorRow.visibility = if (currentPageIndex == 2) View.VISIBLE else View.GONE
        primarySource.visibility = if (currentPageIndex == 3 && settingsExpanded && !activeFree && !activePortal) View.VISIBLE else View.GONE
        freeSource.visibility = if (currentPageIndex == 3 && settingsExpanded && activeFree && !activePortal) View.VISIBLE else View.GONE
        portalSource.visibility = if (currentPageIndex == 3 && settingsExpanded && activePortal) View.VISIBLE else View.GONE
        importPortalButton.visibility = if (currentPageIndex == 3 && settingsExpanded && activePortal) View.VISIBLE else View.GONE
        portalYamlButton.visibility = if (currentPageIndex == 3 && settingsExpanded && activePortal) View.VISIBLE else View.GONE
        bestFreeButton.visibility = if (currentPageIndex == 1 && activeFree) View.VISIBLE else View.GONE
        checkPing.visibility = if (currentPageIndex == 1) View.VISIBLE else View.GONE
        pingStatus.visibility = if (currentPageIndex == 1) View.VISIBLE else View.GONE
        for (i in 0 until settingsPage.childCount) {
            val child = settingsPage.getChildAt(i)
            if (child is Button) {
                val label = child.text.toString()
                val show = currentPageIndex == 3 && settingsExpanded && (
                    (!activePortal && !activeFree && (label == "Импортировать Quattro" || label == "Выбрать DOCX Quattro")) ||
                    (!activePortal && activeFree && label == "Импортировать бесплатные серверы") ||
                    (activePortal && (label == "Импортировать Portal" || label == "Выбрать WARP YAML"))
                )
                if (label.startsWith("Импортировать") || label == "Выбрать DOCX Quattro") {
                    child.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
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

    private fun freeRegion(name: String): String? {
        val value = name.lowercase()
        val europe = listOf("🇳🇱", "🇩🇪", "🇬🇧", "🇫🇷", "🇮🇹", "🇪🇸", "🇵🇱", "🇫🇮", "🇸🇪", "🇳🇴", "🇨🇭", "🇦🇹", "🇧🇪", "🇨🇿", "🇷🇴", "🇧🇬", "🇬🇷", "🇵🇹", "🇮🇪", "🇩🇰", "🇭🇺", "🇺🇦", "🇷🇺",
            "netherlands", "germany", "united kingdom", "uk ", "france", "italy", "spain", "poland", "finland", "sweden", "norway", "switzerland", "austria", "belgium", "czech", "romania", "bulgaria", "greece", "portugal", "ireland", "denmark", "hungary", "ukraine", "russia", "de-", "nl-", "gb-", "fr-", "it-", "es-", "pl-")
        val asia = listOf("🇯🇵", "🇸🇬", "🇭🇰", "🇰🇷", "🇹🇼", "🇮🇳", "🇮🇩", "🇹🇭", "🇲🇾", "🇻🇳", "🇵🇭", "🇨🇳",
            "japan", "singapore", "hong kong", "south korea", "korea", "taiwan", "india", "indonesia", "thailand", "malaysia", "vietnam", "philippines", "china", "jp-", "sg-", "hk-", "kr-", "tw-", "in-", "id-", "th-")
        val usa = listOf("🇺🇸", "united states", "usa", "us-", "us ")
        return when {
            usa.any { value.contains(it) } -> "🇺🇸 США"
            asia.any { value.contains(it) } -> "🌏 Азия"
            europe.any { value.contains(it) } -> "🇪🇺 Европа"
            else -> null
        }
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
        val candidates = freeNodes.take(180)
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
                val bestByRegion = linkedMapOf<String, Pair<ImportedNode, Long>>()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
                var completed = 0
                while (completed < candidates.size && System.nanoTime() < deadline && bestByRegion.size < 3) {
                    val remaining = deadline - System.nanoTime()
                    val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                    completed++
                    runCatching { future.get() }.getOrNull()?.let { result ->
                        checked.add(result)
                        val region = freeRegion(result.first.name)
                        if (region != null) {
                            val previous = bestByRegion[region]
                            if (previous == null || result.second < previous.second) bestByRegion[region] = result
                        }
                    }
                    if (completed % 20 == 0) {
                        val done = completed
                        runOnUiThread {
                            if (scanningFree) status.text = "Автопоиск: проверено $done из ${candidates.size}…"
                        }
                    }
                    if (bestByRegion.size >= 3) break
                }
                val fastest = listOf("🇪🇺 Европа", "🇺🇸 США", "🌏 Азия")
                    .mapNotNull { bestByRegion[it] }
                    .ifEmpty { checked.sortedBy { it.second }.take(1) }
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
                        val labels = fastest.map { pair -> "${freeRegion(pair.first.name) ?: "Регион не определён"} · ${pair.first.name} · ${pair.second} мс" }
                        servers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                        servers.setSelection(0)
                        pingStatus.text = "Проверка через Xray: ${fastest.first().second} мс"
                        status.text = "Проверено: ${fastest.size} региональных серверов. Лучший выбран; остальные — резерв."
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
            val config = GilrTanoVpnService.xrayConfigFromVless(node.uri)
            val fallbackNode = if (activeFree) freeNodes.getOrNull(1) else freeNodes.firstOrNull()
            val fallback = fallbackNode
                ?.let { runCatching { GilrTanoVpnService.xrayConfigFromVless(it.uri) }.getOrNull() }
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
            .putExtra(GilrTanoVpnService.EXTRA_ENGINE, pendingEngine)
            .putExtra(GilrTanoVpnService.EXTRA_AUTO, automatic)
        fallbackConfig?.let { intent.putExtra(GilrTanoVpnService.EXTRA_FALLBACK_CONFIG, it) }
        if (automatic && activeFree) {
            val rotation = freeNodes.mapNotNull { node ->
                runCatching { GilrTanoVpnService.xrayConfigFromVless(node.uri) }.getOrNull()
            }.filter { it != config }
            intent.putExtra(GilrTanoVpnService.EXTRA_ROTATION_CONFIGS, JSONArray(rotation).toString())
        }
        preferredConfig?.let { intent.putExtra(GilrTanoVpnService.EXTRA_PREFERRED_CONFIG, it) }
        startForegroundService(intent)
        status.text = "Запускаю VPN…"
    }

    @Deprecated("Deprecated by Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK) {
            pendingConfig?.let {
                startVpn(it, pendingFallbackConfig, pendingPreferredConfig, pendingAutomatic)
                pendingConfig = null
                pendingFallbackConfig = null
                pendingPreferredConfig = null
                pendingAutomatic = false
            } ?: run { status.text = "Подключение отменено" }
        } else if (requestCode == this.requestCode) {
            pendingConfig = null
            connectButton.isEnabled = true
            status.text = "Разрешение VPN не предоставлено"
        } else if (requestCode == portalYamlRequestCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            status.text = "Импортирую WARP YAML…"
            Thread {
                try {
                    val yaml = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("Не удалось прочитать файл")
                    require(yaml.contains("proxies:") && yaml.contains("type: masque")) {
                        "Это не Clash/Mihomo MASQUE-конфигурация"
                    }
                    val profile = java.io.File(filesDir, "mihomo/PORTALWARP25.yaml")
                    profile.parentFile?.mkdirs()
                    val mobileYaml = yaml
                        .replace("allow-lan: true", "allow-lan: false")
                        .replace("ip: 172.16.0.2\\n", "ip: 172.16.0.2/32\\n")
                        .replace("ipv6: 2606:4700:110:8fa3:508c:50aa:6256:acea\\n", "ipv6: 2606:4700:110:8fa3:508c:50aa:6256:acea/128\\n")
                    profile.writeText(mobileYaml, Charsets.UTF_8)
                    runOnUiThread {
                        portalNodes = listOf(ImportedNode("Cloudflare WARP · MASQUE", "mihomo://profile"))
                        saveNodes("portal_nodes", portalNodes)
                        prefs.edit().putString("portal_mihomo_profile", profile.absolutePath).apply()
                        switchToPortal()
                        servers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Cloudflare WARP · MASQUE"))
                        servers.setSelection(0)
                        status.text = "WARP MASQUE-конфигурация импортирована"
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Ошибка WARP YAML: ${e.message}" }
                }
            }.start()
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