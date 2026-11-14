package com.gilrtano.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Flame frame around the hero artwork. Hidden when disconnected; the fire
 * grows from the power core, wraps the tiger and fades smoothly on disconnect.
 */
class TigerFlameOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var active = false
    private var reveal = 0f

    fun setPhase(value: Float) {
        phase = value
        invalidate()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        animate().cancel()
        if (value) {
            visibility = VISIBLE
            reveal = 0f
            animate().setDuration(1050L).setInterpolator(DecelerateInterpolator())
                .alpha(1f).setUpdateListener { reveal = it.animatedFraction; invalidate() }.start()
        } else {
            animate().setDuration(700L).setInterpolator(DecelerateInterpolator())
                .alpha(0f).withEndAction {
                    if (!active) {
                        visibility = GONE
                        reveal = 0f
                    }
                }.start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = (w / 360f).coerceAtLeast(.65f)
        val cx = w * .5f
        val cy = h * .51f
        val rx = w * .455f
        val ry = h * .43f
        val save = canvas.save()
        canvas.clipRect(0f, 0f, w, h)

        // A narrow ignition trail climbs from the orb before the ring of fire opens.
        val ignitionHeight = h * (.12f + .42f * reveal)
        for (i in 0 until 9) {
            val spread = (i - 4) * 4.5f * unit
            val sway = sin(phase * 1.4f + i * .8f) * 7f * unit
            val baseY = h - 2f * unit
            val tipY = baseY - ignitionHeight * (.72f + .05f * (i % 4))
            val widthBase = (4f + (i % 3) * 2f) * unit
            val path = Path().apply {
                moveTo(cx + spread - widthBase, baseY)
                cubicTo(cx + spread - widthBase * 1.7f, baseY - ignitionHeight * .25f,
                    cx + sway + spread, tipY + ignitionHeight * .2f,
                    cx + sway + spread * .45f, tipY)
                cubicTo(cx + spread + widthBase * 1.5f, tipY + ignitionHeight * .32f,
                    cx + spread + widthBase, baseY - ignitionHeight * .16f,
                    cx + spread + widthBase, baseY)
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = if (i % 3 == 0) Color.rgb(255, 205, 74) else Color.rgb(255, 75, 8)
            paint.alpha = (210 * reveal).toInt().coerceIn(0, 210)
            paint.setShadowLayer(12f * unit, 0f, 0f, Color.rgb(255, 74, 0))
            canvas.drawPath(path, paint)
            paint.clearShadowLayer()
        }

        // Flame tongues follow an oval around the tiger, leaving the eyes and muzzle clear.
        val count = 42
        val visibleCount = (count * reveal).toInt()
        for (i in 0 until visibleCount) {
            val angle = (Math.PI * 2.0 * i / count).toFloat() - (Math.PI / 2f)
            val wave = (sin(phase * 1.65f + i * 1.27f) + 1f) * .5f
            val x = cx + cos(angle) * rx
            val y = cy + sin(angle) * ry
            val nx = cos(angle)
            val ny = sin(angle)
            val length = (18f + 28f * wave) * unit
            val half = (4f + 4f * wave) * unit
            val drift = sin(phase + i * .63f) * 5f * unit
            val path = Path().apply {
                moveTo(x - ny * half, y + nx * half)
                cubicTo(
                    x + nx * length * .18f - ny * half * 1.4f,
                    y + ny * length * .18f + nx * half * 1.4f,
                    x + nx * length * .72f + drift,
                    y + ny * length * .72f,
                    x + nx * length + drift,
                    y + ny * length
                )
                cubicTo(
                    x + nx * length * .54f + ny * half,
                    y + ny * length * .54f - nx * half,
                    x + ny * half,
                    y - nx * half,
                    x - ny * half,
                    y + nx * half
                )
                close()
            }
            paint.color = when (i % 4) {
                0 -> Color.rgb(255, 211, 94)
                1 -> Color.rgb(255, 143, 28)
                else -> Color.rgb(255, 67, 5)
            }
            paint.alpha = (190 + wave * 55).toInt()
            paint.setShadowLayer(10f * unit, 0f, 0f, Color.rgb(255, 72, 0))
            canvas.drawPath(path, paint)
            paint.clearShadowLayer()
        }
        canvas.restoreToCount(save)
    }
}
