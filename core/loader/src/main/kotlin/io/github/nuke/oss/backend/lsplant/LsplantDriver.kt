package io.github.nuke.oss.backend.lsplant

import java.lang.reflect.Executable

interface LsplantDriver {
    val isReady: Boolean

    // Publish the backup before invoking callbacks. The driver owns its native callback reference. A constructor target's
    // callback receives the instance being initialised as its receiver.
    fun install(target: Executable, callback: (receiver: Any?, arguments: Array<Any?>) -> Any?): LsplantBinding
}

interface LsplantBinding {
    // Calls the LSPlant backup directly, never the hooked target or a framework continuation chain.
    // Reflection drivers unwrap exactly the InvocationTargetException introduced by Method.invoke. A constructor backup
    // runs on the receiver it is given and returns null.
    fun invokeOriginal(receiver: Any?, arguments: Array<Any?>): Any?
    // JNI uses a process-lifetime backup tombstone; physical unhook is not a portable guarantee.
    fun unhook()
}
