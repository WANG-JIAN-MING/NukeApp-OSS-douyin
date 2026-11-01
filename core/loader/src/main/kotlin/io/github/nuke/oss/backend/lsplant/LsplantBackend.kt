package io.github.nuke.oss.backend.lsplant

import io.github.nuke.oss.hook.api.*
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class LsplantBackend(private val driver: LsplantDriver = JniLsplantDriver) : HookBackend {
    override val id = "lsplant"

    override fun support(target: Executable, capability: HookCapability): CapabilitySupport {
        if (!driver.isReady) return CapabilitySupport(SupportLevel.UNAVAILABLE, "LSPlant native initialization is required")
        // The backup runs on the already allocated instance through JNI; this has contract tests but no device run yet.
        if (target is Constructor<*>) return CapabilitySupport(
            if (capability == HookCapability.CONSTRUCTOR_OBSERVE_AFTER) SupportLevel.AVAILABLE else SupportLevel.UNAVAILABLE,
            "Constructors are observed only after the backup completes",
        )
        if (target !is Method || Modifier.isAbstract(target.modifiers) || Modifier.isNative(target.modifiers)) {
            return CapabilitySupport(SupportLevel.UNVERIFIED, "Only concrete Java methods are enabled")
        }
        return CapabilitySupport(
            if (capability in PORTABLE_CAPABILITIES) SupportLevel.AVAILABLE else SupportLevel.UNAVAILABLE,
            "Portable method callbacks; advanced native operations are not exposed",
        )
    }

    override fun attach(target: Executable, callbacks: BackendCallbacks): BackendBinding {
        if (!driver.isReady) throw BackendUnavailableException(id, "LSPlant native initialization is required")
        val baseline = if (target is Constructor<*>) HookCapability.CONSTRUCTOR_OBSERVE_AFTER else HookCapability.METHOD_BEFORE_AFTER
        require(support(target, baseline).level == SupportLevel.AVAILABLE)
        val method = target
        val publicationLock = Any()
        lateinit var nativeBinding: LsplantBinding
        var activeCalls = 0
        var retirementRequested = false
        var retired = false
        fun retireIfIdle() {
            if (retirementRequested && activeCalls == 0 && !retired) {
                nativeBinding.unhook()
                retired = true
            }
        }
        // Native hooks can become visible on another thread before install returns its backup.
        synchronized(publicationLock) {
            nativeBinding = driver.install(method) { receiver, arguments ->
                val installed = synchronized(publicationLock) {
                    activeCalls++
                    nativeBinding
                }
                try {
                    val frame = BackendFrame(method, receiver, arguments)
                    val token = callbacks.enter(frame)
                    if (frame.state == CallState.Pending) {
                        frame.state = CallState.Completed(try {
                            // A constructor yields no value; its receiver is the instance being initialised.
                            val value = installed.invokeOriginal(frame.receiver, frame.arguments)
                            CallOutcome.Returned(if (method is Constructor<*>) null else value)
                        } catch (error: Throwable) {
                            CallOutcome.Thrown(error)
                        })
                    }
                    callbacks.exit(frame, token)
                    when (val outcome = (frame.state as CallState.Completed).outcome) {
                        is CallOutcome.Returned -> outcome.value
                        is CallOutcome.Thrown -> throw outcome.error
                    }
                } finally {
                    synchronized(publicationLock) {
                        activeCalls--
                        retireIfIdle()
                    }
                }
            }
        }
        return object : BackendBinding {
            override fun retire() {
                synchronized(publicationLock) {
                    // Closing from before/after must not invalidate a backup used by this call.
                    retirementRequested = true
                    retireIfIdle()
                }
            }
        }
    }

    private companion object {
        val PORTABLE_CAPABILITIES = setOf(
            HookCapability.METHOD_BEFORE_AFTER, HookCapability.ARGUMENT_REWRITE,
            HookCapability.EARLY_RETURN, HookCapability.OUTCOME_REWRITE,
        )
    }
}
