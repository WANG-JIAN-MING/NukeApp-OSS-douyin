package io.github.nuke.oss.runtime

import org.junit.Assert.*
import org.junit.Test
import io.github.nuke.oss.core.FeatureState
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.*
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.ContractTargets
import io.github.nuke.oss.hook.testing.FakeHookBackend

class FeatureManagerTest {
    private val backend = FakeHookBackend()
    private val hooks = DefaultHookService(backend)
    private val manager = FeatureManager("g", hooks, Logger {})
    private val target = ContractTargets::class.java.getMethod("echo", String::class.java)

    @Test fun `failed feature installation rolls back earlier subscriptions`() {
        manager.register(object : RuntimeFeature {
            override val id = "feature"
            override fun install(scope: FeatureScope) {
                scope.hooks.install(target, HookSpec("first", before = { it.returnEarly("bad") }))
                error("later target failed")
            }
        })
        assertEquals(FeatureState.FAILED, manager.setEnabled("feature", true).state)
        assertEquals("original", backend.call(target, null, "original"))
        assertEquals(0, hooks.snapshot().single().subscriptions)
    }

    @Test fun `install and close failures reach the failure sink with the error itself`() {
        val reported = mutableListOf<Triple<String, FailurePhase, Throwable>>()
        manager.failureSink = FeatureFailureSink { id, phase, error -> reported += Triple(id, phase, error) }
        val installError = IllegalStateException("target missing")
        manager.register(object : RuntimeFeature {
            override val id = "broken"
            override fun install(scope: FeatureScope) = throw installError
        })
        val closeError = IllegalStateException("release failed")
        manager.register(object : RuntimeFeature {
            override val id = "sticky"
            override fun install(scope: FeatureScope) { scope.own(AutoCloseable { throw closeError }) }
        })
        manager.setEnabled("broken", true)
        manager.setEnabled("sticky", true)
        assertThrows(IllegalStateException::class.java) { manager.setEnabled("sticky", false) }
        assertEquals(listOf("broken" to FailurePhase.INSTALL, "sticky" to FailurePhase.UNINSTALL), reported.map { it.first to it.second })
        assertSame(installError, reported[0].third)
        assertSame(closeError, reported[1].third)
    }

    @Test fun `a failing sink is logged and never breaks the feature`() {
        val logs = mutableListOf<String>()
        val logged = FeatureManager("g", hooks, Logger { logs += it })
        logged.failureSink = FeatureFailureSink { _, _, _ -> throw IllegalStateException("store down") }
        logged.register(object : RuntimeFeature {
            override val id = "broken"
            override fun install(scope: FeatureScope) = error("install failed")
        })
        assertEquals(FeatureState.FAILED, logged.setEnabled("broken", true).state)
        logged.reportFailure("broken", FailurePhase.AFTER_CALLBACK, IllegalStateException("callback"))
        assertEquals(2, logs.count { "failure_sink_failed" in it })
    }

    @Test fun `enable disable reenable lifecycle preserves ownership`() {
        manager.register(object : RuntimeFeature {
            override val id = "feature"
            override fun install(scope: FeatureScope) {
                scope.hooks.install(target, HookSpec("first", before = { it.returnEarly("on") }))
            }
        })
        assertEquals(FeatureState.ACTIVE, manager.setEnabled("feature", true).state)
        manager.setEnabled("feature", true)
        assertEquals("on", backend.call(target, null, "off"))
        assertEquals(FeatureState.DISABLED, manager.setEnabled("feature", false).state)
        assertEquals("off", backend.call(target, null, "off"))
        manager.setEnabled("feature", true)
        assertEquals("on", backend.call(target, null, "off"))
        assertEquals(1, backend.attachments.get())
    }

    @Test fun `closing scope is idempotent and prevents further installs`() {
        val scope = FeatureScope(HookOwner("f", "g"), hooks)
        scope.hooks.install(target, HookSpec("first", before = {}))
        scope.close(); scope.close()
        assertThrows(IllegalStateException::class.java) { scope.hooks.install(target, HookSpec("next", before = {})) }
    }

    @Test fun `owned resources close with the scope newest first and only once`() {
        val closed = ArrayList<String>()
        val scope = FeatureScope(HookOwner("f", "g"), hooks)
        scope.hooks.install(target, HookSpec("first", before = { it.returnEarly("on") }))
        scope.own(AutoCloseable { closed += "timer" })
        scope.own(AutoCloseable { closed += "task" })
        scope.close(); scope.close()
        assertEquals(listOf("task", "timer"), closed)
        assertEquals("off", backend.call(target, null, "off"))
        scope.own(AutoCloseable { closed += "late" })
        assertEquals(listOf("task", "timer", "late"), closed)
    }

    @Test fun `scope closes every hook even if a release fails`() {
        var released = 0
        val service = object : HookService {
            override fun install(owner: HookOwner, target: java.lang.reflect.Executable, spec: HookSpec) = object : HookHandle {
                override val id = spec.id
                override val owner = owner
                override fun close() { released++; error("release-$id") }
            }
        }
        val scope = FeatureScope(HookOwner("f", "g"), service)
        scope.hooks.install(target, HookSpec("a", before = {}))
        scope.hooks.install(target, HookSpec("b", before = {}))
        val failure = assertThrows(IllegalStateException::class.java) { scope.close() }
        assertEquals(2, released)
        assertEquals(1, failure.suppressed.size)
    }

    @Test fun `closed manager cannot enable old features`() {
        manager.register(object : RuntimeFeature {
            override val id = "feature"
            override fun install(scope: FeatureScope) {}
        })
        manager.close()
        assertEquals(FeatureState.CLOSED, manager.snapshot().single().state)
        assertThrows(IllegalStateException::class.java) { manager.setEnabled("feature", true) }
    }

    @Test fun `recursive feature installation fails and rolls back`() {
        manager.register(object : RuntimeFeature {
            override val id = "feature"
            override fun install(scope: FeatureScope) {
                scope.hooks.install(target, HookSpec("a", before = { it.returnEarly("on") }))
                manager.setEnabled(id, true)
            }
        })
        assertEquals(FeatureState.FAILED, manager.setEnabled("feature", true).state)
        assertEquals("off", backend.call(target, null, "off"))
    }

    @Test fun `closing manager during installation cannot publish an active feature`() {
        manager.register(object : RuntimeFeature {
            override val id = "feature"
            override fun install(scope: FeatureScope) {
                scope.hooks.install(target, HookSpec("a", before = { it.returnEarly("on") }))
                manager.close()
            }
        })
        assertEquals(FeatureState.CLOSED, manager.setEnabled("feature", true).state)
        assertEquals("off", backend.call(target, null, "off"))
    }
}
