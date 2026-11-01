package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.query.TikTokQueries
import io.github.nuke.oss.app.shared.activity.HostPromptsFactory
import io.github.nuke.oss.app.shared.dex.DexCache
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexSearch
import io.github.nuke.oss.app.shared.diagnostics.FailurePhases
import io.github.nuke.oss.app.shared.diagnostics.FailureRecord
import io.github.nuke.oss.app.shared.diagnostics.MemoryDiagnosticsStore
import io.github.nuke.oss.app.shared.init.HostInit
import io.github.nuke.oss.app.shared.init.InitPresenter
import io.github.nuke.oss.app.shared.init.InitStage
import io.github.nuke.oss.app.shared.init.SetupStore
import io.github.nuke.oss.app.shared.settings.FeatureSwitches
import io.github.nuke.oss.app.shared.settings.MemoryOptionStore
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.app.shared.settings.UiConfigStore
import io.github.nuke.oss.app.tiktok.settings.SettingsEntry
import io.github.nuke.oss.assembly.HostScope
import io.github.nuke.oss.core.*
import io.github.nuke.oss.hook.api.HookFailureSink
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.*
import io.github.nuke.oss.ui.hostsettings.featureLabels
import org.junit.Assert.*
import org.junit.Test
import org.luckypray.dexkit.wrap.DexMethod
import java.util.concurrent.Executor

class TikTokHostAdapterTest {
    // Stands in for BaseListFragmentPanel: a void instance method the feature must be able to skip.
    class FakePanel {
        var taps = 0
        fun onDoubleTap(tag: String) { taps++ }
    }

    private val method = FakePanel::class.java.getDeclaredMethod("onDoubleTap", String::class.java)
    private val backend = FakeHookBackend()
    private val paths = HostPaths("/data/user/0/host", "/data/app/host/base.apk")
    private val event = LoadEvent(
        ProcessIdentity(1, 10001, HostScope.TIKTOK, HostScope.TIKTOK),
        BuildIdentity("io.github.nuke.oss", "build", "g"), "xposed82", LoadStage.HOST_CLASSLOADER_READY,
        FakePanel::class.java.classLoader, paths,
    )
    private val mirrorFile = java.io.File(java.nio.file.Files.createTempDirectory("nuke-mirror").toFile(), "process-mirror.json")
    private var searches = 0
    private val presented = ArrayList<HostInit>()
    private val presenter = { _: BuildIdentity, _: Logger -> InitPresenter { _, init -> presented += init } }
    private val prompts = HostPromptsFactory { _, _, _, _ -> RecordingPrompts() }
    private var bugReports: Boolean? = false
    private val diagnostics = MemoryDiagnosticsStore()
    private val hooks = DefaultHookService(backend, HookFailureSink {})
    private val manager = FeatureManager("g", hooks, Logger {})

    // Every query looks up the stand-in panel method unless lookup says otherwise; searches counts the adapter's own
    // searches. An empty cache starts as a launch after the init's analysis, with every query but the pending ones
    // already holding the outcome lookup gives it.
    private fun services(
        lookup: (String) -> Result<String> = { Result.success(DexMethod(method).serialize()) },
        enabled: (String, Boolean) -> Boolean = { _, default -> default },
        pending: List<DexQuery> = emptyList(),
        entries: MutableMap<String, String> = HashMap(),
    ) = TikTokHostAdapter({ _, _ ->
        val cache = object : DexCache {
            override fun get(key: String) = entries[key]
            override fun put(key: String, descriptor: String) { entries[key] = descriptor }
        }
        val answer = DexSearch { queries, _, onEach -> queries.keys.forEach { onEach(it, lookup(it)) } }
        if (entries.isEmpty()) {
            val analysed = generatedDexQueries.filterValues { query -> pending.none { it === query } }
            DexResolver(cache, "scope", answer, analysed).analyze { _, _, _ -> }
        }
        val search = DexSearch { queries, onStep, onEach -> searches++; answer.search(queries, onStep, onEach) }
        TikTokServices(DexResolver(cache, "scope", search, generatedDexQueries), object : FeatureSwitches {
            override fun read(featureId: String, default: Boolean) = enabled(featureId, default)
            override fun write(featureId: String, enabled: Boolean) = error("Registration never writes switches")
        }, object : UiConfigStore {
            override val backgroundFile = java.io.File("unused-background")
            override fun read() = UiConfig()
            override fun write(config: UiConfig) = error("Registration never writes the appearance")
        }, MemoryOptionStore(), io.github.nuke.oss.app.shared.settings.MemoryFeatureMemory(), object : SetupStore {
            override fun bugReports() = bugReports
            override fun setBugReports(enabled: Boolean) { bugReports = enabled }
        }, diagnostics, io.github.nuke.oss.app.shared.settings.ModuleConfig(
            "scope", io.github.nuke.oss.app.shared.settings.MemoryConfigValues(), { null }, forgetDex = {},
        ) {}, java.io.File("unused-streak"), io.github.nuke.oss.app.shared.process.ProcessMirror(mirrorFile), "scope",
            kotlin.io.path.createTempDirectory("scripts").toFile(),
            HashMap<String, String>().let { values ->
                io.github.nuke.oss.app.shared.script.KeyedScriptStates(values::get) { key, value ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            })
    }, presenter, prompts, Executor { it.run() })

    private fun register(adapter: TikTokHostAdapter, e: LoadEvent = event) =
        adapter.register(HostEnvironment(e, backend.id, Logger {}), manager)

    private fun state() = manager.snapshot().single { it.id == "DisableDoubleTapDigg" }

    @Test fun `descriptor identifies the scoped Douyin package`() {
        assertEquals(HostScope.TIKTOK, TikTokHostAdapter(presenter, prompts).descriptor.packageName)
        assertTrue(TikTokHostAdapter(presenter, prompts).descriptor.implemented)
    }

    @Test fun `enabled feature skips the double tap handler`() {
        register(services())
        val panel = FakePanel()
        assertEquals(FeatureState.ACTIVE, state().state)
        backend.call(method, panel, "x")
        assertEquals(0, panel.taps)
        manager.setEnabled("DisableDoubleTapDigg", false)
        backend.call(method, panel, "x")
        assertEquals(1, panel.taps)
        // Installed from the analysed cache; a launch after the analysis searches nothing.
        assertEquals(0, searches)
    }

    @Test fun `a switch stored as off leaves the feature registered but not installed`() {
        register(services(enabled = { _, _ -> false }))
        assertEquals(FeatureState.DISABLED, state().state)
        assertEquals(0, searches)
        val panel = FakePanel()
        backend.call(method, panel, "x")
        assertEquals(1, panel.taps)
    }

    @Test fun `features without a stored switch take their own default`() {
        register(services())
        val active = manager.snapshot().filter { it.state == FeatureState.ACTIVE }.map { it.id }
        assertEquals(listOf("DisableDoubleTapDigg"), active)
        val registered = manager.snapshot().map { it.id }
        assertEquals(registered.size, registered.toSet().size)
        assertTrue(registered.containsAll(listOf(
            "DoubleTapOpenComment", "PauseOnPlaybackCompleted", "BlockForegroundPlaybackResume",
            "HideFeedAvatarFollow", "DisableUpdateChecks", "ForceHighVideoQuality",
            "KeepFeedOnReturn", "SkipSplashAd", SettingsEntry.ID,
        )))
    }

    @Test fun `the settings entry is always attempted and fails explicitly without the host settings screen`() {
        register(services(enabled = { _, _ -> false }))
        val entry = manager.snapshot().single { it.id == SettingsEntry.ID }
        assertEquals(FeatureState.FAILED, entry.state)
        assertTrue(entry.reason!!.contains("settings Activity"))
    }

    @Test fun `every switchable feature has a label in the settings screen`() {
        register(services(enabled = { _, _ -> false }))
        val switchable = manager.snapshot().map { it.id } - SettingsEntry.ID
        assertEquals(switchable.toSet(), featureLabels.keys)
    }

    @Test fun `a query the analysis could not locate holds the features that need it before anything installs`() {
        register(services(lookup = { Result.failure(IllegalStateException("expected one method, found 0")) }))
        // The switch is on by default, yet the feature stays off and is recorded with the analysis's reason.
        assertEquals(FeatureState.DISABLED, state().state)
        val record = diagnostics.read().single { it.featureId == "DisableDoubleTapDigg" }
        assertEquals(FailurePhases.DEX_FIND, record.phase)
        assertTrue(record.message!!.contains("TikTokQueries.DOUBLE_DIGG"))
        assertTrue(record.message!!.contains("found 0"))
        val panel = FakePanel()
        backend.call(method, panel, "x")
        assertEquals(1, panel.taps)
    }

    @Test fun `only the features that need the missing query are held`() {
        register(services(lookup = { if (it == "TikTokQueries.DOUBLE_DIGG") Result.failure(IllegalStateException("gone")) else Result.success(DexMethod(method).serialize()) }))
        assertEquals(listOf("DisableDoubleTapDigg"), diagnostics.read().map { it.featureId } - SettingsEntry.ID)
        assertEquals(FeatureState.DISABLED, state().state)
    }

    @Test fun `a feature with alternative queries is held only when none of them was located`() {
        val one = services(lookup = { if (it == "CommentQueries.BUTTONS_STATE") Result.failure(IllegalStateException("gone")) else Result.success(DexMethod(method).serialize()) })
        register(one)
        assertTrue(diagnostics.read().none { it.featureId == "HideCommentButtons" })
    }

    @Test fun `a feature that is already locked is not recorded again`() {
        diagnostics.write(FailureRecord("DisableDoubleTapDigg", FailurePhases.AFTER_CALLBACK, "E", null, "trace", 1))
        register(services(lookup = { Result.failure(IllegalStateException("gone")) }))
        assertEquals(1, diagnostics.read().count { it.featureId == "DisableDoubleTapDigg" })
        assertEquals(FailurePhases.AFTER_CALLBACK, diagnostics.read().single { it.featureId == "DisableDoubleTapDigg" }.phase)
    }


    @Test fun `a launch with unanalysed queries installs only the init and no Dex search runs during registration`() {
        // Unanswered choices keep the init waiting on the dialog; answered ones would start the analysis thread and race.
        bugReports = null
        register(services(pending = listOf(TikTokQueries.DOUBLE_DIGG)))
        val active = manager.snapshot().filter { it.state == FeatureState.ACTIVE }.map { it.id }
        assertEquals(listOf(TikTokHostAdapter.INIT_ID), active)
        assertEquals(FeatureState.DISABLED, state().state)
        assertFalse(manager.snapshot().any { it.id == SettingsEntry.ID })
        assertEquals(0, searches)
        assertEquals(1, presented.size)
    }

    @Test fun `the init analyses every query and the next launch installs from the cache without searching`() {
        val cache = HashMap<String, String>()
        bugReports = true
        register(services(pending = listOf(TikTokQueries.DOUBLE_DIGG), entries = cache))
        // The choices were answered before, so the analysis started on its own thread at install; wait for it.
        val init = presented.single()
        val deadline = System.nanoTime() + 5_000_000_000
        while (init.state.value.stage !is InitStage.Restarting && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(InitStage.Restarting(searched = 1, failed = 0), init.state.value.stage)
        assertEquals(1, searches)

        val next = FeatureManager("g2", DefaultHookService(FakeHookBackend(), HookFailureSink {}), Logger {})
        services(pending = listOf(TikTokQueries.DOUBLE_DIGG), entries = cache).register(HostEnvironment(event, backend.id, Logger {}), next)
        assertEquals(FeatureState.ACTIVE, next.snapshot().single { it.id == "DisableDoubleTapDigg" }.state)
        assertFalse(next.snapshot().any { it.id == TikTokHostAdapter.INIT_ID })
        assertEquals(1, searches)
    }

    @Test fun `a feature with a failure record stays off until it is unlocked`() {
        diagnostics.write(FailureRecord("DisableDoubleTapDigg", FailurePhases.AFTER_CALLBACK, "E", null, "trace", 1))
        register(services())
        assertEquals(FeatureState.DISABLED, state().state)
        val panel = FakePanel()
        backend.call(method, panel, "x")
        assertEquals(1, panel.taps)
    }

    @Test fun `safe mode registers every feature but installs none`() {
        diagnostics.setSafeMode(true)
        register(services())
        assertEquals(FeatureState.DISABLED, state().state)
        assertTrue(manager.snapshot().none { it.id != SettingsEntry.ID && it.state == FeatureState.ACTIVE })
        assertEquals(0, searches)
    }

    @Test fun `a target the host build lacks is recorded as a Dex failure and locks the feature`() {
        register(services(lookup = { Result.failure(IllegalStateException("Dex query $it was not located")) }))
        assertEquals(FeatureState.DISABLED, state().state)
        val record = diagnostics.read().single { it.featureId == "DisableDoubleTapDigg" }
        assertEquals(FailurePhases.DEX_FIND, record.phase)
        assertTrue(record.stackTrace.contains("DexQueryUnavailable"))
    }

    @Test fun `missing host paths fail registration explicitly`() {
        val error = assertThrows(IllegalStateException::class.java) { register(services(), event.copy(hostPaths = null)) }
        assertTrue(error.message!!.contains("paths"))
        assertTrue(manager.snapshot().isEmpty())
    }
}
