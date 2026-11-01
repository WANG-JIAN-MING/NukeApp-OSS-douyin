package io.github.nuke.oss.app.tiktok.injector

import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ListPopupWindow
import android.widget.TextView
import io.github.nuke.oss.app.tiktok.feature.chat.MessageTabMenu
import io.github.nuke.oss.app.tiktok.feature.chat.TabEntry
import io.github.nuke.oss.app.tiktok.feature.chat.TabMenu
import io.github.nuke.oss.app.tiktok.sdk.ModuleStrings
import io.github.nuke.oss.core.Logger
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

// A ListPopupWindow anchored on the message tab; it picks the side of the tab that has room. The marked views are held
// weakly and put back as they were when the feature closes. All of it runs on the main thread.
internal class AndroidTabMenu(private val strings: ModuleStrings, private val logger: Logger) : TabMenu {
    private class Mark(val tab: WeakReference<View>, val wasLongClickable: Boolean)

    private val marks = WeakHashMap<View, Mark>()
    private var popup: ListPopupWindow? = null

    override fun mark(tab: Any, content: Any) {
        val owner = tab as? View ?: return
        for (view in listOfNotNull(owner, content as? View).distinct()) {
            if (view !in marks) marks[view] = Mark(WeakReference(owner), view.isLongClickable)
            view.isLongClickable = true
        }
    }

    override fun tabOf(view: Any): Any? {
        val tab = marks[view as? View ?: return null]?.tab?.get() ?: return null
        return tab.takeIf { it.isAttachedToWindow && it.isShown }
    }

    override fun show(tab: Any, entries: List<TabEntry>): Boolean {
        val anchor = tab as? View ?: return false
        if (popup?.isShowing == true) return true
        // One missing text means the module package cannot be read from this host, so the host keeps the long press.
        val labels = entries.map { strings.get(anchor.context, it.label) ?: return false }
        val dark = anchor.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val density = anchor.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val frame = Rect().also(anchor::getWindowVisibleDisplayFrame)
        val width = minOf(dp(232), frame.width() - dp(16)).coerceAtLeast(1)
        val textColor = if (dark) WHITE else TEXT_ON_LIGHT
        val menu = ListPopupWindow(anchor.context)
        val detached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = menu.dismiss()
        }
        menu.apply {
            anchorView = anchor
            isModal = true
            inputMethodMode = ListPopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(if (dark) DARK_SURFACE else WHITE)
                cornerRadius = dp(14).toFloat()
            })
            setListSelector(ColorDrawable(SELECTOR))
            setContentWidth(width)
            horizontalOffset = (anchor.width - width) / 2
            setAdapter(object : BaseAdapter() {
                override fun getCount() = labels.size
                override fun getItem(position: Int) = labels[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    ((convertView as? TextView) ?: TextView(anchor.context).apply {
                        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        minHeight = dp(48)
                        gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        setPadding(dp(18), dp(12), dp(18), dp(12))
                        textSize = 15f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }).apply {
                        text = labels[position]
                        setTextColor(textColor)
                    }
            })
            setOnItemClickListener { _, _, position, _ ->
                dismiss()
                entries[position].run()
            }
            setOnDismissListener {
                anchor.removeOnAttachStateChangeListener(detached)
                if (popup === menu) popup = null
            }
        }
        anchor.addOnAttachStateChangeListener(detached)
        popup = menu
        try {
            menu.show()
        } catch (failure: WindowManager.BadTokenException) {
            logger.log("feature=${MessageTabMenu.ID} menu_failed ${failure.javaClass.simpleName}")
            menu.dismiss()
            return false
        }
        return menu.isShowing
    }

    override fun restore() {
        val undo = Runnable {
            for ((view, mark) in marks.entries.toList()) view.isLongClickable = mark.wasLongClickable
            marks.clear()
            popup?.dismiss()
            popup = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) undo.run() else Handler(Looper.getMainLooper()).post(undo)
    }

    private companion object {
        const val WHITE = -1
        const val TEXT_ON_LIGHT = 0xFF1F1F1F.toInt()
        const val DARK_SURFACE = 0xFF2B2B2B.toInt()
        const val SELECTOR = 0x1F808080
    }
}
