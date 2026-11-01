package io.github.nuke.oss.backend.libxposed102.testing

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// Real API interfaces, simulated transport only. No JVM or ART method is patched.
class TestModernFramework(private val version: Int = 102) : XposedInterface {
    data class Registration(
        val target: Executable,
        val priority: Int,
        val mode: XposedInterface.ExceptionMode,
        val id: String?,
        val hooker: XposedInterface.Hooker,
    )

    val registrations = ConcurrentHashMap<Executable, Registration>()
    val attaches = AtomicInteger()
    val proceeds = AtomicInteger()
    val unhooks = AtomicInteger()
    val logs = CopyOnWriteArrayList<String>()
    var attachFailure: Throwable? = null
    var downstream: ((Executable, Any?, Array<Any?>) -> Any?)? = null

    override fun getApiVersion() = version
    override fun getFrameworkName() = "test-transport-only"
    override fun getFrameworkVersion() = "102.0.0"
    override fun getFrameworkVersionCode() = 102L
    override fun getFrameworkProperties() = 0L

    override fun hook(origin: Executable): XposedInterface.HookBuilder = object : XposedInterface.HookBuilder {
        private var priority = XposedInterface.PRIORITY_DEFAULT
        private var mode = XposedInterface.ExceptionMode.DEFAULT
        private var id: String? = null
        override fun setPriority(priority: Int): XposedInterface.HookBuilder = apply { this.priority = priority }
        override fun setExceptionMode(mode: XposedInterface.ExceptionMode): XposedInterface.HookBuilder = apply { this.mode = mode }
        override fun setId(id: String?): XposedInterface.HookBuilder = apply { this.id = id }
        override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
            attachFailure?.let { throw it }
            val registration = Registration(origin, priority, mode, id, hooker)
            check(registrations.putIfAbsent(origin, registration) == null) { "Target already attached" }
            attaches.incrementAndGet()
            return object : XposedInterface.HookHandle {
                override fun getExecutable() = origin
                override fun getId(): String? = registration.id
                override fun unhook() {
                    if (registrations.remove(origin, registration)) unhooks.incrementAndGet()
                }
                override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle =
                    error("Atomic replacement is outside the portable contract")
            }
        }
    }

    fun call(target: Executable, receiver: Any? = null, vararg arguments: Any?): Any? {
        val registration = registrations[target] ?: return next(target, receiver, arrayOf(*arguments))
        check(registration.mode == XposedInterface.ExceptionMode.PASSTHROUGH) {
            "This simulator validates the dispatcher's PASSTHROUGH adapter, not protective fallback"
        }
        val chain = TestChain(target, receiver, arrayOf(*arguments))
        return try { registration.hooker.intercept(chain) } finally { chain.valid = false }
    }

    private fun next(target: Executable, receiver: Any?, arguments: Array<Any?>): Any? {
        proceeds.incrementAndGet()
        downstream?.let { return it(target, receiver, arguments) }
        return try {
            when (target) {
                is Method -> target.invoke(receiver, *arguments)
                // Java cannot initialize an existing instance; the body runs for its outcome only.
                is Constructor<*> -> null.also { target.newInstance(*arguments) }
                else -> error("Unsupported target $target")
            }
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private inner class TestChain(
        private val target: Executable,
        private val receiver: Any?,
        private val arguments: Array<Any?>,
    ) : XposedInterface.Chain {
        var valid = true
        private val thread = Thread.currentThread()
        private fun checkValid() = check(valid && thread === Thread.currentThread())
        override fun getExecutable(): Executable { checkValid(); return target }
        override fun getThisObject(): Any? { checkValid(); return receiver }
        override fun getArgs(): List<Any?> { checkValid(); return Collections.unmodifiableList(arguments.toList()) }
        override fun getArg(index: Int): Any? { checkValid(); return arguments[index] }
        override fun proceed(): Any? { checkValid(); return next(target, receiver, arguments.copyOf()) }
        override fun proceed(args: Array<Any?>): Any? { checkValid(); return next(target, receiver, args.copyOf()) }
        override fun proceedWith(thisObject: Any): Any? { checkValid(); return next(target, thisObject, arguments.copyOf()) }
        override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? { checkValid(); return next(target, thisObject, args.copyOf()) }
    }

    override fun log(priority: Int, tag: String?, msg: String) { logs += "$tag:$msg" }
    override fun log(priority: Int, tag: String?, msg: String, tr: Throwable?) { logs += "$tag:$msg:$tr" }
    override fun hookClassInitializer(origin: Class<*>): XposedInterface.HookBuilder = error("Unused")
    override fun deoptimize(executable: Executable): Boolean = error("Unused")
    override fun getInvoker(method: Method): XposedInterface.Invoker<*, Method> = error("ORIGIN must not replace proceed")
    override fun <T> getInvoker(constructor: Constructor<T>): XposedInterface.CtorInvoker<T> = error("Unused")
    override fun getModuleApplicationInfo(): ApplicationInfo = error("Unused")
    override fun getRemotePreferences(group: String): SharedPreferences = error("Unused")
    override fun listRemoteFiles(): Array<String> = error("Unused")
    override fun openRemoteFile(name: String): ParcelFileDescriptor = error("Unused")
}
