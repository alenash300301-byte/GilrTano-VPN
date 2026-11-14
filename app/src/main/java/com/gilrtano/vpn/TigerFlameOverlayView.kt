package com.gilrtano.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated fire surrounding the tiger artwork.
 *
 * The animation is driven by MainActivity's shared phase clock so the tiger
 * flames and the power-orb fire stay visually synchronized. The reveal value
 * is eased independently, giving ignition and shutdown a deliberate fade.
 */
class TigerFlameOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var active = false
    private var reveal = 0f
    private var revealAnimator: android.animation.ValueAnimator? = null

    fun setPhase(value: Float) {
        phase = value
        invalidate()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        revealAnimator?.cancel()
        if (value) {
            visibility = VISIBLE
            alpha = 1f
            revealAnimator = android.animation.ValueAnimator.ofFloat(reveal, 1f).apply {
                duration = 1150L
                interpolator = DecelerateInterpolator(1.7f)
                addUpdateListener {
                    reveal = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            // Let the fire collapse and fade instead of abruptly disappearing.
            revealAnimator = android.animation.ValueAnimator.ofFloat(reveal, 0f).apply {
                duration = 850L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    reveal = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!active && reveal <= 0.01f) visibility = GONE
                    }
                })
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (reveal <= 0.001f || width <= 0 || height <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        val cx = w * .5f
        val cy = h * .51f
        val rx = w * .455f
        val ry = h * .425f
        val intensity = reveal.coerceIn(0f, 1f)
        val flicker = .90f + .10f * sin(phase * 2.3f)

        // Soft ambient firelight behind the tongues of flame.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, maxOf(w, h) * .62f,
            intArrayOf(
                Color.argb((20 * intensity).toInt(), 255, 54, 0),
                Color.argb((48 * intensity).toInt(), 255, 83, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .66f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Two flowing elliptical arcs make the fire read as a continuous ring.
        for (layer in 0..1) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = (if (layer == 0) 3.5f else 1.4f) * d
            paint.color = if (layer == 0)
                Color.argb((105 * intensity * flicker).toInt(), 255, 88, 8)
            else Color.argb((185 * intensity).toInt(), 255, 191, 66)
            val inset = layer * 5f * d
            val oval = android.graphics.RectF(
                cx - rx + inset, cy - ry + inset,
                cx + rx - inset, cy + ry - inset
            )
            canvas.drawArc(oval, -205f + phase * 5f, 285f, false, paint)
        }

        // Flame tongues: alternating hot cores and orange outer bodies.
        val count = 38
        val visible = (count * intensity).toInt()
        for (i in 0 until visible) {
            val angle = (Math.PI * 2.0 * i / count).toFloat() - Math.PI.toFloat() / 2f
            val wave = (sin(phase * 1.8f + i * 1.37f) + 1f) * .5f
            val flutter = sin(phase * 2.7f + i * .73f) * 4.5f * d
            val nx = cos(angle)
            val ny = sin(angle)
            val x = cx + nx * rx
            val y = cy + ny * ry
            val length = (17f + 31f * wave) * d * flicker
            val half = (3.2f + 3.7f * wave) * d
            val drift = flutter + sin(phase + i * .41f) * 3f * d

            val path = Path().apply {
                moveTo(x - ny * half, y + nx * half)
                cubicTo(
                    x + nx * length * .20f - ny * half * 1.35f,
                    y + ny * length * .20f + nx * half * 1.35f,
                    x + nx * length * .68f + drift - ny * half * .55f,
                    y + ny * length * .68f + nx * half * .55f,
                    x + nx * length + drift, y + ny * length
                )
                cubicTo(
                    x + nx * length * .70f + drift + ny * half * .45f,
                    y + ny * length * .70f - nx * half * .45f,
                    x + nx * length * .20f + ny * half,
                    y + ny * length * .20f - nx * half,
                    x - ny * half, y + nx * half
                )
                close()
            }

            paint.style = Paint.Style.FILL
            paint.color = when (i % 5) {
                0 -> Color.rgb(255, 226, 126)
                1 -> Color.rgb(255, 173, 45)
                2, 3 -> Color.rgb(255, 91, 8)
                else -> Color.rgb(230, 39, 3)
            }
            paint.alpha = (intensity * (190 + wave * 65)).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)

            // Thin luminous center gives each flame a hotter, more realistic core.
            val core = Path().apply {
                moveTo(x - ny * half * .22f, y + nx * half * .22f)
                quadTo(
                    x + nx * length * .42f + drift * .35f,
                    y + ny * length * .42f,
                    x + nx * length * .82f + drift,
                    y + ny * length * .82f
                )
                quadTo(
                    x + nx * length * .40f,
                    y + ny * length * .40f,
                    x - ny * half * .22f,
                    y + nx * half * .22f
                )
                close()
            }
            paint.color = Color.argb((intensity * (145 + wave * 75)).toInt(), 255, 220, 126)
            canvas.drawPath(core, paint)
        }

        // Small embers drift upward around the perimeter; deterministic paths
        // keep the animation smooth without allocating random particles per frame.
        for (i in 0 until 22) {
            val seed = i * 2.399f
            val travel = (phase * (13f + (i % 4) * 2f) + seed * 17f) % (h * .48f)
            val baseX = cx + cos(seed.toDouble()).toFloat() * rx * (0.78f + (i % 3) * .08f)
            val baseY = cy + sin(seed.toDouble()).toFloat() * ry * .82f
            val x = baseX + sin(phase * 1.4f + seed) * 9f * d
            val y = baseY - travel
            val radius = (1.1f + (i % 3) * .55f) * d
            paint.color = if (i % 3 == 0) Color.rgb(255, 220, 128) else Color.rgb(255, 104, 18)
            paint.alpha = (intensity * (110 + 100 * (0.5f + 0.5f * sin(phase * 2f + seed)))).toInt()
            canvas.drawCircle(x, y, radius, paint)
        }
    }
}
