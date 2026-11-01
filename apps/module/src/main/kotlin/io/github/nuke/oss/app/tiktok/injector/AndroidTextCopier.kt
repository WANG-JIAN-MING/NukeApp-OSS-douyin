package io.github.nuke.oss.app.tiktok.injector

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import io.github.nuke.oss.app.shared.activity.ResumedActivity
import io.github.nuke.oss.app.tiktok.feature.feed.TextCopier
import io.github.nuke.oss.runtime.FeatureScope
import java.util.WeakHashMap

// Copies through the clipboard of the Activity in front, so there is a Context even when no View is at hand. Android 13
// and later already confirm a copy themselves. Views that were made long-clickable are put back when the feature closes.
internal class AndroidTextCopier : TextCopier {
    private val activities = ResumedActivity()
    private val originals = WeakHashMap<View, Boolean>()

    override fun install(scope: FeatureScope, featureId: String) {
        activities.install(scope, featureId)
        scope.own(AutoCloseable {
            val views = synchronized(originals) { originals.entries.map { it.key to it.value }.also { originals.clear() } }
            views.forEach { (view, original) -> view.post { view.isLongClickable = original } }
        })
    }

    override fun copy(text: CharSequence?): Boolean {
        if (text.isNullOrBlank()) return false
        val activity = activities.get() ?: return false
        val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text.toString()))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(activity.applicationContext, android.R.string.copy, Toast.LENGTH_SHORT).show()
        return true
    }

    override fun makeLongClickable(view: Any) {
        val target = view as? View ?: return
        synchronized(originals) { if (!originals.containsKey(target)) originals[target] = target.isLongClickable }
        target.isLongClickable = true
    }

    override fun confirmLongPress(view: Any) {
        val target = view as? View ?: return
        target.parent?.requestDisallowInterceptTouchEvent(true)
        target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
