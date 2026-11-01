package io.github.nuke.oss.script.java

import io.github.nuke.oss.hook.api.HookOwner
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.ContractTargets
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.script.RecordingListener
import io.github.nuke.oss.script.ScriptEngine
import io.github.nuke.oss.script.ScriptPermissions
import io.github.nuke.oss.script.ScriptSession
import io.github.nuke.oss.script.run
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HookModuleTest {
    private val engine = ScriptEngine()
    private val backend = FakeHookBackend()
    private val scope = FeatureScope(HookOwner("script.test.script", "gen-1"), DefaultHookService(backend))
    private val echo = ContractTargets::class.java.getMethod("echo", String::class.java)
    private val number = ContractTargets::class.java.getMethod("number", Int::class.javaPrimitiveType)
    private val failing = ContractTargets::class.java.getMethod("fail")
    private val listener = RecordingListener()

    @After
    fun close() {
        engine.close()
        scope.close()
    }

    private fun start(body: String, permissions: ScriptPermissions = ScriptPermissions(java = true, hook = true)): ScriptSession {
        val java = JavaModule(javaClass.classLoader)
        return engine.run(
            """
            import { info } from "nuke:log";
            import { use } from "nuke:java";
            import { hook } from "nuke:hook";
            const Targets = use("${ContractTargets::class.java.name}");
            $body
            """,
            listener,
            listOf(java, HookModule(java, scope.hooks)),
            permissions,
        ).also { it.awaitStart() }
    }

    @Test
    fun beforeRewritesArgumentsAndReturnsEarly() {
        start(
            """
            hook(Targets.method("echo"), {
              before(call) {
                if (call.args[0] === "stop") call.returnEarly("stopped");
                else call.setArg(0, call.args[0] + "!");
              },
            });
            hook(Targets.method("number"), { before(call) { call.setArg(0, call.args[0] * 2); } });
            """,
        )
        assertEquals("hi!", backend.call(echo, null, "hi"))
        assertEquals("stopped", backend.call(echo, null, "stop"))
        assertEquals(1, backend.originalCalls.get())
        assertEquals(42, backend.call(number, null, 21))
    }

    @Test
    fun afterSeesTheOutcomeAndCanReplaceIt() {
        start(
            """
            hook(Targets.method("echo"), { after(call) { call.setResult(call.result.toUpperCase() + ":" + call.args[0]); } });
            hook(Targets.method("fail"), { after(call) { info("error", call.error.className); call.setResult("recovered"); } });
            """,
        )
        assertEquals("HI:hi", backend.call(echo, null, "hi"))
        assertEquals("recovered", backend.call(failing, null))
        listener.awaitLog("error java.lang.IllegalStateException")
    }

    @Test
    fun throwsMessagesAndJavaThrowables() {
        start(
            """
            const Error = use("java.lang.UnsupportedOperationException");
            hook(Targets.method("echo"), {
              before(call) {
                if (call.args[0] === "java") call.throwEarly(Error.new("from java"));
                else call.throwEarly(new TypeError("from script"));
              },
            });
            """,
        )
        try {
            backend.call(echo, null, "java")
            fail("Not thrown")
        } catch (error: UnsupportedOperationException) {
            assertEquals("from java", error.message)
        }
        try {
            backend.call(echo, null, "js")
            fail("Not thrown")
        } catch (error: ScriptThrownException) {
            assertEquals("from script", error.message)
        }
    }

    @Test
    fun scriptFailuresLeaveTheCallUnchanged() {
        start(
            """
            hook(Targets.method("echo"), {
              before(call) {
                if (call.args[0] === "throw") throw new Error("callback broke");
                if (call.args[0] === "type") call.returnEarly(5);
              },
            });
            """,
        )
        assertEquals("throw", backend.call(echo, null, "throw"))
        assertEquals("type", backend.call(echo, null, "type"))
        listener.awaitLog("callback broke")
        listener.awaitLog("could not apply the before answer")
    }

    @Test
    fun observersSeeCallsWithoutChangingThem() {
        start(
            """
            hook(Targets.method("echo"), {
              observe: true,
              before(call) { info("seen", call.args[0], typeof call.setArg, typeof call.returnEarly); },
            });
            """,
        )
        assertEquals("a", backend.call(echo, null, "a"))
        listener.awaitLog("seen a undefined undefined")
    }

    @Test
    fun aSlowHookTimesOutAndIsRemovedAfterThreeTimes() {
        start(
            """
            let calls = 0;
            hook(Targets.method("echo"), {
              timeoutMs: 30,
              before(call) {
                calls++;
                const end = Date.now() + 150;
                while (Date.now() < end) {}
                call.returnEarly("late");
              },
            });
            __nuke.on("count", () => info("calls", calls));
            """,
        )
        repeat(3) { assertEquals("x", backend.call(echo, null, "x")) }
        listener.awaitLog("removed")
        Thread.sleep(400)
        assertEquals("y", backend.call(echo, null, "y"))
        assertEquals("y", backend.call(echo, null, "y"))
        Thread.sleep(400)
        assertTrue(listener.logs.count { "answered later" in it } == 2)
    }

    @Test
    fun closingAHookOrTheSessionUnhooks() {
        val session = start(
            """
            const handle = hook(Targets.method("echo"), { before(call) { call.returnEarly("hooked"); } });
            hook(Targets.method("number"), { before(call) { call.returnEarly(0); } });
            __nuke.on("unhook", () => { handle.close(); handle.close(); info("unhooked"); });
            """,
        )
        assertEquals("hooked", backend.call(echo, null, "a"))
        session.emit("unhook", null)
        listener.awaitLog("unhooked")
        assertEquals("a", backend.call(echo, null, "a"))
        assertEquals(0, backend.call(number, null, 9))
        session.close()
        listener.awaitStop()
        assertEquals(9, backend.call(number, null, 9))
    }

    @Test
    fun hookingNeedsBothPermissions() {
        start(
            """
            try { hook(Targets.method("echo"), { before() {} }); } catch (e) { info(e.name, e.message); }
            """,
            ScriptPermissions(java = true),
        )
        listener.awaitLog("PermissionError The script did not declare the 'hook' permission")
        assertEquals("a", backend.call(echo, null, "a"))
    }
}
