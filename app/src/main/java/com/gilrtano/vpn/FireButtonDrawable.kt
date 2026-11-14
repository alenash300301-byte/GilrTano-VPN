package com.gilrtano.vpn

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Idle is completely dark; press ignites orange fire; connected keeps orange fire around a green core. */
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
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val radius = minOf(b.width(), b.height()) * 0.34f
        val active = pressed || connected
        val save = canvas.save()
        canvas.translate(cx, cy)

        if (active) {
            paint.shader = RadialGradient(0f, 0f, radius * 1.85f,
                intArrayOf(Color.argb(105, 255, 91, 0), Color.argb(42, 255, 48, 0), Color.TRANSPARENT),
                floatArrayOf(0f, .68f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(0f, 0f, radius * 1.85f, paint)
            paint.shader = null
            for (i in 0 until 24) {
                val a = (i * 360f / 24f - 90f) * Math.PI.toFloat() / 180f
                val wobble = sin(phase * 1.7f + i * 1.31f) * .12f
                val length = radius * (.28f + .18f * (.5f + .5f * sin(phase * 2f + i)))
                val base = radius * .92f
                val ux = cos(a); val uy = sin(a); val px = -uy; val py = ux
                val path = Path().apply {
                    moveTo(ux*base+px*radius*.10f, uy*base+py*radius*.10f)
                    quadTo(ux*(base+length*.35f)+px*radius*.15f, uy*(base+length*.35f)+py*radius*.15f,
                        ux*(base+length)+px*wobble*radius, uy*(base+length)+py*wobble*radius)
                    quadTo(ux*(base+length*.28f)-px*radius*.12f, uy*(base+length*.28f)-py*radius*.12f,
                        ux*base-px*radius*.10f, uy*base-py*radius*.10f)
                    close()
                }
                paint.color = if (i % 4 == 0) Color.rgb(255, 222, 105) else Color.rgb(255, 91, 8)
                canvas.drawPath(path, paint)
            }
        }

        paint.color = if (connected) Color.rgb(4, 38, 12) else Color.rgb(5, 5, 6)
        canvas.drawCircle(0f, 0f, radius, paint)
        if (connected) {
            paint.shader = RadialGradient(0f, 0f, radius,
                intArrayOf(Color.rgb(15, 95, 28), Color.rgb(2, 24, 8)), floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP)
            canvas.drawCircle(0f, 0f, radius*.96f, paint)
            paint.shader = null
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * .035f
        paint.color = if (connected) Color.rgb(31, 255, 62) else if (pressed) Color.rgb(255, 170, 40) else Color.rgb(26, 26, 29)
        canvas.drawCircle(0f, 0f, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = radius * .11f
        paint.color = if (connected) Color.WHITE else if (pressed) Color.rgb(255, 202, 120) else Color.rgb(215, 215, 220)
        val p = Path().apply {
            moveTo(0f, -radius*.42f)
            lineTo(0f, -radius*.04f)
            arcTo(RectFCompat.circle(radius*.43f), -48f, 276f, false)
        }
        canvas.drawPath(p, paint)
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    private object RectFCompat {
        fun circle(r: Float): android.graphics.RectF = android.graphics.RectF(-r, -r, r, r)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
