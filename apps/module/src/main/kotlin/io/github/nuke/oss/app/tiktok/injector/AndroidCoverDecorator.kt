package io.github.nuke.oss.app.tiktok.injector

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.ModulePackage
import io.github.nuke.oss.app.tiktok.feature.feed.CoverDecorator
import io.github.nuke.oss.app.tiktok.feature.feed.CoverInfo
import io.github.nuke.oss.app.tiktok.feature.feed.CoverStat
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger

// Adds the lines under the like count the cover already shows. The cover's layout is found by
// the line whose text has "点赞" (like) as its content description. The cover is bound before its children exist, so
// the search repeats briefly. All View work runs on the main thread.
internal class AndroidCoverDecorator(private val build: BuildIdentity, private val logger: Logger) : CoverDecorator {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    override fun decorate(itemView: Any, createdAt: String?, stats: List<Pair<CoverStat, String>>, hideLikeCount: Boolean) {
        val root = itemView as? View ?: return
        main.post { attempt(root, createdAt, stats, hideLikeCount, ATTEMPTS) }
    }

    private fun attempt(root: View, createdAt: String?, stats: List<Pair<CoverStat, String>>, hide: Boolean, left: Int) {
        val line = findLikeLine(root)
        if (line == null) {
            if (left > 0) main.postDelayed({ attempt(root, createdAt, stats, hide, left - 1) }, RETRY_MS)
            return
        }
        val strings = when (val opened = ModulePackage.open(root.context, build)) {
            is ModulePackage.Opened.Ready -> opened.context
            is ModulePackage.Opened.Unavailable -> return logger.log("feature=${CoverInfo.ID} cover_skipped ${opened.reason}")
        }
        val (container, like) = line
        container.orientation = LinearLayout.VERTICAL
        if (hide) like.visibility = View.GONE
        if (createdAt != null) line(container, TIME_TAG).text = createdAt
        if (stats.isNotEmpty()) {
            val labels = mapOf(
                CoverStat.DIGG to R.string.host_cover_stat_digg, CoverStat.SHARE to R.string.host_cover_stat_share,
                CoverStat.COLLECT to R.string.host_cover_stat_collect, CoverStat.COMMENT to R.string.host_cover_stat_comment,
            )
            line(container, STATS_TAG).text = stats.joinToString(" ") { (stat, value) -> strings.getString(labels.getValue(stat), value) }
        }
    }

    private fun line(container: LinearLayout, tag: String): TextView =
        container.findViewWithTag<TextView>(tag) ?: TextView(container.context).apply {
            this.tag = tag
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(PADDING, 0, 0, 0)
            container.addView(this)
        }

    private fun findLikeLine(root: View): Pair<LinearLayout, TextView>? {
        if (root is LinearLayout) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child is TextView && child.contentDescription?.contains(LIKE_DESCRIPTION) == true) return root to child
            }
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) findLikeLine(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        const val LIKE_DESCRIPTION = "点赞"
        const val TIME_TAG = "nuke-cover-time"
        const val STATS_TAG = "nuke-cover-stats"
        const val ATTEMPTS = 50
        const val RETRY_MS = 20L
        const val TEXT_SP = 10f
        const val PADDING = 10
    }
}
