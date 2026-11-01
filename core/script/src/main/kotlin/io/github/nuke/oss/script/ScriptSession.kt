package io.github.nuke.oss.script

import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.ModuleLoader
import com.dokar.quickjs.ModuleNormalizer
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.JsObject
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import io.github.nuke.oss.common.closeAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Receives what a session reports. Called from the script thread or from the thread that stopped the session, so it
// must return quickly and must not throw.
interface ScriptSessionListener {
    fun log(level: ScriptLogLevel, message: String)

    // A listener, timer or Promise inside the script failed; the session keeps running.
    fun scriptError(where: String, message: String)

    // Called exactly once. failure is null when the session was closed on purpose.
    fun stopped(failure: ScriptException?)
}

sealed interface ScriptCallResult {
    data class Returned(val value: Any?) : ScriptCallResult
    data class Failed(val error: ScriptException) : ScriptCallResult
    // No answer within the caller's time limit; a later answer is dropped.
    data object TimedOut : ScriptCallResult
    // The caller is the script thread itself, which cannot run a nested evaluation while it is inside a binding.
    data object Reentrant : ScriptCallResult
    data object Stopped : ScriptCallResult
}

// One running script: a QuickJS runtime that only its own thread touches. The runtime keeps one root evaluation alive
// for the session's lifetime; every event, timer and call runs as a nested evaluation inside it, so an event that
// awaits a Promise does not hold up the others. An interrupt poisons the runtime until that root evaluation ends, and a
// runtime at its memory limit fails unpredictably, so both stop the whole session once they surface; an out-of-memory
// error the script catches itself stays inside the script.
class ScriptSession internal constructor(
    val definition: ScriptDefinition,
    modules: List<ScriptModule>,
    private val limits: ScriptLimits,
    private val listener: ScriptSessionListener,
) : AutoCloseable {
    enum class State { STARTING, RUNNING, STOPPED, FAILED }

    private val executor = ScriptExecutor("nuke-script-${definition.id}")
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<suspend () -> Unit>(limits.pendingEvents)
    private val pending = ConcurrentHashMap<Long, Any?>()
    private val nextPending = AtomicLong(1)
    private val timers = ConcurrentHashMap<Long, Job>()
    private val resources = ArrayList<AutoCloseable>()
    private val resourceLock = Any()
    private val started = CompletableDeferred<Unit>()
    private val state = AtomicReference(State.STARTING)
    private val moduleSources = modules.associate { it.name to it.source }
    @Volatile private var valueAdapter: ScriptValueAdapter? = null
    private val gcRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val quickJs: QuickJs

    init {
        require(moduleSources.size == modules.size) { "Duplicate module names" }
        quickJs = QuickJs.create(dispatcher, BuiltinModules(moduleSources))
        try {
            quickJs.memoryLimit = limits.memoryBytes
            quickJs.maxStackSize = limits.stackBytes
            defineCore()
            val context = ModuleContext()
            modules.forEach { it.bind(context) }
        } catch (error: Throwable) {
            quickJs.close()
            executor.shutdownNow()
            throw error
        }
        events.trySend { evaluate(BOOTSTRAP_SOURCE, "bootstrap.js", module = false) }
        events.trySend {
            try {
                evaluate(definition.entrySource, "${definition.id}/${definition.entryName}", module = true)
                if (state.compareAndSet(State.STARTING, State.RUNNING)) started.complete(Unit)
            } catch (error: Throwable) {
                stop(error.asScriptException("The entry module failed"))
            }
        }
        scope.launch {
            val failure = try {
                quickJs.evaluate<Any?>("(async () => { await __nk_loop(); })()", "loop.js", false)
                null
            } catch (error: CancellationException) {
                null
            } catch (error: Throwable) {
                error.asScriptException("The script stopped")
            }
            stop(failure ?: ScriptException(ScriptException.STOPPED, "The event loop ended").takeIf { !isStopped })
        }
    }

    val currentState: State get() = state.get()
    val isFinished: Boolean get() = state.get().let { it == State.STOPPED || it == State.FAILED }
    private val isStopped: Boolean get() = isFinished

    // Waits until the entry module has been evaluated. Never call it from the script thread.
    fun awaitStart(timeoutMillis: Long = limits.startMillis) {
        val future = CompletableFuture<Unit>()
        started.invokeOnCompletion { error -> if (error == null) future.complete(Unit) else future.completeExceptionally(error) }
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw ScriptException(ScriptException.TIMEOUT, "The script did not start within $timeoutMillis ms")
        } catch (error: ExecutionException) {
            throw error.cause as? ScriptException ?: ScriptException(ScriptException.STOPPED, "The script stopped", error.cause)
        }
    }

    // Delivers payload to the script's listeners for type. Returns false when the session is not running or the
    // queue is full.
    fun emit(type: String, payload: Any?): Boolean {
        if (isStopped) return false
        val id = stash(mapOf("type" to type, "payload" to payload))
        return post(onDrop = { pending.remove(id) }) { evaluate("__nuke.dispatch($id)", "dispatch.js", module = false) }
    }

    // Runs the handler the script registered with __nuke.handle and waits for its synchronous answer.
    fun call(handler: Long, payload: Any?, timeoutMillis: Long): ScriptCallResult {
        if (executor.isScriptThread()) return ScriptCallResult.Reentrant
        if (state.get() != State.RUNNING) return ScriptCallResult.Stopped
        val id = stash(mapOf("handler" to handler, "payload" to payload))
        val answer = CompletableFuture<Any?>()
        val queued = post(onDrop = { pending.remove(id) }) {
            // Resolved here on the script thread, before a later task could release the handles in the answer.
            answer.complete(runCatching { resolve(evaluate("__nuke.invoke($id)", "invoke.js", module = false)) }.getOrElse { it })
        }
        if (!queued) return ScriptCallResult.Stopped
        val value = try {
            answer.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.remove(id)
            return ScriptCallResult.TimedOut
        }
        if (value is Throwable) return ScriptCallResult.Failed(value.asScriptException("The call failed"))
        val result = value as? Map<*, *> ?: return ScriptCallResult.Failed(
            ScriptException(ScriptException.SCRIPT_ERROR, "The handler returned no result")
        )
        return if (result["__nkError"] == true) {
            val message = result["message"].toString()
            stopOnLimit(message, null)
            ScriptCallResult.Failed(ScriptException(result["code"] as? String ?: ScriptException.SCRIPT_ERROR, message))
        } else {
            ScriptCallResult.Returned(result["value"])
        }
    }

    // Runs the handler without waiting; a failure is reported like a listener failure.
    fun notify(handler: Long, payload: Any?): Boolean {
        if (state.get() != State.RUNNING) return false
        val id = stash(mapOf("handler" to handler, "payload" to payload))
        return post(onDrop = { pending.remove(id) }) {
            val result = evaluate("__nuke.invoke($id)", "invoke.js", module = false) as? Map<*, *>
            if (result != null && result["__nkError"] == true) {
                val message = result["message"].toString()
                listener.scriptError("handler", bounded(message))
                stopOnLimit(message, null)
            }
        }
    }

    fun isScriptThread(): Boolean = executor.isScriptThread()

    override fun close() = stop(null)

    // Called by the engine's watchdog.
    internal fun checkBusy(now: Long) {
        val busy = executor.busyMillis(now)
        if (busy > limits.taskMillis) {
            stop(ScriptException(ScriptException.TIMEOUT, "The script ran for $busy ms without yielding"))
        }
    }

    private fun stop(failure: ScriptException?) {
        val target = if (failure == null) State.STOPPED else State.FAILED
        while (true) {
            val current = state.get()
            if (current == State.STOPPED || current == State.FAILED) return
            if (state.compareAndSet(current, target)) break
        }
        started.completeExceptionally(failure ?: ScriptException(ScriptException.STOPPED, "The script was stopped"))
        events.close()
        pending.clear()
        timers.values.forEach { it.cancel() }
        timers.clear()
        val owned = synchronized(resourceLock) { resources.asReversed().toList().also { resources.clear() } }
        try {
            owned.closeAll()
        } catch (error: Throwable) {
            listener.log(ScriptLogLevel.ERROR, "Releasing script resources failed: $error")
        }
        // QuickJs.close interrupts running JavaScript and then waits for it, which the script thread cannot do for
        // itself, so the runtime is always released from a thread of its own.
        Thread({
            try {
                quickJs.close()
            } catch (error: Throwable) {
                listener.log(ScriptLogLevel.ERROR, "Closing the runtime failed: $error")
            }
            scope.cancel()
            executor.shutdown()
        }, "nuke-script-close").apply { isDaemon = true }.start()
        listener.stopped(failure)
    }

    // Collects garbage between tasks so the FinalizationRegistry callbacks that release Java handles get to run.
    private fun requestGc() {
        if (!gcRequested.compareAndSet(false, true)) return
        post(onDrop = { gcRequested.set(false) }) {
            gcRequested.set(false)
            quickJs.gc()
            evaluate("void 0", "gc.js", module = false)
        }
    }

    private fun resolve(value: Any?): Any? {
        val adapter = valueAdapter ?: return value
        val map = value as? Map<*, *> ?: return value
        if (map["__nkError"] == true || !map.containsKey("value")) return value
        return map.toMutableMap().apply { this["value"] = adapter.resolve(map["value"]) }
    }

    // QuickJS turns a Kotlin Map into a JavaScript Map; scripts expect plain objects, which JsObject becomes. Host
    // objects go through the value adapter, and without one they reach the script as their string form.
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun toJs(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Int, is Long, is Double, is Float, is Short, is Byte, is JsObject, is UByteArray -> value
        Unit -> null
        is Char -> value.toString()
        is JavaValue -> value.value?.let { exportForeign(it) }
        is Map<*, *> -> JsObject(value.entries.associate { (key, item) -> key.toString() to toJs(item) })
        is List<*> -> value.map(::toJs)
        // Scripts work with Uint8Array, which is what QuickJS makes of a UByteArray.
        is ByteArray -> value.asUByteArray()
        else -> exportForeign(value)
    }

    // The adapter answers with plain values and maps, which still need converting themselves.
    private fun exportForeign(value: Any): Any? {
        val adapter = valueAdapter ?: return if (value is Number || value is Boolean) value else value.toString()
        return toJs(adapter.export(value))
    }

    private fun stash(value: Any?): Long = nextPending.getAndIncrement().also { pending[it] = value }

    private fun post(onDrop: () -> Unit, block: suspend () -> Unit): Boolean {
        if (events.trySend(block).isSuccess) return true
        onDrop()
        if (!isStopped) listener.log(ScriptLogLevel.WARN, "The event queue is full; an event was dropped")
        return false
    }

    private suspend fun evaluate(code: String, filename: String, module: Boolean): Any? = try {
        quickJs.evaluate<Any?>(code, filename, module)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        // Both leave the runtime unusable for every later evaluation, see the class comment.
        stopOnLimit(error.message.orEmpty(), error)
        throw error
    }

    private fun stopOnLimit(message: String, cause: Throwable?) {
        if (LIMIT_ERRORS.any { message.startsWith(it) || message.contains("Error: $it") }) {
            stop(ScriptException(ScriptException.LIMIT_EXCEEDED, "The script exceeded its limits: ${bounded(message)}", cause))
        }
    }

    private fun defineCore() {
        quickJs.asyncFunction<Unit>("__nk_loop") {
            supervisorScope {
                for (event in events) launch {
                    try {
                        event()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (!isStopped) listener.scriptError("event", error.toString())
                    }
                }
            }
        }
        quickJs.function("__nk_take") { args -> (args.getOrNull(0) as? Number)?.let { toJs(pending.remove(it.toLong())) } }
        quickJs.function("__nk_report") { args ->
            val message = args.getOrNull(1).toString()
            listener.scriptError(args.getOrNull(0).toString(), bounded(message))
            stopOnLimit(message, null)
            null
        }
        quickJs.function("__nk_timer_start") { args -> nativeCall(args.toList()) { startTimer(it) } }
        quickJs.function("__nk_timer_stop") { args ->
            (args.getOrNull(0) as? Number)?.let { timers.remove(it.toLong())?.cancel() }
            null
        }
    }

    private fun startTimer(args: List<Any?>): Any? {
        val id = args.longAt(0, "id")
        val millis = args.longAt(1, "delay")
        val repeat = args.booleanAt(2, "repeat")
        if (timers.size >= limits.timers) {
            throw ScriptException(ScriptException.LIMIT_EXCEEDED, "Too many active timers (${limits.timers})")
        }
        // An interval below 10 ms would flood the event queue.
        val period = if (repeat) maxOf(millis, 10L) else millis
        timers[id] = scope.launch {
            do {
                delay(period)
                post(onDrop = {}) { evaluate("__nuke.fireTimer($id)", "timer.js", module = false) }
            } while (repeat)
            timers.remove(id)
        }
        return null
    }

    private inner class ModuleContext : ScriptModuleContext {
        private val names = HashSet<String>()
        override val script: ScriptDefinition get() = definition

        override fun function(name: String, block: (List<Any?>) -> Any?) {
            quickJs.function(nativeName(name)) { args -> nativeCall(args.toList(), block) }
        }

        override fun asyncFunction(name: String, block: suspend (List<Any?>) -> Any?) {
            quickJs.asyncFunction(nativeName(name)) { args ->
                try {
                    toJs(block(args.toList()))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    errorResult(error)
                }
            }
        }

        override fun emit(type: String, payload: Any?): Boolean = this@ScriptSession.emit(type, payload)

        override fun <T : AutoCloseable> own(resource: T): T {
            val accepted = synchronized(resourceLock) { if (isStopped) false else { resources += resource; true } }
            if (!accepted) resource.close()
            return resource
        }

        override fun log(level: ScriptLogLevel, message: String) = listener.log(level, bounded(message))

        override fun setValueAdapter(adapter: ScriptValueAdapter) {
            check(valueAdapter == null) { "A value adapter is already installed" }
            valueAdapter = adapter
        }

        override fun requestGc() = this@ScriptSession.requestGc()

        override fun call(handler: Long, payload: Any?, timeoutMillis: Long) =
            this@ScriptSession.call(handler, payload, timeoutMillis)

        override fun notify(handler: Long, payload: Any?) = this@ScriptSession.notify(handler, payload)

        override val isScriptThread: Boolean get() = executor.isScriptThread()

        private fun nativeName(name: String): String {
            require(NATIVE_NAME.matches(name)) { "Invalid native function name: $name" }
            require(names.add(name)) { "Native function defined twice: $name" }
            return "__nk_$name"
        }
    }

    private fun nativeCall(args: List<Any?>, block: (List<Any?>) -> Any?): Any? = try {
        toJs(block(args))
    } catch (error: Throwable) {
        errorResult(error)
    }

    private class BuiltinModules(private val sources: Map<String, String>) : ModuleLoader {
        // Only built-in modules can be imported; the SDK bundles everything else into the entry file.
        override val normalizer = ModuleNormalizer { _, requested -> requested }
        override fun load(name: String): ModuleContent? = sources[name]?.let(ModuleContent::Source)
    }

    private companion object {
        val NATIVE_NAME = Regex("^[a-z][a-z0-9_]*$")
        val LIMIT_ERRORS = listOf("InternalError: interrupted", "InternalError: out of memory")
        const val MAX_MESSAGE = 4_000

        fun bounded(message: String) = if (message.length <= MAX_MESSAGE) message else message.take(MAX_MESSAGE) + "…"

        fun errorResult(error: Throwable): JsObject {
            val script = error as? ScriptException
            return JsObject(mapOf(
                "__nkError" to true,
                "code" to (script?.code ?: if (error is JavaInvocationError) ScriptException.JAVA_ERROR else ScriptException.HOST_ERROR),
                "message" to bounded(error.message ?: error.toString()),
                "javaClass" to (error as? JavaInvocationError)?.javaClassName,
            ))
        }

        fun Throwable.asScriptException(context: String): ScriptException =
            this as? ScriptException ?: ScriptException(ScriptException.SCRIPT_ERROR, "$context: ${message ?: this}", this)
    }
}

// Thrown by bindings that called into Java when the Java side threw; the script sees a JavaError with javaClass.
class JavaInvocationError(val javaClassName: String, message: String, cause: Throwable?) : RuntimeException(message, cause)

// A single thread that records when its current task started, so the watchdog can tell a script that never yields.
private class ScriptExecutor(name: String) : ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    { runnable -> Thread(null, runnable, name, STACK_BYTES).apply { isDaemon = true } },
) {
    private val runningSince = AtomicLong(0)
    @Volatile private var thread: Thread? = null

    override fun beforeExecute(t: Thread, r: Runnable) {
        thread = t
        runningSince.set(System.nanoTime())
    }

    override fun afterExecute(r: Runnable, t: Throwable?) {
        runningSince.set(0)
    }

    fun isScriptThread(): Boolean = Thread.currentThread() === thread

    fun busyMillis(now: Long): Long {
        val since = runningSince.get()
        return if (since == 0L) 0L else (now - since) / 1_000_000
    }

    private companion object {
        // Larger than the runtime's own stack limit, so QuickJS reports deep recursion before the thread overflows.
        const val STACK_BYTES = 8L * 1024 * 1024
    }
}
