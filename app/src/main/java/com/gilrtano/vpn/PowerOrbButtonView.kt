package com.gilrtano.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator

/** FlClashX-inspired three-state power control with an animated heartbeat trace. */
class PowerOrbButtonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextView(context, attrs) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private val d = resources.displayMetrics.density
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var state = State.DISCONNECTED
    private var caption = "ПОДКЛЮЧИТЬ"
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val captionPlatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var phase = 0f
    private var morph = 1f
    private var pulse: ValueAnimator? = null
    private var morphAnimator: ValueAnimator? = null
    private var pressAnimator: AnimatorSet? = null
    private var animationsAllowed = true

    fun setCaption(value: String) {
        caption = value
        text = ""
        invalidate()
    }

    fun setConnected(connected: Boolean) {
        setState(if (connected) State.CONNECTED else State.DISCONNECTED)
    }

    fun setConnecting(connecting: Boolean) {
        if (connecting) setState(State.CONNECTING)
        else if (state == State.CONNECTING) setState(State.DISCONNECTED)
    }

    fun setState(next: State) {
        if (state == next) return
        state = next
        pulse?.cancel()
        morphAnimator?.cancel()
        if (animationsAllowed) {
            morph = 0f
            morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 360L
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                addUpdateListener { morph = it.animatedValue as Float; invalidate() }
                start()
            }
            when (next) {
                State.CONNECTING -> startMotion(1000L, true)
                State.CONNECTED -> startMotion(1100L, false)
                State.DISCONNECTED -> {
                    phase = 0f
                    animate().cancel()
                    animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
                }
            }
        } else {
            morph = 1f
            phase = 0f
        }
        glyphPaint.color = when (next) {
            State.DISCONNECTED -> Color.WHITE
            State.CONNECTING -> Color.rgb(255, 184, 74)
            State.CONNECTED -> Color.rgb(85, 255, 125)
        }
        contentDescription = when (next) {
            State.DISCONNECTED -> "VPN отключён. Нажмите, чтобы подключиться"
            State.CONNECTING -> "VPN подключается"
            State.CONNECTED -> "VPN подключён. Нажмите, чтобы отключиться"
        }
        invalidate()
    }

    private fun startMotion(durationMs: Long, continuous: Boolean) {
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animationsAllowed = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) > 0f
        }.getOrDefault(true)
        if (animationsAllowed) {
            when (state) {
                State.CONNECTING -> startMotion(1000L, true)
                State.CONNECTED -> startMotion(1100L, false)
                State.DISCONNECTED -> Unit
            }
        }
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        morphAnimator?.cancel()
        pressAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                pressAnimator?.cancel()
                pressAnimator = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_X, scaleX, 0.94f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_Y, scaleY, 0.94f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.TRANSLATION_Z, translationZ, 5f * d)
                    )
                    duration = 90L
                    interpolator = DecelerateInterpolator()
                    start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressAnimator?.cancel()
                pressAnimator = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_X, scaleX, 1f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_Y, scaleY, 1f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.TRANSLATION_Z, translationZ, 0f)
                    )
                    duration = 180L
                    interpolator = DecelerateInterpolator()
                    start()
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        // Draw the complete control as one coherent neon orb. The TextView's
        // own text is intentionally empty: icon and caption are positioned here.
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.465f
        val connected = state == State.CONNECTED
        val connecting = state == State.CONNECTING
        val neon = when {
            connected -> Color.rgb(80, 255, 72)
            connecting -> Color.rgb(255, 176, 55)
            else -> Color.rgb(255, 54, 65)
        }
        val inner = when {
            connected -> Color.rgb(2, 35, 12)
            connecting -> Color.rgb(35, 23, 5)
            else -> Color.rgb(30, 5, 9)
        }

        // Dark glass center and layered outer halo.
        fillPaint.shader = android.graphics.RadialGradient(
            cx, cy, r, intArrayOf(Color.rgb(2, 10, 7), inner, Color.BLACK),
            floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 0.94f, fillPaint)
        fillPaint.shader = null

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f * d
            color = neon
            setShadowLayer(13f * d, 0f, 0f, neon)
        }
        canvas.drawCircle(cx, cy, r * 0.91f, glowPaint)
        glowPaint.clearShadowLayer()

        ringPaint.color = if (connected) Color.rgb(255, 45, 47) else Color.rgb(190, 34, 43)
        ringPaint.strokeWidth = 2f * d
        ringPaint.alpha = 220
        canvas.drawCircle(cx, cy, r * 1.02f, ringPaint)
        ringPaint.color = neon
        ringPaint.strokeWidth = 2.6f * d
        ringPaint.alpha = 245
        canvas.drawCircle(cx, cy, r * 0.84f, ringPaint)
        ringPaint.color = Color.argb(130, 255, 255, 255)
        ringPaint.strokeWidth = 1f * d
        canvas.drawCircle(cx, cy, r * 0.77f, ringPaint)

        // Bright power glyph in the upper-middle of the orb.
        val iconCx = cx
        val iconCy = cy - r * 0.22f
        val iconR = r * 0.30f
        glyphPaint.color = if (connected) Color.rgb(130, 255, 82) else if (connecting) Color.rgb(255, 190, 80) else Color.WHITE
        glyphPaint.strokeWidth = 5f * d
        glyphPaint.setShadowLayer(9f * d, 0f, 0f, glyphPaint.color)
        canvas.drawArc(RectF(iconCx-iconR, iconCy-iconR, iconCx+iconR, iconCy+iconR),
            135f, 270f, false, glyphPaint)
        canvas.drawLine(iconCx, iconCy-iconR*1.32f, iconCx, iconCy+iconR*0.12f, glyphPaint)
        glyphPaint.clearShadowLayer()

        // Status capsule, sized to the text and kept inside the circular rim.
        val labelSize = 14f * d
        captionPaint.textSize = labelSize
        captionPaint.color = if (connected) Color.rgb(125, 255, 100) else if (connecting) Color.rgb(255, 195, 90) else Color.WHITE
        captionPaint.setShadowLayer(5f * d, 0f, 0f, captionPaint.color)
        val labelWidth = captionPaint.measureText(caption).coerceAtMost(r * 1.36f)
        val plate = RectF(cx-labelWidth/2f-12f*d, cy+r*0.34f, cx+labelWidth/2f+12f*d, cy+r*0.66f)
        fillPaint.color = Color.argb(205, 2, 8, 5)
        canvas.drawRoundRect(plate, 18f*d, 18f*d, fillPaint)
        captionPlatePaint.color = neon
        captionPlatePaint.strokeWidth = 1.2f*d
        captionPlatePaint.setShadowLayer(5f*d, 0f, 0f, neon)
        canvas.drawRoundRect(plate, 18f*d, 18f*d, captionPlatePaint)
        captionPlatePaint.clearShadowLayer()
        val baseline = plate.centerY() - (captionPaint.ascent()+captionPaint.descent())/2f
        canvas.drawText(caption, cx, baseline, captionPaint)
        captionPaint.clearShadowLayer()

        // Subtle moving highlight while connecting/connected.
        if (state != State.DISCONNECTED && animationsAllowed) {
            val angle = -90f + phase * 360f
            val rad = Math.toRadians(angle.toDouble())
            val hx = cx + cos(rad).toFloat() * r * 0.91f
            val hy = cy + sin(rad).toFloat() * r * 0.91f
            fillPaint.color = Color.WHITE
            fillPaint.setShadowLayer(8f*d, 0f, 0f, neon)
            canvas.drawCircle(hx, hy, 2.5f*d, fillPaint)
            fillPaint.clearShadowLayer()
        }
    }ge com.gilrtano.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator

/** FlClashX-inspired three-state power control with an animated heartbeat trace. */
class PowerOrbButtonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextView(context, attrs) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private val d = resources.displayMetrics.density
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var state = State.DISCONNECTED
    private var caption = "ПОДКЛЮЧИТЬ"
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val captionPlatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var phase = 0f
    private var morph = 1f
    private var pulse: ValueAnimator? = null
    private var morphAnimator: ValueAnimator? = null
    private var pressAnimator: AnimatorSet? = null
    private var animationsAllowed = true

    fun setCaption(value: String) {
        caption = value
        text = ""
        invalidate()
    }

    fun setConnected(connected: Boolean) {
        setState(if (connected) State.CONNECTED else State.DISCONNECTED)
    }

    fun setConnecting(connecting: Boolean) {
        if (connecting) setState(State.CONNECTING)
        else if (state == State.CONNECTING) setState(State.DISCONNECTED)
    }

    fun setState(next: State) {
        if (state == next) return
        state = next
        pulse?.cancel()
        morphAnimator?.cancel()
        if (animationsAllowed) {
            morph = 0f
            morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 360L
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                addUpdateListener { morph = it.animatedValue as Float; invalidate() }
                start()
            }
            when (next) {
                State.CONNECTING -> startMotion(1000L, true)
                State.CONNECTED -> startMotion(1100L, false)
                State.DISCONNECTED -> {
                    phase = 0f
                    animate().cancel()
                    animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
                }
            }
        } else {
            morph = 1f
            phase = 0f
        }
        glyphPaint.color = when (next) {
            State.DISCONNECTED -> Color.WHITE
            State.CONNECTING -> Color.rgb(255, 184, 74)
            State.CONNECTED -> Color.rgb(85, 255, 125)
        }
        contentDescription = when (next) {
            State.DISCONNECTED -> "VPN отключён. Нажмите, чтобы подключиться"
            State.CONNECTING -> "VPN подключается"
            State.CONNECTED -> "VPN подключён. Нажмите, чтобы отключиться"
        }
        invalidate()
    }

    private fun startMotion(durationMs: Long, continuous: Boolean) {
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animationsAllowed = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) > 0f
        }.getOrDefault(true)
        if (animationsAllowed) {
            when (state) {
                State.CONNECTING -> startMotion(1000L, true)
                State.CONNECTED -> startMotion(1100L, false)
                State.DISCONNECTED -> Unit
            }
        }
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        morphAnimator?.cancel()
        pressAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                pressAnimator?.cancel()
                pressAnimator = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_X, scaleX, 0.94f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_Y, scaleY, 0.94f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.TRANSLATION_Z, translationZ, 5f * d)
                    )
                    duration = 90L
                    interpolator = DecelerateInterpolator()
                    start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressAnimator?.cancel()
                pressAnimator = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_X, scaleX, 1f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_Y, scaleY, 1f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.TRANSLATION_Z, translationZ, 0f)
                    )
                    duration = 180L
                    interpolator = DecelerateInterpolator()
                    start()
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        // Keep the power symbol in the upper half; the TextView label sits
        // independently near the bottom of the orb.
        val cy = height * 0.37f
        val radius = 18f * d
        canvas.drawArc(RectF(cx-radius, cy-radius, cx+radius, cy+radius), 45f, 270f, false, glyphPaint)
        canvas.drawLine(cx, cy-radius-1f*d, cx, cy+1f*d, glyphPaint)

        // A small ECG trace echoes FlClashX's morphing heartbeat indicator.
        val x = floatArrayOf(-1f, -.55f, -.25f, 0f, .28f, .55f, 1f)
        val waveConnected = floatArrayOf(0f, 0f, -.26f, .30f, -.12f, 0f, 0f)
        val waveConnecting = floatArrayOf(0f, 0f, -.14f, .16f, -.06f, 0f, 0f)
        val waveOff = floatArrayOf(0f, 0f, -.07f, .08f, 0f, 0f, 0f)
        val target = when (state) {
            State.CONNECTED -> waveConnected
            State.CONNECTING -> waveConnecting
            State.DISCONNECTED -> waveOff
        }
        val from = waveOff
        val path = Path()
        for (i in x.indices) {
            val blend = if (state == State.DISCONNECTED) morph else 1f
            val y = 0.0f + (from[i] * (1f-blend) + target[i] * blend) * 12f * d
            val px = cx + x[i] * 10f * d
            if (i == 0) path.moveTo(px, cy + 12f*d + y) else path.lineTo(px, cy + 12f*d + y)
        }
        tracePaint.color = when (state) {
            State.CONNECTED -> Color.rgb(85,255,125)
            State.CONNECTING -> Color.rgb(255,184,74)
            State.DISCONNECTED -> Color.rgb(230,75,80)
        }
        tracePaint.alpha = when (state) {
            State.CONNECTED -> if (phase < .28f) 230 else 70
            State.CONNECTING -> 210
            State.DISCONNECTED -> 65
        }
        canvas.drawPath(path, tracePaint)
        if (state != State.DISCONNECTED && animationsAllowed) {
            val headX = cx - 10f*d + phase * 20f*d
            val headY = cy + 12f*d + if (state == State.CONNECTED) {
                when {
                    phase < .22f -> -4f*d
                    phase < .34f -> 4f*d
                    else -> 0f
                }
            } else 0f
            canvas.drawCircle(headX, headY, 1.8f*d, tracePaint)
        }
    }
}