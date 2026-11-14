package com.gilrtano.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.sin

/**
 * Animated flame layer for the tiger artwork.
 * It is fully hidden while disconnected and fades away on disconnect.
 */
class TigerFlameOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var active = false

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
            alpha = 0f
            animate().alpha(1f).setDuration(850L)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            animate().alpha(0f).setDuration(650L)
                .withEndAction { if (!active) visibility = GONE }
                .start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = (w / 360f).coerceAtLeast(0.65f)
        val save = canvas.save()
        canvas.clipRect(0f, 0f, w, h)

        // Fire rises from the power-button edge and curls around the tiger.
        for (i in 0 until 22) {
            val t = i / 21f
            val x = w * t
            val wave = (sin(phase * 1.8f + i * 1.17f) + 1f) * .5f
            val flameHeight = (h * (.12f + .14f * wave)) + 10f * unit
            val baseY = h - 3f * unit
            val widthAtBase = (7f + 5f * wave) * unit
            val path = Path().apply {
                moveTo(x - widthAtBase, baseY)
                cubicTo(
                    x - widthAtBase * 1.1f, baseY - flameHeight * .34f,
                    x + widthAtBase * .7f, baseY - flameHeight * .72f,
                    x + (sin(phase + i) * 3f) * unit, baseY - flameHeight
                )
                cubicTo(
                    x + widthAtBase * 1.5f, baseY - flameHeight * .55f,
                    x + widthAtBase, baseY - flameHeight * .18f,
                    x + widthAtBase, baseY
                )
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = if (i % 3 == 0) Color.rgb(255, 194, 52) else Color.rgb(255, 78, 8)
            paint.alpha = 210
            paint.setShadowLayer(12f * unit, 0f, 0f, Color.rgb(255, 72, 0))
            canvas.drawPath(path, paint)
            paint.clearShadowLayer()
        }

        // Smaller tongues climb both sides to frame the tiger without covering its face.
        for (side in 0..1) {
            for (i in 0 until 7) {
                val y = h * (.18f + i * .105f)
                val x = if (side == 0) 5f * unit else w - 5f * unit
                val wave = (sin(phase * 1.5f + i * 1.4f + side) + 1f) * .5f
                val len = (20f + 20f * wave) * unit
                val path = Path().apply {
                    moveTo(x, y + 17f * unit)
                    quadTo(x + if (side == 0) len * .45f else -len * .45f, y,
                        x + if (side == 0) len else -len, y - len)
                    quadTo(x + if (side == 0) len * .55f else -len * .55f, y + 4f * unit,
                        x, y + 17f * unit)
                    close()
                }
                paint.color = if (i % 2 == 0) Color.rgb(255, 174, 32) else Color.rgb(255, 75, 7)
                paint.alpha = 185
                paint.setShadowLayer(9f * unit, 0f, 0f, Color.rgb(255, 70, 0))
                canvas.drawPath(path, paint)
                paint.clearShadowLayer()
            }
        }
        canvas.restoreToCount(save)
    }
}
