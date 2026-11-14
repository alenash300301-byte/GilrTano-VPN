package com.gilrtano.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.TextView

/** Draws a platform-independent power glyph; avoids missing-font tofu squares. */
class PowerOrbButtonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextView(context, attrs) {
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        // View draws its background before onDraw; draw only the glyph here.
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val r = 19f * d
        val gap = 7f * d
        val bounds = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(bounds, 45f, 270f, false, symbolPaint)
        canvas.drawLine(cx, cy - r - gap * .15f, cx, cy + 1f * d, symbolPaint)
    }
}