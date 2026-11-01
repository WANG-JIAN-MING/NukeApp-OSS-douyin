package io.github.nuke.oss.script

import io.github.nuke.oss.script.module.CONFIG_EVENT
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScriptSessionTest {
    private val engine = ScriptEngine(ScriptLimits(taskMillis = 500, memoryBytes = 16L * 1024 * 1024))

    @After
    fun closeEngine() = engine.close()

    @Test
    fun entryModuleSeesRuntimeInfoAndLogs() {
        val listener = RecordingListener()
        val session = engine.run(
            """
            import { apiVersion, script, hasPermission } from "nuke:runtime";
            import { info } from "nuke:log";
            info(apiVersion, script.id, script.versionCode, hasPermission("hook"), hasPermission("java"), { a: 1 });
            """,
            listener,
            permissions = ScriptPermissions(hook = true),
        )
        session.awaitStart()
        listener.awaitLog("""INFO 1 test.script 1 true false {"a":1}""")
        assertEquals(ScriptSession.State.RUNNING, session.currentState)
    }

    @Test
    fun emittedEventsReachListenersInOrder() {
        val listener = RecordingListener()
        val session = engine.run(
            """
            import { info } from "nuke:log";
            __nuke.on("ping", (payload) => info("ping", payload.n, payload.items.length));
            """,
            listener,
        )
        session.awaitStart()
        assertTrue(session.emit("ping", mapOf("n" to 1, "items" to listOf("a", "b"))))
        assertTrue(session.emit("ping", mapOf("n" to 2, "items" to emptyList<String>())))
        listener.awaitLog("INFO ping 2 0")
        assertEquals(listOf("INFO ping 1 2", "INFO ping 2 0"), listener.logs.toList())
    }

    @Test
    fun anEventAwaitingAPromiseDoesNotHoldUpTheNextOne() {
        val listener = RecordingListener()
        val sleep = TestModule("test:sleep") { context ->
            context.asyncFunction("test_sleep") { args -> delay(args.longAt(0, "millis")); "slept" }
        }
        val session = engine.run(
            """
            import { info } from "nuke:log";
            __nuke.on("slow", async () => info("slow", await globalThis.__nk_test_sleep(300)));
            __nuke.on("fast", () => info("fast"));
            """,
            listener,
            listOf(sleep),
        )
        session.awaitStart()
        session.emit("slow", null)
        session.emit("fast", null)
        listener.awaitLog("slow slept")
        assertEquals(listOf("INFO fast", "INFO slow slept"), listener.logs.toList())
    }

    @Test
    fun synchronousCallsReturnTheHandlerAnswer() {
        val handlers = Collections.synchronizedMap(HashMap<String, Long>())
        val register = TestModule("test:register") { context ->
            context.function("test_register") { args -> handlers[args.stringAt(0, "name")] = args.longAt(1, "id"); null }
        }
        val session = engine.run(
            """
            const register = (name, fn) => globalThis.__nk_test_register(name, __nuke.handle(fn));
            register("double", (payload) => ({ doubled: payload.n * 2 }));
            register("throws", () => { throw new TypeError("bad input"); });
            register("async", async () => 1);
            """,
            modules = listOf(register),
        )
        session.awaitStart()

        val doubled = session.call(handlers.getValue("double"), mapOf("n" to 21), 1_000)
        assertEquals(42L, ((doubled as ScriptCallResult.Returned).value as Map<*, *>)["doubled"])

        val thrown = session.call(handlers.getValue("throws"), null, 1_000) as ScriptCallResult.Failed
        assertEquals(ScriptException.SCRIPT_ERROR, thrown.error.code)
        assertTrue(thrown.error.message!!.startsWith("TypeError: bad input"))

        val async = session.call(handlers.getValue("async"), null, 1_000) as ScriptCallResult.Failed
        assertTrue(async.error.message!!.contains("synchronously"))

        val removed = session.call(999, null, 1_000) as ScriptCallResult.Failed
        assertEquals(ScriptException.UNAVAILABLE, removed.error.code)
    }

    @Test
    fun aSlowHandlerTimesOutAndItsLateAnswerIsDropped() {
        val listener = RecordingListener()
        var handler = 0L
        val register = TestModule("test:register") { context ->
            context.function("test_register") { args -> handler = args.longAt(0, "id"); null }
        }
        val session = engine.run(
            """
            import { info } from "nuke:log";
            globalThis.__nk_test_register(__nuke.handle(() => {
              const end = Date.now() + 200;
              while (Date.now() < end) {}
              info("handler finished");
              return 1;
            }));
            """,
            listener,
            listOf(register),
        )
        session.awaitStart()
        assertEquals(ScriptCallResult.TimedOut, session.call(handler, null, 50))
        listener.awaitLog("handler finished")
        assertEquals(ScriptCallResult.Returned(1L), session.call(handler, null, 1_000))
        assertEquals(ScriptSession.State.RUNNING, session.currentState)
    }

    @Test
    fun aCallFromTheScriptThreadIsReentrant() {
        lateinit var session: ScriptSession
        val results = Collections.synchronizedList(ArrayList<ScriptCallResult>())
        val probe = TestModule("test:probe") { context ->
            context.function("test_probe") { args -> results += session.call(args.longAt(0, "id"), null, 100); null }
        }
        session = engine.run(
            """globalThis.__nk_test_probe(__nuke.handle(() => 1));""",
            modules = listOf(probe),
        )
        session.awaitStart()
        assertEquals(listOf<ScriptCallResult>(ScriptCallResult.Reentrant), results.toList())
    }

    @Test
    fun timersFireRepeatAndCanBeCleared() {
        val listener = RecordingListener()
        val session = engine.run(
            """
            import { info } from "nuke:log";
            import { sleep } from "nuke:timers";
            const cancelled = setTimeout(() => info("cancelled fired"), 20);
            clearTimeout(cancelled);
            setTimeout((a, b) => info("timeout", a, b), 10, "x", "y");
            let ticks = 0;
            const interval = setInterval(() => {
              ticks++;
              if (ticks === 3) { clearInterval(interval); info("ticks", ticks); }
            }, 10);
            sleep(30).then(() => info("slept"));
            """,
            listener,
        )
        session.awaitStart()
        listener.awaitLog("ticks 3")
        listener.awaitLog("slept")
        Thread.sleep(100)
        assertTrue(listener.logs.contains("INFO timeout x y"))
        assertFalse(listener.logs.any { "cancelled" in it })
        assertEquals(1, listener.logs.count { "ticks" in it })
    }

    @Test
    fun listenerFailuresAreReportedAndTheSessionKeepsRunning() {
        val listener = RecordingListener()
        val session = engine.run(
            """
            import { info } from "nuke:log";
            __nuke.on("e", () => { throw new RangeError("first"); });
            __nuke.on("e", async () => { throw new Error("second"); });
            __nuke.on("e", () => info("third"));
            setTimeout(() => { throw new Error("timer"); }, 1);
            """,
            listener,
        )
        session.awaitStart()
        session.emit("e", null)
        listener.awaitLog("third")
        Thread.sleep(100)
        val errors = listener.errors.toList()
        assertTrue(errors.toString(), errors.any { it.startsWith("e: RangeError: first") })
        assertTrue(errors.toString(), errors.any { it.startsWith("e: Error: second") })
        assertTrue(errors.toString(), errors.any { it.startsWith("timer: Error: timer") })
        assertEquals(ScriptSession.State.RUNNING, session.currentState)
    }

    @Test
    fun nativeErrorsKeepTheirCode() {
        val listener = RecordingListener()
        val guarded = TestModule("test:guarded") { context ->
            context.function("test_guarded") { context.requirePermission(ScriptPermissions.JAVA) }
            context.function("test_broken") { error("host side") }
        }
        val session = engine.run(
            """
            import { info } from "nuke:log";
            import { PermissionError, NukeError } from "nuke:runtime";
            try { __nuke.check(globalThis.__nk_test_guarded()); } catch (e) {
              info(e instanceof PermissionError, e instanceof NukeError, e.code, e.message);
            }
            try { __nuke.check(globalThis.__nk_test_broken()); } catch (e) { info(e.code, e.message); }
            """,
            listener,
            listOf(guarded),
        )
        session.awaitStart()
        listener.awaitLog("HOST_ERROR host side")
        assertTrue(listener.logs.contains("INFO true true PERMISSION_DENIED The script did not declare the 'java' permission"))
    }

    @Test
    fun configValuesAreFrozenAndUpdatesReachListeners() {
        val listener = RecordingListener()
        val session = engine.run(
            """
            import { info } from "nuke:log";
            import { get, values, onChange } from "nuke:config";
            info("initial", get("name"), get("missing", "fallback"), Object.isFrozen(values()));
            onChange((next) => info("changed", next.name, get("name")));
            """,
            listener,
            config = mapOf("name" to "first"),
        )
        session.awaitStart()
        listener.awaitLog("initial first fallback true")
        session.emit(CONFIG_EVENT, mapOf("name" to "second"))
        listener.awaitLog("changed second second")
    }

    @Test
    fun aFailingEntryStopsTheSession() {
        for (source in listOf("throw new Error('at load');", "import { nothing } from 'nuke:missing';", "let x = ;")) {
            val listener = RecordingListener()
            val session = engine.run(source, listener)
            try {
                session.awaitStart()
                fail("Started: $source")
            } catch (error: ScriptException) {
                assertEquals(source, ScriptSession.State.FAILED, session.currentState)
            }
            assertTrue(source, listener.awaitStop() != null)
        }
    }

    @Test
    fun anUnhandledRejectionStopsTheSession() {
        val listener = RecordingListener()
        val session = engine.run("""setTimeout(() => { Promise.reject(new Error("nobody listens")); }, 1);""", listener)
        session.awaitStart()
        val failure = listener.awaitStop()!!
        assertTrue(failure.message, failure.message!!.contains("nobody listens"))
        assertEquals(ScriptSession.State.FAILED, session.currentState)
    }

    @Test
    fun aScriptThatNeverYieldsIsStopped() {
        val listener = RecordingListener()
        val session = engine.run("""__nuke.on("spin", () => { for (;;) {} });""", listener)
        session.awaitStart()
        session.emit("spin", null)
        assertEquals(ScriptException.TIMEOUT, listener.awaitStop()!!.code)
        assertFalse(session.emit("spin", null))
        assertEquals(ScriptCallResult.Stopped, session.call(1, null, 100))
    }

    @Test
    fun runningOutOfMemoryStopsTheSession() {
        for (source in listOf(
            """__nuke.on("grow", () => { const all = []; for (;;) all.push("x".repeat(4096) + all.length); });""",
            """globalThis.kept = []; __nuke.on("grow", () => { for (;;) kept.push("x".repeat(4096) + kept.length); });""",
        )) {
            val listener = RecordingListener()
            val session = engine.run(source, listener)
            session.awaitStart()
            session.emit("grow", null)
            assertEquals(source, ScriptException.LIMIT_EXCEEDED, listener.awaitStop()!!.code)
        }
    }

    @Test
    fun closingReleasesResourcesOnceNewestFirst() {
        val listener = RecordingListener()
        val closed = Collections.synchronizedList(ArrayList<String>())
        lateinit var context: ScriptModuleContext
        val owner = TestModule("test:owner") { bound ->
            context = bound
            bound.own(AutoCloseable { closed += "first" })
            bound.own(AutoCloseable { closed += "second" })
        }
        val session = engine.run("", listener, listOf(owner))
        session.awaitStart()
        session.close()
        session.close()
        assertNull(listener.awaitStop())
        assertEquals(listOf("second", "first"), closed.toList())
        context.own(AutoCloseable { closed += "late" })
        assertEquals(listOf("second", "first", "late"), closed.toList())
        assertEquals(1, listener.stops.size)
        assertEquals(ScriptSession.State.STOPPED, session.currentState)
    }

    @Test
    fun closingInterruptsRunningJavaScript() {
        val listener = RecordingListener()
        val entered = CountDownLatch(1)
        val gate = TestModule("test:gate") { context -> context.function("test_entered") { entered.countDown(); null } }
        val patient = ScriptEngine(ScriptLimits(taskMillis = 60_000))
        val session = patient.run(
            """__nuke.on("spin", () => { globalThis.__nk_test_entered(); for (;;) {} });""",
            listener,
            listOf(gate),
        )
        session.awaitStart()
        session.emit("spin", null)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        session.close()
        assertNull(listener.awaitStop())
        patient.close()
    }
}
