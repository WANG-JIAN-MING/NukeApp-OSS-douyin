package io.github.nuke.oss.ui.init

import android.app.Activity
import android.app.Instrumentation
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.app.shared.activity.ModulePackage
import io.github.nuke.oss.app.shared.activity.ModuleResourcesContext
import io.github.nuke.oss.app.shared.activity.restartHost
import io.github.nuke.oss.app.shared.init.HostInit
import io.github.nuke.oss.app.shared.init.InitPresenter
import io.github.nuke.oss.app.shared.init.InitStage
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.ui.overlay.HostOverlay
import java.util.concurrent.atomic.AtomicBoolean

// Puts the init dialog on whichever host Activity is resumed, following Instrumentation's resume and pause calls the
// way NukeCore follows the host's first Activity. Douyin's splash finishes quickly, so the dialog moves to the next
// Activity instead of staying with the first; its state lives in HostInit, never in the view.
// Main thread only, apart from the hooks, which just post.
internal class InitDialogPresenter(private val build: BuildIdentity, private val logger: Logger) : InitPresenter {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val restarting = AtomicBoolean()
    @Volatile private var installed = false
    private var shown: Shown? = null

    private class Shown(val activity: Activity, val overlay: HostOverlay)

    override fun install(scope: FeatureScope, init: HostInit) {
        val type = Instrumentation::class.resolve()
        scope.hooks.install(type.firstMethod { name = "callActivityOnResume"; parameters(Activity::class) }.self, HookSpec(
            id = "${init.id}:ActivityResumed",
            after = { call -> (call.argument(0) as? Activity)?.let { activity -> main.post { attach(activity, init) } } },
        ))
        scope.hooks.install(type.firstMethod { name = "callActivityOnPause"; parameters(Activity::class) }.self, HookSpec(
            id = "${init.id}:ActivityPaused",
            before = { call ->
                val activity = call.argument(0)
                main.post { if (shown?.activity === activity) detach() }
            },
        ))
        installed = true
        scope.own(AutoCloseable {
            installed = false
            main.post(::detach)
        })
    }

    private fun attach(activity: Activity, init: HostInit) {
        if (!installed || activity.isFinishing || activity.isDestroyed || shown?.activity === activity) return
        detach()
        if (init.state.value.stage == InitStage.Closed) return
        val module = when (val opened = ModulePackage.open(activity, build)) {
            is ModulePackage.Opened.Ready -> ModuleResourcesContext.create(activity, build.moduleId, MODULE_THEME)
            is ModulePackage.Opened.Unavailable -> return init.continueWithoutUi(opened.reason)
        } ?: return init.continueWithoutUi("module resources are not accessible from the host")
        val overlay = HostOverlay.attach(activity, module) {
            val state by init.state.collectAsStateWithLifecycle()
            InitDialog(
                state = state,
                onLanguage = init::setLanguage,
                onBugReports = init::setBugReports,
                onContinue = init::confirm,
                onRetry = init::retry,
                onClose = init::close,
                onRestart = { restart(activity, init.id) },
            )
        } ?: return logger.log("feature=${init.id} init_dialog_skipped no content view in ${activity.javaClass.name}")
        shown = Shown(activity, overlay)
    }

    private fun detach() {
        val current = shown ?: return
        shown = null
        current.overlay.close()
    }

    private fun restart(activity: Activity, featureId: String) {
        if (!restarting.compareAndSet(false, true)) return
        logger.log("feature=$featureId init_restart")
        restartHost(activity) { error -> logger.log("feature=$featureId init_relaunch_failed $error") }
    }

    private companion object {
        const val MODULE_THEME = android.R.style.Theme_Material_Light_NoActionBar
    }
}
