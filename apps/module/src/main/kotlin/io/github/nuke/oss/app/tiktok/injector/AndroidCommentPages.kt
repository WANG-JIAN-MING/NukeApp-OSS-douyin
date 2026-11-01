package io.github.nuke.oss.app.tiktok.injector

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import io.github.nuke.oss.app.tiktok.feature.comment.CheckSentComments
import io.github.nuke.oss.app.tiktok.feature.comment.CommentPages
import io.github.nuke.oss.core.Logger

// Opens the host's post detail page with its comment list at one comment, using the extras the host's own pages pass.
internal class AndroidCommentPages(private val logger: Logger) : CommentPages {
    override fun open(activity: Any, awemeId: String, cid: String) {
        val host = activity as? Activity ?: return
        if (host.isFinishing || host.isDestroyed) return
        val extras = Bundle().apply {
            putInt("comment_list_tab", 1)
            putString("id", awemeId)
            putString("cid", cid)
            putString("eventType", "click")
            putString("from", "hook")
            putString("enterFrom", "hook")
        }
        val intent = Intent().setClassName(host.packageName, DETAIL).putExtras(extras).setPackage(host.packageName)
        try {
            host.startActivity(intent)
        } catch (failure: ActivityNotFoundException) {
            logger.log("feature=${CheckSentComments.ID} open_failed ${failure.javaClass.simpleName}")
        } catch (failure: SecurityException) {
            logger.log("feature=${CheckSentComments.ID} open_failed ${failure.javaClass.simpleName}")
        }
    }

    private companion object {
        const val DETAIL = "com.ss.android.ugc.aweme.detail.ui.DetailActivity"
    }
}
