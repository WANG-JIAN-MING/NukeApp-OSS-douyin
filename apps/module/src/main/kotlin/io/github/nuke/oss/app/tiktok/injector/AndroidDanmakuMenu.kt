package io.github.nuke.oss.app.tiktok.injector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.ModulePackage
import io.github.nuke.oss.app.tiktok.feature.playback.DanmakuMenu
import io.github.nuke.oss.app.tiktok.feature.playback.DanmakuMoreInfo
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger

internal class AndroidDanmakuMenu(private val build: BuildIdentity, private val logger: Logger) : DanmakuMenu {
    override fun attach(popover: Any, onClick: (context: Any) -> Unit) {
        // The host's popover is a PopupWindow, so its views hang off the content view; a View is accepted too.
        val root = (popover as? PopupWindow)?.contentView ?: popover as? View
            ?: return logger.log("feature=${DanmakuMoreInfo.ID} entry_skipped popover is ${popover.javaClass.name}, no content view")
        val views = descendants(root)
        val anchor = views.firstOrNull { it.javaClass.simpleName == "DuxTextView" }
            ?: return logger.log("feature=${DanmakuMoreInfo.ID} entry_skipped no DuxTextView among ${views.size} views")
        val row = anchor.parent as? ViewGroup ?: return logger.log("feature=${DanmakuMoreInfo.ID} entry_skipped the text view has no parent group")
        if (row.findViewWithTag<View>(TAG) != null) return
        val strings = when (val opened = ModulePackage.open(row.context, build)) {
            is ModulePackage.Opened.Ready -> opened.context
            is ModulePackage.Opened.Unavailable -> return logger.log("feature=${DanmakuMoreInfo.ID} entry_skipped ${opened.reason}")
        }
        val entry = TextView(row.context).apply {
            tag = TAG
            text = strings.getString(R.string.host_danmaku_more)
            gravity = Gravity.CENTER
            setTextColor(WHITE)
            setPadding(PADDING, 0, PADDING, 0)
            setOnClickListener { onClick(row.context) }
        }
        row.addView(entry, row.indexOfChild(anchor))
    }

    override fun copy(context: Any, text: String) {
        val host = context as? Context ?: return
        val clipboard = host.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        Toast.makeText(host.applicationContext, host.getString(android.R.string.copy), Toast.LENGTH_SHORT).show()
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
        const val TAG = "nuke-danmaku-more"
        const val PADDING = 15
        const val WHITE = -1
    }
}
