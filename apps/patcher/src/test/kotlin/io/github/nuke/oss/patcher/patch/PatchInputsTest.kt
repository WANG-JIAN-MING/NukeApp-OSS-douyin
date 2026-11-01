package io.github.nuke.oss.patcher.patch

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pxb.android.axml.AxmlWriter
import pxb.android.axml.NodeVisitor

class PatchInputsTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `compatible module exposes selected file identity and version`() {
        val metadata = PatchApkValidator.module(apk(module = true))
        assertEquals("io.github.nuke.oss", metadata.packageName)
        assertEquals("2.0-test", metadata.versionName)
    }

    @Test fun `patcher identity cannot substitute for module`() {
        rejected("module_identity") { PatchApkValidator.module(apk(module = true, identity = "io.github.nuke.oss.patcher")) }
    }

    @Test fun `module with a legacy or unexpected entry is rejected`() {
        rejected("module_entry") { PatchApkValidator.module(apk(module = true, entry = "io.github.nuke.oss.loader.xposed82.Xposed82Entry")) }
    }

    @Test fun `module with multiple modern entry points is rejected`() {
        rejected("module_entry") { PatchApkValidator.module(apk(module = true, entry = "$ENTRY\nother.Module")) }
    }

    @Test fun `module requiring another API version is rejected`() {
        rejected("module_api") { PatchApkValidator.module(apk(module = true, properties = PROPERTIES.replace("targetApiVersion=102", "targetApiVersion=101"))) }
    }

    @Test fun `duplicate property cannot override the verified target API`() {
        rejected("module_api") { PatchApkValidator.module(apk(module = true, properties = "$PROPERTIES\ntargetApiVersion=101")) }
    }

    @Test fun `missing modern metadata is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PatchApkValidator.module(apk(identity = "io.github.nuke.oss")) }
    }

    @Test fun `patched wrapper is rejected for both roles`() {
        for (role in ApkRole.entries) {
            val file = apk(module = role == ApkRole.MODULE, extraEntry = "assets/lspatch/origin.apk")
            rejected("already_patched") {
                if (role == ApkRole.MODULE) PatchApkValidator.module(file) else PatchApkValidator.target(file)
            }
        }
    }

    @Test fun `module split APK is rejected`() {
        rejected("split_apk") { PatchApkValidator.module(apk(module = true, split = true)) }
    }

    @Test fun `oversized metadata is rejected before patch engine sees it`() {
        rejected("apk_entry_size") { PatchApkValidator.module(apk(module = true, entry = ENTRY + " ".repeat(8192))) }
    }

    @Test fun `both module and target are required independently`() {
        val inputs = PatchInputs()
        inputs.replace(ApkRole.TARGET, apk(), "target.apk", "target-sha")
        assertThrows(IllegalStateException::class.java) { inputs.requireReady() }
        inputs.replace(ApkRole.MODULE, apk(module = true), "module.apk", "module-sha")
        val (target, module) = inputs.requireReady()
        assertEquals("target.apk", target.displayName)
        assertEquals("module-sha", module.sha256)
        assertNotEquals(target.file, module.file)
    }

    @Test fun `failed module replacement clears stale module and output but preserves target`() {
        val inputs = readyInputs()
        val target = inputs.target
        inputs.recordOutput(directory.newFile())
        assertThrows(IllegalArgumentException::class.java) {
            inputs.replace(ApkRole.MODULE, apk(), "wrong-module.apk", "new-sha")
        }
        assertNull(inputs.module)
        assertNull(inputs.output)
        assertSame(target, inputs.target)
        assertThrows(IllegalStateException::class.java) { inputs.requireReady() }
    }

    @Test fun `failed target replacement clears stale result but preserves module`() {
        val inputs = readyInputs()
        val module = inputs.module
        inputs.recordOutput(directory.newFile())
        assertThrows(IllegalArgumentException::class.java) {
            inputs.replace(ApkRole.TARGET, apk(identity = "io.github.nuke.oss.patcher"), "self.apk", "new-sha")
        }
        assertNull(inputs.target)
        assertNull(inputs.output)
        assertSame(module, inputs.module)
    }

    @Test fun `beginning document import invalidates previous result before staging`() {
        val inputs = readyInputs()
        inputs.recordOutput(directory.newFile())
        inputs.invalidate(ApkRole.MODULE)
        assertNull(inputs.module)
        assertNull(inputs.output)
        assertNotNull(inputs.target)
    }

    @Test fun `direct engine rejects invalid module without producing patched files`() {
        val output = directory.newFolder()
        val key = LocalPatchKey.loadOrCreate(directory.newFolder())
        rejected("module_identity") { PatchEngine().patch(apk(), apk(), output, key) }
        assertTrue(output.listFiles()!!.isEmpty())
    }

    private fun readyInputs(): PatchInputs = PatchInputs().apply {
        replace(ApkRole.TARGET, apk(), "target.apk", "target-sha")
        replace(ApkRole.MODULE, apk(module = true), "module.apk", "module-sha")
    }

    private fun rejected(code: String, action: () -> Unit) {
        assertEquals(code, assertThrows(IllegalArgumentException::class.java) { action() }.message)
    }

    private fun apk(
        module: Boolean = false,
        identity: String = if (module) "io.github.nuke.oss" else "io.github.nuke.oss.fixture",
        entry: String = ENTRY,
        properties: String = PROPERTIES,
        extraEntry: String? = null,
        split: Boolean = false,
    ): File {
        val xml = AxmlWriter()
        xml.ns("android", NS, 0)
        val root = xml.child(null, "manifest")
        root.attr(null, "package", -1, NodeVisitor.TYPE_STRING, identity)
        root.attr(NS, "versionName", 0x0101021c, NodeVisitor.TYPE_STRING, "2.0-test")
        if (split) root.attr(null, "split", -1, NodeVisitor.TYPE_STRING, "feature")
        root.end()
        xml.end()
        return directory.newFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                fun write(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                write("AndroidManifest.xml", xml.toByteArray())
                if (module) {
                    write("META-INF/xposed/java_init.list", entry.toByteArray())
                    write("META-INF/xposed/module.prop", properties.toByteArray())
                }
                extraEntry?.let { write(it, byteArrayOf(1)) }
            }
        }
    }

    companion object {
        private const val NS = "http://schemas.android.com/apk/res/android"
        private const val ENTRY = "io.github.nuke.oss.loader.libxposed102.Libxposed102Entry"
        private const val PROPERTIES = "minApiVersion=102\ntargetApiVersion=102\nstaticScope=true\nautoHotReload=false"
    }
}
