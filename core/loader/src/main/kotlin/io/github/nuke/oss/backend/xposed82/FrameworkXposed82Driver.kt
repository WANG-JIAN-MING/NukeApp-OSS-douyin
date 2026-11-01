package io.github.nuke.oss.backend.xposed82

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.nuke.oss.hook.api.BackendBinding
import io.github.nuke.oss.hook.api.CallOutcome
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicBoolean

internal class FrameworkXposed82Driver : Xposed82Driver {
    override fun hook(
        target: Executable,
        callbacks: Xposed82Callbacks,
    ): BackendBinding {
        val handle = XposedBridge.hookMethod(target, MethodHook(callbacks))
        return object : BackendBinding {
            private val retired = AtomicBoolean()

            override fun retire() {
                if (retired.compareAndSet(false, true)) handle.unhook()
            }
        }
    }

    // A named subclass rather than an anonymous one: R8 cannot see the compileOnly XC_MethodHook, so it emits the
    // anonymous class without the two overrides and the legacy bridge then calls its no-op base methods instead.
    internal class MethodHook(
        private val callbacks: Xposed82Callbacks,
    ) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) = callbacks.before(Invocation(param))

        override fun afterHookedMethod(param: MethodHookParam) = callbacks.after(Invocation(param))
    }

    private class Invocation(
        private val param: XC_MethodHook.MethodHookParam,
    ) : Xposed82Invocation {
        override val receiver: Any? get() = param.thisObject
        override var arguments: Array<Any?>
            get() = param.args
            set(value) {
                param.args = value
            }
        override var outcome: CallOutcome?
            get() =
                if (param.hasThrowable()) {
                    CallOutcome.Thrown(param.throwable)
                } else {
                    CallOutcome.Returned(param.result)
                }
            set(value) {
                when (value) {
                    is CallOutcome.Returned -> param.result = value.value
                    is CallOutcome.Thrown -> param.throwable = value.error
                    null -> error("Cannot reset a completed framework call to pending")
                }
            }

        override fun putState(
            key: String,
            state: Any?,
        ) {
            param.setObjectExtra(key, state)
        }

        override fun state(key: String): Any? = param.getObjectExtra(key)
    }
}
