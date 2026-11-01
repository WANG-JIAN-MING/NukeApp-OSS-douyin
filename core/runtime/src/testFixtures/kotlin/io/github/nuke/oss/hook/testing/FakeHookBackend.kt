package io.github.nuke.oss.hook.testing

import io.github.nuke.oss.hook.api.*
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Explicit test invocation only. This backend does not patch JVM or ART methods.
class FakeHookBackend : HookBackend {
    override val id = "fake-test-only"
    private val callbacks = ConcurrentHashMap<Executable, BackendCallbacks>()
    val attachments = AtomicInteger()
    val originalCalls = AtomicInteger()

    override fun support(target: Executable, capability: HookCapability) = CapabilitySupport(
        if (capability in setOf(
                HookCapability.METHOD_BEFORE_AFTER, HookCapability.ARGUMENT_REWRITE,
                HookCapability.EARLY_RETURN, HookCapability.OUTCOME_REWRITE,
                HookCapability.CONSTRUCTOR_OBSERVE_AFTER,
            )) SupportLevel.AVAILABLE else SupportLevel.UNAVAILABLE,
    )

    override fun attach(target: Executable, callbacks: BackendCallbacks): BackendBinding {
        check(this.callbacks.putIfAbsent(target, callbacks) == null) { "Target already attached" }
        attachments.incrementAndGet()
        return object : BackendBinding {
            override fun retire() { this@FakeHookBackend.callbacks.remove(target, callbacks) }
        }
    }

    fun call(target: Executable, receiver: Any? = null, vararg arguments: Any?): Any? {
        val frame = BackendFrame(target, receiver, arrayOf(*arguments))
        val callback = callbacks[target]
        val token = callback?.enter(frame)
        if (frame.state == CallState.Pending) {
            originalCalls.incrementAndGet()
            frame.state = CallState.Completed(try {
                target.trySetAccessible()
                when (target) {
                    is Method -> CallOutcome.Returned(target.invoke(receiver, *frame.arguments))
                    is Constructor<*> -> {
                        frame.receiver = target.newInstance(*frame.arguments)
                        CallOutcome.Returned(null)
                    }
                }
            } catch (error: Throwable) {
                CallOutcome.Thrown(if (error is InvocationTargetException) error.targetException else error)
            })
        }
        if (callback != null && token != null) callback.exit(frame, token)
        return when (val outcome = (frame.state as CallState.Completed).outcome) {
            is CallOutcome.Returned -> outcome.value
            is CallOutcome.Thrown -> throw outcome.error
        }
    }
}
