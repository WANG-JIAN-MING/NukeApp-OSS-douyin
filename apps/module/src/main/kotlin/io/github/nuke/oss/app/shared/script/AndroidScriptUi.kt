package io.github.nuke.oss.app.shared.script

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.nuke.oss.app.shared.activity.ResumedActivity
import io.github.nuke.oss.script.ScriptException
import io.github.nuke.oss.script.module.ScriptUi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// nuke:ui on the host Activity in front. Dialogs are titled with the script's name and use the platform's own button
// labels unless the script names them; the script waits until the user dismisses one, and a session that stops while
// a dialog is open closes it.
internal class AndroidScriptUi(private val activities: ResumedActivity) : ScriptUi {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    override suspend fun toast(script: String, message: String, long: Boolean) = onMain { activity, done ->
        Toast.makeText(activity.applicationContext, "$script: $message", if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        done(Unit)
        null
    }

    override suspend fun alert(script: String, title: String?, message: String) = onMain<Unit> { activity, done ->
        AlertDialog.Builder(activity)
            .setTitle(titleOf(script, title))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { done(Unit) }
            .show()
    }

    override suspend fun confirm(script: String, title: String?, message: String, confirm: String?, cancel: String?): Boolean =
        onMain { activity, done ->
            var confirmed = false
            val builder = AlertDialog.Builder(activity)
                .setTitle(titleOf(script, title))
                .setMessage(message)
                .setOnDismissListener { done(confirmed) }
            if (confirm != null) builder.setPositiveButton(confirm) { _, _ -> confirmed = true }
            else builder.setPositiveButton(android.R.string.ok) { _, _ -> confirmed = true }
            if (cancel != null) builder.setNegativeButton(cancel, null) else builder.setNegativeButton(android.R.string.cancel, null)
            builder.show()
        }

    // Runs show on the main thread with the resumed Activity; it reports its answer through done once and returns the
    // dialog, if any, so a cancelled caller can close it.
    private suspend fun <T> onMain(show: (Activity, done: (T) -> Unit) -> AlertDialog?): T = suspendCancellableCoroutine { continuation ->
        main.post {
            val activity = activities.get()
            if (activity == null) {
                continuation.resumeWithException(ScriptException(ScriptException.UNAVAILABLE, "No host screen is in front"))
                return@post
            }
            var answered = false
            val dialog = show(activity) { value ->
                if (!answered) {
                    answered = true
                    if (continuation.isActive) continuation.resume(value)
                }
            }
            continuation.invokeOnCancellation { main.post { dialog?.dismiss() } }
        }
    }

    private fun titleOf(script: String, title: String?) = if (title.isNullOrBlank()) script else "$script · $title"
}
