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

class PatchManifestTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `complete manifest supplies package version and sdk`() {
        val parsed = PatchManifestReader.read(apk())
        assertEquals("io.github.nuke.oss.fixture", parsed.packageName)
        assertEquals("1.0", parsed.versionName)
        assertEquals(27, parsed.minSdk)
    }

    @Test fun `split APK is rejected`() { rejected { it.attr(null, "split", -1, NodeVisitor.TYPE_STRING, "feature") } }
    @Test fun `split base requiring install-time dependencies is rejected`() {
        rejected { root -> root.child(null, "uses-split").apply { attr(NS, "name", 0x01010003, NodeVisitor.TYPE_STRING, "feature"); end() } }
    }
    @Test fun `required split flag is rejected`() {
        rejected { root -> root.child(null, "application").apply { attr(NS, "isSplitRequired", 0x01010591, NodeVisitor.TYPE_INT_BOOLEAN, -1); end() } }
    }
    @Test fun `required split types is rejected`() { rejected { it.attr(NS, "requiredSplitTypes", 0x0101064e, NodeVisitor.TYPE_STRING, "config.en") } }
    @Test fun `explicit false split metadata permits complete APK`() {
        assertEquals("io.github.nuke.oss.fixture", PatchManifestReader.read(apk(extra = { metadata(it, false) })).packageName)
    }
    @Test fun `true split metadata rejects incomplete base APK`() { rejected { metadata(it, true) } }

    @Test fun `self APK is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PatchManifestReader.read(apk(identity = "io.github.nuke.oss")) }
    }
    @Test fun `patcher cannot be a patch target`() {
        assertThrows(IllegalArgumentException::class.java) { PatchManifestReader.read(apk(identity = "io.github.nuke.oss.patcher")) }
    }
    @Test fun `malformed manifest fails`() {
        val file = directory.newFile("broken.apk")
        ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("AndroidManifest.xml")); it.write(byteArrayOf(1, 2, 3)); it.closeEntry() }
        assertThrows(Exception::class.java) { PatchManifestReader.read(file) }
    }

    private fun metadata(root: NodeVisitor, required: Boolean) {
        root.child(null, "application").apply {
            child(null, "meta-data").apply {
                attr(NS, "name", 0x01010003, NodeVisitor.TYPE_STRING, "com.android.vending.splits.required")
                attr(NS, "value", 0x01010024, NodeVisitor.TYPE_INT_BOOLEAN, if (required) -1 else 0)
                end()
            }
            end()
        }
    }

    private fun rejected(extra: (NodeVisitor) -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { PatchManifestReader.read(apk(extra = extra)) }
        assertEquals("split_apk", error.message)
    }

    private fun apk(identity: String = "io.github.nuke.oss.fixture", extra: (NodeVisitor) -> Unit = {}): File {
        val xml = AxmlWriter()
        xml.ns("android", NS, 0)
        val root = xml.child(null, "manifest")
        root.attr(null, "package", -1, NodeVisitor.TYPE_STRING, identity)
        root.attr(NS, "versionName", 0x0101021c, NodeVisitor.TYPE_STRING, "1.0")
        root.child(null, "uses-sdk").apply { attr(NS, "minSdkVersion", 0x0101020c, NodeVisitor.TYPE_FIRST_INT, 27); end() }
        extra(root)
        root.end(); xml.end()
        val file = directory.newFile()
        ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("AndroidManifest.xml")); it.write(xml.toByteArray()); it.closeEntry() }
        return file
    }

    companion object { private const val NS = "http://schemas.android.com/apk/res/android" }
}
