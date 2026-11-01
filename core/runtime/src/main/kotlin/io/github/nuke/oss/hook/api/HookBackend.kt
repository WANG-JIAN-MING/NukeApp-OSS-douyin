package io.github.nuke.oss.hook.api

import java.lang.reflect.Executable

// Adapter-only mutable frame; no framework object is exposed by this type.
class BackendFrame(
    val target: Executable,
    var receiver: Any?,
    arguments: Array<Any?>,
) {
    var arguments: Array<Any?> = arguments.copyOf()
    var state: CallState = CallState.Pending
}

interface InvocationToken

interface BackendCallbacks {
    fun enter(frame: BackendFrame): InvocationToken
    fun exit(frame: BackendFrame, token: InvocationToken)
}

interface BackendBinding {
    // Only call during backend shutdown. Logical subscriptions are managed separately.
    fun retire()
}

interface HookBackend {
    val id: String
    fun support(target: Executable, capability: HookCapability): CapabilitySupport
    fun attach(target: Executable, callbacks: BackendCallbacks): BackendBinding
}

class BackendUnavailableException(val backendId: String, reason: String) :
    UnsupportedOperationException("$backendId: $reason")

// Reserved backends fail explicitly; they never simulate successful installation.
abstract class ReservedHookBackend(final override val id: String, private val reason: String) : HookBackend {
    final override fun support(target: Executable, capability: HookCapability) =
        CapabilitySupport(SupportLevel.UNAVAILABLE, reason)

    final override fun attach(target: Executable, callbacks: BackendCallbacks): BackendBinding =
        throw BackendUnavailableException(id, reason)
}
