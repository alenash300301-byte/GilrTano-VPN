package com.gilrtano.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Intensifies the existing emerald eyes on the unchanged full-screen tiger image.
 * Coordinates are normalized to the portrait screenshot composition (CENTER_CROP).
 * This overlay paints only inside the two eye regions; it never replaces the artwork.
 */
class TigerEyeGlowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val additive = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private var connected = false
    private var level = 0f
    private var pulse = 0.5f
    private var transition: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var flash = 0f

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        transition?.cancel()
        pulseAnimator?.cancel()
        pulseAnimator = null

        if (value) {
            // Distinct ignition flash, then a visible but smooth breathing pulse.
            transition = ValueAnimator.ofFloat(level, 1f).apply {
                duration = 720L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    level = it.animatedValue as Float
                    flash = 1f - (it.animatedFraction)
                    invalidate()
                }
                start()
            }
            pulseAnimator = ValueAnimator.ofFloat(0.30f, 1f).apply {
                duration = 1350L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            transition = ValueAnimator.ofFloat(level, 0f).apply {
                duration = 650L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    level = it.animatedValue as Float
                    flash = 0f
                    invalidate()
                }
                start()
            }
            pulse = 0.5f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (level <= 0.005f || width <= 0 || height <= 0) return

        // Actual eye centers from the supplied on-device screen composition.
        // The image is full-window CENTER_CROP, so normalized coordinates stay aligned
        // across display sizes and densities.
        val w = width.toFloat()
        val h = height.toFloat()
        val eyeY = h * 0.235f
        val eyeX1 = w * 0.285f
        val eyeX2 = w * 0.715f
        val strength = level * (0.78f + 0.22f * pulse)
        drawEye(canvas, eyeX1, eyeY, w * 0.112f, h * 0.046f, strength)
        drawEye(canvas, eyeX2, eyeY, w * 0.112f, h * 0.046f, strength)
    }

    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, strength: Float) {
        val save = canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(rx, ry)

        // Additive green light brightens the actual iris/eye region rather than
        // creating a detached halo that can be missed against the artwork.
        paint.xfermode = additive
        paint.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                Color.argb((205 * strength).toInt(), 105, 255, 125),
                Color.argb((150 * strength).toInt(), 30, 255, 85),
                Color.argb((0 * strength).toInt(), 0, 255, 50)
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(-1f, -1f, 1f, 1f, paint)
        paint.shader = null
        paint.xfermode = null

        // Narrow luminous iris line and catchlight, aligned over the original green iris.
        paint.color = Color.argb((190 * strength).toInt(), 150, 255, 165)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.075f
        canvas.drawOval(-0.58f, -0.22f, 0.58f, 0.22f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((210 * strength).toInt(), 225, 255, 220)
        canvas.drawCircle(0f, -0.04f, 0.055f, paint)
        paint.color = Color.WHITE
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        transition?.cancel()
        pulseAnimator?.cancel()
        transition = null
        pulseAnimator = null
        super.onDetachedFromWindow()
    }
}
