package io.github.nuke.oss.runtime

import io.github.nuke.oss.common.closeAll
import io.github.nuke.oss.core.*
import io.github.nuke.oss.hook.api.HookOwner
import io.github.nuke.oss.hook.api.HookService

interface RuntimeFeature {
    val id: String
    fun install(scope: FeatureScope)
}

// Where a feature failed: installing or closing its scope, or one of its Hook callbacks.
enum class FailurePhase { INSTALL, UNINSTALL, BEFORE_CALLBACK, AFTER_CALLBACK }

// Receives every failure of a registered feature with the error itself, for the host's diagnostics. Called on the
// thread that failed, including Hook callback threads, so it must return quickly and must not throw.
fun interface FeatureFailureSink {
    fun report(featureId: String, phase: FailurePhase, error: Throwable)
}

class FeatureManager(
    private val generationId: String,
    private val service: HookService,
    private val logger: Logger,
) : AutoCloseable {
    private data class Entry(
        val feature: RuntimeFeature,
        var status: FeatureStatus,
        var scope: FeatureScope? = null,
    )
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var closed = false

    // The host adapter installs where failures go while it registers its features; until then they are only logged.
    @Volatile var failureSink: FeatureFailureSink = FeatureFailureSink { _, _, _ -> }

    // A sink that fails is logged and skipped, so diagnostics can never break a feature or a host call.
    fun reportFailure(featureId: String, phase: FailurePhase, error: Throwable) {
        try {
            failureSink.report(featureId, phase, error)
        } catch (sinkError: Throwable) {
            if (sinkError is VirtualMachineError) throw sinkError
            logger.log("feature=$featureId failure_sink_failed $sinkError")
        }
    }

    fun register(feature: RuntimeFeature) = synchronized(lock) {
        check(!closed)
        require(feature.id.isNotBlank() && feature.id !in entries) { "Duplicate or blank feature ID" }
        entries[feature.id] = Entry(feature, FeatureStatus(feature.id, FeatureState.DISABLED))
    }

    @Suppress("DEPRECATION")
    fun setEnabled(id: String, enabled: Boolean): FeatureStatus = synchronized(lock) {
        check(!closed) { "Feature manager is closed" }
        val entry = entries[id] ?: error("Unknown feature: $id")
        check(entry.status.state != FeatureState.INSTALLING) { "Feature is already installing: $id" }
        if (!enabled) {
            val scope = entry.scope
            entry.scope = null
            try {
                scope?.close()
                entry.status = FeatureStatus(id, FeatureState.DISABLED)
            } catch (error: Throwable) {
                entry.status = FeatureStatus(id, FeatureState.FAILED, error.toString())
                reportFailure(id, FailurePhase.UNINSTALL, error)
                throw error
            }
        } else if (entry.scope == null) {
            val scope = FeatureScope(HookOwner(id, generationId), service)
            entry.status = FeatureStatus(id, FeatureState.INSTALLING)
            try {
                entry.feature.install(scope)
                check(!closed) { "Feature manager closed during installation" }
                entry.scope = scope
                entry.status = FeatureStatus(id, FeatureState.ACTIVE)
            } catch (error: Throwable) {
                try { scope.close() } catch (cleanup: Throwable) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                }
                entry.status = FeatureStatus(id, if (closed) FeatureState.CLOSED else FeatureState.FAILED, error.toString())
                if (!closed) reportFailure(id, FailurePhase.INSTALL, error)
                if (error is VirtualMachineError || error is ThreadDeath || error is LinkageError) throw error
            }
        }
        logger.log("feature=$id state=${entry.status.state} ${entry.status.reason.orEmpty()}")
        entry.status
    }

    fun snapshot(): List<FeatureStatus> = synchronized(lock) { entries.values.map { it.status } }

    override fun close() {
        val scopes = synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.mapNotNull { entry ->
                entry.scope.also {
                    entry.scope = null
                    entry.status = FeatureStatus(entry.feature.id, FeatureState.CLOSED)
                }
            }
        }
        scopes.asReversed().closeAll()
    }
}
