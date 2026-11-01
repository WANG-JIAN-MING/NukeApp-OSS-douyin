package io.github.nuke.oss.app.tiktok.injector

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.ResumedActivity
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.tiktok.feature.chat.RecallNotice
import io.github.nuke.oss.app.tiktok.feature.chat.RecallNoticeUi
import io.github.nuke.oss.app.tiktok.sdk.ModuleStrings
import io.github.nuke.oss.runtime.FeatureScope
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

// Draws the "recall attempted" line in the overlay of a message row, so it never becomes a child the host's cell engine
// knows about. A row built from Flare gets its measured height extended instead of padding. The Activity in front is
// found through ResumedActivity, which the feature installs for the scan of rows already on screen.
internal class AndroidRecallNotice(private val strings: ModuleStrings, private val activities: ResumedActivity = ResumedActivity()) : RecallNoticeUi {
    override fun install(scope: FeatureScope, featureId: String) = activities.install(scope, featureId)

    override fun itemRoot(holder: Any): Any? =
        holder.asResolver().optional(silent = true).firstFieldOrNull { name = "itemView"; superclass() }?.get() as? ViewGroup

    override fun show(root: Any, flare: Boolean): RecallNotice? {
        val group = root as? ViewGroup ?: return null
        if (!flare && group !is FrameLayout) return null
        val text = strings.get(group.context, R.string.host_recall_notice) ?: return null
        return Notice(group, text, flare)
    }

    override fun visibleRows(recycler: Class<*>, adapter: Class<*>): List<Pair<Any, Any>> {
        val activity = activities.get() ?: return emptyList()
        val content = activity.findViewById<View>(android.R.id.content) ?: return emptyList()
        val rows = ArrayList<Pair<Any, Any>>()
        val queue = ArrayDeque<View>().apply { add(content) }
        var scanned = 0
        while (queue.isNotEmpty() && scanned++ < MAX_SCANNED) {
            val view = queue.removeFirst() as? ViewGroup ?: continue
            if (recycler.isInstance(view)) {
                val list = view.callByName("getAdapter")
                if (list != null && adapter.isInstance(list)) {
                    for (index in 0 until view.childCount) {
                        val holder = view.callByName("getChildViewHolder", view.getChildAt(index)) ?: continue
                        rows += list to holder
                    }
                    continue
                }
            }
            for (index in 0 until view.childCount) queue.addLast(view.getChildAt(index))
        }
        return rows
    }

    private class Notice(root: ViewGroup, text: String, private val flare: Boolean) : RecallNotice {
        private val rootRef = WeakReference(root)
        private val originalBottomPadding = root.paddingBottom
        private val label: TextView
        private val extraHeight: Int
        private val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layout() }

        init {
            label = TextView(root.context).apply {
                this.text = text
                textSize = 11f
                gravity = Gravity.CENTER
                setSingleLine(true)
                isClickable = false
                isLongClickable = false
                isFocusable = false
                val inset = (resources.displayMetrics.density * 3).roundToInt()
                setPadding(inset, inset, inset, inset)
                val colors = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
                try { setTextColor(colors.getColorStateList(0) ?: ColorStateList.valueOf(FALLBACK_COLOR)) } finally { colors.recycle() }
                measure(
                    View.MeasureSpec.makeMeasureSpec(root.width.coerceAtLeast(resources.displayMetrics.widthPixels), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
            }
            extraHeight = label.measuredHeight
            try {
                root.overlay.add(label)
                if (!flare) root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, originalBottomPadding + extraHeight)
                root.addOnLayoutChangeListener(listener)
                root.requestLayout()
                layout()
            } catch (error: RuntimeException) {
                remove()
                throw error
            }
        }

        private fun layout() {
            val root = rootRef.get() ?: return
            val width = (root.width - root.paddingLeft - root.paddingRight).coerceAtLeast(0)
            label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(extraHeight, View.MeasureSpec.EXACTLY))
            val top = (root.height - originalBottomPadding - extraHeight).coerceAtLeast(0)
            label.layout(root.paddingLeft, top, root.width - root.paddingRight, top + extraHeight)
        }

        // Flare measures its own child rectangles and ignores padding; extending only the measured footer leaves every
        // native child where it is and gives the list the right row height.
        override fun extendMeasurement(root: Any) {
            val group = root as? ViewGroup ?: return
            if (!flare || rootRef.get() !== group) return
            val height = (group.measuredHeight + extraHeight).coerceAtMost(View.MEASURED_SIZE_MASK)
            group.callByName("setMeasuredDimension", group.measuredWidthAndState, height or (group.measuredHeightAndState and View.MEASURED_STATE_MASK))
        }

        override fun remove() {
            val root = rootRef.get() ?: return
            root.removeOnLayoutChangeListener(listener)
            root.overlay.remove(label)
            if (!flare && root.paddingBottom == originalBottomPadding + extraHeight) {
                root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, originalBottomPadding)
            }
            root.requestLayout()
            root.invalidate()
        }
    }

    private companion object {
        const val MAX_SCANNED = 4_096
        const val FALLBACK_COLOR = 0xff888888.toInt()
    }
}
