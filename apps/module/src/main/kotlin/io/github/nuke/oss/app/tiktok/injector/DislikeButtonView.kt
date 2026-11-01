package io.github.nuke.oss.app.tiktok.injector

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.ModulePackage
import io.github.nuke.oss.app.tiktok.feature.feed.AddDislikeButton
import io.github.nuke.oss.app.tiktok.feature.feed.DislikeButton
import io.github.nuke.oss.app.tiktok.feature.feed.DislikePlacement
import io.github.nuke.oss.app.tiktok.feature.feed.dislikeSlot
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger

// Builds the dislike button from the module's own icon and label and inserts it into the host's right-hand column.
// The column is a LinearLayout whose children are made after its constructor, so the insert waits until the column
// has grown and one of the host's own round icons gives a size to match. All View work runs on the main thread.
internal class AndroidDislikeButton(private val build: BuildIdentity, private val logger: Logger) : DislikeButton {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    override fun attach(bar: Any, placement: DislikePlacement, onClick: (context: Any) -> Unit) {
        val column = bar as? LinearLayout ?: return logger.log("feature=${AddDislikeButton.ID} bar_skipped not a LinearLayout")
        main.post { attempt(column, placement, onClick, ATTEMPTS) }
    }

    private fun attempt(column: LinearLayout, placement: DislikePlacement, onClick: (Any) -> Unit, left: Int) {
        if (column.findViewWithTag<View>(TAG) != null) return
        val icon = if (descendants(column).size > MIN_VIEWS) iconSize(column) else null
        if (icon == null) {
            if (left > 0) main.postDelayed({ attempt(column, placement, onClick, left - 1) }, RETRY_MS)
            else logger.log("feature=${AddDislikeButton.ID} button_skipped column never grew")
            return
        }
        val module = when (val opened = ModulePackage.open(column.context, build)) {
            is ModulePackage.Opened.Ready -> opened.context
            is ModulePackage.Opened.Unavailable -> return logger.log("feature=${AddDislikeButton.ID} button_skipped ${opened.reason}")
        }
        val children = (0 until column.childCount).map { index -> descriptions(column.getChildAt(index)) }
        val slot = dislikeSlot(placement, children)
        val button = build(module, column.context, icon)
        button.setOnClickListener { onClick(column.context) }
        if (slot >= 0) column.addView(button, slot) else column.addView(button)
    }

    private fun build(module: Context, host: Context, icon: Pair<Int, Int>): View {
        val label = TextView(host).apply {
            text = module.getString(R.string.host_dislike_button)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
            setTextColor(WHITE)
            setPadding(0, LABEL_TOP, 0, 0)
        }
        val image = ImageView(host).apply {
            setImageDrawable(module.getDrawable(R.drawable.ic_host_dislike))
            setPadding(icon.first / 4, icon.second / 4, icon.first / 4, icon.second / 4)
            layoutParams = LinearLayout.LayoutParams(icon.first, icon.second)
        }
        return LinearLayout(host).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = label.text
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, MARGIN, 0, MARGIN) }
            addView(image)
            addView(label)
        }
    }

    // The size of the host's like, comment or share icon, which the button copies.
    private fun iconSize(column: View): Pair<Int, Int>? = descendants(column)
        .filterIsInstance<ImageView>()
        .firstOrNull { it.javaClass.simpleName in ICONS && it.width > 0 && it.height > 0 }
        ?.let { it.width to it.height }

    private fun descriptions(view: View): List<String> =
        descendants(view).mapNotNull { it.contentDescription?.toString() }

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
        const val TAG = "nuke-dislike"
        val ICONS = setOf("DiggAnimationView", "FeedCommentImageView", "FeedShareImageView")
        const val ATTEMPTS = 20
        const val RETRY_MS = 100L
        const val MIN_VIEWS = 20
        const val LABEL_SP = 11.7f
        const val LABEL_TOP = 5
        const val MARGIN = 10
        const val WHITE = -1
    }
}
