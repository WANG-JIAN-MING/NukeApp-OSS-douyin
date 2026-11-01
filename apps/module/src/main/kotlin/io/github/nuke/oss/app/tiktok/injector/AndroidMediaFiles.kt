package io.github.nuke.oss.app.tiktok.injector

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import com.shakster.gifkt.GifEncoder
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFileType
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

// Downloads, converts and saves media with the host's own storage rights, the way the legacy module did. Sizes are
// capped (128 MiB per download, 3000 frames and 16 million pixels per frame for a sticker), and every temporary file
// is removed whether or not the save worked.
internal class AndroidMediaFiles(private val hostClassLoader: ClassLoader) : MediaFiles {
    override fun requestStoragePermission(activity: Any): Boolean {
        val host = activity as? Activity ?: return false
        if (Build.VERSION.SDK_INT >= 29 || hasLegacyStorage(host)) return false
        host.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE)
        return true
    }

    private fun hasLegacyStorage(host: Context) =
        host.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    override fun save(context: Any, urls: List<String>, audio: Boolean, folder: ImageFolder) {
        val host = (context as? Context)?.applicationContext ?: error("No host Context to save with")
        val source = File.createTempFile("nuke-media-", ".media", host.cacheDir)
        var converted: File? = null
        try {
            var downloaded = false
            var lastError: Exception? = null
            for (url in urls.distinct()) {
                try {
                    download(url, source)
                    downloaded = true
                    break
                } catch (error: java.io.IOException) {
                    lastError = error
                } catch (error: IllegalArgumentException) {
                    lastError = error
                }
            }
            check(downloaded) { "No media URL succeeded: ${lastError?.message}" }
            var type = source.inputStream().use { MediaFileType.detect(header(it), audio) }
            require(if (audio) type.mime.startsWith("audio/") else type.mime.startsWith("image/") || type.mime.startsWith("video/")) {
                "Unexpected downloaded MIME ${type.mime}"
            }
            var output = source
            if (!audio && type.mime.startsWith("video/")) {
                converted = File.createTempFile("nuke-sticker-", ".gif", host.cacheDir)
                toGif(source, converted)
                output = converted
                type = MediaFileType("image/gif", "gif")
            } else if (type.mime == "image/vvic") {
                convertVvic(source)
                type = MediaFileType("image/png", "png")
            }
            if (Build.VERSION.SDK_INT < 29) {
                require(hasLegacyStorage(host)) { "Storage permission is required on Android 8–9" }
                saveLegacy(host, output, type, audio, folder)
            } else {
                saveScoped(host, output, type, audio, folder)
            }
        } finally {
            source.delete()
            converted?.delete()
        }
    }

    override fun saveText(context: Any, name: String, text: String): String {
        val host = (context as? Context)?.applicationContext ?: error("No host Context to save with")
        val filename = name.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.take(100).ifEmpty { "details.txt" }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = host.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Nuke")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Unable to create the file" }
            try {
                checkNotNull(resolver.openOutputStream(uri)) { "Unable to open the file" }.writer(Charsets.UTF_8).use { it.write(text) }
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) { "Unable to publish the file" }
                return "${Environment.DIRECTORY_DOWNLOADS}/Nuke/$filename"
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }
        val directory = File(checkNotNull(host.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)) { "No documents folder" }, "Nuke")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create ${directory.name}" }
        val file = File(directory, filename)
        try {
            file.writeText(text, Charsets.UTF_8)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        return file.absolutePath
    }

    private fun saveLegacy(host: Context, output: File, type: MediaFileType, audio: Boolean, folder: ImageFolder) {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_PICTURES),
            if (audio) "douyin/audio" else "douyin/${folder.directory}",
        )
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create ${directory.name}" }
        val destination = File(directory, "${UUID.randomUUID()}.${type.extension}")
        try {
            output.inputStream().use { input -> destination.outputStream().use { target -> input.copyTo(target) } }
            MediaScannerConnection.scanFile(host, arrayOf(destination.path), arrayOf(type.mime), null)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun saveScoped(host: Context, output: File, type: MediaFileType, audio: Boolean, folder: ImageFolder) {
        val collection = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${if (audio) "audio" else folder.directory}_${UUID.randomUUID()}.${type.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, type.mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (audio) "${Environment.DIRECTORY_MUSIC}/douyin/audio" else "${Environment.DIRECTORY_PICTURES}/douyin/${folder.directory}",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = host.contentResolver
        val uri = checkNotNull(resolver.insert(collection, values)) { "Unable to create the media entry" }
        try {
            checkNotNull(resolver.openOutputStream(uri)) { "Unable to open the media output" }.use { target ->
                output.inputStream().use { input -> input.copyTo(target) }
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { "Unable to publish the media" }
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun download(url: String, target: File) {
        val parsed = URL(if (url.startsWith("//")) "https:$url" else url)
        require(parsed.protocol == "https" || parsed.protocol == "http") { "Unsupported media URL" }
        val connection = parsed.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BYTES) { "The media exceeds 128 MiB" }
                        output.write(buffer, 0, count)
                    }
                    require(total > 0) { "Empty download" }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    // Frames are read one at a time so a long sticker never sits in memory whole.
    private fun toGif(source: File, destination: File) {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                val mime = extractor.getTrackFormat(it).getString("mime").orEmpty()
                mime.startsWith("video/") || mime.startsWith("image/")
            } ?: error("No visual track")
            extractor.selectTrack(track)
            val timestamps = ArrayList<Long>()
            while (extractor.sampleTime >= 0) {
                require(timestamps.size < MAX_FRAMES) { "The sticker has too many frames" }
                timestamps += extractor.sampleTime
                extractor.advance()
            }
            require(timestamps.isNotEmpty()) { "No video frames" }
            retriever.setDataSource(source.path)
            SystemFileSystem.sink(Path(destination.path)).buffered().use { sink ->
                GifEncoder(sink).use { encoder ->
                    timestamps.forEachIndexed { index, time ->
                        val bitmap = (if (Build.VERSION.SDK_INT >= 28) runCatching { retriever.getFrameAtIndex(index) }.getOrNull() else null)
                            ?: retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)
                            ?: error("Unable to decode sticker frame $index")
                        try {
                            require(bitmap.width.toLong() * bitmap.height <= MAX_PIXELS) { "A sticker frame is too large" }
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            val next = timestamps.getOrNull(index + 1) ?: (time + if (index > 0) time - timestamps[index - 1] else DEFAULT_FRAME_MICROS)
                            encoder.writeFrame(pixels, bitmap.width, bitmap.height, ((next - time) / 1000).coerceAtLeast(10).milliseconds)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            try { extractor.release() } finally { retriever.release() }
        }
    }

    // The host's own native decoder produces the bitmap; its reference is closed whether or not encoding worked, and
    // the source is only replaced after the PNG is complete.
    override fun convertVvic(file: File) {
        if (runCatching { MediaFileType.detect(file.inputStream().use(::header)).mime }.getOrNull() != "image/vvic") return
        require(file.length() <= MAX_BYTES) { "VVIC image too large" }
        val bytes = file.readBytes()
        val decoder = "com.bytedance.fresco.nativeheif.Heif".toClass(hostClassLoader).resolve().optional(silent = true)
            .method { name = "toRgba"; parameterCount = 15 }.singleOrNull()
            ?: error("The VVIC decoder is unavailable")
        val data = decoder.invoke(bytes, true, bytes.size, true, 0, true, 1, true, 1, 1, -1, -1, -1, -1, true) ?: error("VVIC decoder returned null")
        val reference = data.callByName("newBitmap", null, Bitmap.Config.ARGB_8888) ?: error("VVIC bitmap unavailable")
        val temporary = File.createTempFile("nuke-vvic-", ".png", file.parentFile)
        try {
            val bitmap = reference.callByName("get") as? Bitmap ?: error("VVIC bitmap type mismatch")
            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Cannot encode the VVIC image" } }
            check(temporary.renameTo(file)) { "Cannot replace the VVIC image" }
        } finally {
            temporary.delete()
            reference.callByName("close")
        }
    }

    private fun header(input: InputStream): ByteArray {
        val bytes = ByteArray(64)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count <= 0) break
            offset += count
        }
        return bytes.copyOf(offset)
    }

    private companion object {
        const val REQUEST_CODE = 0x4d45
        const val TIMEOUT = 15_000
        const val BUFFER = 64 * 1024
        const val MAX_BYTES = 128L * 1024 * 1024
        const val MAX_FRAMES = 3000
        const val MAX_PIXELS = 16_000_000L
        const val DEFAULT_FRAME_MICROS = 100_000L
    }
}
