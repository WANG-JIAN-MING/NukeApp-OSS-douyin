package io.github.nuke.oss.patcher.patch

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FixturePatchIntegrationTest {
    @get:Rule val privateDirectory = TemporaryFolder()

    @Test fun `real fixture patches with current module independent signing and JNI verification`() {
        val original = File(requireNotNull(System.getProperty("nuke.patch.fixture")))
        val module = File(requireNotNull(System.getProperty("nuke.patch.module")))
        val reportDirectory = File(requireNotNull(System.getProperty("nuke.patch.report")))
        reportDirectory.deleteRecursively()
        assertTrue(reportDirectory.mkdirs())
        assertEquals("io.github.nuke.oss.fixture", PatchApkValidator.target(original).packageName)
        assertEquals("io.github.nuke.oss", PatchApkValidator.module(module).packageName)
        NativePatchFiles.ensureLoaded()
        val moduleReport = JsonParser.parseString(NativePatchFiles.inspectApk(module.path)).asJsonObject
        assertTrue(moduleReport.get("ok").asBoolean)
        assertFalse(moduleReport.get("already_patched").asBoolean)
        val key = LocalPatchKey.loadOrCreate(privateDirectory.root)
        val stages = mutableListOf<String>()
        val output = PatchEngine().patch(original, module, File(reportDirectory, "output"), key, stages::add)
        val report = NativePatchFiles.verifyPatched(original.path, module.path, output.path)
        val value = JsonParser.parseString(report).asJsonObject
        assertTrue(report, value.get("ok").asBoolean)
        assertTrue(value.get("already_patched").asBoolean)
        assertEquals(64, value.get("sha256").asString.length)
        assertTrue(stages.contains("stage:VERIFYING"))
        assertEquals("stage:VERIFYING", stages.last { it.startsWith("stage:") })
        File(reportDirectory, "verification.json").writeText(report)
        File(reportDirectory, "signer-sha256.txt").writeText(key.certificateSha256 + "\n")
        // TemporaryFolder removes the private key and password; only public evidence is retained.
    }
}
