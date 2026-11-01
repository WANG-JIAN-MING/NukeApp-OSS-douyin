package io.github.nuke.oss.app.tiktok.injector

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import io.github.nuke.oss.app.tiktok.feature.playback.ProgressLabel
import java.lang.ref.WeakReference

// Adds one small TextView next to the bar in its parent, and reuses it afterwards. Only
// weak references are kept, and all View work runs on the main thread.
internal class AndroidProgressLabel : ProgressLabel {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var bar: WeakReference<SeekBar>? = null
    @Volatile private var label: WeakReference<TextView>? = null

    override fun attach(seekBar: Any) {
        val view = seekBar as? SeekBar ?: return
        if (bar?.get() === view) return
        main.post {
            if (view.x + view.y == 0f) return@post
            val parent = view.parent as? ViewGroup ?: return@post
            val existing = parent.findViewWithTag<TextView>(TAG)
            val added = existing ?: TextView(view.context).apply {
                tag = TAG
                text = "0:00/0:00"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                setSingleLine(true)
                setTextColor(Color.parseColor("#CBCBCB"))
                x = view.x + 25
                y = view.y + 30
                parent.addView(this)
            }
            bar = WeakReference(view)
            label = WeakReference(added)
        }
    }

    override fun show(text: String) {
        val view = label?.get() ?: return
        main.post { if (view.text.toString() != text) view.text = text }
    }

    private companion object {
        const val TAG = "nuke-progress-time"
    }
}
