package io.github.nuke.oss.app.shared.activity

import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope

// Short messages and dialogs a feature shows inside the host. Text is given as module string IDs and resolved against
// the module package when shown. Nothing is shown while no host Activity is in front, and nothing more is shown or run
// once the owning feature is turned off.
internal interface HostPrompts {
    fun install(scope: FeatureScope)
    fun toast(text: Int, vararg args: Any)
    // onUnavailable runs instead when no dialog can be shown, so a feature that held back a user's tap can let it through.
    fun confirm(title: Int, message: Int, messageArgs: Array<out Any>, confirm: Int, onUnavailable: () -> Unit = {}, onConfirm: () -> Unit)

    // A list to pick from. An item that is an Int is a module string ID, anything else is shown as its text. Picking runs
    // onChosen with the position, the cancel button runs onCancel, and onUnavailable runs when no dialog can be shown.
    fun choose(title: Int, items: List<Any>, cancel: Int, onUnavailable: () -> Unit, onCancel: () -> Unit, onChosen: (Int) -> Unit)

    // A list to tick several entries from, at most limit of them; confirming with at least one ticked runs onChosen with
    // their positions, and ticking one past the limit is refused with limitMessage (given the limit). An item that is an
    // Int is a module string ID, anything else is shown as its text.
    fun chooseMany(title: Int, items: List<Any>, limit: Int, confirm: Int, cancel: Int, limitMessage: Int, onUnavailable: () -> Unit, onChosen: (Set<Int>) -> Unit)

    // One result picked from a row of segments under label, the first one picked at the start, the way NukeApp's emoji
    // game dialog picks a dice point or a hand. confirm runs onChosen with the picked position, other runs onOther, and
    // cancel or dismissing runs onCancel; onUnavailable runs when no dialog can be shown. Items are as for choose.
    fun pick(
        title: Int, label: Int, items: List<Any>, confirm: Int, other: Int, cancel: Int,
        onUnavailable: () -> Unit, onCancel: () -> Unit, onOther: () -> Unit, onChosen: (Int) -> Unit,
    )

    // A bottom sheet of labelled fields in sections, the way NukeApp shows a post's details: a search box, rows that copy
    // their value on a tap and expand on a long press, copy-all and save. content builds the sheet once the module's
    // strings are at hand (string resolves one with its format arguments). Save runs onSave with the full text, and
    // onSave calls done, from any thread, with the message to show: a module string ID and its one format argument.
    // Closing the feature closes the sheet.
    fun sheet(content: (string: (Int, Array<out Any>) -> String) -> InfoSheet, onSave: (text: String, done: (message: Int, argument: Any) -> Unit) -> Unit)

    // A dialog counting down from seconds; message takes the seconds left. Cancel runs onCancel, reaching zero runs
    // onFinish. Closing the handle dismisses it without running either.
    fun countdown(title: Int, message: Int, seconds: Int, cancel: Int, onCancel: () -> Unit, onFinish: () -> Unit): AutoCloseable
}

// Builds the prompts of one feature. The assembly supplies the Compose implementation, so host code never links UI
// classes; ui gives the module's appearance (theme, accent, language) at the moment a dialog opens.
internal fun interface HostPromptsFactory {
    fun create(featureId: String, build: BuildIdentity, logger: Logger, ui: () -> UiConfig): HostPrompts
}

// Ends the host app by ending its process; the system then drops its tasks as for any killed app.
internal fun interface HostExit {
    fun exit()
}

internal val processExit = HostExit { android.os.Process.killProcess(android.os.Process.myPid()) }
