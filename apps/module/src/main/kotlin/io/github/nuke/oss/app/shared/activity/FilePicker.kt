package io.github.nuke.oss.app.shared.activity

import io.github.nuke.oss.runtime.FeatureScope
import java.io.File

// What the system picker gave back: a private copy of the chosen file, nothing (the user backed out), or a reason it
// could not be done.
internal sealed interface PickResult {
    class Picked(val file: File, val displayName: String?) : PickResult
    data object Cancelled : PickResult
    class Failed(val reason: String) : PickResult
}

// Lets a feature have the user choose a file from inside the host. The system document picker opens over the Activity in
// front; the chosen file is copied into a private directory of the host before done runs, so the feature never depends
// on a temporary URI grant. One pick at a time per picker; done runs once, on a background thread.
internal interface FilePicker {
    fun install(scope: FeatureScope, featureId: String)

    // mimeTypes are what may be chosen, such as "audio/*"; maxBytes bounds the copy.
    fun pick(mimeTypes: List<String>, maxBytes: Long, done: (PickResult) -> Unit)
}
