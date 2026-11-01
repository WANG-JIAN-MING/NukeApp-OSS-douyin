package io.github.nuke.oss.ui.home

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Decodes the stored home background down to roughly the screen size, off the main thread. Null while loading, when
// there is no background, and when the file cannot be decoded as an image.
@Composable
internal fun rememberBackgroundImage(file: File?, revision: Long): State<ImageBitmap?> {
    val metrics = LocalContext.current.resources.displayMetrics
    val target = maxOf(metrics.widthPixels, metrics.heightPixels)
    return produceState<ImageBitmap?>(null, file, revision, target) {
        value = if (file == null || revision == 0L) null else withContext(Dispatchers.IO) { decode(file, target) }
    }
}

private fun decode(file: File, target: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    var sample = 1
    while (longest / (sample * 2) >= target) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}
