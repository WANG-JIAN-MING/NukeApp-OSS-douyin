package io.github.nuke.oss.app.tiktok.injector

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.chat.RepeatMarks
import io.github.nuke.oss.app.tiktok.sdk.ModuleStrings

// A "+1" box beside the bubble, on the side the bubble faces away from (left of the user's messages, right of the
// others). The host's row is a ConstraintLayout holding the bubble's column; the box is added to that row and moved next
// to the column with a translation, bottom edges level, so no constraint of the host is touched and nothing the host
// lays out moves. It has a soft fill with an outline that reads on a light and a dark chat, a ripple under the finger
// and a short pulse with haptic feedback when it fires. A second tap within a moment is ignored so one press cannot send
// two copies. All View work runs on the main thread.
internal class AndroidRepeatMarks(private val strings: ModuleStrings) : RepeatMarks {
    override fun prepare(row: Any) {
        val bubble = descendants(row as? View ?: return).firstOrNull { it.javaClass.simpleName == "FixWidthFrameLayout" } ?: return
        val column = bubble.parent as? LinearLayout ?: return
        val host = column.parent as? ViewGroup ?: return
        if (host.findViewWithTag<View>(TAG) != null) return
        val pill = pill(column, bubble)
        host.addView(pill, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // The column changes size when a recycled row is bound to another message, and the box has no size until it
        // is first laid out; both move where the box belongs.
        column.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place(pill) }
        pill.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place(pill) }
    }

    // Puts the box level with the bottom of the bubble, one gap away from the column on the open side, kept inside the
    // row so it stays touchable.
    private fun place(pill: Pill) {
        val host = pill.parent as? View ?: return
        if (pill.width == 0) return
        val gap = pill.dp(GAP_DP)
        val column = pill.column
        val wanted = if (pill.mine) column.left - pill.width - gap else column.right + gap
        val x = wanted.coerceIn(0, maxOf(0, host.width - pill.width))
        val y = maxOf(column.top, column.top + pill.bubble.bottom - pill.height)
        pill.translationX = (x - pill.left).toFloat()
        pill.translationY = (y - pill.top).toFloat()
    }

    override fun onTap(row: Any, action: () -> Unit) = onTap(row, false, action)

    override fun onTap(row: Any, mine: Boolean, action: () -> Unit) {
        val pill = (row as? View)?.findViewWithTag<Pill>(TAG) ?: return
        if (pill.mine != mine) {
            pill.mine = mine
            pill.post { place(pill) }
        }
        var lastFired = 0L
        pill.setOnClickListener { view ->
            val now = SystemClock.uptimeMillis()
            if (now - lastFired < DEBOUNCE_MS) return@setOnClickListener
            lastFired = now
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            view.animate().cancel()
            view.animate().scaleX(PULSE).scaleY(PULSE).setDuration(PULSE_MS).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(PULSE_MS).start()
            }.start()
            action()
        }
    }

    private fun pill(column: LinearLayout, bubble: View) = Pill(column, bubble).apply {
        tag = TAG
        text = LABEL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(TEXT_COLOR)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minHeight = dp(HEIGHT_DP)
        minWidth = dp(MIN_WIDTH_DP)
        setPadding(dp(PAD_H_DP), dp(PAD_V_DP), dp(PAD_H_DP), dp(PAD_V_DP))
        isClickable = true
        isFocusable = false
        // The label is a symbol, so give accessibility a word to read.
        contentDescription = strings.get(context, R.string.host_repeat_mark_description)
        val shape = { color: Int, stroke: Int ->
            GradientDrawable().apply {
                cornerRadius = dp(HEIGHT_DP).toFloat()
                setColor(color)
                if (stroke != 0) setStroke(dp(1f), stroke)
            }
        }
        background = RippleDrawable(ColorStateList.valueOf(RIPPLE), shape(FILL, OUTLINE), shape(Color.WHITE, 0))
    }

    private fun View.dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun descendants(root: View): List<View> {
        val found = ArrayList<View>()
        fun walk(view: View) {
            found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
        return found
    }

    // The box, with the column it sits beside and the bubble in it, and the side it was last bound for.
    private class Pill(val column: LinearLayout, val bubble: View) : TextView(column.context) {
        var mine = false
    }

    private companion object {
        const val TAG = "nuke-repeat"
        const val LABEL = "+1"
        const val TEXT_SP = 13f
        const val HEIGHT_DP = 26f
        const val MIN_WIDTH_DP = 40f
        const val PAD_H_DP = 12f
        const val PAD_V_DP = 3f
        const val GAP_DP = 6f
        const val PULSE = 1.18f
        const val PULSE_MS = 90L
        const val DEBOUNCE_MS = 800L
        // Neutral greys with some transparency, so the pill follows a light or a dark chat background.
        val TEXT_COLOR = Color.argb(0xCC, 0x80, 0x80, 0x80)
        val FILL = Color.argb(0x24, 0x80, 0x80, 0x80)
        val OUTLINE = Color.argb(0x4D, 0x80, 0x80, 0x80)
        val RIPPLE = Color.argb(0x33, 0x80, 0x80, 0x80)
    }
}
