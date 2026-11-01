package io.github.nuke.oss.app.shared.init

import io.github.nuke.oss.app.shared.dex.AnalysisStep
import io.github.nuke.oss.app.shared.dex.DexAnalysis
import io.github.nuke.oss.app.shared.settings.LanguageChoice
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.app.shared.settings.UiConfigStore
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookFailureSink
import io.github.nuke.oss.hook.api.HookOwner
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.FeatureScope
import org.junit.Assert.*
import org.junit.Test

class HostInitTest {
    private var storedUi = UiConfig()
    private val ui = object : UiConfigStore {
        override val backgroundFile = java.io.File("unused-background")
        override fun read() = storedUi
        override fun write(config: UiConfig) { storedUi = config }
    }
    private var bugReports: Boolean? = null
    private val setup = object : SetupStore {
        override fun bugReports() = bugReports
        override fun setBugReports(enabled: Boolean) { bugReports = enabled }
    }
    private val logged = ArrayList<String>()
    private val queued = ArrayList<Runnable>()
    private var presented = 0
    private var analyses = 0
    private var outcome: () -> DexAnalysis = { DexAnalysis(3, mapOf("b" to "not located")) }

    private fun init() = HostInit(
        "host.init", ui, setup,
        analyze = { progress ->
            analyses++
            progress(AnalysisStep.SEARCHING, 0, 3)
            progress(AnalysisStep.SEARCHING, 2, 3)
            outcome()
        },
        presenter = { _, _ -> presented++ },
        logger = Logger { logged += it },
        background = { queued += it },
    )

    private fun HostInit.installed() = apply {
        install(FeatureScope(HookOwner(id, "g"), DefaultHookService(FakeHookBackend(), HookFailureSink {})))
    }

    private fun runQueued() {
        val work = queued.toList()
        queued.clear()
        work.forEach(Runnable::run)
    }

    @Test fun `a first setup asks the choices before any analysis with bug reports off`() {
        val init = init().installed()
        assertEquals(1, presented)
        assertEquals(InitStage.Choices(bugReports = false), init.state.value.stage)
        assertTrue(queued.isEmpty())
    }

    @Test fun `the language is stored at once and the choices on continue, which starts the analysis`() {
        val init = init().installed()
        init.setLanguage(LanguageChoice.CHINESE)
        assertEquals(LanguageChoice.CHINESE, storedUi.language)
        assertEquals(LanguageChoice.CHINESE, init.state.value.ui.language)
        init.setBugReports(true)
        assertNull(bugReports)
        init.confirm()
        assertEquals(true, bugReports)
        assertEquals(InitStage.Analyzing(AnalysisStep.LOADING_NATIVE, 0, 0), init.state.value.stage)
        runQueued()
        assertEquals(InitStage.Restarting(searched = 3, failed = 1), init.state.value.stage)
        assertEquals(1, analyses)
        assertTrue(logged.any { it.contains("init_analyzed searched=3 failed=1 b") })
    }

    @Test fun `progress is published while the analysis runs`() {
        bugReports = false
        val stages = ArrayList<InitStage>()
        lateinit var init: HostInit
        init = HostInit("host.init", ui, setup, analyze = { progress ->
            progress(AnalysisStep.SEARCHING, 1, 4)
            stages += init.state.value.stage
            DexAnalysis(4, emptyMap())
        }, presenter = { _, _ -> }, logger = Logger {}, background = { it.run() })
        init.installed()
        assertEquals(listOf<InitStage>(InitStage.Analyzing(AnalysisStep.SEARCHING, 1, 4)), stages)
        assertEquals(InitStage.Restarting(4, 0), init.state.value.stage)
    }

    @Test fun `the bar follows NukeCore's split of the analysis`() {
        assertEquals(0.02f, InitStage.Analyzing(AnalysisStep.LOADING_NATIVE, 0, 0).progress())
        assertEquals(0.38f, InitStage.Analyzing(AnalysisStep.OPENING_DEX, 0, 10).progress())
        assertEquals(0.40f, InitStage.Analyzing(AnalysisStep.SEARCHING, 0, 10).progress()!!, 0.0001f)
        assertEquals(0.65f, InitStage.Analyzing(AnalysisStep.SEARCHING, 5, 10).progress()!!, 0.0001f)
        assertEquals(0.90f, InitStage.Analyzing(AnalysisStep.SEARCHING, 10, 10).progress()!!, 0.0001f)
        assertEquals(1f, InitStage.Restarting(10, 0).progress())
        assertNull(InitStage.Failed("x").progress())
        assertNull(InitStage.Choices(false).progress())
    }

    @Test fun `answered choices skip straight to the analysis at install`() {
        bugReports = false
        val init = init().installed()
        assertEquals(InitStage.Analyzing(AnalysisStep.LOADING_NATIVE, 0, 0), init.state.value.stage)
        assertEquals(1, queued.size)
        init.setBugReports(true)
        init.confirm()
        assertEquals(1, queued.size)
        assertEquals(false, bugReports)
    }

    @Test fun `a failed analysis can be retried or closed`() {
        outcome = { throw UnsatisfiedLinkError("dexkit") }
        bugReports = false
        val init = init().installed()
        runQueued()
        val failed = init.state.value.stage as InitStage.Failed
        assertTrue(failed.reason.contains("dexkit"))
        outcome = { DexAnalysis(3, emptyMap()) }
        init.retry()
        runQueued()
        assertEquals(InitStage.Restarting(3, 0), init.state.value.stage)
        assertEquals(2, analyses)
        init.close()
        assertEquals(InitStage.Restarting(3, 0), init.state.value.stage)

        outcome = { error("broken") }
        val other = init().installed()
        runQueued()
        other.close()
        assertEquals(InitStage.Closed, other.state.value.stage)
        other.retry()
        assertTrue(queued.isEmpty())
    }

    @Test fun `without a dialog the analysis runs and the choices stay unanswered`() {
        val init = init().installed()
        init.continueWithoutUi("module package is not installed")
        init.continueWithoutUi("again")
        assertNull(bugReports)
        assertEquals(1, queued.size)
        assertEquals(1, logged.count { it.contains("init_ui_unavailable") })
        runQueued()
        init.continueWithoutUi("after the analysis")
        assertTrue(queued.isEmpty())
    }

    @Test fun `a second start while the analysis runs is ignored`() {
        val init = init().installed()
        init.confirm()
        init.confirm()
        init.continueWithoutUi("late")
        assertEquals(1, queued.size)
    }
}
