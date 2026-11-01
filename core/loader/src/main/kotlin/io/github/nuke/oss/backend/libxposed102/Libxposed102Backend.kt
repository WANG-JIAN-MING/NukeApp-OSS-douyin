package io.github.nuke.oss.backend.libxposed102

import io.github.libxposed.api.XposedInterface
import io.github.nuke.oss.hook.api.*
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// Maps the real API 102 Chain to the existing portable dispatcher. No legacy API fallback.
class Libxposed102Backend(private val framework: XposedInterface) : HookBackend {
    override val id = "libxposed102"

    init {
        require(framework.apiVersion >= 102) { "libxposed API 102 or newer is required" }
    }

    override fun support(target: Executable, capability: HookCapability): CapabilitySupport {
        if (target is Constructor<*>) return CapabilitySupport(
            if (capability == HookCapability.CONSTRUCTOR_OBSERVE_AFTER) SupportLevel.AVAILABLE else SupportLevel.UNAVAILABLE,
            "Constructors are observed only after the chain completes",
        )
        if (target !is Method || Modifier.isAbstract(target.modifiers) || Modifier.isNative(target.modifiers)) {
            return CapabilitySupport(SupportLevel.UNVERIFIED, "Only concrete Java methods are enabled")
        }
        return CapabilitySupport(
            if (capability in setOf(
                    HookCapability.METHOD_BEFORE_AFTER, HookCapability.ARGUMENT_REWRITE,
                    HookCapability.EARLY_RETURN, HookCapability.OUTCOME_REWRITE,
                )) SupportLevel.AVAILABLE else SupportLevel.UNAVAILABLE,
            "Portable method callbacks; advanced API 102 operations are not exposed",
        )
    }

    override fun attach(target: Executable, callbacks: BackendCallbacks): BackendBinding {
        val baseline = if (target is Constructor<*>) HookCapability.CONSTRUCTOR_OBSERVE_AFTER else HookCapability.METHOD_BEFORE_AFTER
        require(support(target, baseline).level == SupportLevel.AVAILABLE)
        val handle = framework.hook(target)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            // The dispatcher protects bad callbacks. Deliberate target exceptions must propagate.
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept(Hooker(target, callbacks))
        return object : BackendBinding {
            private var retired = false
            @Synchronized override fun retire() {
                if (!retired) {
                    handle.unhook()
                    retired = true
                }
            }
        }
    }

    // A named implementation rather than a lambda: R8 cannot see the compileOnly framework API, so it emits the
    // synthetic lambda class without intercept() and the framework fails with AbstractMethodError on dispatch.
    internal class Hooker(
        private val target: Executable,
        private val callbacks: BackendCallbacks,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            check(chain.executable == target) { "Framework delivered another executable" }
            val frame = BackendFrame(target, chain.thisObject, chain.args.toTypedArray())
            val token = callbacks.enter(frame)
            if (frame.state == CallState.Pending) {
                frame.state = CallState.Completed(try {
                    // A constructor chain yields no value; its receiver is the instance being initialized.
                    val value = chain.proceed(frame.arguments)
                    CallOutcome.Returned(if (target is Constructor<*>) null else value)
                } catch (error: Throwable) {
                    CallOutcome.Thrown(error)
                })
            }
            callbacks.exit(frame, token)
            return when (val outcome = (frame.state as CallState.Completed).outcome) {
                is CallOutcome.Returned -> outcome.value
                is CallOutcome.Thrown -> throw outcome.error
            }
        }
    }
}
