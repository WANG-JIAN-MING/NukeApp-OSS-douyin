package io.github.nuke.oss.app.shared.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

// Runs against the real package reader in the host-built Rust core.
class ScriptRepositoryTest {
    private val root = createTempDirectory("scripts").toFile()
    private val repository = ScriptRepository(root, HOST, maxApi = 1, clock = { 42 })

    private fun stage(bytes: ByteArray) = repository.stage(bytes.inputStream())

    private fun failure(bytes: ByteArray): ScriptInstallException = try {
        stage(bytes)
        throw AssertionError("Staged")
    } catch (error: ScriptInstallException) {
        error
    }

    @Test
    fun stagesAndCommitsAPackage() {
        val staged = stage(simplePackage(files = mapOf("README.md" to "# Hello")))
        assertEquals("com.example.hello", staged.manifest.id)
        assertEquals("README.md", staged.manifest.readme)
        assertNull(staged.previous)
        assertTrue(repository.list().isEmpty())

        val installed = repository.commit(staged.token)
        assertEquals(staged.sha256, installed.sha256)
        assertEquals(42L, installed.installedAt)
        assertEquals("# Hello", installed.file("README.md").readText())
        assertEquals(listOf("com.example.hello"), repository.list().map { it.manifest.id })
        assertFalse(File(root, "staging/${staged.token}").exists())
    }

    @Test
    fun anUpdateReplacesTheOldFiles() {
        repository.commit(stage(simplePackage(files = mapOf("old.js" to ""))).token).let { assertTrue(it.file("old.js").exists()) }
        val update = stage(simplePackage(versionCode = 2))
        assertEquals(1L, update.previous!!.manifest.versionCode)
        val installed = repository.commit(update.token)
        assertEquals(2L, installed.manifest.versionCode)
        assertFalse(installed.file("old.js").exists())
        assertEquals(1, repository.list().size)
    }

    @Test
    fun rejectsInvalidPackagesWithTheReaderCode() {
        assertEquals("INVALID_PACKAGE", failure("not a zip".toByteArray()).code)
        assertEquals("INVALID_MANIFEST", failure(simplePackage(id = "nodots")).code)
        assertEquals("UNSUPPORTED_API", failure(scriptPackage(mapOf(
            "manifest.json" to manifest().replace("\"apiVersion\":1", "\"apiVersion\":9"), "main.js" to "",
        ))).code)
        assertEquals("WRONG_HOST", failure(simplePackage(extra = ",\"hosts\":[\"com.other.app\"]")).code)
        assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun acceptsAPackageForThisHost() {
        assertEquals(listOf(HOST), stage(simplePackage(extra = ",\"hosts\":[\"$HOST\"]")).manifest.hosts)
    }

    @Test
    fun discardingAndUninstallingRemoveFiles() {
        val staged = stage(simplePackage())
        repository.discard(staged.token)
        try {
            repository.commit(staged.token)
            fail("Committed a discarded package")
        } catch (error: ScriptInstallException) {
            assertEquals("EXPIRED", error.code)
        }
        repository.commit(stage(simplePackage()).token)
        repository.dataDir("com.example.hello").apply { mkdirs() }.resolve("kept.txt").writeText("x")
        repository.commit(stage(simplePackage(versionCode = 2)).token)
        assertTrue(repository.dataDir("com.example.hello").resolve("kept.txt").exists())
        assertTrue(repository.uninstall("com.example.hello"))
        assertFalse(repository.dataDir("com.example.hello").exists())
        assertFalse(repository.uninstall("com.example.hello"))
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun leftoversOfAnUnfinishedInstallAreCleared() {
        stage(simplePackage())
        File(root, "incoming").mkdirs()
        File(root, "incoming/partial.nks").writeText("x")
        ScriptRepository(root, HOST, maxApi = 1)
        assertFalse(File(root, "staging").exists())
        assertFalse(File(root, "incoming").exists())
    }

    @Test
    fun readsPermissionsBack() {
        val manifest = stage(simplePackage(permissions = """{"java":true,"hook":true,"network":{"origins":["https://api.example.com"]},"host":["douyin.messaging"]}""")).manifest
        val permissions = manifest.permissions
        assertEquals(listOf("https://api.example.com"), permissions.networkOrigins)
        assertEquals(listOf("java", "hook", "douyin.messaging"), permissions.dangerous())
        assertEquals(listOf("network", "java", "hook", "douyin.messaging"), permissions.declared())
    }

    private companion object {
        const val HOST = "com.ss.android.ugc.aweme"
    }
}
