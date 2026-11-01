package io.github.nuke.oss.script.module

import io.github.nuke.oss.script.ScriptModule
import io.github.nuke.oss.script.ScriptModuleContext
import io.github.nuke.oss.script.optionalStringAt
import io.github.nuke.oss.script.stringAt

// What the host shows for nuke:ui. Every call names the script so the user can tell where a dialog comes from; a host
// without a screen to show it on throws a ScriptException with UNAVAILABLE.
interface ScriptUi {
    suspend fun toast(script: String, message: String, long: Boolean)
    suspend fun alert(script: String, title: String?, message: String)
    suspend fun confirm(script: String, title: String?, message: String, confirm: String?, cancel: String?): Boolean
}

class UiModule(private val ui: ScriptUi) : ScriptModule {
    override val name = "nuke:ui"
    override val source = """
const n = globalThis;
const c = __nuke.check;
const text = (value) => (value === undefined || value === null ? null : String(value));
export async function toast(message, options) {
  c(await n.__nk_ui_toast(String(message), !!(options && options.long)));
}
export async function alert(options) {
  const settings = typeof options === "string" ? { message: options } : options || {};
  c(await n.__nk_ui_alert(text(settings.title), String(settings.message ?? "")));
}
export async function confirm(options) {
  const settings = typeof options === "string" ? { message: options } : options || {};
  return c(await n.__nk_ui_confirm(text(settings.title), String(settings.message ?? ""), text(settings.confirmText), text(settings.cancelText)));
}
"""

    override fun bind(context: ScriptModuleContext) {
        val script = context.script.name
        context.asyncFunction("ui_toast") { args ->
            ui.toast(script, args.stringAt(0, "message").take(MAX_TEXT), args.getOrNull(1) == true)
            null
        }
        context.asyncFunction("ui_alert") { args ->
            ui.alert(script, args.optionalStringAt(0, "title")?.take(MAX_TITLE), args.stringAt(1, "message").take(MAX_TEXT))
            null
        }
        context.asyncFunction("ui_confirm") { args ->
            ui.confirm(
                script,
                args.optionalStringAt(0, "title")?.take(MAX_TITLE),
                args.stringAt(1, "message").take(MAX_TEXT),
                args.optionalStringAt(2, "confirmText")?.take(MAX_TITLE),
                args.optionalStringAt(3, "cancelText")?.take(MAX_TITLE),
            )
        }
    }

    private companion object {
        const val MAX_TITLE = 64
        const val MAX_TEXT = 2_000
    }
}
