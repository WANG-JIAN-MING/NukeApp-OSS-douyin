package io.github.nuke.oss.app.tiktok.injector

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.nuke.oss.app.tiktok.feature.feed.BottomInfoLabels
import java.lang.ref.WeakReference
import java.util.WeakHashMap

// Adds one small TextView to each intro layout, once its description View exists, and keeps only weak references.
// All View work runs on the main thread.
internal class AndroidBottomInfoLabels : BottomInfoLabels {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val labels = WeakHashMap<ViewGroup, WeakReference<TextView>>()
    @Volatile private var lastText = ""

    override fun attach(layout: Any) {
        val group = layout as? ViewGroup ?: return
        main.post { tryAttach(group, ATTEMPTS) }
    }

    private fun tryAttach(group: ViewGroup, attemptsLeft: Int) {
        if (labels[group]?.get() != null) return
        val resources = group.context.resources
        val descId = resources.getIdentifier("desc", "id", group.context.packageName)
        if (descId != 0 && group.findViewById<View>(descId) == null) {
            if (attemptsLeft > 0) main.postDelayed({ tryAttach(group, attemptsLeft - 1) }, RETRY_MS)
            return
        }
        val label = group.findViewWithTag<TextView>(TAG) ?: TextView(group.context).apply {
            tag = TAG
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.argb(0xCC, 0xFF, 0xFF, 0xFF))
            setPadding(PADDING_START, 0, 0, 0)
            group.addView(this)
        }
        label.text = lastText
        label.visibility = if (lastText.isBlank()) View.GONE else View.VISIBLE
        labels[group] = WeakReference(label)
    }

    override fun show(text: String) {
        lastText = text
        main.post {
            for ((group, label) in labels.entries.toList()) {
                val view = label.get() ?: continue
                // An empty line would still take a line of height, so it is hidden instead.
                view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                if (group.isShown && view.text.toString() != text) view.text = text
            }
        }
    }

    private companion object {
        const val TAG = "nuke-video-info"
        const val ATTEMPTS = 20
        const val RETRY_MS = 100L
        const val PADDING_START = 33
    }
}
