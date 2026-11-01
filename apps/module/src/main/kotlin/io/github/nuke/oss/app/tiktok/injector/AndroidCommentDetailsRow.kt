package io.github.nuke.oss.app.tiktok.injector

import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.ModulePackage
import io.github.nuke.oss.app.tiktok.feature.comment.CommentDetails
import io.github.nuke.oss.app.tiktok.feature.comment.CommentDetailsRow
import io.github.nuke.oss.app.tiktok.feature.comment.RowButton
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger
import java.lang.ref.WeakReference

// The menu's items sit in a LinearLayout whose first child is a plain FrameLayout; the row goes in
// right after that frame. The menu fills in after its scroll view is built, so the search retries briefly on the main
// thread and gives up quietly when the shape never appears.
internal class AndroidCommentDetailsRow(private val build: BuildIdentity, private val logger: Logger) : CommentDetailsRow {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    override fun attach(panel: Any, buttons: List<RowButton>) {
        val menu = WeakReference(panel as? View ?: return)
        main.post { attempt(menu, buttons, ATTEMPTS) }
    }

    private fun attempt(menu: WeakReference<View>, buttons: List<RowButton>, left: Int) {
        val root = menu.get() ?: return
        val column = frames(root).firstNotNullOfOrNull { it.parent as? LinearLayout }
        if (column == null) {
            if (left > 0) main.postDelayed({ attempt(menu, buttons, left - 1) }, RETRY_MS)
            return
        }
        if (column.findViewWithTag<View>(TAG) != null) return
        val strings = when (val opened = ModulePackage.open(column.context, build)) {
            is ModulePackage.Opened.Ready -> opened.context
            is ModulePackage.Opened.Unavailable -> return logger.log("feature=${CommentDetails.ID} row_skipped ${opened.reason}")
        }
        val padding = (PADDING_DP * column.resources.displayMetrics.density).toInt()
        val row = LinearLayout(column.context).apply {
            tag = TAG
            orientation = LinearLayout.HORIZONTAL
            for (button in buttons) addView(TextView(column.context).apply {
                text = strings.getString(button.label)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
                setPadding(padding, padding, padding, padding)
                isClickable = true
                setOnClickListener { button.onClick() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        column.addView(row, minOf(1, column.childCount), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // Plain FrameLayouts only: the host's own subclasses are item views, not the frame the row anchors on.
    private fun frames(root: View): List<View> {
        val found = ArrayList<View>()
        fun walk(view: View) {
            if (view.javaClass == FrameLayout::class.java) found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
        return found
    }

    private companion object {
        const val TAG = "nuke-comment-details"
        const val ATTEMPTS = 20
        const val RETRY_MS = 50L
        const val TEXT_SP = 15f
        const val PADDING_DP = 14f
    }
}
