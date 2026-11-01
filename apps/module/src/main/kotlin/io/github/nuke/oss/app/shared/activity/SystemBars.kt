package io.github.nuke.oss.app.shared.activity

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import io.github.nuke.oss.runtime.FeatureScope

// Shows or hides the status bar of the host Activity in front.
internal interface SystemBars {
    fun install(scope: FeatureScope, featureId: String)
    fun setStatusBarVisible(visible: Boolean)

    // Draws the video behind the navigation bar, which gets no background of its own.
    fun setNavigationBarTransparent()
}

internal class AndroidSystemBars : SystemBars {
    private val activities = ResumedActivity()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var installed = false

    override fun install(scope: FeatureScope, featureId: String) {
        activities.install(scope, featureId)
        installed = true
        scope.own(AutoCloseable { installed = false })
    }

    override fun setStatusBarVisible(visible: Boolean) {
        main.post {
            val activity = activities.get()
            if (installed && activity != null) apply(activity, visible)
        }
    }

    override fun setNavigationBarTransparent() {
        main.post {
            val activity = activities.get()
            if (installed && activity != null) makeNavigationBarTransparent(activity)
        }
    }

    @Suppress("DEPRECATION")
    private fun makeNavigationBarTransparent(activity: Activity) {
        val window = activity.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or LEGACY_EDGE_TO_EDGE
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    // A hidden status bar comes back on a swipe from the edge.
    @Suppress("DEPRECATION")
    private fun apply(activity: Activity, visible: Boolean) {
        val window = activity.window
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.systemUiVisibility = if (visible) 0 else LEGACY_HIDDEN
            return
        }
        val controller = window.insetsController ?: return
        if (visible) {
            controller.show(WindowInsets.Type.statusBars())
        } else {
            controller.hide(WindowInsets.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        @Suppress("DEPRECATION")
        const val LEGACY_EDGE_TO_EDGE = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        @Suppress("DEPRECATION")
        const val LEGACY_HIDDEN = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}
