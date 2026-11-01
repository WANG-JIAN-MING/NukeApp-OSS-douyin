package io.github.nuke.oss.backend.xposed82

import io.github.nuke.oss.hook.api.BackendBinding
import io.github.nuke.oss.hook.api.CallOutcome
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Emulates callback transport only. It never claims to patch ART or execute XposedBridge.
internal class LegacyProtocolTestDriver : Xposed82Driver {
    val installed = ConcurrentHashMap<Executable, Xposed82Callbacks>()
    val originalCalls = AtomicInteger()
    val attachCount = AtomicInteger()

    override fun hook(target: Executable, callbacks: Xposed82Callbacks): BackendBinding {
        check(installed.putIfAbsent(target, callbacks) == null)
        attachCount.incrementAndGet()
        return object : BackendBinding {
            override fun retire() { installed.remove(target, callbacks) }
        }
    }

    fun invoke(target: Executable, receiver: Any? = null, vararg args: Any?): Any? {
        val call = Invocation(receiver, arrayOf(*args))
        val callback = installed[target]
        callback?.before(call)
        if (call.outcome == null) {
            originalCalls.incrementAndGet()
            call.outcome = try {
                when (target) {
                    is Method -> CallOutcome.Returned(target.invoke(receiver, *call.arguments))
                    // Java cannot initialize an existing instance; the body runs for its outcome only.
                    is Constructor<*> -> CallOutcome.Returned(null).also { target.newInstance(*call.arguments) }
                    else -> error("Unsupported target $target")
                }
            } catch (error: InvocationTargetException) {
                CallOutcome.Thrown(error.targetException)
            }
        }
        callback?.after(call)
        return when (val outcome = requireNotNull(call.outcome)) {
            is CallOutcome.Returned -> outcome.value
            is CallOutcome.Thrown -> throw outcome.error
        }
    }

    class Invocation(override val receiver: Any?, override var arguments: Array<Any?>) : Xposed82Invocation {
        override var outcome: CallOutcome? = null
        private val state = HashMap<String, Any>()
        override fun putState(key: String, state: Any?) {
            if (state == null) this.state.remove(key) else this.state[key] = state
        }
        override fun state(key: String): Any? = state[key]
        val stateCount: Int get() = state.size
    }
}
