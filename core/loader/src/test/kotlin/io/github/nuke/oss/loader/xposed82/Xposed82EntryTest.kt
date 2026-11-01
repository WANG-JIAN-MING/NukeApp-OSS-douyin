package io.github.nuke.oss.loader.xposed82

import android.content.pm.ApplicationInfo
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nuke.oss.common.ProcessEntryGate
import io.github.nuke.oss.loader.LoaderProfile
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.*
import io.github.nuke.oss.testing.TestProfile
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe
import java.util.Properties

class Xposed82EntryTest {
    private val events = ArrayList<LoadEvent>()
    private val backend = FakeHookBackend()
    private var created = 0
    private var backendCreated = 0
    private var idsRead = 0
    private val properties = Properties()
    private val gate = ProcessEntryGate(properties)
    private val entry = Xposed82Entry(
        TestProfile,
        { idsRead++; 42 to 10001 },
        { created++; recorder() },
        { backendCreated++; backend },
        Logger {},
        { gate.claim("xposed82") },
        frameworkName = { "LSPosed" },
        bridgeVersion = { 93 },
    )

    private fun recorder() = object : RuntimeBootstrap {
        override fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult {
            events += event
            assertSame(backend, services.backend)
            return BootstrapResult.Ignored("recorded")
        }
    }

    // The android.jar constructor is a stub; allocate the passive DTO and set its public fields.
    private fun hostInfo(): ApplicationInfo {
        val access = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        return (access.allocateInstance(ApplicationInfo::class.java) as ApplicationInfo).apply {
            dataDir = "/data/user/0/host"
            sourceDir = "/data/app/host/base.apk"
        }
    }

    // API 82 ships throwing constructors. Allocate only this passive callback DTO in the JVM harness.
    private fun loaded(
        name: String = "io.github.nuke.oss.fixture",
        process: String = name,
        loader: ClassLoader = requireNotNull(javaClass.classLoader),
    ): XC_LoadPackage.LoadPackageParam {
        val access = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        return (access.allocateInstance(XC_LoadPackage.LoadPackageParam::class.java) as XC_LoadPackage.LoadPackageParam).apply {
            packageName = name
            processName = process
            classLoader = loader
            isFirstApplication = true
            appInfo = hostInfo()
        }
    }

    @Test fun `construction does not create a backend runtime or Android process identity`() {
        assertEquals(0, created)
        assertEquals(0, backendCreated)
        assertEquals(0, idsRead)
        assertTrue(properties.isEmpty())
    }

    @Test fun `only fixture main process callbacks claim an entry`() {
        entry.handleLoadPackage(loaded("other"))
        entry.handleLoadPackage(loaded(process = "io.github.nuke.oss.fixture:secondary"))
        assertTrue(events.isEmpty())
        assertTrue(properties.isEmpty())
        assertEquals(0, idsRead)
    }

    @Test fun `tiktok host main process is accepted and its secondary process is not`() {
        entry.handleLoadPackage(loaded("com.ss.android.ugc.aweme", process = "com.ss.android.ugc.aweme:push"))
        assertTrue(events.isEmpty())
        assertTrue(properties.isEmpty())
        entry.handleLoadPackage(loaded("com.ss.android.ugc.aweme"))
        assertEquals("com.ss.android.ugc.aweme", events.single().process.packageName)
    }

    @Test fun `first callback maps loader and identity and repeated delivery is idempotent`() {
        val loader = object : ClassLoader(javaClass.classLoader) {}
        val param = loaded(loader = loader)
        entry.handleLoadPackage(param)
        entry.handleLoadPackage(param)
        assertEquals(1, created)
        assertEquals(1, backendCreated)
        assertEquals(1, idsRead)
        val event = events.single()
        assertSame(loader, event.hostClassLoader)
        assertEquals("xposed82", event.loaderId)
        assertEquals(FrameworkInfo("LSPosed", apiVersion = 93), event.framework)
        assertEquals(HostPaths("/data/user/0/host", "/data/app/host/base.apk"), event.hostPaths)
        assertEquals(42, event.process.pid)
        assertEquals(10001, event.process.uid)
    }

    @Test fun `the module's own main process reports the framework and claims nothing`() {
        val reports = ArrayList<Pair<ClassLoader, String>>()
        val loader = object : ClassLoader(javaClass.classLoader) {}
        val self = Xposed82Entry(
            TestProfile,
            { error("No process access") }, { error("No runtime") }, { error("No backend") }, Logger {},
            { gate.claim("xposed82") },
            { "LSPosed" },
            { classLoader, framework -> reports += classLoader to framework },
        )
        self.handleLoadPackage(loaded(TestProfile.applicationId, process = "${TestProfile.applicationId}:remote", loader = loader))
        assertTrue(reports.isEmpty())
        self.handleLoadPackage(loaded(TestProfile.applicationId, loader = loader))
        assertEquals(listOf<Pair<ClassLoader, String>>(loader to "LSPosed"), reports)
        assertTrue(properties.isEmpty())
        // The report claimed nothing, so a later host callback in another process still starts normally.
        self.handleLoadPackage(loaded(TestProfile.applicationId, loader = loader))
        assertEquals(2, reports.size)
    }

    @Test fun `a status holder the module cannot reach is logged and not thrown`() {
        val logged = ArrayList<String>()
        val self = Xposed82Entry(
            TestProfile,
            { error("No process access") }, { error("No runtime") }, { error("No backend") }, Logger { logged += it },
            { error("No claim") },
            { "Xposed" },
            { _, _ -> throw ClassNotFoundException("io.github.nuke.oss.status.ModuleStatus") },
        )
        self.handleLoadPackage(loaded(TestProfile.applicationId))
        assertEquals(listOf("xposed82_status_failed ClassNotFoundException"), logged)
    }

    @Test fun `all other modes deny legacy before backend initialization`() {
        for (mode in listOf("libxposed102", "zygisk")) {
            val occupied = Properties()
            assertTrue(ProcessEntryGate(occupied).claim(mode))
            val contender = ProcessEntryGate(occupied)
            Xposed82Entry(
                TestProfile,
                { error("No process access") }, { error("No runtime") }, { error("No backend") }, Logger {},
                { contender.claim("xposed82") },
            ).handleLoadPackage(loaded())
        }
    }

    @Test fun `reentrant bootstrap and repeated callback do not dispatch twice`() {
        lateinit var reentrant: Xposed82Entry
        val param = loaded()
        var dispatches = 0
        reentrant = Xposed82Entry(
            TestProfile,
            { 42 to 10001 },
            { object : RuntimeBootstrap {
                override fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult {
                    dispatches++
                    reentrant.handleLoadPackage(param)
                    return BootstrapResult.Ignored("recorded")
                }
            } },
            { backend }, Logger {}, { true },
        )
        reentrant.handleLoadPackage(param)
        reentrant.handleLoadPackage(param)
        assertEquals(1, dispatches)
    }

    @Test fun `bootstrap failure is sticky within entry and across modes`() {
        var attempts = 0
        val failed = Xposed82Entry(
            TestProfile,
            { 42 to 10001 },
            { object : RuntimeBootstrap {
                override fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult {
                    attempts++
                    error("partial installation")
                }
            } },
            { backend }, Logger {}, { gate.claim("xposed82") },
        )
        assertThrows(IllegalStateException::class.java) { failed.handleLoadPackage(loaded()) }
        failed.handleLoadPackage(loaded())
        assertEquals(1, attempts)
        assertFalse(ProcessEntryGate(properties).claim("libxposed102"))
        assertFalse(ProcessEntryGate(properties).claim("zygisk"))
    }

    @Test fun `legacy entry loads and starts with modern API entirely unavailable`() {
        val parent = requireNotNull(javaClass.classLoader)
        val prefix = Xposed82Entry::class.java.name
        val isolated = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("io.github.libxposed.")) throw ClassNotFoundException(name)
                if (!name.startsWith(prefix)) return super.loadClass(name, resolve)
                return findLoadedClass(name) ?: run {
                    val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
                    defineClass(name, bytes, 0, bytes.size)
                }
            }
        }
        assertThrows(ClassNotFoundException::class.java) { isolated.loadClass("io.github.libxposed.api.XposedModule") }
        val type = isolated.loadClass(prefix)
        val parameters = arrayOf<Class<*>>(
            LoaderProfile::class.java, kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function0::class.java,
            kotlin.jvm.functions.Function0::class.java, Logger::class.java, kotlin.jvm.functions.Function0::class.java,
            kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function2::class.java, kotlin.jvm.functions.Function0::class.java,
        )
        val isolatedEntry = type.getConstructor(*parameters).newInstance(
            TestProfile, { 42 to 10001 }, { recorder() }, { backend }, Logger {}, { true },
            { "Xposed" }, { _: ClassLoader, _: String -> error("Not the module's own process") }, { 93 },
        )
        type.getMethod("handleLoadPackage", XC_LoadPackage.LoadPackageParam::class.java).invoke(isolatedEntry, loaded())
        assertEquals("xposed82", events.single().loaderId)
    }
}
