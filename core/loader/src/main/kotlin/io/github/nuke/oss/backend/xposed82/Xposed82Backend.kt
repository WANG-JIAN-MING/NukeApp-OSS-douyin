package io.github.nuke.oss.backend.xposed82

import io.github.nuke.oss.hook.api.*
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.UUID

class Xposed82Backend(private val driver: Xposed82Driver) : HookBackend {
    override val id = "xposed82"

    override fun support(target: Executable, capability: HookCapability): CapabilitySupport {
        if (target is Constructor<*>) return CapabilitySupport(
            if (capability == HookCapability.CONSTRUCTOR_OBSERVE_AFTER) SupportLevel.AVAILABLE else SupportLevel.UNAVAILABLE,
            "Constructors are observed only after the original completes",
        )
        if (target !is Method || Modifier.isAbstract(target.modifiers) || Modifier.isNative(target.modifiers)) {
            return CapabilitySupport(SupportLevel.UNVERIFIED, "Only concrete Java methods are enabled in P1")
        }
        return CapabilitySupport(
            if (capability in setOf(
                    HookCapability.METHOD_BEFORE_AFTER, HookCapability.ARGUMENT_REWRITE,
                    HookCapability.EARLY_RETURN, HookCapability.OUTCOME_REWRITE,
                )) SupportLevel.AVAILABLE else SupportLevel.UNAVAILABLE,
            "P1 supports the portable method callback contract",
        )
    }

    override fun attach(target: Executable, callbacks: BackendCallbacks): BackendBinding {
        val baseline = if (target is Constructor<*>) HookCapability.CONSTRUCTOR_OBSERVE_AFTER else HookCapability.METHOD_BEFORE_AFTER
        require(support(target, baseline).level == SupportLevel.AVAILABLE)
        val stateKey = "io.github.nuke.oss.xp82.${UUID.randomUUID()}"
        return driver.hook(target, object : Xposed82Callbacks {
            override fun before(call: Xposed82Invocation) {
                val frame = BackendFrame(target, call.receiver, call.arguments)
                val token = callbacks.enter(frame)
                call.putState(stateKey, ActiveCall(frame, token))
                call.arguments = frame.arguments
                (frame.state as? CallState.Completed)?.let { call.outcome = it.outcome }
            }

            override fun after(call: Xposed82Invocation) {
                val active = call.state(stateKey) as? ActiveCall ?: return
                call.putState(stateKey, null)
                active.frame.receiver = call.receiver
                active.frame.state = CallState.Completed(requireNotNull(call.outcome) { "Legacy call has no outcome" })
                callbacks.exit(active.frame, active.token)
                call.outcome = (active.frame.state as CallState.Completed).outcome
            }
        })
    }

    private data class ActiveCall(val frame: BackendFrame, val token: InvocationToken)

    companion object {
        fun forFramework(): Xposed82Backend = Xposed82Backend(FrameworkXposed82Driver())
    }
}
