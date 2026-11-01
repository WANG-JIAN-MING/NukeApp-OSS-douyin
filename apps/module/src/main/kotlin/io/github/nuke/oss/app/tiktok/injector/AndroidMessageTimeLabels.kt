package io.github.nuke.oss.app.tiktok.injector

import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nuke.oss.app.tiktok.feature.chat.MessageTimeLabels

// Adds the label under the bubble: the host's bubble sits in a FixWidthFrameLayout inside a LinearLayout, and the label
// becomes that LinearLayout's last child. Rows without that shape are left alone. All View work runs on the main thread.
internal class AndroidMessageTimeLabels : MessageTimeLabels {
    override fun prepare(row: Any, color: Int?) {
        val bubble = descendants(row as? View ?: return).firstOrNull { it.javaClass.simpleName == BUBBLE } ?: return
        val column = bubble.parent as? LinearLayout ?: return
        if (column.findViewWithTag<View>(TAG) != null) return
        column.addView(TextView(column.context).apply {
            tag = TAG
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            setTextColor(color ?: Color.GRAY)
        })
    }

    override fun show(row: Any, text: String, mine: Boolean) {
        val label = (row as? View)?.findViewWithTag<TextView>(TAG) ?: return
        label.text = text
        label.gravity = if (mine) Gravity.END else Gravity.START
    }

    private fun descendants(root: View): List<View> {
        val found = ArrayList<View>()
        fun walk(view: View) {
            found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
        return found
    }

    private companion object {
        const val TAG = "nuke-message-time"
        const val BUBBLE = "FixWidthFrameLayout"
        const val TEXT_SP = 11f
    }
}
