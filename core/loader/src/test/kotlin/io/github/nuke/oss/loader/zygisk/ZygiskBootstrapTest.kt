package io.github.nuke.oss.loader.zygisk

import io.github.nuke.oss.testing.TestProfile
import io.github.nuke.oss.common.ProcessEntryGate
import io.github.nuke.oss.loader.LoaderProfile
import io.github.nuke.oss.core.*
import io.github.nuke.oss.hook.api.HookBackend
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

class ZygiskBootstrapTest {
    class AttachTarget {
        fun attach(context: Any) { require(context != "bad") }
        fun newApplication(loader: ClassLoader, name: String, context: Any): Any {
            require(name.isNotBlank() && loader !== context)
            return context
        }
    }

    private val method = AttachTarget::class.java.getMethod("attach", Any::class.java)
    private val backend = FakeHookBackend()
    private val properties = Properties()
    private val events = ArrayList<LoadEvent>()
    private val messages = ArrayList<String>()
    private var initializations = 0
    private val hostLoader = object : ClassLoader(javaClass.classLoader) {}
    private val context = Any()
    private val hostPaths = HostPaths("/data/user/0/host", "/data/app/host/base.apk")

    private fun entry(
        processName: String = TestProfile.FIXTURE,
        uid: Int = 10001,
        initialize: () -> Boolean = { initializations++; true },
        accept: (LoadEvent) -> Unit = { events += it },
        prepareNatives: (Any) -> String? = { null },
        exemptHiddenApis: () -> Unit = {},
    ) = ZygiskBootstrap(
        TestProfile,
        ProcessIdentity(42, uid, processName, TestProfile.FIXTURE),
        BuildIdentity("io.github.nuke.oss", "test", "generation"),
        backend, initialize,
        object : RuntimeBootstrap {
            override fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult {
                accept(event)
                return BootstrapResult.Ignored("recorded")
            }
        },
        Logger { messages += it },
        { assertSame(context, it); hostLoader },
        { hostPaths },
        ProcessEntryGate(properties),
        prepareNatives,
        exemptHiddenApis,
    )

    @Test fun `native initialization waits for claim and host readiness waits for attach`() {
        val entry = entry()
        assertTrue(entry.start(method))
        assertEquals(1, initializations)
        assertTrue(events.isEmpty())
        backend.call(method, AttachTarget(), context)
        assertSame(hostLoader, events.single().hostClassLoader)
        assertEquals("zygisk", events.single().loaderId)
        assertEquals(FrameworkInfo("Zygisk"), events.single().framework)
        assertEquals(hostPaths, events.single().hostPaths)
        backend.call(method, AttachTarget(), context)
        assertEquals(1, events.size)
        assertTrue(entry.start(method))
        assertEquals(1, backend.attachments.get())
    }

    @Test fun `hidden APIs are exempted once the process is owned and before LSPlant starts`() {
        val order = ArrayList<String>()
        val entry = entry(initialize = { order += "lsplant"; true }, exemptHiddenApis = { order += "exempt" })
        assertTrue(entry.start(method))
        assertEquals(listOf("exempt", "lsplant"), order)
    }

    @Test fun `a refused exemption is logged and startup goes on`() {
        val entry = entry(exemptHiddenApis = { throw IllegalStateException("blocked") })
        assertTrue(entry.start(method))
        assertEquals(1, initializations)
        assertTrue(messages.any { it.startsWith("zygisk_hidden_api_unavailable") && it.contains("blocked") })
    }

    @Test fun `no exemption is made when another entry owns the process`() {
        assertTrue(ProcessEntryGate(properties).claim("xposed82"))
        var exempted = false
        assertFalse(entry(exemptHiddenApis = { exempted = true }).start(method))
        assertFalse(exempted)
    }

    @Test fun `native libraries are prepared with the host context before the runtime starts`() {
        val order = ArrayList<String>()
        val entry = entry(accept = { order += "runtime:${it.moduleApkPath}" }, prepareNatives = {
            assertSame(context, it)
            order += "natives"
            "/data/app/module/base.apk"
        })
        entry.start(method)
        backend.call(method, AttachTarget(), context)
        assertEquals(listOf("natives", "runtime:/data/app/module/base.apk"), order)
    }

    @Test fun `unavailable native libraries are logged and the runtime still starts`() {
        val entry = entry(prepareNatives = { throw IllegalStateException("module not visible") })
        entry.start(method)
        backend.call(method, AttachTarget(), context)
        assertEquals(1, events.size)
        assertNull(events.single().moduleApkPath)
        assertTrue(messages.any { it.startsWith("zygisk_natives_unavailable") && it.contains("module not visible") })
    }

    @Test fun `a package without native libraries is reported instead of loading nothing`() {
        val failure = runCatching { ModuleNativeLibraries.attach(ClassLoader.getSystemClassLoader(), null) {} }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("no native libraries"))
    }

    @Test fun `other process and isolated uid cannot initialize native`() {
        assertFalse(entry(processName = "io.github.nuke.oss.fixture:other").start(method))
        assertFalse(entry(uid = 99001).start(method))
        assertEquals(0, initializations)
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `application factory uses final context argument after successful creation`() {
        val factory = AttachTarget::class.java.getMethod("newApplication", ClassLoader::class.java, String::class.java, Any::class.java)
        entry().start(factory)
        assertSame(context, backend.call(factory, AttachTarget(), javaClass.classLoader, "fixture.Application", context))
        assertSame(hostLoader, events.single().hostClassLoader)
    }

    @Test fun `an existing xposed owner prevents LSPlant initialization`() {
        assertTrue(ProcessEntryGate(properties).claim("xposed82"))
        assertFalse(entry().start(method))
        assertEquals(0, initializations)
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `failed initialization keeps ownership and rejects retries`() {
        val entry = entry(initialize = { initializations++; false })
        assertThrows(IllegalStateException::class.java) { entry.start(method) }
        assertThrows(IllegalStateException::class.java) { entry.start(method) }
        assertEquals(1, initializations)
        assertFalse(ProcessEntryGate(properties).claim("libxposed102"))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `throwing Application attach does not start the runtime`() {
        entry().start(method)
        assertThrows(IllegalArgumentException::class.java) { backend.call(method, AttachTarget(), "bad") }
        assertTrue(events.isEmpty())
    }

    @Test fun `runtime failure is reported once without changing attach result`() {
        var accepts = 0
        entry(accept = { accepts++; error("registration failed") }).start(method)
        assertNull(backend.call(method, AttachTarget(), context))
        assertNull(backend.call(method, AttachTarget(), context))
        assertEquals(1, accepts)
        assertTrue(messages.any { it.startsWith("zygisk_runtime_failed") })
        assertFalse(messages.any { it == "zygisk_runtime_ready" })
    }

    @Test fun `zygisk startup remains usable without either Xposed API`() {
        val parent = requireNotNull(javaClass.classLoader)
        val prefix = "io.github.nuke.oss.loader.zygisk."
        val isolated = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("de.robv.android.xposed.") || name.startsWith("io.github.libxposed.")) {
                    throw ClassNotFoundException(name)
                }
                if (!name.startsWith(prefix) || name.endsWith("Test")) return super.loadClass(name, resolve)
                return findLoadedClass(name) ?: run {
                    val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
                    defineClass(name, bytes, 0, bytes.size)
                }
            }
        }
        assertThrows(ClassNotFoundException::class.java) { isolated.loadClass("de.robv.android.xposed.XposedBridge") }
        assertThrows(ClassNotFoundException::class.java) { isolated.loadClass("io.github.libxposed.api.XposedModule") }
        assertNotNull(isolated.loadClass(prefix + "ZygiskEntry").getField("INSTANCE").get(null))
        val type = isolated.loadClass(prefix + "ZygiskBootstrap")
        val constructor = type.getConstructor(
            LoaderProfile::class.java, ProcessIdentity::class.java, BuildIdentity::class.java, HookBackend::class.java,
            kotlin.jvm.functions.Function0::class.java, RuntimeBootstrap::class.java, Logger::class.java,
            kotlin.jvm.functions.Function1::class.java, kotlin.jvm.functions.Function1::class.java, ProcessEntryGate::class.java,
            kotlin.jvm.functions.Function1::class.java, kotlin.jvm.functions.Function0::class.java,
        )
        val recorder = object : RuntimeBootstrap {
            override fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult {
                events += event
                return BootstrapResult.Ignored("recorded")
            }
        }
        val instance = constructor.newInstance(
            TestProfile, ProcessIdentity(42, 10001, TestProfile.FIXTURE, TestProfile.FIXTURE),
            BuildIdentity("io.github.nuke.oss", "test", "isolated"), backend,
            { initializations++; true }, recorder, Logger {},
            { _: Any -> hostLoader }, { _: Any -> hostPaths }, ProcessEntryGate(properties), { _: Any -> null }, {},
        )
        assertEquals(true, type.getMethod("start", java.lang.reflect.Method::class.java).invoke(instance, method))
        backend.call(method, AttachTarget(), context)
        assertEquals(1, initializations)
        assertEquals("zygisk", events.single().loaderId)
        assertSame(hostLoader, events.single().hostClassLoader)
    }
}
