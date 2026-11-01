package io.github.nuke.oss.backend.xposed82

import org.junit.Assert.*
import org.junit.Test
import io.github.nuke.oss.testing.ProbeBootstrap
import io.github.nuke.oss.core.*
import io.github.nuke.oss.fixture.ProbeTarget
import io.github.nuke.oss.hook.api.*
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.ContractTargets
import io.github.nuke.oss.runtime.*

class Xposed82BackendTest {
    private val driver = LegacyProtocolTestDriver()
    private val backend = Xposed82Backend(driver)
    private val hooks = DefaultHookService(backend)
    private val owner = HookOwner("feature", "generation")
    private val target = ContractTargets::class.java.getMethod("echo", String::class.java)

    @Test fun `legacy adapter maps arguments results and logical disable`() {
        val handle = hooks.install(owner, target, HookSpec("rewrite", before = { it.setArgument(0, "changed") }, after = {
            it.returnValue("after:${(it.outcome as CallOutcome.Returned).value}")
        }))
        assertEquals("after:changed", driver.invoke(target, null, "original"))
        handle.close()
        assertEquals("original", driver.invoke(target, null, "original"))
        assertEquals(1, driver.attachCount.get())
    }

    @Test fun `legacy adapter preserves early null return without invoking original`() {
        hooks.install(owner, target, HookSpec("short", before = { it.returnEarly(null) }))
        assertNull(driver.invoke(target, null, "original"))
        assertEquals(0, driver.originalCalls.get())
    }

    @Test fun `legacy adapter propagates deliberate target exceptions`() {
        val expected = IllegalStateException("intentional")
        hooks.install(owner, target, HookSpec("short", before = { it.throwEarly(expected) }))
        assertSame(expected, assertThrows(IllegalStateException::class.java) { driver.invoke(target, null, "v") })
        assertEquals(0, driver.originalCalls.get())
    }

    @Test fun `legacy adapter leaves host failures visible`() {
        val fail = ContractTargets::class.java.getMethod("fail")
        hooks.install(owner, fail, HookSpec("observe", after = {}))
        assertEquals("host-failure", assertThrows(IllegalStateException::class.java) { driver.invoke(fail) }.message)
    }

    @Test fun `after without our before does nothing`() {
        hooks.install(owner, target, HookSpec("after", after = { it.returnValue("changed") }))
        val call = LegacyProtocolTestDriver.Invocation(null, arrayOf("v"))
        call.outcome = CallOutcome.Returned("external")
        driver.installed.getValue(target).after(call)
        assertEquals(CallOutcome.Returned("external"), call.outcome)
    }

    @Test fun `legacy parameter state is removed after the call`() {
        hooks.install(owner, target, HookSpec("observe", before = {}, after = {}))
        val call = LegacyProtocolTestDriver.Invocation(null, arrayOf("v"))
        val callback = driver.installed.getValue(target)
        callback.before(call)
        assertEquals(1, call.stateCount)
        call.outcome = CallOutcome.Returned("v")
        callback.after(call)
        assertEquals(0, call.stateCount)
        callback.after(call)
        assertEquals(0, hooks.snapshot().single().inFlight)
    }

    @Test fun `constructor is observed after success with the framework receiver`() {
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
        assertNull(driver.invoke(constructor, instance, "created"))
        assertSame(instance, observed)
        assertEquals("created", argument)
        assertEquals(CallOutcome.Returned(null), outcome)
        assertEquals(1, driver.originalCalls.get())
    }

    @Test fun `failed constructor skips observers and keeps the host exception`() {
        val constructor = ContractTargets.Constructed::class.java.getConstructor(String::class.java)
        var observed = 0
        hooks.install(owner, constructor, HookSpec("observe", after = { observed++ }))
        val error = assertThrows(IllegalStateException::class.java) { driver.invoke(constructor, Any(), null) }
        assertEquals("construction-failed", error.message)
        assertEquals(0, observed)
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
        assertEquals(0, driver.attachCount.get())
    }

    @Test fun `advanced capabilities are not silently simulated`() {
        assertEquals(SupportLevel.UNAVAILABLE, backend.support(target, HookCapability.NATIVE_AROUND_CHAIN).level)
        assertEquals(SupportLevel.UNAVAILABLE, backend.support(target, HookCapability.BACKEND_ORIGINAL_INVOKE).level)
    }

    @Test fun `assembly to legacy callback protocol completes the fixture lifecycle`() {
        val assembly = ProbeBootstrap()
        val event = LoadEvent(
            ProcessIdentity(1, 10001, "io.github.nuke.oss.fixture", "io.github.nuke.oss.fixture"),
            BuildIdentity("io.github.nuke.oss", "test", "generation"), "xposed82", LoadStage.HOST_CLASSLOADER_READY,
            requireNotNull(ProbeTarget::class.java.classLoader),
        )
        val result = assembly.accept(event, BootstrapServices(backend, Logger {})) as BootstrapResult.Started
        val greet = ProbeTarget::class.java.getMethod("greet", String::class.java)
        val toggle = ProbeTarget::class.java.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        assertEquals("hooked:original:NukeApp", driver.invoke(greet, null, "Alice"))
        driver.invoke(toggle, null, false)
        assertEquals("original:Alice", driver.invoke(greet, null, "Alice"))
        driver.invoke(toggle, null, true)
        assertEquals("hooked:original:NukeApp", driver.invoke(greet, null, "Alice"))
        assertEquals(3, driver.attachCount.get())
        result.session.close()
    }
}
