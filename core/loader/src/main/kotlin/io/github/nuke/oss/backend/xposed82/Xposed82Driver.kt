package io.github.nuke.oss.backend.xposed82

import io.github.nuke.oss.hook.api.BackendBinding
import io.github.nuke.oss.hook.api.CallOutcome
import java.lang.reflect.Executable

// Test seam for the legacy callback protocol. Framework types stay in FrameworkXposed82Driver.
interface Xposed82Driver {
    fun hook(target: Executable, callbacks: Xposed82Callbacks): BackendBinding
}

interface Xposed82Callbacks {
    fun before(call: Xposed82Invocation)
    fun after(call: Xposed82Invocation)
}

interface Xposed82Invocation {
    val receiver: Any?
    var arguments: Array<Any?>
    var outcome: CallOutcome?
    fun putState(key: String, state: Any?)
    fun state(key: String): Any?
}
