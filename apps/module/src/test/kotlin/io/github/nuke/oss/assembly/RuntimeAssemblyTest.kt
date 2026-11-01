package io.github.nuke.oss.assembly

import org.junit.Assert.*
import org.junit.Test
import io.github.nuke.oss.core.*
import io.github.nuke.oss.fixture.ProbeTarget
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.*

class RuntimeAssemblyTest {
    private val backend = FakeHookBackend()
    private val services = BootstrapServices(backend, Logger {})
    private val event = LoadEvent(
        ProcessIdentity(1, 10001, "io.github.nuke.oss.fixture", "io.github.nuke.oss.fixture"),
        BuildIdentity("io.github.nuke.oss", "test", "g"), "xposed82", LoadStage.HOST_CLASSLOADER_READY,
        ProbeTarget::class.java.classLoader,
    )

    @Test fun `fixture full bootstrap rewrites disables and restores a method`() {
        val result = RuntimeAssembly().accept(event, services) as BootstrapResult.Started
        val greet = ProbeTarget::class.java.getMethod("greet", String::class.java)
        val toggle = ProbeTarget::class.java.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        val describe = ProbeTarget::class.java.getMethod("describe")
        assertEquals("hooked:original:NukeApp", backend.call(greet, null, "Alice"))
        assertTrue((backend.call(describe) as String).contains("fixture.greeting: ACTIVE"))
        backend.call(toggle, null, false)
        assertEquals("original:Alice", backend.call(greet, null, "Alice"))
        backend.call(toggle, null, true)
        assertEquals("hooked:original:NukeApp", backend.call(greet, null, "Alice"))
        assertEquals(3, backend.attachments.get())
        result.session.close()
        assertEquals("original:Alice", backend.call(greet, null, "Alice"))
    }

    @Test fun `a module APK with another signer starts nothing`() {
        val messages = ArrayList<String>()
        val assembly = RuntimeAssembly(signatureRefusal = { _, _ -> "The module APK is not signed with the release certificate" })
        val result = assembly.accept(event, BootstrapServices(backend, Logger { messages += it }))
        assertEquals("The module APK is not signed with the release certificate", (result as BootstrapResult.Ignored).reason)
        assertEquals(0, backend.attachments.get())
        assertTrue(messages.any { it.startsWith("module_signature_refused") })
    }

    @Test fun `an entry's module APK is the one checked`() {
        var checked: String? = null
        val assembly = RuntimeAssembly(signatureRefusal = { loaded, _ -> checked = loaded.moduleApkPath; null })
        assembly.accept(event.copy(moduleApkPath = "/data/app/module/base.apk"), services)
        assertEquals("/data/app/module/base.apk", checked)
    }

    @Test fun `duplicate load event reuses one session`() {
        val assembly = RuntimeAssembly()
        val first = assembly.accept(event, services) as BootstrapResult.Started
        val second = assembly.accept(event, services) as BootstrapResult.Started
        assertSame(first.session, second.session)
        assertEquals(3, backend.attachments.get())
    }

    @Test fun `unsupported package and secondary process do not attach hooks`() {
        val assembly = RuntimeAssembly()
        assertTrue(assembly.accept(event.copy(process = event.process.copy(packageName = "other")), services) is BootstrapResult.Ignored)
        assertTrue(assembly.accept(event.copy(process = event.process.copy(processName = "io.github.nuke.oss.fixture:other")), services) is BootstrapResult.Ignored)
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `another backend cannot replace an active runtime`() {
        val assembly = RuntimeAssembly()
        assembly.accept(event, services)
        val different = object : io.github.nuke.oss.hook.api.HookBackend by backend { override val id = "different" }
        assertThrows(IllegalStateException::class.java) { assembly.accept(event, services.copy(backend = different)) }
    }

    @Test fun `same backend name does not allow another backend instance`() {
        val assembly = RuntimeAssembly()
        assembly.accept(event, services)
        assertThrows(IllegalStateException::class.java) { assembly.accept(event, services.copy(backend = FakeHookBackend())) }
    }

    @Test fun `closed session is not returned as a newly started runtime`() {
        val assembly = RuntimeAssembly()
        (assembly.accept(event, services) as BootstrapResult.Started).session.close()
        assertTrue(assembly.accept(event, services) is BootstrapResult.Ignored)
    }
}
