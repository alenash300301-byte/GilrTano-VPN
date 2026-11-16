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
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import kotlin.math.cos
import kotlin.math.sin

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
    private var pressFeedback: ValueAnimator? = null
    private var idleBreath: ValueAnimator? = null
    private var pressAmount = 0f
    private var breath = 0f
    private var animationsAllowed = true
    var useGlArtwork: Boolean = false


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

    private fun startIdleBreath() {
        idleBreath?.cancel()
        idleBreath = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { breath = it.animatedValue as Float; invalidate() }
            start()
        }
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
            startIdleBreath()
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
        pressFeedback?.cancel()
        idleBreath?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                pressAnimator?.cancel()
                pressFeedback?.cancel()
                pressFeedback = ValueAnimator.ofFloat(pressAmount, 1f).apply {
                    duration = 110L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { pressAmount = it.animatedValue as Float; invalidate() }
                    start()
                }
                pressAnimator = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_X, scaleX, 0.925f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_Y, scaleY, 0.925f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.TRANSLATION_Z, translationZ, -3f * d),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.ROTATION_X, rotationX, 2.2f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.ROTATION_Y, rotationY, -1.5f)
                    )
                    duration = 105L
                    interpolator = DecelerateInterpolator(2f)
                    start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressAnimator?.cancel()
                pressFeedback?.cancel()
                pressFeedback = ValueAnimator.ofFloat(pressAmount, 0f).apply {
                    duration = 360L
                    interpolator = OvershootInterpolator(1.8f)
                    addUpdateListener { pressAmount = it.animatedValue as Float; invalidate() }
                    start()
                }
                pressAnimator = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_X, scaleX, 1f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_Y, scaleY, 1f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.TRANSLATION_Z, translationZ, 0f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.ROTATION_X, rotationX, 0f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.ROTATION_Y, rotationY, 0f)
                    )
                    duration = 360L
                    interpolator = OvershootInterpolator(1.35f)
                    start()
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private val buttonArtwork by lazy {
        requireNotNull(context.getDrawable(R.drawable.file_000000004cb08210bfb53b4ce7ceb0dd))
    }

    override fun onDraw(canvas: Canvas) {
        // Use the exact artwork uploaded to the repository. Clip its black
        // square corners so the tiger background remains visible around the orb.
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        val left = (width - side) / 2f
        val top = (height - side) / 2f

        // Draw animated green plasma tongues directly on this View. This avoids
        // a separate SurfaceView, whose opaque buffer caused a visible square.
        val connected = state == State.CONNECTED
        val connecting = state == State.CONNECTING
        val energy = if (connected) 1f else if (connecting) 0.88f else 0.48f + breath * 0.22f
        val t = if (state == State.DISCONNECTED) breath * 0.45f else phase
        val flameGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = side * 0.025f
            color = Color.rgb(35, 255, 25)
            alpha = (105 * energy).toInt()
            setShadowLayer(side * 0.075f, 0f, 0f, Color.rgb(25, 255, 15))
        }
        val flameCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = side * 0.008f
            color = Color.rgb(150, 255, 35)
            alpha = (220 * energy).toInt()
        }
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = side * 0.455f
        // Soft luminous ring under the artwork's outer rim.
        canvas.drawCircle(cx, cy, baseRadius, flameGlow)
        canvas.drawCircle(cx, cy, baseRadius, flameCore)
        // Individually animated curved tongues of fire.
        for (i in 0 until 28) {
            val a = (Math.PI * 2.0 * i / 28.0).toFloat()
            val wave = kotlin.math.sin(t * 6.28318f + i * 1.73f)
            val wave2 = kotlin.math.cos(t * 4.1f + i * 0.91f)
            val length = side * (0.025f + (0.025f + 0.018f * wave) * energy)
            val r0 = baseRadius - side * 0.008f
            val r1 = baseRadius + length
            val tangent = side * (0.012f + 0.006f * wave2)
            val path = Path().apply {
                moveTo(cx + kotlin.math.cos(a) * r0, cy + kotlin.math.sin(a) * r0)
                cubicTo(
                    cx + kotlin.math.cos(a - 0.12f) * (r0 + length * 0.55f) - kotlin.math.sin(a) * tangent,
                    cy + kotlin.math.sin(a - 0.12f) * (r0 + length * 0.55f) + kotlin.math.cos(a) * tangent,
                    cx + kotlin.math.cos(a + 0.08f) * r1,
                    cy + kotlin.math.sin(a + 0.08f) * r1,
                    cx + kotlin.math.cos(a) * r0,
                    cy + kotlin.math.sin(a) * r0
                )
            }
            flameGlow.strokeWidth = side * (0.014f + 0.006f * energy)
            canvas.drawPath(path, flameGlow)
            flameCore.strokeWidth = side * 0.006f
            canvas.drawPath(path, flameCore)
        }

        val save = canvas.save()
        val clip = Path().apply {
            addCircle(width / 2f, height / 2f, side * 0.5f, Path.Direction.CW)
        }
        canvas.clipPath(clip)
        if (!useGlArtwork) {
            buttonArtwork.bounds = android.graphics.Rect(
                left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt()
            )
            buttonArtwork.draw(canvas)
        }
        canvas.restoreToCount(save)

        // The artwork contains an intentionally empty neon capsule. Draw the
        // live VPN status there so it can change without editing the PNG.
        val connecting = state == State.CONNECTING
        captionPaint.textAlign = Paint.Align.CENTER
        captionPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        captionPaint.textSize = side * 0.060f
        captionPaint.color = if (connecting) Color.rgb(255, 205, 90) else Color.rgb(125, 255, 95)
        captionPaint.setShadowLayer(5f * d, 0f, 0f, captionPaint.color)
        val label = caption
        val maxWidth = side * 0.57f
        var size = captionPaint.textSize
        while (captionPaint.measureText(label) > maxWidth && size > side * 0.035f) {
            size -= 0.5f * d
            captionPaint.textSize = size
        }
        val baseline = top + side * 0.594f - (captionPaint.ascent() + captionPaint.descent()) / 2f
        canvas.drawText(label, width / 2f, baseline, captionPaint)
        captionPaint.clearShadowLayer()

        // Subtle breathing halo and animated contact ripple stay independent
        // of the static artwork.
        if (pressAmount > 0.01f) {
            val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = if (connecting) Color.rgb(255, 190, 70) else Color.rgb(90, 255, 78)
                alpha = (150f * pressAmount).toInt()
                strokeWidth = (2f + 2f * pressAmount) * d
            }
            canvas.drawCircle(width / 2f, height / 2f, side * (0.43f + pressAmount * 0.035f), ripplePaint)
        }
        if (breath > 0f && state != State.CONNECTED) {
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.argb((25 + breath * 35).toInt(), 95, 255, 85)
                strokeWidth = 1.2f * d
            }
            canvas.drawCircle(width / 2f, height / 2f, side * (0.49f + breath * 0.006f), halo)
        }
    }
}