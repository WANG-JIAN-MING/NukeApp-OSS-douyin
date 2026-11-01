package io.github.nuke.oss.hook.api

import java.lang.reflect.Executable

enum class HookCapability {
    METHOD_BEFORE_AFTER, ARGUMENT_REWRITE, EARLY_RETURN, OUTCOME_REWRITE,
    CONSTRUCTOR_OBSERVE_AFTER, BACKEND_ORIGINAL_INVOKE, DEOPTIMIZE,
    NATIVE_AROUND_CHAIN, ATOMIC_HOOK_REPLACE,
}

enum class SupportLevel { AVAILABLE, UNAVAILABLE, UNVERIFIED }

data class CapabilitySupport(val level: SupportLevel, val reason: String? = null)

sealed interface CallOutcome {
    data class Returned(val value: Any?) : CallOutcome
    data class Thrown(val error: Throwable) : CallOutcome
}

sealed interface CallState {
    data object Pending : CallState
    data class Completed(val outcome: CallOutcome) : CallState
}

data class HookOwner(val featureId: String, val generationId: String) {
    init {
        require(featureId.isNotBlank())
        require(generationId.isNotBlank())
    }
}

interface HookHandle : AutoCloseable {
    val id: String
    val owner: HookOwner
    override fun close()
}

interface BeforeCall {
    val target: Executable
    val receiver: Any?
    val argumentCount: Int
    fun argument(index: Int): Any?
    fun setArgument(index: Int, value: Any?)
    fun returnEarly(value: Any?)
    fun throwEarly(error: Throwable)
}

interface AfterCall {
    val target: Executable
    val receiver: Any?
    val outcome: CallOutcome
    fun argument(index: Int): Any?
    fun returnValue(value: Any?)
    fun throwError(error: Throwable)
}

data class HookSpec(
    val id: String,
    val priority: Int = 0,
    val requiredCapabilities: Set<HookCapability> = emptySet(),
    val before: ((BeforeCall) -> Unit)? = null,
    val after: ((AfterCall) -> Unit)? = null,
) {
    init {
        require(id.isNotBlank())
        require(before != null || after != null) { "A Hook needs at least one callback" }
    }
}

interface HookService {
    fun install(owner: HookOwner, target: Executable, spec: HookSpec): HookHandle
}

data class HookFailure(
    val owner: HookOwner,
    val subscriptionId: String,
    val target: Executable,
    val phase: String,
    val error: Throwable,
)

// Implementations must not throw or retain unbounded host object graphs.
fun interface HookFailureSink {
    fun report(failure: HookFailure)
}
