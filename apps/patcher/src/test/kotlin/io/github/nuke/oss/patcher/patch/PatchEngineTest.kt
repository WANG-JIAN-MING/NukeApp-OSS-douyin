package io.github.nuke.oss.patcher.patch

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lsposed.patch.ApkPatcher

class PatchEngineTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acceptsAllSixExactBundledPayloads() {
        PatchEngine().verifyPayloads(payloadArchive())
    }

    @Test fun rejectsModifiedMetaloaderBytes() {
        val failure = assertThrows(IllegalStateException::class.java) {
            PatchEngine().verifyPayloads(payloadArchive(modified = "classes.dex"))
        }
        assertTrue(failure.message!!.contains("mismatch"))
    }

    @Test fun rejectsMissingNativeAbiEvenWhenOtherPayloadsMatch() {
        val failure = assertThrows(IllegalStateException::class.java) {
            PatchEngine().verifyPayloads(payloadArchive(missing = "assets/lspatch/so/x86/liblspatch.so"))
        }
        assertTrue(failure.message!!.contains("Missing"))
    }

    @Test fun rejectsMissingInputBeforePatcherRuns() {
        val key = LocalPatchKey.loadOrCreate(temporary.newFolder())
        val module = temporary.newFile("module.apk").apply { writeText("module") }
        assertThrows(IllegalArgumentException::class.java) {
            PatchEngine().patch(File(temporary.root, "missing.apk"), module, temporary.newFolder(), key)
        }
    }

    @Test fun rejectsNonemptyOutputDirectoryWithoutTouchingFiles() {
        val key = LocalPatchKey.loadOrCreate(temporary.newFolder())
        val input = temporary.newFile("input.apk").apply { writeText("input") }
        val module = temporary.newFile("module.apk").apply { writeText("module") }
        val directory = temporary.newFolder()
        val existing = File(directory, "existing.apk").apply { writeText("preserve") }
        assertThrows(IllegalArgumentException::class.java) { PatchEngine().patch(input, module, directory, key) }
        assertTrue(existing.readText() == "preserve")
    }

    private fun payloadArchive(modified: String? = null, missing: String? = null): File {
        val archive = temporary.newFile()
        val mappings = linkedMapOf(
            "classes.dex" to "assets/lspatch/metaloader.dex",
            "assets/lspatch/loader.dex" to "assets/lspatch/loader.dex",
        ).apply {
            listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64").forEach { abi ->
                val name = "assets/lspatch/so/$abi/liblspatch.so"
                put(name, name)
            }
        }
        ZipOutputStream(archive.outputStream()).use { zip ->
            mappings.filterKeys { it != missing }.forEach { (name, resource) ->
                zip.putNextEntry(ZipEntry(name))
                if (name == modified) {
                    zip.write(byteArrayOf(1, 2, 3))
                } else {
                    checkNotNull(checkNotNull(ApkPatcher::class.java.classLoader).getResourceAsStream(resource)).use { it.copyTo(zip) }
                }
                zip.closeEntry()
            }
        }
        return archive
    }
}
