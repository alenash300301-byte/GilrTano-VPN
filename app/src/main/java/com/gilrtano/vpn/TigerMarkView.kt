package com.gilrtano.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Original tiger mascot mark with ember fur and emerald eyes. */
class TigerMarkView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun fill(color: Int) { paint.color = color; paint.style = Paint.Style.FILL }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        paint.setShadowLayer(10f, 0f, 0f, 0xFFFF5A12.toInt())
        fill(0xFFFF6418.toInt())
        canvas.drawCircle(cx, h / 2f, w * .47f, paint)
        paint.clearShadowLayer()
        fill(0xFF120A08.toInt())
        canvas.drawCircle(cx, h / 2f, w * .40f, paint)
        val ears = Path().apply {
            moveTo(w*.18f,h*.34f); lineTo(w*.12f,h*.08f); lineTo(w*.40f,h*.23f); close()
            moveTo(w*.82f,h*.34f); lineTo(w*.88f,h*.08f); lineTo(w*.60f,h*.23f); close()
        }
        fill(0xFFFF7A1B.toInt()); canvas.drawPath(ears, paint)
        fill(0xFFFF7A1B.toInt())
        canvas.drawOval(RectF(w*.24f,h*.20f,w*.76f,h*.86f), paint)
        fill(0xFF21100B.toInt())
        canvas.drawOval(RectF(w*.30f,h*.28f,w*.70f,h*.80f), paint)
        fill(0xFF080707.toInt())
        for (i in 0..2) {
            val y = h*(.32f+i*.12f)
            val l = Path().apply { moveTo(w*.34f,y); lineTo(w*.45f,y+h*.05f); lineTo(w*.42f,y+h*.015f); close() }
            val r = Path().apply { moveTo(w*.66f,y); lineTo(w*.55f,y+h*.05f); lineTo(w*.58f,y+h*.015f); close() }
            canvas.drawPath(l, paint); canvas.drawPath(r, paint)
        }
        fill(0xFF25E878.toInt())
        canvas.drawOval(RectF(w*.31f,h*.43f,w*.46f,h*.51f), paint)
        canvas.drawOval(RectF(w*.54f,h*.43f,w*.69f,h*.51f), paint)
        fill(0xFF06120A.toInt())
        canvas.drawOval(RectF(w*.375f,h*.435f,w*.395f,h*.505f), paint)
        canvas.drawOval(RectF(w*.605f,h*.435f,w*.625f,h*.505f), paint)
        fill(0xFFFFD6A7.toInt())
        canvas.drawOval(RectF(w*.36f,h*.56f,w*.50f,h*.68f), paint)
        canvas.drawOval(RectF(w*.50f,h*.56f,w*.64f,h*.68f), paint)
        fill(0xFF30100B.toInt())
        val nose = Path().apply { moveTo(cx,h*.59f); lineTo(w*.45f,h*.65f); lineTo(w*.55f,h*.65f); close() }
        canvas.drawPath(nose, paint)
    }
}
