package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.activity.HostPromptsFactory
import io.github.nuke.oss.app.tiktok.query.MiniAppQueries
import com.bytedance.reparo.IReparoConfig
import io.github.nuke.oss.app.shared.process.MirrorState
import io.github.nuke.oss.app.shared.process.ProcessMirror
import io.github.nuke.oss.app.shared.registry.HostProcess
import io.github.nuke.oss.assembly.HostScope
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.FeatureState
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.LoadEvent
import io.github.nuke.oss.runtime.LoadStage
import io.github.nuke.oss.runtime.HostPaths
import io.github.nuke.oss.core.ProcessIdentity
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.api.HookFailureSink
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.FeatureManager
import io.github.nuke.oss.runtime.HostEnvironment
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luckypray.dexkit.wrap.DexMethod

// The process scope: which processes of the host are entered and as what, what the main process leaves for the others
// and what a child process does with it.
class ProcessScopeTest {
    private val prompts = HostPromptsFactory { _, _, _, _ -> RecordingPrompts() }
    @Test fun `the main process of each host and the non-isolated children of Douyin are in scope`() {
        assertEquals(HostProcess.MAIN, HostScope.processOf(HostScope.TIKTOK, HostScope.TIKTOK))
        assertEquals(HostProcess.MAIN, HostScope.processOf(HostScope.FIXTURE, HostScope.FIXTURE))
        for (name in listOf("miniapp1", "miniapp12", "miniappX", "minigame3", "minigameX")) {
            assertEquals(name, HostProcess.MINI_APP, HostScope.processOf(HostScope.TIKTOK, "${HostScope.TIKTOK}:$name"))
        }
        for (name in listOf("push", "downloader", "miniapp", "miniappY", "xminiapp1")) {
            assertEquals(name, HostProcess.OTHER, HostScope.processOf(HostScope.TIKTOK, "${HostScope.TIKTOK}:$name"))
        }
    }

    @Test fun `sandboxes, other packages and the test host's children are out of scope`() {
        assertNull(HostScope.processOf(HostScope.TIKTOK, "${HostScope.TIKTOK}:sandboxed_process0"))
        assertNull(HostScope.processOf(HostScope.TIKTOK, "${HostScope.TIKTOK}:"))
        assertNull(HostScope.processOf(HostScope.TIKTOK, "com.ss.android.ugc.trill"))
        assertNull(HostScope.processOf(HostScope.TIKTOK, "${HostScope.TIKTOK}x:push"))
        assertNull(HostScope.processOf(HostScope.FIXTURE, "${HostScope.FIXTURE}:other"))
        assertNull(HostScope.processOf("com.example", "com.example"))
        assertFalse(HostScope.isTargetProcess(HostScope.TIKTOK, "other"))
    }

    private val directory = Files.createTempDirectory("nuke-mirror").toFile()

    @Test fun `the mirror round-trips and only reads for its own scope`() {
        val mirror = ProcessMirror(File(directory, "a/mirror.json"))
        val state = MirrorState("s1", mapOf("a" to true, "b" to false), true, setOf("x"), mapOf("k" to "v"))
        assertNull(mirror.read("s1"))
        mirror.write(state)
        assertEquals(state, mirror.read("s1"))
        assertNull(mirror.read("s2"))
        mirror.updateSwitch("s1", "b", true)
        assertEquals(mapOf("a" to true, "b" to true), mirror.read("s1")?.switches)
        mirror.updateSwitch("s2", "b", false)
        assertEquals(true, mirror.read("s1")?.switches?.getValue("b"))
    }

    @Test fun `deleting the mirror leaves children with nothing to read until the main process writes a new one`() {
        val mirror = ProcessMirror(File(directory, "b/mirror.json"))
        mirror.write(MirrorState("s1", mapOf("a" to true), false, emptySet(), mapOf("k" to "v")))
        mirror.delete()
        assertNull(mirror.read("s1"))
        mirror.delete()
        mirror.write(MirrorState("s1", mapOf("a" to false), false, emptySet(), emptyMap()))
        assertEquals(mapOf("a" to false), mirror.read("s1")?.switches)
    }

    @Test fun `a damaged mirror reads as absent`() {
        val file = File(directory, "broken.json").apply { writeText("{\"scope\":\"s\",\"switches\":[1]}") }
        assertNull(ProcessMirror(file).read("s"))
        file.writeText("not json")
        assertNull(ProcessMirror(file).read("s"))
    }

    class Reparo {
        var ran = 0
        fun init(config: IReparoConfig?) { ran++ }
        fun fetch() { ran++ }
    }

    private val backend = FakeHookBackend()
    private val logged = ArrayList<String>()
    private val manager = FeatureManager("g", DefaultHookService(backend, HookFailureSink {}), Logger {})
    private val dataDir = File(directory, "data")
    private val apk = File(directory, "base.apk")
    private val scope = "build|${apk.length()}|${apk.lastModified()}"
    private val init = Reparo::class.java.getDeclaredMethod("init", IReparoConfig::class.java)
    private val fetch = Reparo::class.java.getDeclaredMethod("fetch")
    private val mirror = ProcessMirror(File(dataDir, "no_backup/nuke/${TikTokHostAdapter.MIRROR_FILE}"))

    private fun childEvent(name: String) = LoadEvent(
        ProcessIdentity(2, 10001, name, HostScope.TIKTOK), BuildIdentity("io.github.nuke.oss", "build", "g"), "xposed82",
        LoadStage.HOST_CLASSLOADER_READY, Reparo::class.java.classLoader, HostPaths(dataDir.path, apk.path),
    )

    private fun child(name: String) =
        TikTokHostAdapter({ _, _ -> error("A child process has no init") }, prompts).register(HostEnvironment(childEvent(name), backend.id, Logger { logged += it }), manager)

    private fun dex() = mapOf(
        "$scope|MiniAppQueries.REPARO_INIT|v1" to DexMethod(init).serialize(), "$scope|MiniAppQueries.REPARO_FETCH|v1" to DexMethod(fetch).serialize(),
    )

    @Test fun `a child process installs the switched-on features declared for it from the mirror`() {
        mirror.write(MirrorState(scope, mapOf("DisableHotUpdates" to true, "SkipMiniAppRewardAds" to false), false, emptySet(), dex()))
        child("${HostScope.TIKTOK}:push")
        val states = manager.snapshot().associate { it.id to it.state }
        assertEquals(FeatureState.ACTIVE, states["DisableHotUpdates"])
        // A feature declared for the main and mini-app processes does not exist in another kind of child.
        assertFalse("SkipMiniAppRewardAds" in states)
        assertFalse("DisableDoubleTapDigg" in states)
        val reparo = Reparo()
        backend.call(init, reparo, null)
        backend.call(fetch, reparo)
        assertEquals(0, reparo.ran)
    }

    @Test fun `a mini-app process also gets the mini-app features, and an unanalysed target fails only that feature`() {
        mirror.write(MirrorState(scope, mapOf("DisableHotUpdates" to true, "SkipMiniAppRewardAds" to true), false, emptySet(), dex()))
        child("${HostScope.TIKTOK}:miniapp1")
        val states = manager.snapshot().associate { it.id to it.state }
        assertEquals(FeatureState.ACTIVE, states["DisableHotUpdates"])
        assertEquals(FeatureState.FAILED, states["SkipMiniAppRewardAds"])
    }

    @Test fun `a feature that is off, locked or held by safe mode is not installed in a child process`() {
        mirror.write(MirrorState(scope, mapOf("DisableHotUpdates" to false), false, emptySet(), dex()))
        child("${HostScope.TIKTOK}:push")
        assertEquals(FeatureState.DISABLED, manager.snapshot().single { it.id == "DisableHotUpdates" }.state)
        val locked = FeatureManager("g2", DefaultHookService(FakeHookBackend(), HookFailureSink {}), Logger {})
        mirror.write(MirrorState(scope, mapOf("DisableHotUpdates" to true), false, setOf("DisableHotUpdates"), dex()))
        TikTokHostAdapter({ _, _ -> error("no init") }, prompts).register(HostEnvironment(childEvent("${HostScope.TIKTOK}:push"), backend.id, Logger {}), locked)
        assertEquals(FeatureState.DISABLED, locked.snapshot().single { it.id == "DisableHotUpdates" }.state)
        mirror.write(MirrorState(scope, mapOf("DisableHotUpdates" to true), true, emptySet(), dex()))
        val safe = FeatureManager("g3", DefaultHookService(FakeHookBackend(), HookFailureSink {}), Logger {})
        TikTokHostAdapter({ _, _ -> error("no init") }, prompts).register(HostEnvironment(childEvent("${HostScope.TIKTOK}:push"), backend.id, Logger {}), safe)
        assertTrue(safe.snapshot().isEmpty())
    }

    @Test fun `without a mirror of this build a child process installs nothing`() {
        child("${HostScope.TIKTOK}:push")
        assertTrue(manager.snapshot().isEmpty())
        assertTrue(logged.any { it.contains("no_mirror") })
        mirror.write(MirrorState("another build", mapOf("DisableHotUpdates" to true), false, emptySet(), dex()))
        child("${HostScope.TIKTOK}:push")
        assertTrue(manager.snapshot().isEmpty())
    }
}
