package io.github.nuke.oss.app.tiktok.injector

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.nuke.oss.app.shared.activity.ResumedActivity
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanNode
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanPlan
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanScreen
import io.github.nuke.oss.runtime.FeatureScope
import java.lang.ref.WeakReference

private const val CONSTRAINT = "androidx.constraintlayout.widget.ConstraintLayout"

// Carries the plan out on the main thread. Only weak references to the Views are kept, with the look to bring back.
internal class AndroidCleanScreen : CleanScreen {
    private val activities = ResumedActivity()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private class Faded(val view: WeakReference<View>, val alpha: Float)
    private var faded = emptyList<Faded>()
    private var gone = emptyList<WeakReference<View>>()
    private var transparent = false

    override fun install(scope: FeatureScope, featureId: String) {
        activities.install(scope, featureId)
        scope.own(AutoCloseable { main.post { restoreNow() } })
    }

    override fun snapshot(): CleanNode? {
        val root = activities.get()?.window?.decorView?.rootView ?: return null
        return snapshotOf(root)
    }

    override fun hide(plan: CleanPlan, keepClickable: Boolean) {
        main.post {
            restoreNow()
            transparent = keepClickable
            faded = plan.fade.filterIsInstance<View>().map { Faded(WeakReference(it), it.alpha) }
            gone = plan.gone.filterIsInstance<View>().map { WeakReference(it) }
            for (entry in faded) entry.view.get()?.let { if (keepClickable) it.alpha = 0f else it.visibility = View.GONE }
            for (entry in gone) entry.get()?.visibility = View.GONE
        }
    }

    override fun restore() { main.post { restoreNow() } }

    private fun restoreNow() {
        for (entry in faded) entry.view.get()?.let { if (transparent) it.alpha = entry.alpha else it.visibility = View.VISIBLE }
        for (entry in gone) entry.get()?.visibility = View.VISIBLE
        faded = emptyList()
        gone = emptyList()
    }

}

private fun idNameOf(view: View): String? =
    if (view.id == View.NO_ID) null else try { view.resources.getResourceEntryName(view.id) } catch (_: android.content.res.Resources.NotFoundException) { null }

// Takes a snapshot of a View and everything below it, for the rules in CleanRules to run on.
internal fun snapshotOf(view: View): CleanNode {
    val kind = when (view) {
        is SeekBar -> CleanNode.Kind.SEEK_BAR
        is EditText -> CleanNode.Kind.EDIT_TEXT
        is TextView -> CleanNode.Kind.TEXT
        is LinearLayout -> CleanNode.Kind.LINEAR
        is FrameLayout -> CleanNode.Kind.FRAME
        is RelativeLayout -> CleanNode.Kind.RELATIVE
        else -> if (view.javaClass.name == CONSTRAINT) CleanNode.Kind.CONSTRAINT else CleanNode.Kind.OTHER
    }
    val node = CleanNode(
        view.javaClass.simpleName, view.javaClass.name, (view as? TextView)?.text?.toString(),
        (view as? EditText)?.hint?.toString(), kind, view, idNameOf(view),
    )
    if (view is ViewGroup) for (index in 0 until view.childCount) node.add(snapshotOf(view.getChildAt(index)))
    return node
}
