package com.gilrtano.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Transparent eye-light overlay for the existing tiger_hero artwork.
 * The mascot bitmap/vector is never modified: only two soft emerald halos
 * are rendered above the original eye positions in the centered 360x360 art.
 */
class TigerEyeGlowView(context: Context) : View(context) {
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var connected = false
    private var pulse = 0.35f
    private var animator: ValueAnimator? = null
    private var transition: ValueAnimator? = null
    private var glow = 0f

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 1f
    }

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        transition?.cancel()
        transition = ValueAnimator.ofFloat(glow, if (value) 1f else 0f).apply {
            duration = if (value) 650L else 500L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                glow = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        animator?.cancel()
        animator = null
        if (value) {
            animator = ValueAnimator.ofFloat(0.22f, 1f).apply {
                duration = 1850L
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
            pulse = 0.35f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (glow <= 0.005f || width == 0 || height == 0) return

        // tiger_hero_art.xml uses a centered 360x360 viewBox. Keep the glow
        // aligned with that square even when the device is in a tall layout.
        val side = minOf(width, height).toFloat()
        val save = canvas.save()
        canvas.translate((width - side) / 2f, (height - side) / 2f)
        canvas.scale(side / 360f, side / 360f)

        val strength = glow * (0.72f + 0.28f * pulse)
        drawEye(canvas, 122f, 188f, strength)
        drawEye(canvas, 238f, 188f, strength)
        canvas.restoreToCount(save)
    }

    private fun drawEye(canvas: Canvas, x: Float, y: Float, strength: Float) {
        val radius = 31f
        glowPaint.shader = RadialGradient(
            x, y, radius,
            intArrayOf(
                Color.argb((105 * strength).toInt(), 40, 255, 105),
                Color.argb((55 * strength).toInt(), 30, 255, 90),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, radius, glowPaint)
        glowPaint.shader = null

        // A restrained bright core enhances the existing emerald iris without
        // repainting, reshaping, or covering the original eye artwork.
        glowPaint.color = Color.argb((58 * strength).toInt(), 105, 255, 145)
        canvas.drawOval(x - 15f, y - 7f, x + 15f, y + 7f, glowPaint)
        glowPaint.color = Color.WHITE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (connected && animator == null) setConnected(false).also { setConnected(true) }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        transition?.cancel()
        animator = null
        transition = null
        super.onDetachedFromWindow()
    }
}
