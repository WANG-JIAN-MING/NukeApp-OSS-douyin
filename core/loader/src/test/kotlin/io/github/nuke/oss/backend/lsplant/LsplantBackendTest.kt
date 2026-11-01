package io.github.nuke.oss.backend.lsplant

import io.github.nuke.oss.testing.ProbeBootstrap
import io.github.nuke.oss.core.*
import io.github.nuke.oss.fixture.ProbeTarget
import io.github.nuke.oss.hook.api.*
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.ContractTargets
import io.github.nuke.oss.runtime.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LsplantBackendTest {
    private val driver = TestDriver()
    private val backend = LsplantBackend(driver)
    private val failures = ArrayList<HookFailure>()
    private val hooks = DefaultHookService(backend, HookFailureSink { failures += it })
    private val owner = HookOwner("feature", "generation")
    private val echo = ContractTargets::class.java.getMethod("echo", String::class.java)

    @After fun callbackAssertionsRemainObservable() {
        failures.firstOrNull { it.error is AssertionError }?.let { throw it.error }
    }

    @Test fun `argument copy is rewritten and original is called once`() {
        hooks.install(owner, echo, HookSpec("rewrite", before = { it.setArgument(0, "changed") }, after = {
            assertEquals("changed", it.argument(0))
            it.returnValue("after:${(it.outcome as CallOutcome.Returned).value}")
        }))
        val source = arrayOf<Any?>("source")
        assertEquals("after:changed", driver.callArray(echo, null, source))
        assertEquals("source", source.single())
        assertEquals(1, driver.originalCalls.get())
        assertTrue(failures.isEmpty())
    }

    @Test fun `explicit null return skips original`() {
        hooks.install(owner, echo, HookSpec("short", before = { it.returnEarly(null) }))
        assertNull(driver.call(echo, null, "value"))
        assertEquals(0, driver.originalCalls.get())
    }

    @Test fun `before target exception is deliberate and skips original`() {
        val expected = IllegalStateException("deliberate")
        hooks.install(owner, echo, HookSpec("throw", before = { it.throwEarly(expected) }))
        assertSame(expected, assertThrows(IllegalStateException::class.java) { driver.call(echo, null, "value") })
        assertEquals(0, driver.originalCalls.get())
        assertTrue(failures.isEmpty())
    }

    @Test fun `after can replace the result with a deliberate exception`() {
        val expected = IllegalStateException("after")
        hooks.install(owner, echo, HookSpec("throw", after = { it.throwError(expected) }))
        assertSame(expected, assertThrows(IllegalStateException::class.java) { driver.call(echo, null, "value") })
        assertEquals(1, driver.originalCalls.get())
        assertTrue(failures.isEmpty())
    }

    @Test fun `reflection wrapper is unwrapped by driver`() {
        val fail = ContractTargets::class.java.getMethod("fail")
        hooks.install(owner, fail, HookSpec("observe", after = {}))
        assertEquals("host-failure", assertThrows(IllegalStateException::class.java) { driver.call(fail) }.message)
        assertTrue(failures.isEmpty())
    }

    @Test fun `a target deliberately throwing InvocationTargetException preserves that throwable`() {
        val expected = InvocationTargetException(IllegalStateException("nested"), "deliberate")
        driver.original = { _, _, _ -> throw expected }
        hooks.install(owner, echo, HookSpec("observe", after = {}))
        assertSame(expected, assertThrows(InvocationTargetException::class.java) { driver.call(echo, null, "value") })
    }

    @Test fun `after can recover a target exception`() {
        val fail = ContractTargets::class.java.getMethod("fail")
        hooks.install(owner, fail, HookSpec("recover", after = {
            assertTrue(it.outcome is CallOutcome.Thrown)
            it.returnValue("recovered")
        }))
        assertEquals("recovered", driver.call(fail))
        assertEquals(1, driver.originalCalls.get())
    }

    @Test fun `broken before rolls back and calls original once`() {
        hooks.install(owner, echo, HookSpec("broken", before = {
            it.setArgument(0, "bad")
            it.returnEarly("bad")
            error("callback")
        }))
        assertEquals("source", driver.call(echo, null, "source"))
        assertEquals("before", failures.single().phase)
        assertEquals(1, driver.originalCalls.get())
    }

    @Test fun `broken after rolls back without repeating original`() {
        hooks.install(owner, echo, HookSpec("broken", after = {
            it.returnValue("bad")
            error("callback")
        }))
        assertEquals("source", driver.call(echo, null, "source"))
        assertEquals("after", failures.single().phase)
        assertEquals(1, driver.originalCalls.get())
    }

    @Test fun `dispatcher preserves ordered pairing and early exit`() {
        val events = ArrayList<String>()
        hooks.install(owner, echo, HookSpec("a", priority = 20, before = { events += "a+" }, after = { events += "a-" }))
        hooks.install(owner, echo, HookSpec("b", priority = 10, before = { events += "b+"; it.returnEarly("short") }, after = { events += "b-" }))
        hooks.install(owner, echo, HookSpec("c", after = { events += "c-" }))
        assertEquals("short", driver.call(echo, null, "value"))
        assertEquals(listOf("a+", "b+", "b-", "a-"), events)
        assertEquals(1, driver.installs.get())
        assertEquals(0, driver.originalCalls.get())
    }

    @Test fun `primitive and void return validation remain shared`() {
        val number = ContractTargets::class.java.getMethod("number", Int::class.javaPrimitiveType)
        val nothing = ContractTargets::class.java.getMethod("nothing")
        hooks.install(owner, number, HookSpec("bad", before = { it.returnEarly(null) }))
        hooks.install(owner, nothing, HookSpec("bad", after = { it.returnValue(7) }))
        assertEquals(3, driver.call(number, null, 3))
        assertNull(driver.call(nothing))
        assertEquals(2, failures.size)
        assertEquals(2, driver.originalCalls.get())
    }

    @Test fun `instance receiver is retained for the backup`() {
        val method = ContractTargets::class.java.getMethod("instance", String::class.java)
        val receiver = ContractTargets()
        hooks.install(owner, method, HookSpec("observe", before = { assertSame(receiver, it.receiver) }))
        assertEquals("instance:value", driver.call(method, receiver, "value"))
    }

    @Test fun `logical disable and reenable retain one native registration`() {
        val first = hooks.install(owner, echo, HookSpec("a", before = { it.returnEarly("enabled") }))
        first.close()
        first.close()
        assertEquals("disabled", driver.call(echo, null, "disabled"))
        hooks.install(owner, echo, HookSpec("a", before = { it.returnEarly("again") }))
        assertEquals("again", driver.call(echo, null, "disabled"))
        assertEquals(1, driver.installs.get())
        assertEquals(0, driver.unhooks.get())
        hooks.close()
        hooks.close()
        assertEquals(1, driver.unhooks.get())
    }

    @Test fun `recursive calls keep independent frames and after pairing`() {
        var nested = false
        var exits = 0
        hooks.install(owner, echo, HookSpec("recursive", before = {
            if (!nested) {
                nested = true
                assertEquals("inner", driver.call(echo, null, "inner"))
                nested = false
            }
        }, after = { exits++ }))
        assertEquals("outer", driver.call(echo, null, "outer"))
        assertEquals(2, exits)
        assertEquals(0, hooks.snapshot().single().inFlight)
    }

    @Test fun `in-flight call pairs after while future calls see logical disable`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val subscription = hooks.install(owner, echo, HookSpec("block", before = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }, after = { it.returnValue("paired") }))
            val first = executor.submit<Any?> { driver.call(echo, null, "first") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            subscription.close()
            assertEquals("second", driver.call(echo, null, "second"))
            release.countDown()
            assertEquals("paired", first.get(5, TimeUnit.SECONDS))
            assertEquals(0, hooks.snapshot().single().inFlight)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `uninitialized driver does not advertise or install any capability`() {
        driver.isReady = false
        HookCapability.entries.forEach { assertEquals(SupportLevel.UNAVAILABLE, backend.support(echo, it).level) }
        assertThrows(BackendUnavailableException::class.java) { backend.attach(echo, emptyCallbacks()) }
        assertThrows(IllegalArgumentException::class.java) { hooks.install(owner, echo, HookSpec("a", before = {})) }
        assertEquals(0, driver.installs.get())
        assertTrue(hooks.snapshot().isEmpty())
    }

    @Test fun `native and abstract methods do not claim support`() {
        val native = UnsupportedTargets::class.java.getMethod("nativeCall")
        val abstract = UnsupportedTargets::class.java.getMethod("abstractCall")
        listOf(native, abstract).forEach {
            assertEquals(SupportLevel.UNVERIFIED, backend.support(it, HookCapability.METHOD_BEFORE_AFTER).level)
            assertThrows(IllegalArgumentException::class.java) { backend.attach(it, emptyCallbacks()) }
        }
        assertEquals(0, driver.installs.get())
    }

    private val constructor = ContractTargets.Constructed::class.java.getConstructor(String::class.java)

    @Test fun `constructor is observed after its backup with the instance being initialised`() {
        val instance = Any()
        var observed: Any? = null
        var argument: Any? = null
        var outcome: CallOutcome? = null
        hooks.install(owner, constructor, HookSpec("observe", after = {
            observed = it.receiver
            argument = it.argument(0)
            outcome = it.outcome
        }))
        assertNull(driver.callArray(constructor, instance, arrayOf("created")))
        assertSame(instance, observed)
        assertEquals("created", argument)
        assertEquals(CallOutcome.Returned(null), outcome)
        assertEquals(1, driver.originalCalls.get())
    }

    @Test fun `failed constructor skips observers and keeps the host exception`() {
        var observed = 0
        hooks.install(owner, constructor, HookSpec("observe", after = { observed++ }))
        val error = assertThrows(IllegalStateException::class.java) { driver.callArray(constructor, Any(), arrayOf(null)) }
        assertEquals("construction-failed", error.message)
        assertEquals(0, observed)
        assertTrue(failures.isEmpty())
    }

    @Test fun `constructor only offers successful observation`() {
        assertEquals(SupportLevel.AVAILABLE, backend.support(constructor, HookCapability.CONSTRUCTOR_OBSERVE_AFTER).level)
        listOf(HookCapability.METHOD_BEFORE_AFTER, HookCapability.ARGUMENT_REWRITE, HookCapability.EARLY_RETURN, HookCapability.OUTCOME_REWRITE)
            .forEach { assertEquals(SupportLevel.UNAVAILABLE, backend.support(constructor, it).level) }
        assertThrows(IllegalArgumentException::class.java) { hooks.install(owner, constructor, HookSpec("before", before = {})) }
        driver.isReady = false
        assertEquals(SupportLevel.UNAVAILABLE, backend.support(constructor, HookCapability.CONSTRUCTOR_OBSERVE_AFTER).level)
        assertEquals(0, driver.installs.get())
    }

    @Test fun `only the four portable capabilities are exposed`() {
        val enabled = setOf(HookCapability.METHOD_BEFORE_AFTER, HookCapability.ARGUMENT_REWRITE,
            HookCapability.EARLY_RETURN, HookCapability.OUTCOME_REWRITE)
        HookCapability.entries.forEach {
            assertEquals(if (it in enabled) SupportLevel.AVAILABLE else SupportLevel.UNAVAILABLE, backend.support(echo, it).level)
        }
    }

    @Test fun `failed native installation publishes no subscription and can retry`() {
        driver.installFailure = IllegalStateException("attach-failed")
        assertThrows(IllegalStateException::class.java) { hooks.install(owner, echo, HookSpec("a", before = {})) }
        assertTrue(hooks.snapshot().isEmpty())
        driver.installFailure = null
        hooks.install(owner, echo, HookSpec("a", before = { it.returnEarly("retry") }))
        assertEquals("retry", driver.call(echo, null, "source"))
    }

    @Test fun `retirement is idempotent and retries a failed native unhook`() {
        val binding = backend.attach(echo, emptyCallbacks())
        driver.unhookFailure = IllegalStateException("unhook-failed")
        assertThrows(IllegalStateException::class.java) { binding.retire() }
        driver.unhookFailure = null
        binding.retire()
        binding.retire()
        assertEquals(1, driver.unhooks.get())
    }

    @Test fun `closing from before retains the backup until its paired after has completed`() {
        val events = ArrayList<String>()
        driver.original = { _, _, arguments ->
            assertEquals(0, driver.unhooks.get())
            events += "original"
            arguments.single()
        }
        hooks.install(owner, echo, HookSpec("close", before = {
            events += "before"
            hooks.close()
            assertEquals(0, driver.unhooks.get())
        }, after = {
            events += "after"
            assertEquals(0, driver.unhooks.get())
        }))
        assertEquals("value", driver.call(echo, null, "value"))
        assertEquals(listOf("before", "original", "after"), events)
        assertEquals(1, driver.unhooks.get())
        hooks.close()
        assertEquals(1, driver.unhooks.get())
    }

    @Test fun `external close defers native unhook while another call still uses backup`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            driver.original = { _, _, arguments ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                assertEquals(0, driver.unhooks.get())
                arguments.single()
            }
            hooks.install(owner, echo, HookSpec("observe", after = {
                assertEquals(0, driver.unhooks.get())
                it.returnValue("paired")
            }))
            val first = executor.submit<Any?> { driver.call(echo, null, "first") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            hooks.close()
            assertEquals(0, driver.unhooks.get())
            release.countDown()
            assertEquals("paired", first.get(5, TimeUnit.SECONDS))
            assertEquals(1, driver.unhooks.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `native callback published during installation waits for its binding`() {
        val dispatched = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var result: java.util.concurrent.Future<Any?>? = null
        driver.beforeCallback = { dispatched.countDown() }
        val racingDriver = object : LsplantDriver {
            override val isReady = true
            override fun install(target: Executable, callback: (Any?, Array<Any?>) -> Any?): LsplantBinding {
                val installed = driver.install(target, callback)
                result = executor.submit<Any?> { driver.call(target as Method, null, "raced") }
                check(dispatched.await(5, TimeUnit.SECONDS))
                assertFalse(requireNotNull(result).isDone)
                return installed
            }
        }
        try {
            val binding = LsplantBackend(racingDriver).attach(echo, emptyCallbacks())
            assertEquals("raced", requireNotNull(result).get(5, TimeUnit.SECONDS))
            assertEquals(1, driver.originalCalls.get())
            binding.retire()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `LSPlant backend reuses existing assembly and fixture lifecycle`() {
        val event = LoadEvent(
            ProcessIdentity(1, 10001, "io.github.nuke.oss.fixture", "io.github.nuke.oss.fixture"),
            BuildIdentity("io.github.nuke.oss", "test", "g"), "lsplant", LoadStage.HOST_CLASSLOADER_READY,
            requireNotNull(ProbeTarget::class.java.classLoader),
        )
        val result = ProbeBootstrap().accept(event, BootstrapServices(backend, Logger {})) as BootstrapResult.Started
        val greet = ProbeTarget::class.java.getMethod("greet", String::class.java)
        val toggle = ProbeTarget::class.java.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        val describe = ProbeTarget::class.java.getMethod("describe")
        assertEquals("hooked:original:NukeApp", driver.call(greet, null, "Alice"))
        assertTrue((driver.call(describe) as String).contains("backend=lsplant"))
        driver.call(toggle, null, false)
        assertEquals("original:Alice", driver.call(greet, null, "Alice"))
        driver.call(toggle, null, true)
        assertEquals("hooked:original:NukeApp", driver.call(greet, null, "Alice"))
        assertEquals(3, driver.installs.get())
        result.session.close()
        assertEquals(3, driver.unhooks.get())
    }

    private fun emptyCallbacks() = object : BackendCallbacks {
        override fun enter(frame: BackendFrame) = object : InvocationToken {}
        override fun exit(frame: BackendFrame, token: InvocationToken) = Unit
    }

    abstract class UnsupportedTargets {
        external fun nativeCall(): Any?
        abstract fun abstractCall(): Any?
    }

    private class TestDriver : LsplantDriver {
        override var isReady = true
        val installs = AtomicInteger()
        val originalCalls = AtomicInteger()
        val unhooks = AtomicInteger()
        private val callbacks = ConcurrentHashMap<Executable, (Any?, Array<Any?>) -> Any?>()
        var installFailure: Throwable? = null
        var unhookFailure: Throwable? = null
        var beforeCallback: (() -> Unit)? = null
        // Java cannot initialise an existing instance, so a constructor's body runs for its outcome only.
        var original: (Executable, Any?, Array<Any?>) -> Any? = { target, receiver, arguments ->
            try {
                when (target) {
                    is Method -> target.invoke(receiver, *arguments)
                    is Constructor<*> -> null.also { target.newInstance(*arguments) }
                    else -> error("Unsupported target $target")
                }
            } catch (error: InvocationTargetException) { throw error.targetException }
        }

        override fun install(target: Executable, callback: (Any?, Array<Any?>) -> Any?): LsplantBinding {
            installFailure?.let { throw it }
            check(callbacks.putIfAbsent(target, callback) == null)
            installs.incrementAndGet()
            return object : LsplantBinding {
                override fun invokeOriginal(receiver: Any?, arguments: Array<Any?>): Any? {
                    originalCalls.incrementAndGet()
                    return original(target, receiver, arguments)
                }
                override fun unhook() {
                    unhookFailure?.let { throw it }
                    callbacks.remove(target, callback)
                    unhooks.incrementAndGet()
                }
            }
        }

        fun callArray(target: Executable, receiver: Any?, arguments: Array<Any?>): Any? {
            val callback = callbacks[target]
            return if (callback == null) original(target, receiver, arguments) else {
                beforeCallback?.invoke()
                callback(receiver, arguments)
            }
        }

        fun call(target: Method, receiver: Any? = null, vararg arguments: Any?): Any? =
            callArray(target, receiver, arrayOf(*arguments))
    }
}
