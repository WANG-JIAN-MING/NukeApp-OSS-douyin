package io.github.nuke.oss.app.tiktok.injector

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.github.nuke.oss.app.tiktok.feature.comment.CommentButtons
import io.github.nuke.oss.app.tiktok.feature.comment.commentButtonToHide

internal class AndroidCommentButtons : CommentButtons {
    override fun hide(row: Any, like: Boolean, dislike: Boolean) {
        val root = row as? View ?: return
        fun walk(view: View) {
            val parent = view.parent as? View
            if (parent != null && commentButtonToHide(view.contentDescription?.toString(), view is FrameLayout, view is LinearLayout, parent.javaClass.simpleName, like, dislike)) {
                parent.visibility = View.GONE
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
    }
}
