package io.github.nuke.oss.app.shared.diagnostics

import io.github.nuke.oss.app.shared.dex.DexQueryUnavailable
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FailurePhase
import io.github.nuke.oss.script.ScriptException
import io.github.nuke.oss.runtime.FeatureFailureSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// The last failure recorded for one feature, the way NukeApp's HookerDebugRecord keeps it. A feature with a record is
// locked: it is not installed at the next host start until the record is cleared on the debug page. Records stay in
// the host's private storage and are only shown there; they are never logged or sent anywhere.
internal data class FailureRecord(
    val featureId: String,
    val phase: String,
    val errorClass: String,
    val message: String?,
    val stackTrace: String,
    val timestamp: Long,
) {
    // One stored string per record: the fields joined by NUL, which is removed from the text first.
    fun encode(): String = listOf(phase, errorClass, message ?: NO_MESSAGE, stackTrace, timestamp.toString())
        .joinToString(SEPARATOR) { it.replace(SEPARATOR, "") }

    companion object {
        private const val SEPARATOR = "\u0000"
        private const val NO_MESSAGE = "\u0001"

        // A stored value that does not read back is dropped rather than shown half parsed.
        fun decode(featureId: String, stored: String): FailureRecord? {
            val fields = stored.split(SEPARATOR)
            if (fields.size != 5) return null
            val time = fields[4].toLongOrNull() ?: return null
            return FailureRecord(featureId, fields[0], fields[1], fields[2].takeIf { it != NO_MESSAGE }, fields[3], time)
        }
    }
}

internal object FailurePhases {
    const val INSTALL = "install"
    const val DEX_FIND = "dex_find"
    const val UNINSTALL = "uninstall"
    const val BEFORE_CALLBACK = "before_callback"
    const val AFTER_CALLBACK = "after_callback"
    const val SCRIPT = "script"

    // A missing Dex query fails the install, but it is the host build that lacks it, so it is named apart; so is a
    // script that stopped while running, which the runtime reports against its feature's install.
    fun of(phase: FailurePhase, error: Throwable): String = when (phase) {
        FailurePhase.INSTALL -> when {
            error.causes().any { it is DexQueryUnavailable } -> DEX_FIND
            error.causes().any { it is ScriptException } -> SCRIPT
            else -> INSTALL
        }
        FailurePhase.UNINSTALL -> UNINSTALL
        FailurePhase.BEFORE_CALLBACK -> BEFORE_CALLBACK
        FailurePhase.AFTER_CALLBACK -> AFTER_CALLBACK
    }

    private fun Throwable.causes() = generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }.take(8)
}

// Where failure records live. The host adapter backs it with the host's private storage.
internal interface FailureStore {
    fun read(): List<FailureRecord>
    fun write(record: FailureRecord)
    fun clear(featureId: String)
}

// One daemon thread that writes records off the failing thread. It starts on the first failure and lives with the
// process, like the runtime it serves.
internal fun diagnosticsWorker(): Executor =
    Executors.newSingleThreadExecutor { task -> Thread(task, "nuke-diagnostics").apply { isDaemon = true } }

// The host's private storage for diagnostics: the failure records, and safe mode, under which no feature is installed
// at start except the settings entry and the init, as NukeApp's security mode keeps only its core hooks.
internal interface DiagnosticsStore : FailureStore {
    fun safeMode(): Boolean
    fun setSafeMode(enabled: Boolean)
}

// Turns feature failures into records. The failing thread, which may be a Hook callback, only queues the work, so a
// callback never touches storage. The first failure of a feature in a process is kept and later ones dropped, so a
// callback failing on every frame cannot flood the store. A feature that may be locked and fails while running is also
// switched off at once, as NukeApp stops a locked hooker's callbacks; the settings entry and the init are exempt, so
// the screen that clears records can never lock itself out.
internal class FailureRecorder(
    private val store: FailureStore,
    private val exempt: Set<String>,
    private val disable: (featureId: String) -> Unit,
    private val logger: Logger,
    private val worker: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
) : FeatureFailureSink {
    private val reported = ConcurrentHashMap.newKeySet<String>()

    override fun report(featureId: String, phase: FailurePhase, error: Throwable) {
        if (!reported.add(featureId)) return
        val record = FailureRecord(
            featureId,
            FailurePhases.of(phase, error),
            error.javaClass.name,
            error.message?.take(MAX_MESSAGE),
            error.stackTraceToString().take(MAX_TRACE),
            clock(),
        )
        val running = phase == FailurePhase.BEFORE_CALLBACK || phase == FailurePhase.AFTER_CALLBACK
        worker.execute {
            try {
                store.write(record)
            } catch (storeError: RuntimeException) {
                logger.log("feature=$featureId diagnostics_write_failed ${storeError.javaClass.name}")
            }
            if (running && lockable(featureId)) {
                try {
                    disable(featureId)
                    logger.log("feature=$featureId locked phase=${record.phase}")
                } catch (disableError: RuntimeException) {
                    logger.log("feature=$featureId lock_failed $disableError")
                }
            }
        }
    }

    fun lockable(featureId: String): Boolean = featureId !in exempt

    // Read once while the host registers its features, before any of them is installed.
    fun locked(): Set<String> = store.read().map { it.featureId }.filter(::lockable).toSet()

    private companion object {
        const val MAX_MESSAGE = 2_000
        const val MAX_TRACE = 16_000
    }
}
