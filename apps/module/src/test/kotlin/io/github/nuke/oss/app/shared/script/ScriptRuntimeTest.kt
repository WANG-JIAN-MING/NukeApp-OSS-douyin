package io.github.nuke.oss.app.shared.script

import io.github.nuke.oss.core.FeatureState
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.ContractTargets
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.FailurePhase
import io.github.nuke.oss.runtime.FeatureFailureSink
import io.github.nuke.oss.runtime.FeatureManager
import io.github.nuke.oss.script.ScriptEngine
import io.github.nuke.oss.script.ScriptException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executor
import kotlin.io.path.createTempDirectory

// The whole script path on the JVM: the Rust package reader, the feature manager and real QuickJS sessions.
class ScriptRuntimeTest {
    private val backend = FakeHookBackend()
    private val manager = FeatureManager("gen-1", DefaultHookService(backend), Logger {})
    private val failures = Collections.synchronizedList(ArrayList<Triple<String, FailurePhase, Throwable>>())
    private val engine = ScriptEngine()
    private val values = HashMap<String, String>()
    private val states = KeyedScriptStates({ synchronized(values) { values[it] } }) { key, value ->
        synchronized(values) { if (value == null) values.remove(key) else values[key] = value }
    }
    private val root = createTempDirectory("scripts").toFile()
    private var hold: (String) -> ScriptHold = { ScriptHold.NONE }
    private val logs = ScriptLogs()
    private val runtime = newRuntime()

    init {
        manager.failureSink = FeatureFailureSink { id, phase, error -> failures += Triple(id, phase, error) }
    }

    private fun newRuntime() = ScriptRuntime(
        ScriptRepository(root, "com.ss.android.ugc.aweme", 1), states, logs, manager, javaClass.classLoader!!, engine,
        Executor { it.run() }, { hold(it) },
    )

    @After
    fun close() {
        manager.close()
        engine.close()
    }

    private fun install(bytes: ByteArray, grant: Boolean = true, enable: Boolean = true) =
        runtime.commit(runtime.stage(bytes.inputStream()), grant, enable)

    private fun entry(id: String = "com.example.hello") = runtime.entries().single { it.script.manifest.id == id }

    private fun awaitLog(text: String, id: String = "com.example.hello") {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (logs.read(id).any { text in it.message }) return
            Thread.sleep(10)
        }
        throw AssertionError("No log '$text' in ${logs.read(id)}")
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Condition not met")
            Thread.sleep(10)
        }
    }

    @Test
    fun anInstalledScriptRunsAsAFeature() {
        install(simplePackage())
        awaitLog("hello")
        val entry = entry()
        assertTrue(entry.running)
        assertEquals(ScriptHold.NONE, entry.hold)
        assertEquals(FeatureState.ACTIVE, manager.snapshot().single { it.id == "script.com.example.hello" }.state)

        runtime.setEnabled("com.example.hello", false)
        assertFalse(entry().running)
        awaitLog("Stopped")
        assertFalse(entry().state.enabled)
    }

    @Test
    fun dangerousCapabilitiesWaitForAGrant() {
        val bytes = simplePackage(permissions = """{"java":true}""")
        install(bytes, grant = false)
        assertEquals(ScriptHold.NEEDS_GRANT, entry().hold)
        assertFalse(entry().running)
        runtime.grant("com.example.hello")
        awaitLog("hello")
        assertTrue(entry().running)

        // An update asking for more needs a new grant before it runs.
        install(simplePackage(versionCode = 2, permissions = """{"java":true,"hook":true}"""), grant = false)
        assertEquals(ScriptHold.NEEDS_GRANT, entry().hold)
        assertFalse(entry().running)
    }

    @Test
    fun registeredScriptsStartAgainUnlessHeld() {
        install(simplePackage())
        install(simplePackage(id = "com.example.off"), enable = false)
        manager.close()

        val fresh = FeatureManager("gen-2", DefaultHookService(FakeHookBackend()), Logger {})
        val again = ScriptRuntime(
            ScriptRepository(root, "com.ss.android.ugc.aweme", 1), states, logs, fresh, javaClass.classLoader!!, engine,
            Executor { it.run() }, { if (it == "script.com.example.hello") ScriptHold.LOCKED else ScriptHold.NONE },
        )
        again.registerAll()
        val statuses = fresh.snapshot().associate { it.id to it.state }
        assertEquals(FeatureState.DISABLED, statuses["script.com.example.hello"])
        assertEquals(FeatureState.DISABLED, statuses["script.com.example.off"])
        assertEquals(ScriptHold.LOCKED, again.entries().single { it.script.manifest.id == "com.example.hello" }.hold)
        fresh.close()
    }

    @Test
    fun aScriptThatFailsIsReportedAndSwitchedOff() {
        install(simplePackage("""setTimeout(() => { Promise.reject(new Error("broken")); }, 1);"""))
        await { failures.isNotEmpty() }
        val (id, phase, error) = failures.single()
        assertEquals("script.com.example.hello", id)
        assertEquals(FailurePhase.INSTALL, phase)
        assertTrue(error is ScriptException)
        await { !entry().running }
        assertTrue(entry().failure!!.contains("broken"))
        assertEquals(FeatureState.DISABLED, manager.snapshot().single { it.id == id }.state)
    }

    @Test
    fun savedSettingsReachTheRunningScript() {
        install(
            simplePackage(
                """
                import { info } from "nuke:log";
                import { get, onChange } from "nuke:config";
                info("start", get("greeting"));
                onChange((values) => info("now", values.greeting));
                """,
                extra = ",\"configSchema\":\"config.json\"",
                files = mapOf("config.json" to """{"fields":[{"key":"greeting","type":"string","title":"Greeting","default":"hi"}]}"""),
            ),
        )
        awaitLog("start hi")
        runtime.saveConfig("com.example.hello", mapOf("greeting" to "yo"))
        awaitLog("now yo")
        assertEquals(mapOf("greeting" to "yo"), entry().state.config)
    }

    @Test
    fun scriptHooksBelongToTheScriptFeature() {
        val echo = ContractTargets::class.java.getMethod("echo", String::class.java)
        install(
            simplePackage(
                """
                import { info } from "nuke:log";
                import { use } from "nuke:java";
                import { hook } from "nuke:hook";
                hook(use("${ContractTargets::class.java.name}").method("echo"), { before(call) { call.returnEarly("from script"); } });
                info("hooked");
                """,
                permissions = """{"java":true,"hook":true}""",
            ),
        )
        awaitLog("hooked")
        assertEquals("from script", backend.call(echo, null, "x"))
        runtime.setEnabled("com.example.hello", false)
        assertEquals("x", backend.call(echo, null, "x"))
    }

    @Test
    fun uninstallingStopsAndForgetsTheScript() {
        install(simplePackage())
        awaitLog("hello")
        runtime.uninstall("com.example.hello")
        assertTrue(runtime.entries().isEmpty())
        assertEquals(FeatureState.DISABLED, manager.snapshot().single { it.id == "script.com.example.hello" }.state)
        assertTrue(values.isEmpty())
    }
}
