package io.github.nuke.oss.script.java

import io.github.nuke.oss.script.ScriptException
import java.util.IdentityHashMap

// The Java objects one session's script can reach. Each export hands out one reference the script's wrapper releases
// when it is finalized; the same object keeps one id while any reference is alive. Exports happen on the script
// thread, lookups also on host threads applying a Hook answer, so access is synchronized.
internal class JavaHandles(private val maxHandles: Int) {
    private class Entry(val value: Any, var references: Int)

    private val byId = HashMap<Long, Entry>()
    private val byValue = IdentityHashMap<Any, Long>()
    private var nextId = 1L
    private var exportsSinceCollection = 0

    // Returns the id and whether enough exports piled up that a garbage collection should run.
    @Synchronized
    fun export(value: Any): Pair<Long, Boolean> {
        val existing = byValue[value]
        val id = if (existing != null) {
            byId.getValue(existing).references++
            existing
        } else {
            if (byId.size >= maxHandles) {
                throw ScriptException(ScriptException.LIMIT_EXCEEDED, "The script holds too many Java objects ($maxHandles)")
            }
            val id = nextId++
            byId[id] = Entry(value, 1)
            byValue[value] = id
            id
        }
        exportsSinceCollection++
        val collect = exportsSinceCollection >= COLLECT_AFTER
        if (collect) exportsSinceCollection = 0
        return id to collect
    }

    @Synchronized
    fun get(id: Long): Any = byId[id]?.value
        ?: throw ScriptException(ScriptException.INVALID_ARGUMENT, "The Java object $id was already released")

    @Synchronized
    fun release(id: Long) {
        val entry = byId[id] ?: return
        if (--entry.references > 0) return
        byId.remove(id)
        byValue.remove(entry.value)
    }

    @Synchronized
    fun size(): Int = byId.size

    @Synchronized
    fun clear() {
        byId.clear()
        byValue.clear()
    }

    private companion object {
        const val COLLECT_AFTER = 2_048
    }
}
