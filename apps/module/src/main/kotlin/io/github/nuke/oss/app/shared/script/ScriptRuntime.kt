package io.github.nuke.oss.app.shared.script

import io.github.nuke.oss.core.FeatureState
import io.github.nuke.oss.runtime.FailurePhase
import io.github.nuke.oss.runtime.FeatureManager
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import io.github.nuke.oss.script.ScriptDefinition
import io.github.nuke.oss.script.ScriptEngine
import io.github.nuke.oss.script.ScriptException
import io.github.nuke.oss.script.ScriptLogLevel
import io.github.nuke.oss.script.ScriptModule
import io.github.nuke.oss.script.ScriptSession
import io.github.nuke.oss.script.ScriptSessionListener
import io.github.nuke.oss.script.java.HookModule
import io.github.nuke.oss.script.java.JavaModule
import io.github.nuke.oss.script.module.CONFIG_EVENT
import io.github.nuke.oss.script.module.CORE_MODULES
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

// Why a script is not running although the user switched it on.
internal enum class ScriptHold { NONE, NEEDS_GRANT, SAFE_MODE, LOCKED }

internal data class ScriptEntry(
    val script: InstalledScript,
    val state: ScriptState,
    val running: Boolean,
    val hold: ScriptHold,
    val failure: String?,
) {
    val needsGrant: Boolean get() = script.manifest.permissions.dangerous().isNotEmpty() &&
        state.grant != script.manifest.permissions.grantDigest()
}

// Runs the installed scripts of the host's main process, each as a feature named script.<id> so installs, failures,
// locking and safe mode work as for every other feature. The scripts page drives it from the same process; switching
// a script, granting it or saving its settings takes effect at once.
internal class ScriptRuntime(
    private val repository: ScriptRepository,
    private val states: ScriptStateStore,
    val logs: ScriptLogs,
    private val manager: FeatureManager,
    private val loader: ClassLoader,
    private val engine: ScriptEngine,
    private val worker: Executor,
    private val hold: (featureId: String) -> ScriptHold,
    private val hostModules: (ScriptDefinition, FeatureScope) -> List<ScriptModule> = { _, _ -> emptyList() },
) {
    private val registered = ConcurrentHashMap.newKeySet<String>()
    private val sessions = ConcurrentHashMap<String, ScriptSession>()
    private val failures = ConcurrentHashMap<String, String>()
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> get() = changes

    // Called once while the host registers its features.
    fun registerAll() {
        for (script in repository.list()) {
            register(script.manifest.id)
            if (canRun(script)) manager.setEnabled(script.manifest.featureId, true)
        }
    }

    fun entries(): List<ScriptEntry> {
        val statuses = manager.snapshot().associateBy { it.id }
        return repository.list().map { script ->
            val state = states.read(script.manifest.id)
            val status = statuses[script.manifest.featureId]
            ScriptEntry(
                script,
                state,
                running = status?.state == FeatureState.ACTIVE && sessions[script.manifest.id]?.isFinished == false,
                hold = holdOf(script, state),
                failure = failures[script.manifest.id] ?: status?.takeIf { it.state == FeatureState.FAILED }?.reason,
            )
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val script = repository.get(id) ?: return
        states.write(id, states.read(id).copy(enabled = enabled))
        apply(script)
    }

    fun grant(id: String) {
        val script = repository.get(id) ?: return
        states.write(id, states.read(id).copy(grant = script.manifest.permissions.grantDigest()))
        apply(script)
    }

    // Takes the grant back; a running script stops until the user authorizes it again.
    fun revoke(id: String) {
        val script = repository.get(id) ?: return
        states.write(id, states.read(id).copy(grant = null))
        apply(script)
    }

    fun readme(script: InstalledScript): String? = script.manifest.readme?.let { script.file(it).readText() }

    fun stage(source: InputStream): StagedScript = repository.stage(source)

    fun discard(staged: StagedScript) = repository.discard(staged.token)

    // Installs a staged package. Granting here is the user confirming the permissions shown with it; a running older
    // version is restarted with the new one.
    fun commit(staged: StagedScript, grant: Boolean, enable: Boolean): InstalledScript {
        val script = repository.commit(staged.token)
        val id = script.manifest.id
        val state = states.read(id)
        states.write(
            id,
            state.copy(
                enabled = enable || state.enabled,
                grant = if (grant) script.manifest.permissions.grantDigest() else state.grant,
            ),
        )
        register(id)
        stop(id)
        failures.remove(id)
        apply(script)
        return script
    }

    fun uninstall(id: String) {
        stop(id)
        repository.uninstall(id)
        states.remove(id)
        failures.remove(id)
        logs.clear(id)
        changed()
    }

    fun saveConfig(id: String, values: Map<String, Any?>) {
        val script = repository.get(id) ?: return
        states.write(id, states.read(id).copy(config = values))
        sessions[id]?.emit(CONFIG_EVENT, schemaOf(script).values(values))
        changed()
    }

    fun schemaOf(script: InstalledScript): ScriptConfigSchema =
        script.manifest.configSchema?.let { ScriptConfigSchema.parse(script.file(it).readText()) } ?: ScriptConfigSchema.EMPTY

    private fun register(id: String) {
        if (registered.add(id)) manager.register(ScriptFeature(id))
    }

    private fun apply(script: InstalledScript) {
        val featureId = script.manifest.featureId
        if (canRun(script)) {
            failures.remove(script.manifest.id)
            manager.setEnabled(featureId, true)
        } else {
            manager.setEnabled(featureId, false)
        }
        changed()
    }

    private fun stop(id: String) {
        if (id in registered) manager.setEnabled(ScriptManifest.featureIdOf(id), false)
    }

    private fun canRun(script: InstalledScript): Boolean {
        val state = states.read(script.manifest.id)
        return state.enabled && holdOf(script, state) == ScriptHold.NONE
    }

    private fun holdOf(script: InstalledScript, state: ScriptState): ScriptHold {
        val held = hold(script.manifest.featureId)
        if (held != ScriptHold.NONE) return held
        val permissions = script.manifest.permissions
        return if (permissions.dangerous().isNotEmpty() && state.grant != permissions.grantDigest()) ScriptHold.NEEDS_GRANT else ScriptHold.NONE
    }

    private fun changed() = changes.update { it + 1 }

    private inner class ScriptFeature(private val scriptId: String) : RuntimeFeature {
        override val id = ScriptManifest.featureIdOf(scriptId)

        override fun install(scope: FeatureScope) {
            val script = repository.get(scriptId) ?: error("Script $scriptId is not installed")
            val manifest = script.manifest
            val state = states.read(scriptId)
            check(holdOf(script, state) == ScriptHold.NONE) { "Script $scriptId is held: ${holdOf(script, state)}" }
            val definition = ScriptDefinition(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                versionCode = manifest.versionCode,
                entrySource = script.file(manifest.entry).readText(),
                entryName = manifest.entry,
                permissions = manifest.permissions.toScript(),
                config = schemaOf(script).values(state.config),
            )
            val java = JavaModule(loader)
            val modules = CORE_MODULES + java + HookModule(java, scope.hooks) + hostModules(definition, scope)
            // The session starts in the background; a failure after this point arrives through the listener.
            val session = engine.start(definition, modules, Listener(scriptId, id))
            sessions[scriptId] = session
            scope.own(AutoCloseable {
                sessions.remove(scriptId, session)
                session.close()
            })
            logs.add(scriptId, ScriptLogLevel.INFO, "Started ${manifest.name} ${manifest.version}")
        }
    }

    private inner class Listener(private val scriptId: String, private val featureId: String) : ScriptSessionListener {
        override fun log(level: ScriptLogLevel, message: String) = logs.add(scriptId, level, message)

        override fun scriptError(where: String, message: String) = logs.add(scriptId, ScriptLogLevel.ERROR, "$where: $message")

        override fun stopped(failure: ScriptException?) {
            if (failure == null) {
                logs.add(scriptId, ScriptLogLevel.INFO, "Stopped")
                changed()
                return
            }
            logs.add(scriptId, ScriptLogLevel.ERROR, "Stopped: ${failure.code} ${failure.message}")
            failures[scriptId] = "${failure.code}: ${failure.message}"
            // The session may stop on its own thread, which must not wait on the feature manager.
            worker.execute {
                manager.reportFailure(featureId, FailurePhase.INSTALL, failure)
                try {
                    manager.setEnabled(featureId, false)
                } catch (_: IllegalStateException) {
                }
                changed()
            }
        }
    }
}
