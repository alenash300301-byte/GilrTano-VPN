package com.gilrtano.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/** Subtle green traffic waveform matching the approved GilrTano home screen. */
class TrafficWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF21F34A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(8f * resources.displayMetrics.density, 0f, 0f, 0xAA18FF42.toInt())
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x3318E943
        style = Paint.Style.FILL
    }
    private val wave = Path()
    private val area = Path()
    private var phase = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        wave.reset()
        area.reset()
        val mid = h * .52f
        val amp = h * .19f
        val steps = 90
        for (i in 0..steps) {
            val x = w * i / steps
            val t = i.toFloat() / steps
            val y = mid + (sin(t * 7.2 * Math.PI + phase) * .72 + sin(t * 17.0 * Math.PI + phase * .6) * .28).toFloat() * amp
            if (i == 0) { wave.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y) }
            else { wave.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(w, h)
        area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(wave, line)
        phase += 0.10f
        postInvalidateDelayed(80L)
    }
}