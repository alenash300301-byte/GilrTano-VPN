package com.gilrtano.vpn

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Wide rounded action control matching the approved GilrTano home screen. */
class FireButtonDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var pressed = false
    private var connected = false

    fun setPhase(value: Float) { phase = value; invalidateSelf() }
    fun setPressed(value: Boolean) { pressed = value; invalidateSelf() }
    fun setConnected(value: Boolean) { connected = value; if (value) pressed = false; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val rect = RectF(b.left + 3f, b.top + 3f, b.right - 3f, b.bottom - 3f)
        val radius = rect.height() / 2f
        val active = pressed || connected
        val save = canvas.save()

        if (active) {
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(
                rect.centerX(), rect.centerY(), rect.width() * .62f,
                if (connected) intArrayOf(Color.argb(105, 255, 91, 0), Color.argb(32, 255, 48, 0), Color.TRANSPARENT)
                else intArrayOf(Color.argb(65, 255, 91, 0), Color.argb(18, 255, 48, 0), Color.TRANSPARENT),
                floatArrayOf(0f, .68f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(RectF(rect.left - 8f, rect.top - 8f, rect.right + 8f, rect.bottom + 8f), radius + 8f, radius + 8f, paint)
            paint.shader = null
        }

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(8, 7, 7)
        canvas.drawRoundRect(rect, radius, radius, paint)

        if (connected) {
            paint.shader = RadialGradient(
                rect.centerX(), rect.centerY(), rect.width() * .48f,
                intArrayOf(Color.argb(48, 0, 255, 48), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (active) 3.2f else 2.4f
        paint.color = if (connected) Color.rgb(255, 112, 18)
            else if (pressed) Color.rgb(255, 170, 45)
            else Color.rgb(255, 112, 18)
        canvas.drawRoundRect(rect, radius, radius, paint)

        if (connected) {
            paint.strokeWidth = 1.5f
            paint.color = Color.argb(110, 40, 255, 70)
            canvas.drawRoundRect(RectF(rect.left + 3f, rect.top + 3f, rect.right - 3f, rect.bottom - 3f), radius - 3f, radius - 3f, paint)
        }
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
