package com.gilrtano.vpn

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Circular flame power core matching the approved disconnected/connected states. */
class PowerOrbDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var connected = false
    private var phase = 0f

    fun setConnected(value: Boolean) { connected = value; invalidateSelf() }
    fun setPhase(value: Float) { phase = value; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val radius = minOf(b.width(), b.height()) * 0.39f
        val flameColor = if (connected) Color.rgb(255, 83, 8) else Color.rgb(255, 112, 18)
        val coreColor = if (connected) Color.rgb(0, 255, 48) else Color.WHITE

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, radius * 2.05f,
            if (connected) intArrayOf(Color.argb(120, 0, 255, 40), Color.argb(70, 255, 80, 0), Color.TRANSPARENT)
            else intArrayOf(Color.argb(85, 255, 80, 0), Color.argb(38, 255, 40, 0), Color.TRANSPARENT),
            floatArrayOf(0f, .62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * 2.05f, paint)
        paint.shader = null

        // Animated flame tongues surrounding the circular core.
        for (i in 0 until 28) {
            val angle = (Math.PI * 2.0 * i / 28.0).toFloat() + phase * .18f
            val wave = (sin(phase * 1.7f + i * 1.31f) + 1f) * .5f
            val inner = radius * 1.02f
            val outer = radius * (1.18f + .26f * wave)
            val spread = .075f + .045f * wave
            val path = Path()
            path.moveTo(cx + (cos(angle - spread) * inner).toFloat(), cy + (sin(angle - spread) * inner).toFloat())
            path.quadTo(
                cx + (cos(angle) * (outer + radius * .10f)).toFloat(),
                cy + (sin(angle) * (outer + radius * .10f)).toFloat(),
                cx + (cos(angle) * outer).toFloat(), cy + (sin(angle) * outer).toFloat()
            )
            path.quadTo(
                cx + (cos(angle + spread) * inner).toFloat(),
                cy + (sin(angle + spread) * inner).toFloat(),
                cx + (cos(angle - spread) * inner).toFloat(), cy + (sin(angle - spread) * inner).toFloat()
            )
            paint.color = if (i % 3 == 0) Color.rgb(255, 198, 55) else flameColor
            canvas.drawPath(path, paint)
        }

        paint.color = Color.rgb(7, 8, 8)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * .055f
        paint.color = if (connected) Color.rgb(0, 255, 48) else Color.rgb(255, 112, 18)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, radius,
            if (connected) intArrayOf(Color.rgb(0, 67, 18), Color.rgb(0, 15, 5))
            else intArrayOf(Color.rgb(36, 25, 18), Color.rgb(5, 5, 6)),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * .94f, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
