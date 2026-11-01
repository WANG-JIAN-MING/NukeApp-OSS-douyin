package io.github.nuke.oss.loader.libxposed102

import android.app.AppComponentFactory
import android.content.pm.ApplicationInfo
import io.github.libxposed.api.XposedModuleInterface.*
import org.junit.Assert.*
import org.junit.Test
import io.github.nuke.oss.backend.libxposed102.testing.TestModernFramework
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nuke.oss.common.ProcessEntryGate
import io.github.nuke.oss.loader.LoaderProfile
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.loader.xposed82.Xposed82Entry
import java.util.Properties
import sun.misc.Unsafe
import io.github.nuke.oss.runtime.*
import io.github.nuke.oss.testing.TestProfile

class Libxposed102EntryTest {
    private val framework = TestModernFramework()
    private val events = ArrayList<LoadEvent>()
    private val services = ArrayList<BootstrapServices>()
    private var created = 0
    private var idsRead = 0
    private var detached = 0
    private val entry = Libxposed102Entry(
        TestProfile,
        { idsRead++; 42 to 10001 },
        {
            created++
            object : RuntimeBootstrap {
                override fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult {
                    events += event
                    this@Libxposed102EntryTest.services += services
                    return BootstrapResult.Ignored("recorded by test bootstrap")
                }
            }
        },
        { true },
    )

    init {
        // Test harness acts as the framework. Production module code never calls attachFramework.
        entry.attachFramework(framework, Runnable { detached++ })
    }

    private fun loaded(name: String = "io.github.nuke.oss.fixture", system: Boolean = false) = object : ModuleLoadedParam {
        override fun getProcessName() = name
        override fun isSystemServer() = system
    }

    // The android.jar constructor is a stub; allocate the passive DTO and set its public fields.
    private fun hostInfo(): ApplicationInfo {
        val access = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        return (access.allocateInstance(ApplicationInfo::class.java) as ApplicationInfo).apply {
            dataDir = "/data/user/0/host"
            sourceDir = "/data/app/host/base.apk"
        }
    }

    private fun ready(
        name: String = "io.github.nuke.oss.fixture",
        first: Boolean = true,
        loader: ClassLoader = requireNotNull(javaClass.classLoader),
    ) = object : PackageReadyParam {
        override fun getPackageName() = name
        override fun isFirstPackage() = first
        override fun getClassLoader() = loader
        override fun getDefaultClassLoader(): ClassLoader = error("Must use the final package ClassLoader")
        override fun getApplicationInfo(): ApplicationInfo = hostInfo()
        override fun getAppComponentFactory(): AppComponentFactory = error("Not required at this stage")
    }

    @Test fun `construction performs no runtime or Android process initialization`() {
        assertEquals(0, created)
        assertEquals(0, idsRead)
        assertEquals(0, framework.attaches.get())
    }

    @Test fun `module load waits for final package readiness`() {
        entry.onModuleLoaded(loaded())
        entry.onPackageLoaded(ready())
        assertEquals(0, created)
        assertEquals(0, idsRead)
        assertTrue(events.isEmpty())
    }

    @Test fun `package ready maps final loader and process into existing bootstrap`() {
        val finalLoader = object : ClassLoader(javaClass.classLoader) {}
        entry.onModuleLoaded(loaded())
        entry.onPackageReady(ready(loader = finalLoader))
        val event = events.single()
        assertSame(finalLoader, event.hostClassLoader)
        assertEquals(HostPaths("/data/user/0/host", "/data/app/host/base.apk"), event.hostPaths)
        assertEquals("libxposed102", event.loaderId)
        // What the framework says about itself travels with the event, for the failure report's environment.
        assertEquals(FrameworkInfo("test-transport-only", "102.0.0", 102L, 102), event.framework)
        assertEquals("io.github.nuke.oss.fixture", event.process.packageName)
        assertEquals(42, event.process.pid)
        assertEquals(10001, event.process.uid)
        assertEquals("libxposed102", services.single().backend.id)
        assertEquals(1, created)
    }

    @Test fun `tiktok host main process maps into the shared bootstrap`() {
        entry.onModuleLoaded(loaded("com.ss.android.ugc.aweme"))
        entry.onPackageReady(ready("com.ss.android.ugc.aweme"))
        assertEquals("com.ss.android.ugc.aweme", events.single().process.packageName)
        assertEquals(0, detached)
    }

    @Test fun `tiktok host secondary process is detached`() {
        entry.onModuleLoaded(loaded("com.ss.android.ugc.aweme:push"))
        entry.onPackageReady(ready("com.ss.android.ugc.aweme"))
        assertEquals(1, detached)
        assertTrue(events.isEmpty())
        assertEquals(0, created)
    }

    @Test fun `other process is detached without creating backend hooks`() {
        entry.onModuleLoaded(loaded("io.github.nuke.oss.fixture:secondary"))
        entry.onPackageReady(ready())
        assertEquals(1, detached)
        assertEquals(0, created)
        assertEquals(0, framework.attaches.get())
    }

    @Test fun `another entry owner detaches before creating a runtime`() {
        val denied = Libxposed102Entry(
            TestProfile,
            { error("Process IDs must not be read") },
            { error("Runtime must not be created") },
            { false },
        )
        denied.attachFramework(framework, Runnable { detached++ })
        denied.onModuleLoaded(loaded())
        denied.onPackageReady(ready())
        assertEquals(1, detached)
        assertEquals(0, framework.attaches.get())
    }

    @Test fun `system server is detached even if name looks like the fixture`() {
        entry.onModuleLoaded(loaded(system = true))
        assertEquals(1, detached)
        assertEquals(0, created)
    }

    @Test fun `other packages and non-main package callbacks are ignored`() {
        entry.onModuleLoaded(loaded())
        entry.onPackageReady(ready(name = "other"))
        entry.onPackageReady(ready(first = false))
        assertTrue(events.isEmpty())
        assertEquals(0, created)
    }

    @Test fun `readiness before module initialization does not create a runtime`() {
        entry.onPackageReady(ready())
        assertTrue(events.isEmpty())
        assertEquals(0, idsRead)
    }

    @Test fun `repeated module and package events dispatch only one generation`() {
        entry.onModuleLoaded(loaded())
        entry.onPackageReady(ready())
        entry.onPackageReady(ready())
        assertEquals(1, created)
        assertEquals(1, events.size)
        assertEquals(1, services.size)
    }

    @Test fun `duplicate module callback is idempotent`() {
        entry.onModuleLoaded(loaded())
        entry.onModuleLoaded(loaded())
        entry.onPackageReady(ready())
        assertEquals(1, events.size)
        assertEquals(0, detached)
    }

    @Test fun `entry reads the attached API 102 wrapper version rather than transport version`() {
        var claims = 0
        val attached = Libxposed102Entry(
            TestProfile,
            { error("No process access") }, { error("No runtime") }, { claims++; true },
        )
        // The real wrapper's final getApiVersion uses the API interface default, not the transport delegate.
        attached.attachFramework(TestModernFramework(101), Runnable {})
        assertEquals(102, attached.apiVersion)
        attached.onModuleLoaded(loaded())
        assertEquals(1, claims)
    }

    @Test fun `reentrant package callback cannot bootstrap twice`() {
        var dispatches = 0
        lateinit var reentrant: Libxposed102Entry
        reentrant = Libxposed102Entry(
            TestProfile,
            { 42 to 10001 },
            { object : RuntimeBootstrap {
                override fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult {
                    dispatches++
                    reentrant.onPackageReady(ready())
                    return BootstrapResult.Ignored("recorded")
                }
            } },
            { true },
        )
        reentrant.attachFramework(framework, Runnable {})
        reentrant.onModuleLoaded(loaded())
        reentrant.onPackageReady(ready())
        reentrant.onPackageReady(ready())
        assertEquals(1, dispatches)
    }

    @Test fun `bootstrap failure keeps ownership and prevents all later retries`() {
        val properties = Properties()
        val gate = ProcessEntryGate(properties)
        var attempts = 0
        val failed = Libxposed102Entry(
            TestProfile,
            { 42 to 10001 },
            { object : RuntimeBootstrap {
                override fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult {
                    attempts++
                    error("partial installation")
                }
            } },
            { gate.claim("libxposed102") },
        )
        failed.attachFramework(framework, Runnable {})
        failed.onModuleLoaded(loaded())
        assertThrows(IllegalStateException::class.java) { failed.onPackageReady(ready()) }
        failed.onModuleLoaded(loaded())
        failed.onPackageReady(ready())
        assertEquals(1, attempts)
        assertFalse(ProcessEntryGate(properties).claim("xposed82"))
        assertFalse(ProcessEntryGate(properties).claim("zygisk"))
    }

    @Test fun `legacy and modern valid callbacks select exactly their first arrival`() {
        for (modernFirst in listOf(true, false)) {
            val properties = Properties()
            val modernGate = ProcessEntryGate(properties)
            val legacyGate = ProcessEntryGate(properties)
            val accepted = ArrayList<String>()
            val recorder = object : RuntimeBootstrap {
                override fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult {
                    accepted += event.loaderId
                    return BootstrapResult.Ignored("recorded")
                }
            }
            val modern = Libxposed102Entry(TestProfile, { 42 to 10001 }, { recorder }, { modernGate.claim("libxposed102") })
            modern.attachFramework(framework, Runnable {})
            val legacy = Xposed82Entry(
                TestProfile,
                { 42 to 10001 }, { recorder }, { FakeHookBackend() }, Logger {}, { legacyGate.claim("xposed82") },
            )
            val access = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
            val param = (access.allocateInstance(XC_LoadPackage.LoadPackageParam::class.java) as XC_LoadPackage.LoadPackageParam).apply {
                packageName = "io.github.nuke.oss.fixture"
                processName = packageName
                classLoader = javaClass.classLoader
            }
            if (modernFirst) modern.onModuleLoaded(loaded()) else legacy.handleLoadPackage(param)
            if (modernFirst) legacy.handleLoadPackage(param) else modern.onModuleLoaded(loaded())
            modern.onPackageReady(ready())
            legacy.handleLoadPackage(param)
            assertEquals(listOf(if (modernFirst) "libxposed102" else "xposed82"), accepted)
            assertFalse(ProcessEntryGate(properties).claim("zygisk"))
        }
    }

    @Test fun `modern entry loads and starts with legacy API entirely unavailable`() {
        val parent = requireNotNull(javaClass.classLoader)
        val prefix = Libxposed102Entry::class.java.name
        val isolated = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("de.robv.android.xposed.")) throw ClassNotFoundException(name)
                if (!name.startsWith(prefix)) return super.loadClass(name, resolve)
                return findLoadedClass(name) ?: run {
                    val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
                    defineClass(name, bytes, 0, bytes.size)
                }
            }
        }
        assertThrows(ClassNotFoundException::class.java) { isolated.loadClass("de.robv.android.xposed.XposedBridge") }
        val type = isolated.loadClass(prefix)
        val callbackType = kotlin.jvm.functions.Function0::class.java
        val constructor = type.getDeclaredConstructor(LoaderProfile::class.java, callbackType, callbackType, callbackType).apply { isAccessible = true }
        val isolatedEvents = ArrayList<LoadEvent>()
        val recorder = object : RuntimeBootstrap {
            override fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult {
                isolatedEvents += event
                return BootstrapResult.Ignored("recorded")
            }
        }
        val isolatedEntry = constructor.newInstance(TestProfile, { 42 to 10001 }, { recorder }, { true }) as io.github.libxposed.api.XposedModule
        isolatedEntry.attachFramework(framework, Runnable {})
        isolatedEntry.onModuleLoaded(loaded())
        isolatedEntry.onPackageReady(ready())
        assertEquals("libxposed102", isolatedEvents.single().loaderId)
    }

    @Test fun `hot reload is explicitly rejected`() {
        val param = object : HotReloadingParam {
            override fun getExtras(): android.os.Bundle? = null
            override fun setSavedInstanceState(outState: Any?) = error("Reload must not be accepted")
        }
        assertFalse(entry.onHotReloading(param))
    }
}
