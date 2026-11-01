package io.github.nuke.oss.app.tiktok.injector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.ModulePackage
import io.github.nuke.oss.app.tiktok.feature.feed.AddAuthorInfoRow
import io.github.nuke.oss.app.tiktok.feature.feed.AuthorFullInfo
import io.github.nuke.oss.app.tiktok.feature.feed.AuthorInfo
import io.github.nuke.oss.app.tiktok.feature.feed.AuthorRow
import io.github.nuke.oss.app.tiktok.feature.feed.authorSummary
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger

// The header's first rounded block sits in a LinearLayout; the row becomes that layout's first child. All View work
// runs on the main thread, and the copy puts only the text the author's page already shows on the clipboard.
internal class AndroidAuthorRow(private val build: BuildIdentity, private val logger: Logger) : AuthorRow {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    override fun attach(root: Any, info: AuthorInfo, full: AuthorFullInfo?) {
        val view = root as? View ?: return
        main.post { attempt(view, info, full, ATTEMPTS) }
    }

    private fun attempt(root: View, info: AuthorInfo, full: AuthorFullInfo?, left: Int) {
        // The profile header passes the container of its info area, which is a LinearLayout already; any other root is a
        // header whose first rounded block shows once it has grown.
        val anchor = descendants(root).takeIf { it.size >= MIN_VIEWS }?.firstOrNull { it.javaClass.simpleName == BLOCK }
        val column = (anchor?.parent ?: root) as? LinearLayout
        if (column == null) {
            if (left > 0) main.postDelayed({ attempt(root, info, full, left - 1) }, RETRY_MS)
            else logger.log("feature=${AddAuthorInfoRow.ID} row_skipped header never grew")
            return
        }
        column.findViewWithTag<View>(TAG)?.let(column::removeView)
        val strings = when (val opened = ModulePackage.open(column.context, build)) {
            is ModulePackage.Opened.Ready -> opened.context
            is ModulePackage.Opened.Unavailable -> return logger.log("feature=${AddAuthorInfoRow.ID} row_skipped ${opened.reason}")
        }
        val row = LinearLayout(column.context).apply {
            tag = TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(PADDING, PADDING, PADDING, PADDING)
            addView(button(column.context, strings.getString(R.string.host_author_copy_uid)) { copy(column.context, info.uid, strings) })
            addView(button(column.context, strings.getString(R.string.host_author_copy_info)) { copy(column.context, authorSummary(info), strings) })
            if (full != null) {
                addView(button(column.context, strings.getString(R.string.host_author_copy_all)) {
                    full.json()?.let { copy(column.context, it, strings) } ?: unavailable(column.context, strings)
                })
                addView(button(column.context, strings.getString(R.string.host_author_save_all)) {
                    full.json()?.let { full.save(column.context, it) } ?: unavailable(column.context, strings)
                })
            }
        }
        column.addView(row, 0)
    }

    private fun button(context: Context, label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        setPadding(PADDING, PADDING / 2, PADDING, PADDING / 2)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun copy(context: Context, text: String, strings: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        Toast.makeText(context.applicationContext, strings.getString(R.string.host_author_copied), Toast.LENGTH_SHORT).show()
    }

    private fun unavailable(context: Context, strings: Context) =
        Toast.makeText(context.applicationContext, strings.getString(R.string.host_author_unavailable), Toast.LENGTH_SHORT).show()

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
        const val TAG = "nuke-author-row"
        const val BLOCK = "RoundedLinearLayout"
        const val ATTEMPTS = 50
        const val RETRY_MS = 100L
        const val MIN_VIEWS = 6
        const val PADDING = 24
        const val TEXT_SP = 13f
    }
}
