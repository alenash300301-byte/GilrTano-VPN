package com.gilrtano.vpn

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated ignition that starts at the lower centre (the power button),
 * climbs both sides of the tiger, then closes into a fiery crown.
 * This is a procedural Canvas effect; it does not use photographic fire footage.
 */
class TigerFlameOverlayView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var active = false
    private var reveal = 0f
    private var animator: ValueAnimator? = null

    fun setPhase(value: Float) { phase = value; invalidate() }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        animator?.cancel()
        if (value) {
            visibility = VISIBLE
            animator = ValueAnimator.ofFloat(reveal, 1f).apply {
                duration = 1350L
                interpolator = DecelerateInterpolator(1.15f)
                addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            animator = ValueAnimator.ofFloat(reveal, 0f).apply {
                duration = 1100L
                interpolator = DecelerateInterpolator()
                addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!active && reveal < .01f) visibility = GONE
                    }
                })
                start()
            }
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (reveal <= .001f || width == 0 || height == 0) return
        val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        val cx = w * .5f
        val powerY = h * 1.015f
        val topY = h * .12f
        val spread = w * .43f
        val r = reveal.coerceIn(0f, 1f)
        val flicker = .86f + .14f * sin(phase * 2.1f)

        // Warm light rising from the power-button ignition point.
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, h * .80f, w * .70f,
            intArrayOf(Color.argb((54*r).toInt(),255,65,0), Color.argb((25*r).toInt(),255,35,0), Color.TRANSPARENT),
            floatArrayOf(0f,.62f,1f), Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,w,h,p); p.shader = null

        // The fire rises from the bottom centre in two curling streams.
        val sides = 2
        for (side in 0 until sides) {
            val sign = if (side == 0) -1f else 1f
            for (strand in 0 until 9) {
                val q = strand / 8f
                val sway = sin(phase * 1.8f + strand * .9f + side) * 10f * d
                val reach = spread * (0.25f + .75f * r)
                val path = Path().apply {
                    moveTo(cx + sign * (8f + strand * 2f) * d, powerY)
                    cubicTo(
                        cx + sign * (18f + q * 26f) * d + sway, h * .83f,
                        cx + sign * (reach * .52f) + sway, h * (.55f - q * .08f),
                        cx + sign * (reach * .88f) + sway * .5f, topY + q * h * .30f
                    )
                    cubicTo(
                        cx + sign * (reach * .70f) - sway, h * (.36f + q * .10f),
                        cx + sign * (reach * .30f) + sway, h * .18f,
                        cx + sign * (reach * .10f), topY
                    )
                }
                p.style = Paint.Style.STROKE
                p.strokeCap = Paint.Cap.ROUND
                p.strokeJoin = Paint.Join.ROUND
                p.strokeWidth = (3.5f + (strand % 3) * 1.4f) * d
                p.color = Color.argb((r * 180).toInt(), 255, if (strand%2==0) 83 else 125, 8)
                c.drawPath(path,p)
                p.strokeWidth = 1.15f*d
                p.color = Color.argb((r * 210).toInt(),255,215,112)
                c.drawPath(path,p)
            }
        }

        // Fiery lower arc wraps the power button and joins both rising streams.
        for (layer in 0..2) {
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = (if(layer==0) 8f else if(layer==1) 4f else 1.5f)*d
            p.color = when(layer) {
                0 -> Color.argb((r*85).toInt(),255,55,0)
                1 -> Color.argb((r*190).toInt(),255,112,8)
                else -> Color.argb((r*235).toInt(),255,224,130)
            }
            val oval = RectF(cx-spread*.46f, h*.72f, cx+spread*.46f, h*1.16f)
            c.drawArc(oval, 190f + phase*4f, 160f, false, p)
        }

        // Flickering tongues and sparks climb around the tiger silhouette.
        for (i in 0 until 44) {
            val t = i / 43f
            val side = if (i%2==0) -1f else 1f
            val y = h * (.88f - t*.73f)
            val x = cx + side * (spread * (.12f + .82f*t)) +
                sin(phase*2.4f+i*1.7f)*7f*d
            val len = (12f + 24f*(.5f+.5f*sin(phase*2f+i)))*d*flicker
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = (2f + (i%3))*d
            p.color = Color.argb((r*(130+(i%4)*25)).toInt().coerceIn(0,255),255,if(i%3==0)220 else 105,12)
            val flame = Path().apply {
                moveTo(x,y+len*.35f)
                cubicTo(x-side*len*.25f,y,x+side*len*.18f,y-len*.55f,x+side*len*.12f,y-len)
            }
            c.drawPath(flame,p)
        }
        for (i in 0 until 24) {
            val a = i*2.399f + phase*.3f
            val x = cx + cos(a.toDouble()).toFloat()*spread*(.25f+(i%4)*.17f)
            val y = h*(.25f+(i%7)*.085f) - (phase*17f+i*9f)%(h*.22f)
            p.style = Paint.Style.FILL
            p.color = if(i%3==0) Color.rgb(255,225,130) else Color.rgb(255,103,12)
            p.alpha = (r*(115+70*(.5f+.5f*sin(phase*2f+i)))).toInt().coerceIn(0,255)
            c.drawCircle(x,y,(1f+(i%3)*.65f)*d,p)
        }
        if (active || reveal > .001f) postInvalidateOnAnimation()
    }
}
