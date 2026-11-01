package io.github.nuke.oss.ui.hostsettings

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.nuke.oss.app.shared.data.downloadImage

private const val MAX_TRIES = 4

// Downloads the account avatar for the profile card, blocking; callers run it off the main thread. The host's CDN
// lists several sizes and formats, so each URL is tried in order until one decodes. Bounded in count, time and size,
// kept only in memory, and nothing about it is logged. A null result leaves the card's placeholder glyph.
internal fun fetchAvatar(urls: List<String>): ImageBitmap? {
    for (url in urls.take(MAX_TRIES)) {
        val bytes = downloadImage(url) ?: continue
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
        return bitmap.asImageBitmap()
    }
    return null
}
