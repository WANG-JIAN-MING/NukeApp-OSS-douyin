package io.github.nuke.oss.script.java

import io.github.nuke.oss.hook.api.AfterCall
import io.github.nuke.oss.hook.api.BeforeCall
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookHandle
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.ScopedHooks
import io.github.nuke.oss.script.JavaValue
import io.github.nuke.oss.script.ScriptCallResult
import io.github.nuke.oss.script.ScriptException
import io.github.nuke.oss.script.ScriptLogLevel
import io.github.nuke.oss.script.ScriptModule
import io.github.nuke.oss.script.ScriptModuleContext
import io.github.nuke.oss.script.ScriptPermissions
import io.github.nuke.oss.script.longAt
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// The exception a script's throwEarly or setError raises in the host when it passes a message instead of a Java
// Throwable.
class ScriptThrownException(message: String) : RuntimeException(message)

// nuke:hook for one session. The host thread never hands its Hook frame to the script: it sends a snapshot of the call,
// waits for the script's answer within the hook's time limit and applies the answer to the frame itself. A failed,
// late or reentrant answer leaves the call unchanged, and nothing the script does is thrown into the host.
class HookModule(
    private val java: JavaModule,
    private val hooks: ScopedHooks,
    private val defaultTimeoutMillis: Long = 100,
    private val maxTimeoutMillis: Long = 1_000,
) : ScriptModule {
    override val name = "nuke:hook"
    override val source = SOURCE

    private val installed = ConcurrentHashMap<Long, HookHandle>()
    private val nextId = AtomicLong(1)

    override fun bind(context: ScriptModuleContext) {
        context.function("hook_install") { args ->
            context.requirePermission(ScriptPermissions.HOOK)
            val target = java.objectOf(args.getOrNull(0), "member") as? Executable
                ?: throw ScriptException.argument("member must be a Java method or constructor")
            val before = args.getOrNull(2) == true
            val after = args.getOrNull(3) == true
            val observe = args.getOrNull(6) == true
            val timeout = if (args.getOrNull(5) == null) defaultTimeoutMillis else args.longAt(5, "timeoutMs")
            if (timeout !in 1..maxTimeoutMillis) throw ScriptException.argument("timeoutMs must be between 1 and $maxTimeoutMillis")
            val id = nextId.getAndIncrement()
            val subscription = Subscription(context, id, args.longAt(1, "handler"), target, timeout, observe)
            val spec = HookSpec(
                id = "script.${context.script.id}.hook.$id",
                priority = args.longAt(4, "priority").toInt(),
                requiredCapabilities = if (observe) emptySet() else buildSet {
                    if (before) addAll(listOf(HookCapability.ARGUMENT_REWRITE, HookCapability.EARLY_RETURN))
                    if (after) add(HookCapability.OUTCOME_REWRITE)
                },
                before = if (before) subscription::before else null,
                after = if (after) subscription::after else null,
            )
            val handle = try {
                hooks.install(target, spec)
            } catch (error: IllegalArgumentException) {
                throw ScriptException(ScriptException.UNAVAILABLE, "Cannot hook ${JavaValues.signature(target)}: ${error.message}")
            } catch (error: IllegalStateException) {
                throw ScriptException(ScriptException.UNAVAILABLE, "Cannot hook ${JavaValues.signature(target)}: ${error.message}")
            }
            installed[id] = context.own(handle)
            id
        }
        context.function("hook_close") { args ->
            installed.remove(args.longAt(0, "id"))?.close()
            null
        }
    }

    private inner class Subscription(
        private val context: ScriptModuleContext,
        private val id: Long,
        private val handler: Long,
        private val target: Executable,
        private val timeoutMillis: Long,
        private val observe: Boolean,
    ) {
        private val timeouts = AtomicInteger()
        private val failures = AtomicInteger()
        private val reentrant = AtomicInteger()
        private val signature = JavaValues.signature(target)

        fun before(call: BeforeCall) {
            val payload = mapOf(
                "phase" to "before",
                "method" to JavaValue(target),
                "thisObject" to JavaValue(call.receiver),
                "args" to List(call.argumentCount) { JavaValue(call.argument(it)) },
            )
            val change = answer(payload) ?: return
            apply("before") {
                applyArguments(change) { index, value -> call.setArgument(index, value) }
                val thrown = change["thrown"]
                when {
                    thrown != null -> call.throwEarly(throwable(thrown))
                    change["returned"] == true -> call.returnEarly(returnValue(change["value"]))
                }
            }
        }

        fun after(call: AfterCall) {
            val outcome = call.outcome
            val payload = mapOf(
                "phase" to "after",
                "method" to JavaValue(target),
                "thisObject" to JavaValue(call.receiver),
                "args" to List(target.parameterCount) { JavaValue(call.argument(it)) },
                "result" to JavaValue((outcome as? CallOutcome.Returned)?.value),
                "error" to JavaValue((outcome as? CallOutcome.Thrown)?.error),
            )
            val change = answer(payload) ?: return
            apply("after") {
                val thrown = change["thrown"]
                when {
                    thrown != null -> call.throwError(throwable(thrown))
                    change["returned"] == true -> call.returnValue(returnValue(change["value"]))
                }
            }
        }

        // Runs on the host thread; null means leave the call as it is.
        private fun answer(payload: Map<String, Any?>): Map<*, *>? {
            if (observe) {
                context.notify(handler, payload)
                return null
            }
            return when (val result = context.call(handler, payload, timeoutMillis)) {
                is ScriptCallResult.Returned -> {
                    timeouts.set(0)
                    result.value as? Map<*, *>
                }
                is ScriptCallResult.Failed -> {
                    report(failures, ScriptLogLevel.ERROR, "Hook $signature failed: ${result.error.message}")
                    null
                }
                ScriptCallResult.TimedOut -> {
                    if (timeouts.incrementAndGet() >= MAX_TIMEOUTS) {
                        installed.remove(id)?.close()
                        context.log(ScriptLogLevel.ERROR, "Hook $signature timed out $MAX_TIMEOUTS times in a row and was removed")
                    } else {
                        context.log(ScriptLogLevel.WARN, "Hook $signature answered later than $timeoutMillis ms; the call went on unchanged")
                    }
                    null
                }
                ScriptCallResult.Reentrant -> {
                    report(reentrant, ScriptLogLevel.WARN, "Hook $signature ran on the script's own thread and was skipped")
                    null
                }
                ScriptCallResult.Stopped -> null
            }
        }

        private inline fun apply(phase: String, block: () -> Unit) {
            try {
                block()
            } catch (error: RuntimeException) {
                report(failures, ScriptLogLevel.ERROR, "Hook $signature could not apply the $phase answer: ${error.message}")
            }
        }

        private fun applyArguments(change: Map<*, *>, set: (Int, Any?) -> Unit) {
            val types = target.parameterTypes
            (change["args"] as? List<*>)?.forEach { entry ->
                val pair = entry as? List<*> ?: return@forEach
                val index = (pair.getOrNull(0) as? Number)?.toInt()
                if (index == null || index !in types.indices) throw ScriptException.argument("Argument index $index is out of range")
                set(index, JavaValues.coerce(pair.getOrNull(1), types[index]))
            }
        }

        private fun returnValue(value: Any?): Any? {
            val type = (target as? Method)?.returnType ?: Void.TYPE
            if (type == Void.TYPE) {
                if (value != null) throw ScriptException.argument("$signature returns nothing")
                return null
            }
            return JavaValues.coerce(value, type)
        }

        private fun throwable(value: Any?): Throwable = when (value) {
            is JavaRef -> value.value as? Throwable ?: throw ScriptException.argument("Only a Java Throwable can be thrown")
            is Map<*, *> -> ScriptThrownException(value["message"]?.toString() ?: "Thrown by script ${context.script.id}")
            else -> ScriptThrownException(value.toString())
        }

        // Keeps a misbehaving hook from flooding the log: the first few failures and then every hundredth.
        private fun report(counter: AtomicInteger, level: ScriptLogLevel, message: String) {
            val count = counter.incrementAndGet()
            if (count <= 5 || count % 100 == 0) context.log(level, if (count > 5) "$message (#$count)" else message)
        }
    }

    private companion object {
        const val MAX_TIMEOUTS = 3

        val SOURCE = """
import { JavaObject, __wrap as wrap, __unwrap as unwrap } from "nuke:java";
const n = globalThis;
const c = __nuke.check;

function thrown(error) {
  if (error instanceof JavaObject) return unwrap(error);
  return { message: String(error instanceof Error ? error.message : error) };
}

function prepare(event, observe) {
  const change = { args: [], returned: false, value: null, thrown: null };
  const args = wrap(event.args);
  const call = {
    phase: event.phase,
    method: wrap(event.method),
    thisObject: wrap(event.thisObject),
    args,
  };
  if (!observe) {
    call.setArg = (index, value) => {
      if (!Number.isInteger(index) || index < 0 || index >= args.length) throw new RangeError("argument index " + index);
      args[index] = value;
      change.args.push([index, unwrap(value)]);
    };
  }
  if (event.phase === "before") {
    if (!observe) {
      call.returnEarly = (value) => { change.returned = true; change.value = unwrap(value); change.thrown = null; };
      call.throwEarly = (error) => { change.thrown = thrown(error); change.returned = false; };
    }
  } else {
    call.result = wrap(event.result);
    call.error = wrap(event.error);
    if (!observe) {
      call.setResult = (value) => { change.returned = true; change.value = unwrap(value); change.thrown = null; };
      call.setError = (error) => { change.thrown = thrown(error); change.returned = false; };
    }
  }
  return { call, change };
}

export function hook(member, callbacks) {
  if (!(member instanceof JavaObject)) throw new TypeError("member must be a Java method or constructor");
  const options = callbacks || {};
  const before = typeof options.before === "function" ? options.before : null;
  const after = typeof options.after === "function" ? options.after : null;
  if (!before && !after) throw new TypeError("hook needs a before or an after callback");
  const observe = options.observe === true;
  const handler = __nuke.handle((event) => {
    const { call, change } = prepare(event, observe);
    (event.phase === "before" ? before : after)(call);
    return observe ? null : change;
  });
  let id;
  try {
    id = c(n.__nk_hook_install(
      unwrap(member), handler, before !== null, after !== null, Math.trunc(Number(options.priority || 0)),
      options.timeoutMs === undefined ? null : Number(options.timeoutMs), observe,
    ));
  } catch (error) {
    __nuke.unhandle(handler);
    throw error;
  }
  let closed = false;
  return Object.freeze({
    close() {
      if (closed) return;
      closed = true;
      n.__nk_hook_close(id);
      __nuke.unhandle(handler);
    },
  });
}
"""
    }
}
