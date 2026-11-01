package io.github.nuke.oss.app.shared.init

import io.github.nuke.oss.app.shared.dex.AnalysisStep
import io.github.nuke.oss.app.shared.dex.DexAnalysis
import io.github.nuke.oss.app.shared.settings.LanguageChoice
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.app.shared.settings.UiConfigStore
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.util.concurrent.atomic.AtomicBoolean

// What the first-launch setup asked. A null answer means the user has not been asked yet.
interface SetupStore {
    fun bugReports(): Boolean?
    fun setBugReports(enabled: Boolean)
}

sealed interface InitStage {
    // Asked once, before the first analysis. Bug reports start off: sharing them is opt-in.
    data class Choices(val bugReports: Boolean) : InitStage
    // The step the search is on; done counts the targets looked up so far out of total.
    data class Analyzing(val step: AnalysisStep, val done: Int, val total: Int) : InitStage
    data class Failed(val reason: String) : InitStage
    // Every target has an outcome; the host restarts so features install from the cache.
    data class Restarting(val searched: Int, val failed: Int) : InitStage
    // Closed after a failure: the host runs without module features and the init runs again next launch.
    data object Closed : InitStage
}

data class InitState(val ui: UiConfig, val stage: InitStage)

// NukeCore's split of the analysis bar: the native library at 2%, the Dex environment at 38%, the targets filling
// 40% to 90%, and 100% once every target has an outcome. Null for a stage that has no bar of its own.
fun InitStage.progress(): Float? = when (this) {
    is InitStage.Analyzing -> when (step) {
        AnalysisStep.LOADING_NATIVE -> 0.02f
        AnalysisStep.OPENING_DEX -> 0.38f
        AnalysisStep.SEARCHING -> 0.40f + done.toFloat() / total.coerceAtLeast(1) * 0.50f
    }
    is InitStage.Restarting -> 1f
    else -> null
}

// Shows a host's init on the host Activity in front. It installs into the init feature's scope, so its hooks and
// any open dialog go away with the feature. Restarting the host is its job, because that needs an Activity.
internal fun interface InitPresenter {
    fun install(scope: FeatureScope, init: HostInit)
}

// A host's first launch after the host or the module changed, the way NukeCore runs it: the user's choices first
// when they were never asked, then one Dex analysis over every target with progress, then a host restart. The
// adapter registers no other feature for that launch, so nothing scans Dex while the host starts.
// Owns the state; the dialog only reads it and sends intents. Intents may come from any thread.
internal class HostInit(
    override val id: String,
    private val ui: UiConfigStore,
    private val setup: SetupStore,
    private val analyze: (onProgress: (step: AnalysisStep, done: Int, total: Int) -> Unit) -> DexAnalysis,
    private val presenter: InitPresenter,
    private val logger: Logger,
    private val background: (Runnable) -> Unit = { work -> Thread(work, "NukeInit").apply { isDaemon = true }.start() },
) : RuntimeFeature {
    private val running = AtomicBoolean()
    private val mutableState = MutableStateFlow(InitState(
        ui.read(),
        if (setup.bugReports() == null) InitStage.Choices(bugReports = false) else STARTING,
    ))
    val state: StateFlow<InitState> = mutableState.asStateFlow()

    // With the choices already answered, the analysis starts at once and the dialog shows its progress on arrival.
    override fun install(scope: FeatureScope) {
        presenter.install(scope, this)
        if (state.value.stage is InitStage.Analyzing) startAnalysis()
    }

    // Written straight away, so the dialog switches language while it is still open.
    fun setLanguage(language: LanguageChoice) {
        val updated = mutableState.updateAndGet { it.copy(ui = it.ui.copy(language = language)) }
        ui.write(updated.ui)
    }

    fun setBugReports(enabled: Boolean) = mutableState.update { current ->
        val stage = current.stage as? InitStage.Choices ?: return@update current
        current.copy(stage = stage.copy(bugReports = enabled))
    }

    fun confirm() {
        val stage = state.value.stage as? InitStage.Choices ?: return
        setup.setBugReports(stage.bugReports)
        logger.log("feature=$id init_choices language=${state.value.ui.language} bug_reports=${stage.bugReports}")
        startAnalysis()
    }

    // No dialog can be shown, so nothing is asked: the choices stay unanswered for a later init and the analysis
    // runs anyway, taking effect the next time the host starts. Past the choices there is nothing to stand in for.
    fun continueWithoutUi(reason: String) {
        if (state.value.stage !is InitStage.Choices) return
        logger.log("feature=$id init_ui_unavailable $reason")
        startAnalysis()
    }

    fun retry() {
        if (state.value.stage is InitStage.Failed) startAnalysis()
    }

    fun close() = mutableState.update { current ->
        if (current.stage is InitStage.Failed) current.copy(stage = InitStage.Closed) else current
    }

    private fun startAnalysis() {
        if (!running.compareAndSet(false, true)) return
        setStage(STARTING)
        background(Runnable {
            try {
                val analysis = analyze { step, done, total -> setStage(InitStage.Analyzing(step, done, total)) }
                val failed = analysis.failures.keys
                logger.log("feature=$id init_analyzed searched=${analysis.searched} failed=${failed.size} " +
                    failed.take(MAX_LOGGED_FAILURES).joinToString(","))
                setStage(InitStage.Restarting(analysis.searched, failed.size))
            } catch (error: Exception) {
                fail(error)
            } catch (error: LinkageError) {
                fail(error)
            } finally {
                running.set(false)
            }
        })
    }

    private fun fail(error: Throwable) {
        logger.log("feature=$id init_failed $error")
        setStage(InitStage.Failed(error.toString()))
    }

    private fun setStage(stage: InitStage) = mutableState.update { it.copy(stage = stage) }

    private companion object {
        const val MAX_LOGGED_FAILURES = 20
        val STARTING = InitStage.Analyzing(AnalysisStep.LOADING_NATIVE, 0, 0)
    }
}
