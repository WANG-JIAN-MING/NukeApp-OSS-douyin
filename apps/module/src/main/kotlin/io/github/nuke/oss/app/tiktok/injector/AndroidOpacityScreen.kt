package io.github.nuke.oss.app.tiktok.injector

import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.nuke.oss.app.shared.activity.ResumedActivity
import io.github.nuke.oss.app.tiktok.feature.screen.OpacityScreen
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanNode
import io.github.nuke.oss.runtime.FeatureScope
import java.lang.ref.WeakReference

internal class AndroidOpacityScreen : OpacityScreen {
    private val activities = ResumedActivity()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val originals = ArrayList<Pair<WeakReference<View>, Float>>()

    override fun install(scope: FeatureScope, featureId: String) {
        activities.install(scope, featureId)
        scope.own(AutoCloseable { restore() })
    }

    override fun snapshot(): CleanNode? = activities.get()?.window?.decorView?.rootView?.let(::snapshotOf)

    override fun apply(views: List<Any>, alpha: Float) {
        main.post {
            for (view in views.filterIsInstance<View>()) {
                if (originals.none { it.first.get() === view }) originals += WeakReference(view) to view.alpha
                view.alpha = alpha
            }
        }
    }

    override fun restore() {
        main.post {
            for ((view, alpha) in originals) view.get()?.alpha = alpha
            originals.clear()
        }
    }
}
