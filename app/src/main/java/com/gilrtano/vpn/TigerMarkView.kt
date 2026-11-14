package com.gilrtano.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** App mascot: ember flames framing a black tiger with emerald eyes, matching the launcher icon. */
class TigerMarkView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun color(value: Long) { p.color = value.toInt(); p.style = Paint.Style.FILL; p.clearShadowLayer() }
    private fun path(canvas: Canvas, c: Long, build: Path.() -> Unit) {
        color(c); canvas.drawPath(Path().apply(build), p)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        val save = canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s / 512f, s / 512f)

        // Open flame silhouette: no red badge, shield, or enclosing ring.
        path(canvas, 0xFFFF4B08L) {
            moveTo(42f, 402f); cubicTo(12f, 338f, 50f, 286f, 78f, 250f)
            cubicTo(55f, 202f, 76f, 161f, 110f, 133f)
            cubicTo(95f, 196f, 126f, 224f, 145f, 237f)
            cubicTo(134f, 174f, 171f, 119f, 211f, 91f)
            cubicTo(190f, 153f, 218f, 193f, 236f, 211f)
            cubicTo(230f, 144f, 263f, 94f, 287f, 55f)
            cubicTo(281f, 128f, 311f, 170f, 330f, 194f)
            cubicTo(343f, 142f, 382f, 111f, 419f, 96f)
            cubicTo(386f, 153f, 397f, 193f, 427f, 226f)
            cubicTo(466f, 274f, 459f, 343f, 421f, 384f)
            cubicTo(426f, 327f, 397f, 291f, 375f, 277f)
            cubicTo(389f, 332f, 355f, 382f, 319f, 407f)
            cubicTo(333f, 353f, 314f, 315f, 294f, 294f)
            cubicTo(285f, 359f, 242f, 408f, 199f, 431f)
            cubicTo(214f, 373f, 194f, 343f, 174f, 326f)
            cubicTo(154f, 378f, 106f, 413f, 61f, 423f)
            cubicTo(89f, 385f, 83f, 350f, 66f, 331f)
            cubicTo(61f, 364f, 51f, 388f, 42f, 402f); close()
        }
        path(canvas, 0xFFFF9A18L) {
            moveTo(77f, 357f); cubicTo(57f, 311f, 94f, 276f, 115f, 259f)
            cubicTo(105f, 226f, 124f, 204f, 143f, 188f)
            cubicTo(133f, 232f, 153f, 246f, 168f, 257f)
            cubicTo(160f, 214f, 190f, 177f, 215f, 157f)
            cubicTo(201f, 200f, 221f, 225f, 239f, 239f)
            cubicTo(237f, 198f, 259f, 160f, 278f, 135f)
            cubicTo(275f, 181f, 299f, 212f, 316f, 231f)
            cubicTo(331f, 196f, 359f, 173f, 385f, 161f)
            cubicTo(365f, 201f, 381f, 231f, 401f, 251f)
            cubicTo(423f, 280f, 427f, 318f, 408f, 348f)
            cubicTo(399f, 309f, 380f, 290f, 363f, 279f)
            cubicTo(369f, 321f, 344f, 352f, 317f, 370f)
            cubicTo(329f, 334f, 315f, 309f, 299f, 295f)
            cubicTo(289f, 340f, 258f, 373f, 225f, 391f)
            cubicTo(237f, 354f, 222f, 330f, 205f, 316f)
            cubicTo(183f, 356f, 148f, 379f, 114f, 386f)
            cubicTo(134f, 355f, 125f, 330f, 111f, 313f)
            cubicTo(101f, 337f, 89f, 349f, 77f, 357f); close()
        }

        // Tiger silhouette and dark facial mask.
        color(0xFFE87520L); p.setShadowLayer(12f, 0f, 0f, 0xFFFF5A12.toInt())
        canvas.drawOval(RectF(85f, 69f, 427f, 461f), p); p.clearShadowLayer()
        path(canvas, 0xFF090909L) {
            moveTo(256f, 91f); cubicTo(213f, 91f, 181f, 118f, 163f, 151f)
            lineTo(127f, 131f); lineTo(141f, 190f)
            cubicTo(111f, 242f, 121f, 322f, 166f, 373f)
            cubicTo(190f, 402f, 224f, 423f, 256f, 436f)
            cubicTo(288f, 423f, 322f, 402f, 346f, 373f)
            cubicTo(391f, 322f, 401f, 242f, 371f, 190f)
            lineTo(385f, 131f); lineTo(349f, 151f)
            cubicTo(331f, 118f, 299f, 91f, 256f, 91f); close()
        }
        path(canvas, 0xFFF0E5D5L) {
            moveTo(145f, 177f); lineTo(121f, 111f); lineTo(177f, 145f); close()
            moveTo(367f, 177f); lineTo(391f, 111f); lineTo(335f, 145f); close()
        }
        // Fur stripes.
        path(canvas, 0xFFE87520L) {
            moveTo(219f, 105f); lineTo(250f, 187f); lineTo(226f, 165f); close()
            moveTo(293f, 105f); lineTo(262f, 187f); lineTo(286f, 165f); close()
            moveTo(170f, 163f); lineTo(224f, 218f); lineTo(192f, 205f); lineTo(153f, 185f); close()
            moveTo(342f, 163f); lineTo(288f, 218f); lineTo(320f, 205f); lineTo(359f, 185f); close()
            moveTo(130f, 232f); lineTo(215f, 260f); lineTo(178f, 274f); lineTo(124f, 256f); close()
            moveTo(382f, 232f); lineTo(297f, 260f); lineTo(334f, 274f); lineTo(388f, 256f); close()
        }
        // Emerald eyes and dark pupils.
        color(0xFF27FF45L)
        canvas.drawOval(RectF(151f, 229f, 240f, 282f), p)
        canvas.drawOval(RectF(272f, 229f, 361f, 282f), p)
        color(0xFF06120AL)
        canvas.drawOval(RectF(192f, 232f, 210f, 280f), p)
        canvas.drawOval(RectF(302f, 232f, 320f, 280f), p)
        color(0xFFFFFFFFL)
        canvas.drawCircle(178f, 241f, 5f, p); canvas.drawCircle(334f, 241f, 5f, p)

        // Cream muzzle and black nose.
        color(0xFFE9E2D6L)
        canvas.drawOval(RectF(174f, 286f, 257f, 376f), p)
        canvas.drawOval(RectF(255f, 286f, 338f, 376f), p)
        path(canvas, 0xFF171010L) {
            moveTo(256f, 301f); lineTo(223f, 326f); quadTo(256f, 351f, 289f, 326f); close()
        }
        path(canvas, 0xFF171010L) {
            moveTo(256f, 344f); cubicTo(243f, 367f, 225f, 370f, 208f, 359f)
            cubicTo(224f, 389f, 245f, 390f, 256f, 374f)
            cubicTo(267f, 390f, 288f, 389f, 304f, 359f)
            cubicTo(287f, 370f, 269f, 367f, 256f, 344f); close()
        }
        canvas.restoreToCount(save)
    }
}