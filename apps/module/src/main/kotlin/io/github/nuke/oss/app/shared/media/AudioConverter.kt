package io.github.nuke.oss.app.shared.media

import java.io.File

// The result of turning an audio file into M4A: the file and its length, or why it could not be done.
internal sealed interface AudioConversion {
    class Converted(val file: File, val durationMillis: Long) : AudioConversion
    class Failed(val reason: String) : AudioConversion
}

// Turns a chosen audio file into an M4A (AAC) file at output. Blocking; callers run it off the main thread.
internal fun interface AudioConverter {
    fun toM4a(input: File, output: File): AudioConversion
}
