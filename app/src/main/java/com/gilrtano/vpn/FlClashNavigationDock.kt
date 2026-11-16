package com.gilrtano.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating four-destination dock inspired by FlClashX v0.5.7.
 *
 * The indicator follows the selected destination with a short spring-like
 * overshoot. Each destination gives immediate tactile/visual press feedback.
 * This is deliberately presentation-only: VPN state and routing remain in
 * MainActivity and GilrTanoVpnService.
 */
class FlClashNavigationDock(
    context: Context,
    private val onSelected: (Int) -> Unit
) : FrameLayout(context) {
    private val labels = listOf("Главная", "Прокси", "Профили", "Настройки")
    private val glyphs = listOf("⌂", "◇", "▤", "⚙")
    private val cells = mutableListOf<LinearLayout>()
    private val indicator = View(context)
    private val row = LinearLayout(context)
    private var selectedIndex = 0
    private var indicatorAnimator: ValueAnimator? = null

    init {
        clipChildren = false
        clipToPadding = false
        background = rounded(Color.rgb(17, 17, 20), dp(30), Color.rgb(54, 48, 48), dp(1))
        elevation = dp(10).toFloat()
        setPadding(dp(4), dp(5), dp(4), dp(5))
        isFocusable = true
        contentDescription = "Нижняя навигация"

        indicator.background = rounded(Color.rgb(57, 25, 18), dp(26), Color.rgb(126, 43, 25), dp(1))
        addView(indicator, LayoutParams(0, LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START))

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        labels.indices.forEach { index ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = labels[index]
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            val glyph = TextView(context).apply {
                text = glyphs[index]
                textSize = if (index == 3) 20f else 23f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(if (index == 0) Color.rgb(255, 174, 118) else Color.LTGRAY)
            }
            val label = TextView(context).apply {
                text = labels[index]
                textSize = 10f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, dp(2), 0, 0)
                setTextColor(if (index == 0) Color.WHITE else Color.LTGRAY)
            }
            cell.addView(glyph, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(25)))
            cell.addView(label, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(15)))
            cell.tag = Pair(glyph, label)
            cell.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().cancel()
                        view.animate().scaleX(0.92f).scaleY(0.92f)
                            .alpha(0.82f).setDuration(85L).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(230L)
                            .setInterpolator(OvershootInterpolator(0.7f)).start()
                    }
                }
                false
            }
            cell.setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                select(index, true)
                onSelected(index)
            }
            cells += cell
            row.addView(cell, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        post { positionIndicator(false) }
    }

    fun setSelectedIndex(index: Int, animate: Boolean = true) {
        select(index.coerceIn(labels.indices), animate)
    }

    private fun select(index: Int, animate: Boolean) {
        if (index !in labels.indices) return
        selectedIndex = index
        cells.forEachIndexed { i, cell ->
            val (glyph, label) = cell.tag as Pair<TextView, TextView>
            val active = i == index
            glyph.setTextColor(if (active) Color.rgb(255, 174, 118) else Color.LTGRAY)
            label.setTextColor(if (active) Color.WHITE else Color.LTGRAY)
            glyph.animate().cancel()
            glyph.animate().scaleX(if (active) 1.08f else 1f)
                .scaleY(if (active) 1.08f else 1f)
                .setDuration(if (animate) 200L else 0L)
                .setInterpolator(OvershootInterpolator(0.35f)).start()
        }
        positionIndicator(animate)
    }

    private fun positionIndicator(animate: Boolean) {
        if (width <= 0 || row.width <= 0) return
        val cellWidth = row.width.toFloat() / labels.size
        val targetX = cellWidth * selectedIndex + dp(4)
        val targetWidth = (cellWidth - dp(8)).toInt().coerceAtLeast(0)
        val params = indicator.layoutParams
        if (params.width != targetWidth) {
            params.width = targetWidth
            indicator.layoutParams = params
        }
        indicatorAnimator?.cancel()
        if (!animate) {
            indicator.translationX = targetX
            indicator.scaleY = 1f
            return
        }
        indicatorAnimator = ValueAnimator.ofFloat(indicator.translationX, targetX).apply {
            duration = 400L
            interpolator = OvershootInterpolator(0.12f)
            addUpdateListener { indicator.translationX = it.animatedValue as Float }
            start()
        }
        indicator.animate().cancel()
        indicator.animate().scaleY(1.04f).setDuration(120L)
            .withEndAction {
                indicator.animate().scaleY(1f).setDuration(250L)
                    .setInterpolator(OvershootInterpolator(0.25f)).start()
            }.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { positionIndicator(false) }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int, strokeWidth: Int) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            setStroke(strokeWidth, stroke)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
