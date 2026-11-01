package io.github.nuke.oss.script

// One built-in ES module, such as "nuke:log". Its source runs inside the script's runtime and reaches Kotlin only
// through the native functions bind registers; module sources use globalThis.__nuke for error checks and events.
interface ScriptModule {
    val name: String
    val source: String
    fun bind(context: ScriptModuleContext)
}

// What a module sees of the session it is bound into. Native functions return a value or throw; a ScriptException
// keeps its code in the NukeError the script catches, any other throwable becomes HOST_ERROR.
interface ScriptModuleContext {
    val script: ScriptDefinition

    // Registered as globalThis.__nk_<name>, called on the script thread; it must not block for long because the
    // whole script waits for it.
    fun function(name: String, block: (List<Any?>) -> Any?)

    // Runs on the script thread as a coroutine and settles a Promise; blocking work belongs on another dispatcher.
    fun asyncFunction(name: String, block: suspend (List<Any?>) -> Any?)

    // Delivers payload to the listeners the script added with __nuke.on(type, ...). Safe from any thread; returns
    // false when the session is stopped or its event queue is full.
    fun emit(type: String, payload: Any?): Boolean

    // Closed when the session stops, newest first; a resource handed over after that is closed at once.
    fun <T : AutoCloseable> own(resource: T): T

    fun log(level: ScriptLogLevel, message: String)

    // Decides how host objects reach the script and how the script's references come back; the Java module installs
    // it, at most once per session.
    fun setValueAdapter(adapter: ScriptValueAdapter)

    // Asks for a garbage collection between tasks, which lets released handle wrappers be finalized.
    fun requestGc()

    // Runs a handler the script registered with __nuke.handle; see ScriptSession.call and notify.
    fun call(handler: Long, payload: Any?, timeoutMillis: Long): ScriptCallResult
    fun notify(handler: Long, payload: Any?): Boolean

    val isScriptThread: Boolean

    fun requirePermission(name: String) {
        if (!script.permissions.has(name)) throw ScriptException.permission(name)
    }
}

// Marks a value as a host object, so it reaches the script through the value adapter even when it is a collection the
// session would otherwise convert to a JavaScript array or object.
class JavaValue(val value: Any?)

interface ScriptValueAdapter {
    // Called on the script thread for each host object handed to the script.
    fun export(value: Any): Any?

    // Called on the script thread for a value the script returned; replaces its object references with host objects.
    fun resolve(value: Any?): Any?
}

fun List<Any?>.stringAt(index: Int, name: String): String =
    getOrNull(index) as? String ?: throw ScriptException.argument("$name must be a string")

fun List<Any?>.optionalStringAt(index: Int, name: String): String? = when (val value = getOrNull(index)) {
    null, Unit -> null
    is String -> value
    else -> throw ScriptException.argument("$name must be a string")
}

fun List<Any?>.longAt(index: Int, name: String): Long = when (val value = getOrNull(index)) {
    is Int -> value.toLong()
    is Long -> value
    is Double -> if (value % 1.0 == 0.0 && value in -9.007199254740991E15..9.007199254740991E15) value.toLong()
        else throw ScriptException.argument("$name must be a safe integer")
    else -> throw ScriptException.argument("$name must be a number")
}

fun List<Any?>.booleanAt(index: Int, name: String): Boolean =
    getOrNull(index) as? Boolean ?: throw ScriptException.argument("$name must be a boolean")

// Accepts a Uint8Array, an Int8Array or an array of byte values, and a string as UTF-8 when text is allowed.
@OptIn(ExperimentalUnsignedTypes::class)
fun List<Any?>.bytesAt(index: Int, name: String, text: Boolean = true): ByteArray =
    toBytes(getOrNull(index), text) ?: throw ScriptException.argument(
        if (text) "$name must be a string or a byte array" else "$name must be a byte array"
    )

fun List<Any?>.optionalBytesAt(index: Int, name: String, text: Boolean = true): ByteArray? = when (getOrNull(index)) {
    null, Unit -> null
    else -> bytesAt(index, name, text)
}

@OptIn(ExperimentalUnsignedTypes::class)
private fun toBytes(value: Any?, text: Boolean): ByteArray? = when (value) {
    is ByteArray -> value
    is UByteArray -> value.asByteArray()
    is String -> if (text) value.toByteArray() else null
    is List<*> -> ByteArray(value.size) { index ->
        val item = value[index] as? Number ?: return null
        val byte = item.toLong()
        if (byte !in -128..255) return null
        byte.toByte()
    }
    else -> null
}
