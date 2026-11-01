package io.github.nuke.oss.app.shared.script

import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.script.ScriptLogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// What the user chose for one script: whether it runs, which set of dangerous capabilities they granted and the values
// of its settings.
internal data class ScriptState(
    val enabled: Boolean = false,
    val grant: String? = null,
    val config: Map<String, Any?> = emptyMap(),
)

internal interface ScriptStateStore {
    fun read(id: String): ScriptState
    fun write(id: String, state: ScriptState)
    fun remove(id: String)
}

// One JSON value per script under script.<id> in a string store.
internal class KeyedScriptStates(
    private val get: (key: String) -> String?,
    private val put: (key: String, value: String?) -> Unit,
) : ScriptStateStore {
    override fun read(id: String): ScriptState {
        val root = get(KEY_PREFIX + id)?.let { MiniJson.obj(MiniJson.parse(it)) } ?: return ScriptState()
        return ScriptState(
            enabled = root["enabled"] == true,
            grant = root["grant"] as? String,
            config = MiniJson.obj(root["config"]).orEmpty(),
        )
    }

    override fun write(id: String, state: ScriptState) =
        put(KEY_PREFIX + id, MiniJson.write(mapOf("enabled" to state.enabled, "grant" to state.grant, "config" to state.config)))

    override fun remove(id: String) = put(KEY_PREFIX + id, null)

    companion object {
        const val KEY_PREFIX = "script."
    }
}

internal data class ScriptLogEntry(val time: Long, val level: ScriptLogLevel, val message: String)

// The recent log lines of each script, kept in memory for the scripts page. Writers are script and host threads; the
// revision lets the page refresh without polling.
internal class ScriptLogs(private val capacity: Int = 500, private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = HashMap<String, ArrayDeque<ScriptLogEntry>>()
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> get() = changes

    fun add(id: String, level: ScriptLogLevel, message: String) {
        synchronized(entries) {
            val lines = entries.getOrPut(id) { ArrayDeque() }
            if (lines.size >= capacity) lines.removeFirst()
            lines.addLast(ScriptLogEntry(clock(), level, message))
        }
        changes.update { it + 1 }
    }

    fun read(id: String): List<ScriptLogEntry> = synchronized(entries) { entries[id]?.toList().orEmpty() }

    fun clear(id: String) {
        synchronized(entries) { entries.remove(id) }
        changes.update { it + 1 }
    }
}
