package com.gilrtano.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Displays the uploaded 16-frame photographic-style fire GIF.
 * The GIF contains the tiger and the power orb as one composed hero scene.
 * It loops continuously while connected and fades out when disconnected.
 */
class TigerFlameOverlayView(context: Context) : View(context) {
    private val movie: Movie = context.resources.openRawResource(R.drawable.gilrtano_fire).use { stream ->
        Movie.decodeStream(stream) ?: throw IllegalStateException("Не удалось открыть GIF GilrTano")
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var active = false
    private var startedAt = 0L
    private var frameTime = 0
    private var fade = 1f
    private var fadeAnimator: ValueAnimator? = null
    private val duration = movie.duration().takeIf { it > 0 } ?: 2240

    fun setPhase(value: Float) {
        // The GIF has its own frame clock; avoid redundant invalidations.
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        fadeAnimator?.cancel()
        if (value) {
            visibility = VISIBLE
            alpha = 1f
            fade = 1f
            startedAt = SystemClock.uptimeMillis()
            frameTime = 0
            invalidate()
        } else {
            // Fade the moving fire out, then reset to the clean first frame.
            fadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 750L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    fade = it.animatedValue as Float
                    alpha = fade
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!active) {
                            frameTime = 0
                            movie.setTime(0)
                            alpha = 1f
                            fade = 1f
                            invalidate()
                        }
                    }
                })
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (active) {
            val elapsed = (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
            frameTime = (elapsed % duration.toLong()).toInt()
            postInvalidateOnAnimation()
        }
        movie.setTime(frameTime)
        val scale = minOf(width.toFloat() / movie.width().coerceAtLeast(1), height.toFloat() / movie.height().coerceAtLeast(1))
        val drawW = movie.width() * scale
        val drawH = movie.height() * scale
        val save = canvas.save()
        canvas.translate((width - drawW) / 2f, (height - drawH) / 2f)
        canvas.scale(scale, scale)
        paint.alpha = (255f * fade.coerceIn(0f, 1f)).toInt()
        movie.draw(canvas, 0f, 0f, paint)
        canvas.restoreToCount(save)
    }
}
