package io.github.nuke.oss.app.tiktok.injector

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import io.github.nuke.oss.app.tiktok.feature.chat.PreventMessageRecall
import io.github.nuke.oss.app.tiktok.feature.chat.RecallMarks
import java.util.WeakHashMap

// Marks the bubble area of a chat row, the host's DrawChildOptConstraintLayout, with a rounded outline or tint. The
// background the host had is remembered per View and put back when the row is bound to another message. Main thread only.
internal class AndroidRecallMarks : RecallMarks {
    private val originals = WeakHashMap<View, Original>()

    override fun mark(row: Any, style: String) {
        val area = bubbleArea(row) ?: return
        originals.getOrPut(area) { Original(area.background) }
        val density = area.resources.displayMetrics.density
        area.background = GradientDrawable().apply {
            cornerRadius = RADIUS_DP * density
            if (style == PreventMessageRecall.TINT) setColor(TINT) else setStroke((STROKE_DP * density).toInt().coerceAtLeast(1), OUTLINE)
        }
    }

    override fun clear(row: Any) {
        val area = bubbleArea(row) ?: return
        val original = originals.remove(area) ?: return
        area.background = original.drawable
    }

    private fun bubbleArea(row: Any): View? {
        fun find(view: View): View? {
            if (view.javaClass.simpleName == AREA) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return find(row as? View ?: return null)
    }

    private class Original(val drawable: Drawable?)

    private companion object {
        const val AREA = "DrawChildOptConstraintLayout"
        const val RADIUS_DP = 10f
        const val STROKE_DP = 1.5f
        val OUTLINE = Color.argb(0xCC, 0xFF, 0x45, 0x45)
        val TINT = Color.argb(0x33, 0xFF, 0x45, 0x45)
    }
}
