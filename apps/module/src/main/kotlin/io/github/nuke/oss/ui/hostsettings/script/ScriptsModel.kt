package io.github.nuke.oss.ui.hostsettings.script

import androidx.lifecycle.ViewModel
import io.github.nuke.oss.app.shared.script.ConfigField
import io.github.nuke.oss.app.shared.script.InstalledScript
import io.github.nuke.oss.app.shared.script.ScriptEntry
import io.github.nuke.oss.app.shared.script.ScriptHold
import io.github.nuke.oss.app.shared.script.ScriptInstallException
import io.github.nuke.oss.app.shared.script.ScriptLogEntry
import io.github.nuke.oss.app.shared.script.ScriptRuntime
import io.github.nuke.oss.app.shared.script.StagedScript
import io.github.nuke.oss.script.ScriptPermissions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

internal data class ScriptPermissionItem(val name: String, val reason: String?)

// One installed script as the page shows it.
internal data class ScriptItem(
    val id: String,
    val name: String,
    val version: String,
    val author: String?,
    val description: String?,
    val enabled: Boolean,
    val running: Boolean,
    val hold: ScriptHold,
    val failure: String?,
    val hasReadme: Boolean,
    val configFields: Int,
    // Shown only: network access and picked files.
    val declared: List<String>,
    val networkOrigins: List<String>,
    val anyNetwork: Boolean,
    // Need the user's grant before the script runs.
    val dangerous: List<ScriptPermissionItem>,
    val granted: Boolean,
)

internal enum class ImportKind { NEW, UPDATE, REINSTALL, DOWNGRADE }

internal data class StagedImport(val staged: StagedScript, val kind: ImportKind) {
    val previousVersion: String? get() = staged.previous?.manifest?.version
}

// A failed import: the package reader's code, mapped to a message by the page, and its own detail.
internal class ImportFailure(val code: String, message: String) : IOException(message)

internal data class ScriptConfigForm(val fields: List<ConfigField>, val values: Map<String, Any?>)

// What the scripts page reads and changes. Runtime calls touch storage and the feature manager, so they run on the IO
// dispatcher; the page collects items() and calls the rest from its own coroutines. A package staged but neither
// installed nor discarded when the page goes away is discarded.
internal class ScriptsModel(
    private val runtime: ScriptRuntime?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val pending = ConcurrentHashMap.newKeySet<StagedScript>()

    val available: Boolean get() = runtime != null

    // A new list whenever a script is installed, switched, granted, configured, stops or is removed.
    fun items(): Flow<List<ScriptItem>> {
        val scripts = runtime ?: return flowOf(emptyList())
        return scripts.revision.map { scripts.entries().map(::itemOf) }.flowOn(io)
    }

    // False when the script needs a grant first; the page then asks for it.
    suspend fun setRunning(id: String, running: Boolean): Boolean = withScripts { scripts ->
        val entry = scripts.entries().firstOrNull { it.script.manifest.id == id } ?: return@withScripts true
        if (running && entry.needsGrant) return@withScripts false
        scripts.setEnabled(id, running)
        true
    }

    suspend fun grant(id: String, enable: Boolean) = withScripts { scripts ->
        scripts.grant(id)
        if (enable) scripts.setEnabled(id, true)
    }

    suspend fun revoke(id: String) = withScripts { it.revoke(id) }

    suspend fun delete(id: String) = withScripts { it.uninstall(id) }

    suspend fun stage(open: () -> InputStream?): StagedImport = withScripts { scripts ->
        val staged = try {
            (open() ?: throw ImportFailure("IO", "The file cannot be opened")).use(scripts::stage)
        } catch (error: ScriptInstallException) {
            throw ImportFailure(error.code, error.message.orEmpty())
        }
        pending += staged
        StagedImport(staged, kindOf(staged))
    }

    // Installed switched off and without a grant: like the legacy page, switching it on asks for the grant.
    suspend fun install(staged: StagedImport) = withScripts { scripts ->
        try {
            scripts.commit(staged.staged, grant = false, enable = false)
        } catch (error: ScriptInstallException) {
            throw ImportFailure(error.code, error.message.orEmpty())
        } finally {
            pending -= staged.staged
        }
    }

    suspend fun discard(staged: StagedImport) = withScripts { scripts ->
        if (pending.remove(staged.staged)) scripts.discard(staged.staged)
    }

    suspend fun config(id: String): ScriptConfigForm? = withScripts { scripts ->
        val entry = scripts.entries().firstOrNull { it.script.manifest.id == id } ?: return@withScripts null
        val schema = scripts.schemaOf(entry.script)
        ScriptConfigForm(schema.fields, schema.values(entry.state.config))
    }

    suspend fun saveConfig(id: String, values: Map<String, Any?>) = withScripts { it.saveConfig(id, values) }

    suspend fun readme(id: String): String? = withScripts { scripts ->
        scripts.entries().firstOrNull { it.script.manifest.id == id }?.let { scripts.readme(it.script) }
    }

    fun logs(id: String): Flow<List<ScriptLogEntry>> {
        val scripts = runtime ?: return flowOf(emptyList())
        return scripts.logs.revision.map { scripts.logs.read(id) }
    }

    fun clearLogs(id: String) {
        runtime?.logs?.clear(id)
    }

    override fun onCleared() {
        val scripts = runtime ?: return
        pending.toList().forEach(scripts::discard)
        pending.clear()
    }

    private suspend fun <T> withScripts(block: (ScriptRuntime) -> T): T {
        val scripts = runtime ?: throw IllegalStateException("Scripts are unavailable in this process")
        return withContext(io) { block(scripts) }
    }

    private fun kindOf(staged: StagedScript): ImportKind {
        val previous = staged.previous?.manifest ?: return ImportKind.NEW
        return when {
            staged.manifest.versionCode > previous.versionCode -> ImportKind.UPDATE
            staged.manifest.versionCode == previous.versionCode -> ImportKind.REINSTALL
            else -> ImportKind.DOWNGRADE
        }
    }

    private fun itemOf(entry: ScriptEntry): ScriptItem {
        val script: InstalledScript = entry.script
        val manifest = script.manifest
        val permissions = manifest.permissions
        val fields = runCatching { runtime!!.schemaOf(script).fields.size }.getOrDefault(0)
        return ScriptItem(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            author = manifest.author,
            description = manifest.description,
            enabled = entry.state.enabled,
            running = entry.running,
            hold = entry.hold,
            failure = entry.failure,
            hasReadme = manifest.readme != null,
            configFields = fields,
            declared = permissions.declared() - permissions.dangerous().toSet(),
            networkOrigins = permissions.networkOrigins,
            anyNetwork = permissions.anyNetwork,
            dangerous = permissions.dangerous().map { name ->
                ScriptPermissionItem(name, permissions.filesReason.takeIf { name == ScriptPermissions.HOST_FILES })
            },
            granted = !entry.needsGrant,
        )
    }
}
