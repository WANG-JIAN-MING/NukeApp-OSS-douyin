package io.github.nuke.oss.script.module

import io.github.nuke.oss.script.SCRIPT_API_VERSION
import io.github.nuke.oss.script.ScriptLogLevel
import io.github.nuke.oss.script.ScriptModule
import io.github.nuke.oss.script.ScriptModuleContext
import io.github.nuke.oss.script.stringAt

// The modules every session gets regardless of host or permissions.
val CORE_MODULES: List<ScriptModule> = listOf(RuntimeModule, LogModule, TimersModule, ConfigModule, EncodingModule, CryptoModule)

// Event type the config module listens to; the owner emits the full new value map after the user saves settings.
const val CONFIG_EVENT = "nuke:config"

internal object RuntimeModule : ScriptModule {
    override val name = "nuke:runtime"
    override val source = """
const info = __nuke.check(globalThis.__nk_runtime_info());
export const apiVersion = info.apiVersion;
export const script = Object.freeze({ id: info.id, name: info.name, version: info.version, versionCode: info.versionCode });
export const permissions = Object.freeze([...info.permissions]);
export function hasPermission(name) { return permissions.includes(String(name)); }
export const NukeError = __nuke.NukeError;
export const PermissionError = __nuke.PermissionError;
export const JavaError = __nuke.JavaError;
"""

    override fun bind(context: ScriptModuleContext) {
        val script = context.script
        context.function("runtime_info") {
            mapOf(
                "apiVersion" to SCRIPT_API_VERSION,
                "id" to script.id,
                "name" to script.name,
                "version" to script.version,
                "versionCode" to script.versionCode,
                "permissions" to script.permissions.names(),
            )
        }
    }
}

internal object LogModule : ScriptModule {
    override val name = "nuke:log"
    override val source = """
function text(value) {
  if (typeof value === "string") return value;
  if (value instanceof Error) return __nuke.describe(value);
  try {
    const json = JSON.stringify(value);
    return json === undefined ? String(value) : json;
  } catch (_) {
    return String(value);
  }
}
function write(level, values) { __nuke.check(globalThis.__nk_log_write(level, values.map(text).join(" "))); }
export function debug(...values) { write("DEBUG", values); }
export function info(...values) { write("INFO", values); }
export function warn(...values) { write("WARN", values); }
export function error(...values) { write("ERROR", values); }
"""

    override fun bind(context: ScriptModuleContext) {
        context.function("log_write") { args ->
            context.log(ScriptLogLevel.valueOf(args.stringAt(0, "level")), args.stringAt(1, "message"))
            null
        }
    }
}

internal object TimersModule : ScriptModule {
    override val name = "nuke:timers"
    override val source = """
export const setTimeout = globalThis.setTimeout;
export const clearTimeout = globalThis.clearTimeout;
export const setInterval = globalThis.setInterval;
export const clearInterval = globalThis.clearInterval;
export function sleep(millis) { return new Promise((resolve) => globalThis.setTimeout(resolve, millis)); }
"""

    // The timer natives belong to the session itself because the globals exist without importing this module.
    override fun bind(context: ScriptModuleContext) = Unit
}

internal object ConfigModule : ScriptModule {
    override val name = "nuke:config"
    override val source = """
function freeze(value) {
  if (value !== null && typeof value === "object") {
    Object.values(value).forEach(freeze);
    Object.freeze(value);
  }
  return value;
}
let current = freeze(__nuke.check(globalThis.__nk_config_values()));
const listeners = new Set();
__nuke.on("$CONFIG_EVENT", (next) => {
  current = freeze(next);
  for (const listener of [...listeners]) {
    try { listener(current); } catch (error) { globalThis.__nk_report("config", __nuke.describe(error)); }
  }
});
export function values() { return current; }
export function has(key) { return Object.prototype.hasOwnProperty.call(current, key); }
export function get(key, fallback) { return has(key) ? current[key] : fallback; }
export function onChange(listener) {
  if (typeof listener !== "function") throw new TypeError("listener must be a function");
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
"""

    override fun bind(context: ScriptModuleContext) {
        context.function("config_values") { context.script.config }
    }
}
