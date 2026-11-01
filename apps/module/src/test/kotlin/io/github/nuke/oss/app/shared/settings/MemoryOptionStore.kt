package io.github.nuke.oss.app.shared.settings

// An option store that keeps its values in memory, for tests of everything above the host's private storage.
class MemoryOptionStore : OptionStore {
    val stored = LinkedHashMap<String, String>()
    override fun read(featureId: String, key: String): String? = stored["$featureId/$key"]
    override fun write(featureId: String, key: String, value: String) { stored["$featureId/$key"] = value }
}

class MemoryFeatureMemory : FeatureMemory {
    val stored = LinkedHashMap<String, String>()
    override fun read(featureId: String, key: String): String? = stored["$featureId/$key"]
    override fun write(featureId: String, key: String, value: String) { stored["$featureId/$key"] = value }
}
