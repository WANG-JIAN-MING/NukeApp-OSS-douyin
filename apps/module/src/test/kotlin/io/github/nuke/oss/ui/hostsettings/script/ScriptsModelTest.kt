package io.github.nuke.oss.ui.hostsettings.script

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nuke.oss.app.shared.script.KeyedScriptStates
import io.github.nuke.oss.app.shared.script.ScriptHold
import io.github.nuke.oss.app.shared.script.ScriptLogs
import io.github.nuke.oss.app.shared.script.ScriptRepository
import io.github.nuke.oss.app.shared.script.ScriptRuntime
import io.github.nuke.oss.app.shared.script.simplePackage
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.FeatureManager
import io.github.nuke.oss.script.ScriptEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor
import kotlin.io.path.createTempDirectory

class ScriptsModelTest {
    private val manager = FeatureManager("gen-1", DefaultHookService(FakeHookBackend()), Logger {})
    private val engine = ScriptEngine()
    private val values = HashMap<String, String>()
    private val root = createTempDirectory("scripts").toFile()
    private val runtime = ScriptRuntime(
        ScriptRepository(root, "com.ss.android.ugc.aweme", 1),
        KeyedScriptStates({ synchronized(values) { values[it] } }) { key, value ->
            synchronized(values) { if (value == null) values.remove(key) else values[key] = value }
        },
        ScriptLogs(), manager, javaClass.classLoader!!, engine, Executor { it.run() }, { ScriptHold.NONE },
    )
    private val model = ScriptsModel(runtime)

    @After
    fun close() {
        manager.close()
        engine.close()
    }

    private fun item(id: String = "com.example.hello", predicate: (ScriptItem) -> Boolean = { true }) = runBlocking {
        withTimeout(5_000) { model.items().first { list -> list.any { it.id == id && predicate(it) } }.single { it.id == id } }
    }

    private fun install(bytes: ByteArray): StagedImport = runBlocking {
        model.stage { bytes.inputStream() }.also { model.install(it) }
    }

    @Test
    fun anImportedScriptStartsSwitchedOffAndAsksForItsGrant() = runBlocking {
        install(
            simplePackage(
                permissions = """{"java":true,"network":{"origins":["https://api.example.com"]},"filesystem":{"host":true,"reason":"reads the cache"}}""",
                extra = ",\"description\":\"Says hello\",\"author\":\"ana\",\"configSchema\":\"c.json\"",
                files = mapOf(
                    "README.md" to "# Hello",
                    "c.json" to """{"fields":[{"key":"a","type":"boolean","title":"A"},{"key":"b","type":"string","title":"B"}]}""",
                ),
            ),
        )
        val installed = item()
        assertFalse(installed.enabled)
        assertFalse(installed.granted)
        assertEquals(listOf("network"), installed.declared)
        assertEquals(listOf(ScriptPermissionItem("java", null), ScriptPermissionItem("filesystem.host", "reads the cache")), installed.dangerous)
        assertEquals(2, installed.configFields)
        assertTrue(installed.hasReadme)
        assertEquals("# Hello", model.readme("com.example.hello"))

        assertFalse(model.setRunning("com.example.hello", true))
        assertFalse(item().enabled)
        model.grant("com.example.hello", enable = true)
        assertTrue(item { it.running }.granted)

        model.revoke("com.example.hello")
        val revoked = item { !it.running }
        assertEquals(ScriptHold.NEEDS_GRANT, revoked.hold)
        assertTrue(revoked.enabled)
    }

    @Test
    fun importsAreClassifiedAgainstTheInstalledVersion() = runBlocking {
        assertEquals(ImportKind.NEW, install(simplePackage(versionCode = 2)).kind)
        val same = model.stage { simplePackage(versionCode = 2).inputStream() }
        assertEquals(ImportKind.REINSTALL, same.kind)
        model.discard(same)
        assertEquals(ImportKind.UPDATE, model.stage { simplePackage(versionCode = 3).inputStream() }.also { model.discard(it) }.kind)
        val older = model.stage { simplePackage(versionCode = 1).inputStream() }
        assertEquals(ImportKind.DOWNGRADE, older.kind)
        assertEquals("1.0.2", older.previousVersion)
        model.discard(older)
        assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aRejectedPackageReportsTheReaderCode() = runBlocking {
        try {
            model.stage { "not a package".byteInputStream() }
            fail("Staged")
        } catch (error: ImportFailure) {
            assertEquals("INVALID_PACKAGE", error.code)
        }
        try {
            model.stage { null }
            fail("Staged")
        } catch (error: ImportFailure) {
            assertEquals("IO", error.code)
        }
    }

    @Test
    fun settingsAreReadAndSaved() = runBlocking {
        install(
            simplePackage(
                extra = ",\"configSchema\":\"c.json\"",
                files = mapOf("c.json" to """{"fields":[{"key":"n","type":"integer","title":"N","default":2}]}"""),
            ),
        )
        assertEquals(mapOf("n" to 2L), model.config("com.example.hello")!!.values)
        model.saveConfig("com.example.hello", mapOf("n" to 5L))
        assertEquals(mapOf("n" to 5L), model.config("com.example.hello")!!.values)
    }

    @Test
    fun deletingRemovesTheScript() = runBlocking {
        install(simplePackage())
        item()
        model.delete("com.example.hello")
        withTimeout(5_000) { model.items().first { it.isEmpty() } }
        Unit
    }

    @Test
    fun leavingThePageDiscardsAStagedPackage() = runBlocking {
        val store = ViewModelStore()
        val owned = ViewModelProvider.create(store, viewModelFactory { initializer { ScriptsModel(runtime) } })[ScriptsModel::class.java]
        owned.stage { simplePackage().inputStream() }
        assertEquals(1, File(root, "staging").listFiles().orEmpty().size)
        store.clear()
        assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun withoutAScriptStoreThePageSaysSo() = runBlocking {
        val unavailable = ScriptsModel(null)
        assertFalse(unavailable.available)
        assertEquals(emptyList<ScriptItem>(), unavailable.items().first())
        try {
            unavailable.setRunning("x", true)
            fail("Ran")
        } catch (_: IllegalStateException) {
        }
    }
}
