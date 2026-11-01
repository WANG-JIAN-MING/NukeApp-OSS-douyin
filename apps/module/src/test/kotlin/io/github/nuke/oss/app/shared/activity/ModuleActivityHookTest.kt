package io.github.nuke.oss.app.shared.activity

import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookFailureSink
import io.github.nuke.oss.hook.api.HookOwner
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.FeatureScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleActivityHookTest {
    // Stands in for Instrumentation: the same (ClassLoader, String, Intent) shape, with the host's own result.
    open class FakeInstrumentation {
        val hostActivity = Any()
        open fun newActivity(loader: ClassLoader, className: String, intent: Any?): Any = hostActivity
    }

    private val instrumentation = FakeInstrumentation()
    private val method = FakeInstrumentation::class.java.getDeclaredMethod(
        "newActivity", ClassLoader::class.java, String::class.java, Any::class.java,
    )
    private val backend = FakeHookBackend()
    private val logs = mutableListOf<String>()
    private val scope = FeatureScope(HookOwner("feature", "generation"), DefaultHookService(backend, HookFailureSink {}))

    private fun install(create: (Any?) -> Any?) =
        ModuleActivityHook(method, create, Logger { logs += it }, "feature").install(scope)

    private fun launch(intent: Any?) = backend.call(method, instrumentation, javaClass.classLoader, "stub", intent)

    @Test fun `a launch the module claims gets the module Activity without reaching the host`() {
        val module = Any()
        install { intent -> if (intent == "mine") module else null }
        assertSame(module, launch("mine"))
        assertEquals(0, backend.originalCalls.get())
    }

    @Test fun `a launch the module does not claim reaches the host unchanged`() {
        install { null }
        assertSame(instrumentation.hostActivity, launch("other"))
        assertEquals(1, backend.originalCalls.get())
    }

    @Test fun `a module Activity that cannot be built leaves the host Activity and logs the reason`() {
        install { throw ClassNotFoundException("missing") }
        assertSame(instrumentation.hostActivity, launch("mine"))
        assertTrue(logs.single().contains("module_activity_failed"))
        assertTrue(logs.single().contains("missing"))
    }

    @Test fun `a linkage error while building the module Activity is handled the same way`() {
        install { throw NoClassDefFoundError("androidx/activity/ComponentActivity") }
        assertSame(instrumentation.hostActivity, launch("mine"))
        assertTrue(logs.single().contains("ComponentActivity"))
    }

    // Builds its Activities itself without reaching the base method, as Douyin's Mira wrapper does.
    open class WrappingInstrumentation : FakeInstrumentation() {
        val wrapperActivity = Any()
        override fun newActivity(loader: ClassLoader, className: String, intent: Any?): Any = wrapperActivity
    }

    class OuterInstrumentation : WrappingInstrumentation()

    private val wrapperMethod = WrappingInstrumentation::class.java.getDeclaredMethod(
        "newActivity", ClassLoader::class.java, String::class.java, Any::class.java,
    )

    @Test fun `an override that skips the base method is answered once the live instance is covered`() {
        val module = Any()
        val hook = ModuleActivityHook(method, { intent -> if (intent == "mine") module else null }, Logger { logs += it }, "feature")
        hook.install(scope)
        val wrapper = OuterInstrumentation()
        assertSame(wrapper.wrapperActivity, backend.call(wrapperMethod, wrapper, javaClass.classLoader, "stub", "mine"))

        hook.cover(wrapper)
        hook.cover(wrapper)
        assertSame(module, backend.call(wrapperMethod, wrapper, javaClass.classLoader, "stub", "mine"))
        assertSame(wrapper.wrapperActivity, backend.call(wrapperMethod, wrapper, javaClass.classLoader, "stub", "other"))
        assertEquals(listOf("feature=feature module_activity_covered class=${WrappingInstrumentation::class.java.name}"), logs)
    }

    @Test fun `closing the scope restores the host behaviour`() {
        install { Any() }
        scope.close()
        assertSame(instrumentation.hostActivity, launch("mine"))
    }
}
