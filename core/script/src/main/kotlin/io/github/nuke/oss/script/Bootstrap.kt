package io.github.nuke.oss.script

// Evaluated once per session before the entry module. It defines the error types, the native result check, the event
// and handler tables Kotlin dispatches into, and the timer globals; everything else is a built-in module.
internal const val BOOTSTRAP_SOURCE = """
(() => {
  const g = globalThis;
  const take = g.__nk_take;
  const report = g.__nk_report;

  class NukeError extends Error {
    constructor(code, message) {
      super(message);
      this.name = "NukeError";
      this.code = code;
    }
  }
  class PermissionError extends NukeError {
    constructor(message) {
      super("PERMISSION_DENIED", message);
      this.name = "PermissionError";
    }
  }
  class JavaError extends NukeError {
    constructor(message, javaClass) {
      super("JAVA_ERROR", message);
      this.name = "JavaError";
      this.javaClass = javaClass;
    }
  }

  function toError(result) {
    if (result.code === "PERMISSION_DENIED") return new PermissionError(result.message);
    if (result.code === "JAVA_ERROR") return new JavaError(result.message, result.javaClass);
    return new NukeError(result.code, result.message);
  }

  function check(result) {
    if (result !== null && typeof result === "object" && result.__nkError === true) throw toError(result);
    return result;
  }

  function describe(error) {
    if (error instanceof Error) {
      const head = error.name + ": " + error.message;
      return error.stack ? head + "\n" + error.stack : head;
    }
    return String(error);
  }

  function isThenable(value) {
    return value !== null && (typeof value === "object" || typeof value === "function") && typeof value.then === "function";
  }

  // A listener's failure is reported and never reaches the caller, so one bad listener cannot stop the others.
  function guard(where, fn, args) {
    try {
      const result = fn(...args);
      if (isThenable(result)) result.then(undefined, (error) => report(where, describe(error)));
    } catch (error) {
      report(where, describe(error));
    }
  }

  const listeners = new Map();
  function on(type, listener) {
    if (typeof listener !== "function") throw new TypeError("listener must be a function");
    let set = listeners.get(type);
    if (!set) listeners.set(type, (set = new Set()));
    set.add(listener);
    return () => { set.delete(listener); };
  }

  function dispatch(id) {
    const event = take(id);
    if (event === null || event === undefined) return;
    const set = listeners.get(event.type);
    if (!set) return;
    for (const listener of [...set]) guard(event.type, listener, [event.payload]);
  }

  // Handlers answer synchronous calls from Kotlin, such as Hook callbacks waiting on a host thread.
  const handlers = new Map();
  let nextHandler = 1;
  function handle(fn) {
    if (typeof fn !== "function") throw new TypeError("handler must be a function");
    const id = nextHandler++;
    handlers.set(id, fn);
    return id;
  }
  function unhandle(id) { handlers.delete(id); }

  function invoke(id) {
    const call = take(id);
    if (call === null || call === undefined) return { __nkError: true, code: "TIMEOUT", message: "The call was abandoned" };
    const fn = handlers.get(call.handler);
    if (!fn) return { __nkError: true, code: "UNAVAILABLE", message: "The handler was removed" };
    try {
      const value = fn(call.payload);
      if (isThenable(value)) {
        value.then(undefined, () => {});
        return { __nkError: true, code: "SCRIPT_ERROR", message: "The handler must return synchronously" };
      }
      return { value: value === undefined ? null : value };
    } catch (error) {
      return { __nkError: true, code: "SCRIPT_ERROR", message: describe(error) };
    }
  }

  const timers = new Map();
  let nextTimer = 1;
  function startTimer(fn, delay, args, repeat) {
    if (typeof fn !== "function") throw new TypeError("callback must be a function");
    const id = nextTimer++;
    const millis = Math.max(0, Math.floor(Number(delay) || 0));
    check(g.__nk_timer_start(id, millis, repeat));
    timers.set(id, { fn, args, repeat });
    return id;
  }
  function stopTimer(id) {
    if (timers.delete(id)) g.__nk_timer_stop(id);
  }
  function fireTimer(id) {
    const timer = timers.get(id);
    if (!timer) return;
    if (!timer.repeat) timers.delete(id);
    guard("timer", timer.fn, timer.args);
  }

  g.setTimeout = (fn, delay, ...args) => startTimer(fn, delay, args, false);
  g.setInterval = (fn, delay, ...args) => startTimer(fn, delay, args, true);
  g.clearTimeout = (id) => stopTimer(id);
  g.clearInterval = (id) => stopTimer(id);

  Object.defineProperty(g, "__nuke", {
    value: Object.freeze({
      NukeError, PermissionError, JavaError, check, describe, on, dispatch, handle, unhandle, invoke, fireTimer,
    }),
    enumerable: false,
  });
})();
"""
