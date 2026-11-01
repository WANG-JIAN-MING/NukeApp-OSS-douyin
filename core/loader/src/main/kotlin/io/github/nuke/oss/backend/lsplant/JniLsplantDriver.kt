package io.github.nuke.oss.backend.lsplant

import io.github.nuke.oss.hook.api.BackendUnavailableException
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// Zygisk registers these methods against this APK's class loader before bootstrap.
object JniLsplantDriver : LsplantDriver {
    @Volatile private var initialized = false
    private val registrations = HashMap<Executable, NativeCallback>()
    override val isReady: Boolean get() = initialized

    @Synchronized fun initialize(): Boolean {
        if (initialized) return true
        initialized = try {
            nativeInitialize()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        return initialized
    }

    @Synchronized override fun install(target: Executable, callback: (Any?, Array<Any?>) -> Any?): LsplantBinding {
        if (!isReady) throw BackendUnavailableException("lsplant", "LSPlant native initialization is required")
        check(target !in registrations) { "LSPlant target was already installed; restart the process before reinstalling" }
        val bridge = NativeCallback(Modifier.isStatic(target.modifiers), target.parameterCount, callback = callback)
        val callbackMethod = NativeCallback::class.java.getDeclaredMethod("invoke", Array<Any?>::class.java)
        synchronized(bridge) {
            val method = nativeHook(target, bridge, callbackMethod)
                ?: throw BackendUnavailableException("lsplant", "Native hook installation failed: ${target.name}")
            // Keep native callbacks reachable for the process, including a retired tombstone.
            registrations[target] = bridge
            bridge.publishBackup(method)
        }
        return object : LsplantBinding {
            override fun invokeOriginal(receiver: Any?, arguments: Array<Any?>): Any? = bridge.invokeOriginal(receiver, arguments)

            override fun unhook() = bridge.retire()
        }
    }

    class NativeCallback internal constructor(
        private val isStatic: Boolean,
        private val parameterCount: Int,
        // Reflection cannot run a constructor on an existing instance, so a constructor backup goes through JNI.
        private val runConstructor: (Constructor<*>, Any?, Array<Any?>) -> Unit = ::invokeConstructorBackup,
        callback: (Any?, Array<Any?>) -> Any?,
    ) {
        private var callback: ((Any?, Array<Any?>) -> Any?)? = callback
        private var backup: Executable? = null

        @Synchronized internal fun publishBackup(executable: Executable) {
            executable.isAccessible = true
            backup = executable
        }

        @Synchronized internal fun retire() {
            check(backup != null) { "LSPlant backup has not been published" }
            // ART may already be in the generated trampoline before Java activeCalls increments.
            // Leave the hook installed and route late calls to its retained backup until process exit.
            callback = null
        }

        internal fun invokeOriginal(receiver: Any?, arguments: Array<Any?>): Any? =
            when (val executable = synchronized(this) { checkNotNull(backup) { "LSPlant backup has not been published" } }) {
                is Constructor<*> -> null.also { runConstructor(executable, receiver, arguments) }
                is Method -> try {
                    executable.invoke(receiver, *arguments)
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
                else -> error("Unsupported LSPlant backup ${executable.javaClass.name}")
            }

        fun invoke(arguments: Array<Any?>): Any? {
            val dispatch = synchronized(this) {
                check(backup != null) { "LSPlant backup has not been published" }
                callback
            }
            val offset = if (isStatic) 0 else 1
            require(arguments.size == parameterCount + offset) { "Unexpected LSPlant callback argument count" }
            val receiver = if (isStatic) null else arguments[0]
            val copied = arguments.copyOfRange(offset, arguments.size)
            return if (dispatch != null) dispatch(receiver, copied) else invokeOriginal(receiver, copied)
        }
    }

    // One JVM type letter per parameter, as nativeInvokeConstructor reads them.
    internal fun parameterKinds(executable: Executable): CharArray = CharArray(executable.parameterCount) { index ->
        when (executable.parameterTypes[index]) {
            Boolean::class.javaPrimitiveType -> 'Z'
            Byte::class.javaPrimitiveType -> 'B'
            Char::class.javaPrimitiveType -> 'C'
            Short::class.javaPrimitiveType -> 'S'
            Int::class.javaPrimitiveType -> 'I'
            Long::class.javaPrimitiveType -> 'J'
            Float::class.javaPrimitiveType -> 'F'
            Double::class.javaPrimitiveType -> 'D'
            else -> 'L'
        }
    }

    private fun invokeConstructorBackup(backup: Constructor<*>, receiver: Any?, arguments: Array<Any?>) {
        requireNotNull(receiver) { "A constructor backup needs the instance being initialised" }
        nativeInvokeConstructor(backup, backup.declaringClass, receiver, arguments, parameterKinds(backup))
    }

    @JvmStatic external fun nativeInitialize(): Boolean
    @JvmStatic external fun nativeHook(target: Executable, callback: Any, callbackMethod: Method): Executable?
    // Reserved low-level binding; normal shutdown uses tombstones and never physically unhooks ART.
    @JvmStatic external fun nativeUnhook(target: Executable): Boolean
    // Runs a constructor backup on an instance that is already allocated; the constructor's own exception propagates.
    @JvmStatic external fun nativeInvokeConstructor(backup: Executable, type: Class<*>, receiver: Any, arguments: Array<Any?>, kinds: CharArray)
}
