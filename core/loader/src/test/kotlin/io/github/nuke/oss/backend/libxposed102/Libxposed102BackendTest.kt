package io.github.nuke.oss.backend.libxposed102

import io.github.libxposed.api.XposedInterface
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import io.github.nuke.oss.testing.ProbeBootstrap
import io.github.nuke.oss.core.*
import io.github.nuke.oss.fixture.ProbeTarget
import io.github.nuke.oss.hook.api.*
import io.github.nuke.oss.backend.libxposed102.testing.TestModernFramework
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.ContractTargets
import io.github.nuke.oss.runtime.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Libxposed102BackendTest {
    private val framework = TestModernFramework()
    private val backend = Libxposed102Backend(framework)
    private val failures = ArrayList<HookFailure>()
    private val hooks = DefaultHookService(backend, HookFailureSink { failures += it })
    private val owner = HookOwner("feature", "generation")
    private val echo = ContractTargets::class.java.getMethod("echo", String::class.java)

    @After fun callbackAssertionsRemainObservable() {
        failures.firstOrNull { it.error is AssertionError }?.let { throw it.error }
    }

    @Test fun `rewrites immutable chain arguments and returned result`() {
        hooks.install(owner, echo, HookSpec("change", before = { it.setArgument(0, "changed") }, after = {
            assertEquals("changed", it.argument(0))
            it.returnValue("after:${(it.outcome as CallOutcome.Returned).value}")
        }))
        assertEquals("after:changed", framework.call(echo, null, "original"))
        assertEquals(1, framework.proceeds.get())
        assertTrue(failures.isEmpty())
    }

    @Test fun `continues downstream interceptors instead of bypassing the chain`() {
        framework.downstream = { _, _, arguments -> "external:${arguments[0]}" }
        hooks.install(owner, echo, HookSpec("observe", after = { it.returnValue("ours:${(it.outcome as CallOutcome.Returned).value}") }))
        assertEquals("ours:external:input", framework.call(echo, null, "input"))
        assertEquals(1, framework.proceeds.get())
    }

    @Test fun `explicit null return skips proceed`() {
        hooks.install(owner, echo, HookSpec("short", before = { it.returnEarly(null) }))
        assertNull(framework.call(echo, null, "input"))
        assertEquals(0, framework.proceeds.get())
    }

    @Test fun `deliberate before exception propagates without protective fallback`() {
        val expected = IllegalStateException("deliberate")
        hooks.install(owner, echo, HookSpec("throw", before = { it.throwEarly(expected) }))
        assertSame(expected, assertThrows(IllegalStateException::class.java) { framework.call(echo, null, "v") })
        assertEquals(0, framework.proceeds.get())
        assertTrue(failures.isEmpty())
    }

    @Test fun `deliberate after exception replaces returned value`() {
        val expected = IllegalStateException("deliberate-after")
        hooks.install(owner, echo, HookSpec("throw", after = { it.throwError(expected) }))
        assertSame(expected, assertThrows(IllegalStateException::class.java) { framework.call(echo, null, "v") })
        assertEquals(1, framework.proceeds.get())
        assertTrue(failures.isEmpty())
    }

    @Test fun `host exception remains visible and is not a callback failure`() {
        val fail = ContractTargets::class.java.getMethod("fail")
        hooks.install(owner, fail, HookSpec("observe", after = {}))
        assertEquals("host-failure", assertThrows(IllegalStateException::class.java) { framework.call(fail) }.message)
        assertTrue(failures.isEmpty())
    }

    @Test fun `after can explicitly recover a downstream exception`() {
        val fail = ContractTargets::class.java.getMethod("fail")
        hooks.install(owner, fail, HookSpec("recover", after = {
            assertTrue(it.outcome is CallOutcome.Thrown)
            it.returnValue("recovered")
        }))
        assertEquals("recovered", framework.call(fail))
        assertEquals(1, framework.proceeds.get())
    }

    @Test fun `broken before rolls back and calls downstream exactly once`() {
        hooks.install(owner, echo, HookSpec("broken", before = {
            it.setArgument(0, "bad"); it.returnEarly("bad"); error("callback")
        }))
        assertEquals("original", framework.call(echo, null, "original"))
        assertEquals("before", failures.single().phase)
        assertEquals(1, framework.proceeds.get())
    }

    @Test fun `broken after preserves downstream result and does not repeat it`() {
        hooks.install(owner, echo, HookSpec("broken", after = { it.returnValue("bad"); error("callback") }))
        assertEquals("original", framework.call(echo, null, "original"))
        assertEquals("after", failures.single().phase)
        assertEquals(1, framework.proceeds.get())
    }

    @Test fun `portable ordering and early exit are provided by existing dispatcher`() {
        val events = ArrayList<String>()
        hooks.install(owner, echo, HookSpec("a", priority = 20, before = { events += "a+" }, after = { events += "a-" }))
        hooks.install(owner, echo, HookSpec("b", priority = 10, before = { events += "b+"; it.returnEarly("short") }, after = { events += "b-" }))
        hooks.install(owner, echo, HookSpec("c", after = { events += "c-" }))
        assertEquals("short", framework.call(echo, null, "v"))
        assertEquals(listOf("a+", "b+", "b-", "a-"), events)
        assertEquals(1, framework.attaches.get())
        assertEquals(0, framework.proceeds.get())
    }

    @Test fun `primitive and void results use the common validation rules`() {
        val number = ContractTargets::class.java.getMethod("number", Int::class.javaPrimitiveType)
        val nothing = ContractTargets::class.java.getMethod("nothing")
        hooks.install(owner, number, HookSpec("bad", before = { it.returnEarly(null) }))
        hooks.install(owner, nothing, HookSpec("bad", after = { it.returnValue(7) }))
        assertEquals(3, framework.call(number, null, 3))
        assertNull(framework.call(nothing))
        assertEquals(2, failures.size)
        assertEquals(2, framework.proceeds.get())
    }

    @Test fun `instance receiver is preserved`() {
        val target = ContractTargets::class.java.getMethod("instance", String::class.java)
        val receiver = ContractTargets()
        hooks.install(owner, target, HookSpec("observe", before = { assertSame(receiver, it.receiver) }))
        assertEquals("instance:value", framework.call(target, receiver, "value"))
    }

    @Test fun `logical disable and reenable reuse one framework handle`() {
        val handle = hooks.install(owner, echo, HookSpec("a", before = { it.returnEarly("on") }))
        handle.close(); handle.close()
        assertEquals("off", framework.call(echo, null, "off"))
        hooks.install(owner, echo, HookSpec("a", before = { it.returnEarly("again") }))
        assertEquals("again", framework.call(echo, null, "off"))
        assertEquals(1, framework.attaches.get())
        assertEquals(0, framework.unhooks.get())
        hooks.close(); hooks.close()
        assertEquals(1, framework.unhooks.get())
    }

    @Test fun `recursive calls keep independent tokens`() {
        var nested = false
        var exits = 0
        hooks.install(owner, echo, HookSpec("recurse", before = {
            if (!nested) {
                nested = true
                assertEquals("inner", framework.call(echo, null, "inner"))
                nested = false
            }
        }, after = { exits++ }))
        assertEquals("outer", framework.call(echo, null, "outer"))
        assertEquals(2, exits)
        assertEquals(0, hooks.snapshot().single().inFlight)
    }

    @Test fun `in-flight call pairs after while new calls see logical disable`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val handle = hooks.install(owner, echo, HookSpec("block", before = {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }, after = { it.returnValue("paired") }))
            val first = executor.submit<Any?> { framework.call(echo, null, "first") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            handle.close()
            assertEquals("second", framework.call(echo, null, "second"))
            release.countDown()
            assertEquals("paired", first.get(5, TimeUnit.SECONDS))
            assertEquals(0, hooks.snapshot().single().inFlight)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `unsupported API versions are rejected before any hook`() {
        val old = TestModernFramework(101)
        assertThrows(IllegalArgumentException::class.java) { Libxposed102Backend(old) }
        assertEquals(0, old.attaches.get())
    }

    @Test fun `advanced capabilities are not silently enabled`() {
        assertEquals(SupportLevel.UNAVAILABLE, backend.support(echo, HookCapability.ATOMIC_HOOK_REPLACE).level)
        assertEquals(SupportLevel.UNAVAILABLE, backend.support(echo, HookCapability.NATIVE_AROUND_CHAIN).level)
        assertEquals(SupportLevel.UNAVAILABLE, backend.support(echo, HookCapability.BACKEND_ORIGINAL_INVOKE).level)
    }

    @Test fun `constructor is observed after the chain with the chain receiver`() {
        val constructor = ContractTargets.Constructed::class.java.getConstructor(String::class.java)
        val instance = Any()
        var observed: Any? = null
        var argument: Any? = null
        var outcome: CallOutcome? = null
        hooks.install(owner, constructor, HookSpec("observe", after = {
            observed = it.receiver
            argument = it.argument(0)
            outcome = it.outcome
            it.returnValue("illegal")
        }))
        assertNull(framework.call(constructor, instance, "created"))
        assertSame(instance, observed)
        assertEquals("created", argument)
        assertEquals(CallOutcome.Returned(null), outcome)
        assertEquals(1, framework.proceeds.get())
        assertTrue(failures.single().error is IllegalStateException)
    }

    @Test fun `failed constructor skips observers and keeps the host exception`() {
        val constructor = ContractTargets.Constructed::class.java.getConstructor(String::class.java)
        var observed = 0
        hooks.install(owner, constructor, HookSpec("observe", after = { observed++ }))
        val error = assertThrows(IllegalStateException::class.java) { framework.call(constructor, Any(), null) }
        assertEquals("construction-failed", error.message)
        assertEquals(0, observed)
        assertTrue(failures.isEmpty())
    }

    @Test fun `constructor only offers successful observation`() {
        val constructor = ContractTargets.Constructed::class.java.getConstructor(String::class.java)
        assertEquals(SupportLevel.AVAILABLE, backend.support(constructor, HookCapability.CONSTRUCTOR_OBSERVE_AFTER).level)
        listOf(HookCapability.METHOD_BEFORE_AFTER, HookCapability.ARGUMENT_REWRITE, HookCapability.EARLY_RETURN, HookCapability.OUTCOME_REWRITE)
            .forEach { assertEquals(SupportLevel.UNAVAILABLE, backend.support(constructor, it).level) }
        assertThrows(IllegalArgumentException::class.java) { hooks.install(owner, constructor, HookSpec("before", before = {})) }
        assertThrows(IllegalArgumentException::class.java) {
            hooks.install(owner, constructor, HookSpec("rewrite", requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE), after = {}))
        }
        assertEquals(0, framework.attaches.get())
    }

    @Test fun `failed framework attachment is not published and can be retried`() {
        framework.attachFailure = IllegalStateException("attach failed")
        assertThrows(IllegalStateException::class.java) { hooks.install(owner, echo, HookSpec("h", before = {})) }
        assertTrue(hooks.snapshot().isEmpty())
        framework.attachFailure = null
        hooks.install(owner, echo, HookSpec("h", before = { it.returnEarly("retry") }))
        assertEquals("retry", framework.call(echo, null, "v"))
    }

    @Test fun `framework configuration uses fixed outer priority and passthrough`() {
        hooks.install(owner, echo, HookSpec("h", priority = 9000, before = {}))
        val registration = framework.registrations.getValue(echo)
        assertEquals(XposedInterface.PRIORITY_DEFAULT, registration.priority)
        assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, registration.mode)
    }

    @Test fun `real API adapter and existing fixture complete the feature lifecycle`() {
        val event = LoadEvent(
            ProcessIdentity(1, 10001, "io.github.nuke.oss.fixture", "io.github.nuke.oss.fixture"),
            BuildIdentity("io.github.nuke.oss", "test", "g"), "libxposed102", LoadStage.HOST_CLASSLOADER_READY,
            requireNotNull(ProbeTarget::class.java.classLoader),
        )
        val result = ProbeBootstrap().accept(event, BootstrapServices(backend, Logger {})) as BootstrapResult.Started
        val greet = ProbeTarget::class.java.getMethod("greet", String::class.java)
        val toggle = ProbeTarget::class.java.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        val describe = ProbeTarget::class.java.getMethod("describe")
        assertEquals("hooked:original:NukeApp", framework.call(greet, null, "Alice"))
        assertTrue((framework.call(describe) as String).contains("backend=libxposed102"))
        framework.call(toggle, null, false)
        assertEquals("original:Alice", framework.call(greet, null, "Alice"))
        framework.call(toggle, null, true)
        assertEquals("hooked:original:NukeApp", framework.call(greet, null, "Alice"))
        assertEquals(3, framework.attaches.get())
        result.session.close()
        assertEquals(3, framework.unhooks.get())
    }
}
