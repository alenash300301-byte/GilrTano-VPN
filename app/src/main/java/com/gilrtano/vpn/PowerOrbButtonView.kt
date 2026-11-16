package com.gilrtano.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
    private var phase = 0f
    private var morph = 1f
    private var pulse: ValueAnimator? = null
    private var morphAnimator: ValueAnimator? = null
    private var pressAnimator: AnimatorSet? = null
    private var animationsAllowed = true

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
        val cy = height / 2f
        val radius = 19f * d
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