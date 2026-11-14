package com.gilrtano.vpn

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Circular power-button artwork: dark while disconnected, animated tongues of fire while connected. */
class FireButtonDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var lit = false

    fun setPhase(value: Float) {
        phase = value
        invalidateSelf()
    }

    fun setIgnited(value: Boolean) {
        lit = value
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val radius = minOf(b.width(), b.height()) * 0.405f
        val save = canvas.save()
        canvas.translate(cx, cy)

        if (lit) {
            paint.shader = RadialGradient(0f, 0f, radius * 1.55f,
                intArrayOf(Color.argb(125, 255, 95, 0), Color.argb(35, 255, 55, 0), Color.TRANSPARENT),
                floatArrayOf(0f, 0.68f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(0f, 0f, radius * 1.55f, paint)
            paint.shader = null

            // Separate tapered flame tongues around the outer rim.
            for (i in 0 until 14) {
                val angle = (i * 360f / 14f - 90f) * Math.PI.toFloat() / 180f
                val wobble = sin(phase * 1.7f + i * 1.31f) * 0.11f
                val length = radius * (0.22f + 0.10f * (0.5f + 0.5f * sin(phase * 2f + i)))
                val base = radius * 0.88f
                val ux = cos(angle); val uy = sin(angle)
                val px = -uy; val py = ux
                val path = Path().apply {
                    moveTo(ux * base + px * radius * 0.12f, uy * base + py * radius * 0.12f)
                    quadTo(ux * (base + length * 0.35f) + px * radius * 0.18f,
                        uy * (base + length * 0.35f) + py * radius * 0.18f,
                        ux * (base + length) + px * wobble * radius,
                        uy * (base + length) + py * wobble * radius)
                    quadTo(ux * (base + length * 0.30f) - px * radius * 0.13f,
                        uy * (base + length * 0.30f) - py * radius * 0.13f,
                        ux * base - px * radius * 0.12f, uy * base - py * radius * 0.12f)
                    close()
                }
                paint.color = if (i % 3 == 0) Color.rgb(255, 220, 85) else Color.rgb(255, 91, 8)
                canvas.drawPath(path, paint)
            }
        }

        paint.color = if (lit) Color.rgb(49, 17, 7) else Color.rgb(8, 7, 7)
        canvas.drawCircle(0f, 0f, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.035f
        paint.color = if (lit) Color.rgb(255, 154, 35) else Color.rgb(48, 48, 52)
        canvas.drawCircle(0f, 0f, radius, paint)
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
