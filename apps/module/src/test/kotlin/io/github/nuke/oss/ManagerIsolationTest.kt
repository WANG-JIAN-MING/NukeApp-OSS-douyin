package io.github.nuke.oss

import org.junit.Assert.*
import org.junit.Test

class ManagerIsolationTest {
    @Test fun `module manager links without framework APIs or patch engine`() {
        val parent = requireNotNull(javaClass.classLoader)
        val manager = "io.github.nuke.oss.MainActivity"
        val settings = "io.github.nuke.oss.ui.manager.ManagerScreenKt"
        val isolated = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("de.robv.android.xposed.") || name.startsWith("io.github.libxposed.") ||
                    name.startsWith("org.lsposed.patch.") || name.startsWith("io.github.nuke.oss.patcher.")) {
                    throw ClassNotFoundException(name)
                }
                if (name != manager && !name.startsWith("$manager$") && name != settings && !name.startsWith("$settings$")) {
                    return super.loadClass(name, resolve)
                }
                return findLoadedClass(name) ?: run {
                    val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
                    defineClass(name, bytes, 0, bytes.size)
                }
            }
        }
        assertThrows(ClassNotFoundException::class.java) { isolated.loadClass("de.robv.android.xposed.XposedBridge") }
        assertThrows(ClassNotFoundException::class.java) { isolated.loadClass("io.github.libxposed.api.XposedModule") }
        assertThrows(ClassNotFoundException::class.java) { isolated.loadClass("org.lsposed.patch.ApkPatcher") }
        assertThrows(ClassNotFoundException::class.java) { isolated.loadClass("io.github.nuke.oss.patcher.patch.PatchEngine") }
        val activity = Class.forName(manager, false, isolated)
        assertSame(isolated, activity.classLoader)
        assertEquals("androidx.activity.ComponentActivity", checkNotNull(activity.superclass).name)
        assertTrue(activity.declaredMethods.isNotEmpty())
        val view = Class.forName(settings, false, isolated)
        assertSame(isolated, view.classLoader)
        assertTrue(view.declaredMethods.any { it.name == "ManagerScreen" })
    }
}
