package io.github.nuke.oss.app.shared.activity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper

// Relaunches the host on its own entry Activity and ends this process. The launch request reaches the system before
// the process ends, so the host comes back by itself; if that request fails the process still ends and the user opens
// the host again.
internal fun restartHost(activity: Activity, onLaunchFailed: (RuntimeException) -> Unit = {}) {
    val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
    try {
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)?.let(activity::startActivity)
    } catch (error: ActivityNotFoundException) {
        onLaunchFailed(error)
    } catch (error: SecurityException) {
        onLaunchFailed(error)
    }
    Handler(Looper.getMainLooper()).postDelayed({ processExit.exit() }, EXIT_DELAY_MILLIS)
}

private const val EXIT_DELAY_MILLIS = 300L
