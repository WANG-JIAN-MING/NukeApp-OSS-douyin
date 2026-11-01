package io.github.nuke.oss.app.tiktok.sdk

// A duration as h:mm:ss, or m:ss under an hour.
internal fun clockText(millis: Long): String {
    val seconds = millis / 1000
    val (h, m, s) = Triple(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
