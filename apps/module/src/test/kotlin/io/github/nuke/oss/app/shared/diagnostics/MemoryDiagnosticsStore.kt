package io.github.nuke.oss.app.shared.diagnostics

// Diagnostics kept in memory for tests, stored the way the host store keeps them: one encoded value per feature.
internal class MemoryDiagnosticsStore(private var safe: Boolean = false) : DiagnosticsStore {
    val stored = LinkedHashMap<String, String>()

    override fun read(): List<FailureRecord> = stored.mapNotNull { (id, value) -> FailureRecord.decode(id, value) }
    override fun write(record: FailureRecord) { stored[record.featureId] = record.encode() }
    override fun clear(featureId: String) { stored.remove(featureId) }
    override fun safeMode() = safe
    override fun setSafeMode(enabled: Boolean) { safe = enabled }
}
