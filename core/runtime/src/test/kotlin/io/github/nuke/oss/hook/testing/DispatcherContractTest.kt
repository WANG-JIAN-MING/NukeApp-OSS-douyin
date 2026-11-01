package io.github.nuke.oss.hook.testing

import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import io.github.nuke.oss.hook.api.*
import io.github.nuke.oss.hook.runtime.DefaultHookService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DispatcherContractTest {
    private val owner = HookOwner("feature", "generation")
    private val echo = ContractTargets::class.java.getMethod("echo", String::class.java)
    private val number = ContractTargets::class.java.getMethod("number", Int::class.javaPrimitiveType)
    private val backend = FakeHookBackend()
    private val failures = ArrayList<HookFailure>()
    private val hooks = DefaultHookService(backend, HookFailureSink { failures += it })

    @After fun callbackAssertionsRemainObservable() {
        failures.firstOrNull { it.error is AssertionError }?.let { throw it.error }
    }

    @Test fun `priority runs before forward and after reverse`() {
        val events = ArrayList<String>()
        hooks.install(owner, echo, HookSpec("b", priority = 1, before = { events += "b+" }, after = { events += "b-" }))
        hooks.install(owner, echo, HookSpec("a", priority = 2, before = { events += "a+" }, after = { events += "a-" }))
        assertEquals("value", backend.call(echo, null, "value"))
        assertEquals(listOf("a+", "b+", "b-", "a-"), events)
        assertEquals(1, backend.attachments.get())
        assertEquals(1, backend.originalCalls.get())
    }

    @Test fun `equal priorities use stable owner and subscription IDs`() {
        val events = ArrayList<String>()
        hooks.install(owner, echo, HookSpec("z", before = { events += "z" }))
        hooks.install(owner, echo, HookSpec("a", before = { events += "a" }))
        backend.call(echo, null, "v")
        assertEquals(listOf("a", "z"), events)
    }

    @Test fun `early return stops unentered callbacks and still pairs after`() {
        val events = ArrayList<String>()
        hooks.install(owner, echo, HookSpec("a", before = { events += "a+"; it.returnEarly(null) }, after = { events += "a-" }))
        hooks.install(owner, echo, HookSpec("b", before = { events += "b+" }, after = { events += "b-" }))
        assertNull(backend.call(echo, null, "ignored"))
        assertEquals(listOf("a+", "a-"), events)
        assertEquals(0, backend.originalCalls.get())
    }

    @Test fun `after-only subscription participates in entry ordering`() {
        val events = ArrayList<String>()
        hooks.install(owner, echo, HookSpec("a", after = { events += "a-" }))
        hooks.install(owner, echo, HookSpec("b", before = { it.returnEarly("short") }, after = { events += "b-" }))
        hooks.install(owner, echo, HookSpec("c", after = { events += "c-" }))
        assertEquals("short", backend.call(echo, null, "v"))
        assertEquals(listOf("b-", "a-"), events)
    }

    @Test fun `arguments and returned value are rewritten`() {
        hooks.install(owner, echo, HookSpec("change", before = { it.setArgument(0, "new") }, after = {
            assertEquals("new", it.argument(0))
            it.returnValue("after:${(it.outcome as CallOutcome.Returned).value}")
        }))
        assertEquals("after:new", backend.call(echo, null, "old"))
    }

    @Test fun `explicit throwable short circuits and can be recovered after`() {
        val expected = IllegalStateException("intentional")
        hooks.install(owner, echo, HookSpec("change", before = { it.throwEarly(expected) }, after = {
            assertSame(expected, (it.outcome as CallOutcome.Thrown).error)
            it.returnValue("recovered")
        }))
        assertEquals("recovered", backend.call(echo, null, "v"))
        assertEquals(0, backend.originalCalls.get())
        assertTrue(failures.isEmpty())
    }

    @Test fun `host exception remains a host exception`() {
        val fail = ContractTargets::class.java.getMethod("fail")
        hooks.install(owner, fail, HookSpec("observe", after = { assertTrue(it.outcome is CallOutcome.Thrown) }))
        val error = assertThrows(IllegalStateException::class.java) { backend.call(fail) }
        assertEquals("host-failure", error.message)
        assertTrue(failures.isEmpty())
    }

    @Test fun `broken before restores both arguments and terminal decision`() {
        hooks.install(owner, echo, HookSpec("broken", before = {
            it.setArgument(0, "corrupted")
            it.returnEarly("short")
            error("callback-failure")
        }))
        assertEquals("original", backend.call(echo, null, "original"))
        assertEquals("before", failures.single().phase)
        assertEquals(1, backend.originalCalls.get())
    }

    @Test fun `broken after preserves prior outcome`() {
        hooks.install(owner, echo, HookSpec("broken", after = { it.returnValue("corrupted"); error("failure") }))
        assertEquals("original", backend.call(echo, null, "original"))
        assertEquals("after", failures.single().phase)
    }

    @Test fun `primitive cannot return null`() {
        hooks.install(owner, number, HookSpec("bad", before = { it.returnEarly(null) }))
        assertEquals(7, backend.call(number, null, 7))
        assertTrue(failures.single().error is IllegalArgumentException)
    }

    @Test fun `incompatible argument rolls back the callback`() {
        hooks.install(owner, number, HookSpec("bad", before = { it.setArgument(0, "wrong") }))
        assertEquals(7, backend.call(number, null, 7))
        assertEquals(1, failures.size)
    }

    @Test fun `void cannot manufacture a return value`() {
        val nothing = ContractTargets::class.java.getMethod("nothing")
        hooks.install(owner, nothing, HookSpec("bad", after = { it.returnValue("wrong") }))
        assertNull(backend.call(nothing))
        assertEquals(1, failures.size)
    }

    @Test fun `instance receiver is preserved`() {
        val receiver = ContractTargets()
        val target = ContractTargets::class.java.getMethod("instance", String::class.java)
        hooks.install(owner, target, HookSpec("observe", before = { assertSame(receiver, it.receiver) }))
        assertEquals("instance:v", backend.call(target, receiver, "v"))
    }

    @Test fun `duplicate subscription rejected without another backend hook`() {
        hooks.install(owner, echo, HookSpec("same", before = {}))
        assertThrows(IllegalArgumentException::class.java) { hooks.install(owner, echo, HookSpec("same", before = {})) }
        assertEquals(1, backend.attachments.get())
    }

    @Test fun `closing one subscription does not close another and can be repeated`() {
        val first = hooks.install(owner, echo, HookSpec("a", before = { it.setArgument(0, "first") }))
        hooks.install(owner, echo, HookSpec("b", after = { it.returnValue("second") }))
        first.close(); first.close()
        assertEquals("second", backend.call(echo, null, "original"))
        assertEquals(1, hooks.snapshot().single().subscriptions)
        assertEquals(1, backend.attachments.get())
    }

    @Test fun `disable and reenable reuse the bottom attachment`() {
        val first = hooks.install(owner, echo, HookSpec("a", before = { it.returnEarly("on") }))
        first.close()
        assertEquals("off", backend.call(echo, null, "off"))
        hooks.install(owner, echo, HookSpec("a", before = { it.returnEarly("again") }))
        first.close()
        assertEquals("again", backend.call(echo, null, "off"))
        assertEquals(1, backend.attachments.get())
    }

    @Test fun `callback view expires as soon as callback returns`() {
        var retained: BeforeCall? = null
        hooks.install(owner, echo, HookSpec("capture", before = { retained = it }))
        backend.call(echo, null, "v")
        assertThrows(IllegalStateException::class.java) { retained!!.argument(0) }
    }

    @Test fun `recursive entry has independent call frames`() {
        var inside = false
        var exits = 0
        hooks.install(owner, echo, HookSpec("nested", before = {
            if (!inside) {
                inside = true
                assertEquals("inner", backend.call(echo, null, "inner"))
                inside = false
            }
        }, after = { exits++ }))
        assertEquals("outer", backend.call(echo, null, "outer"))
        assertEquals(2, exits)
        assertEquals(0, hooks.snapshot().single().inFlight)
    }

    @Test fun `in-flight call pairs after while later calls see disable`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val handle = hooks.install(owner, echo, HookSpec("blocked", before = {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }, after = { it.returnValue("paired") }))
            val call = executor.submit<Any?> { backend.call(echo, null, "first") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            handle.close()
            assertEquals("second", backend.call(echo, null, "second"))
            resume.countDown()
            assertEquals("paired", call.get(5, TimeUnit.SECONDS))
            assertEquals(0, hooks.snapshot().single().inFlight)
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test fun `concurrent installations attach the target once`() {
        val executor = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val calls = (1..12).map { index -> executor.submit {
                check(start.await(5, TimeUnit.SECONDS))
                hooks.install(HookOwner("feature-$index", "g"), echo, HookSpec("hook", before = {}))
            } }
            start.countDown()
            calls.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, backend.attachments.get())
            assertEquals(12, hooks.snapshot().single().subscriptions)
        } finally { executor.shutdownNow() }
    }

    @Test fun `constructor can be observed but not replaced`() {
        val constructor = ContractTargets.Constructed::class.java.getConstructor(String::class.java)
        var observed: Any? = null
        hooks.install(owner, constructor, HookSpec("observe", after = {
            observed = it.receiver
            it.returnValue("illegal")
        }))
        assertNull(backend.call(constructor, null, "created"))
        assertEquals("created", (observed as ContractTargets.Constructed).value)
        assertEquals(1, failures.size)
    }

    @Test fun `constructor failure never calls successful observer`() {
        val constructor = ContractTargets.Constructed::class.java.getConstructor(String::class.java)
        val observed = AtomicInteger()
        hooks.install(owner, constructor, HookSpec("observe", after = { observed.incrementAndGet() }))
        assertThrows(IllegalStateException::class.java) { backend.call(constructor, null, null) }
        assertEquals(0, observed.get())
    }

    @Test fun `constructor before is rejected at installation`() {
        val constructor = ContractTargets.Constructed::class.java.getConstructor(String::class.java)
        assertThrows(IllegalArgumentException::class.java) { hooks.install(owner, constructor, HookSpec("before", before = {})) }
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `broken diagnostic sink does not repeat the original call`() {
        val service = DefaultHookService(backend, HookFailureSink { error("sink") })
        service.install(owner, echo, HookSpec("bad", before = { error("callback") }))
        assertEquals("original", backend.call(echo, null, "original"))
        assertEquals(1, service.diagnosticSinkFailures)
        assertEquals(1, backend.originalCalls.get())
    }

    @Test fun `service close releases all registrations and rejects new ones`() {
        hooks.install(owner, echo, HookSpec("a", before = { it.returnEarly("on") }))
        hooks.close(); hooks.close()
        assertEquals("off", backend.call(echo, null, "off"))
        assertThrows(IllegalStateException::class.java) { hooks.install(owner, echo, HookSpec("b", before = {})) }
    }

    @Test fun `same class name in different classloaders keeps separate target entries`() {
        val name = ContractTargets::class.java.name
        val bytes = ContractTargets::class.java.getResourceAsStream("ContractTargets.class")!!.use { it.readBytes() }
        fun load(): Class<*> = object : ClassLoader(null) {
            fun defined(): Class<*> = defineClass(name, bytes, 0, bytes.size)
        }.defined()
        val first = load().getMethod("echo", String::class.java)
        val second = load().getMethod("echo", String::class.java)
        hooks.install(owner, first, HookSpec("same", before = { it.returnEarly("first") }))
        hooks.install(owner, second, HookSpec("same", before = { it.returnEarly("second") }))
        assertEquals("first", backend.call(first, null, "x"))
        assertEquals("second", backend.call(second, null, "x"))
        assertEquals(2, backend.attachments.get())
    }

    @Test fun `failed bottom installation is not published and can be retried`() {
        var fail = true
        val adapter = object : HookBackend by backend {
            override fun attach(target: java.lang.reflect.Executable, callbacks: BackendCallbacks): BackendBinding {
                if (fail) { fail = false; error("installation failed") }
                return backend.attach(target, callbacks)
            }
        }
        val service = DefaultHookService(adapter)
        assertThrows(IllegalStateException::class.java) { service.install(owner, echo, HookSpec("same", before = {})) }
        assertTrue(service.snapshot().isEmpty())
        service.install(owner, echo, HookSpec("same", before = { it.returnEarly("retry") }))
        assertEquals("retry", backend.call(echo, null, "v"))
        assertEquals(1, backend.attachments.get())
    }

    @Test fun `fatal VM or linkage errors are not hidden as successful callbacks`() {
        val failure = LinkageError("fatal-backend-or-vm-condition")
        hooks.install(owner, echo, HookSpec("fatal", before = { throw failure }))
        assertSame(failure, assertThrows(LinkageError::class.java) { backend.call(echo, null, "v") })
        assertTrue(failures.isEmpty())
        assertEquals(0, hooks.snapshot().single().inFlight)
    }
}
