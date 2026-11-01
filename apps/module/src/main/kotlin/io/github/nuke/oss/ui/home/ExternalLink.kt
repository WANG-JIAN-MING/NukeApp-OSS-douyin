package io.github.nuke.oss.ui.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri

// Opens a web address in the system browser rather than in whatever app claims the link, which is where the old module
// sent its links too. Returns false when nothing could take it, so the caller can say so.
internal fun openInBrowser(context: Context, url: String): Boolean {
    val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).apply {
        data = Uri.parse(url)
        // The settings screen runs in the host, whose Context may not be an Activity.
        if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

internal const val TELEGRAM_CHANNEL_URL = "https://t.me/MikoCIBuilds"
