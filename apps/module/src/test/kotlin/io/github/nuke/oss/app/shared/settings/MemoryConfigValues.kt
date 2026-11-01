package io.github.nuke.oss.app.shared.settings

// A plain map standing in for the host's key/value store.
internal class MemoryConfigValues : ConfigValues {
    val stored = LinkedHashMap<String, Any?>()

    override fun all(): Map<String, Any?> = LinkedHashMap(stored)
    override fun put(key: String, value: Any) { stored[key] = value }
    override fun remove(key: String) { stored.remove(key) }
    override fun clear() = stored.clear()
}
