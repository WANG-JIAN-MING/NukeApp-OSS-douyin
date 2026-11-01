package io.github.nuke.oss.app.tiktok.sdk.media

import java.io.File
import java.util.Base64

// What a downloaded file turned out to be, from its first bytes.
internal data class MediaFileType(val mime: String, val extension: String) {
    companion object {
        // Throws IllegalStateException for a format that is not recognized, so an unknown file is never saved as one.
        fun detect(header: ByteArray, audio: Boolean = false): MediaFileType {
            fun text(offset: Int, length: Int) = if (header.size >= offset + length) String(header, offset, length, Charsets.US_ASCII) else ""
            fun starts(vararg bytes: Int) = header.size >= bytes.size && bytes.indices.all { header[it].toInt() and 255 == bytes[it] }
            return when {
                starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> MediaFileType("image/png", "png")
                starts(0xff, 0xd8, 0xff) -> MediaFileType("image/jpeg", "jpg")
                text(0, 6) in listOf("GIF87a", "GIF89a") -> MediaFileType("image/gif", "gif")
                text(0, 4) == "RIFF" && text(8, 4) == "WEBP" -> MediaFileType("image/webp", "webp")
                text(0, 4) == "RIFF" && text(8, 4) == "WAVE" -> MediaFileType("audio/wav", "wav")
                text(0, 4) == "fLaC" -> MediaFileType("audio/flac", "flac")
                text(0, 4) == "OggS" -> MediaFileType("audio/ogg", "ogg")
                text(0, 5) == "#!AMR" -> MediaFileType("audio/amr", "amr")
                text(0, 3) == "ID3" || starts(0xff, 0xfb) || starts(0xff, 0xf3) || starts(0xff, 0xf2) -> MediaFileType("audio/mpeg", "mp3")
                header.size > 1 && header[0].toInt() and 255 == 255 && header[1].toInt() and 0xf6 == 0xf0 -> MediaFileType("audio/aac", "aac")
                text(4, 4) == "ftyp" -> when (text(8, 4)) {
                    "vvic" -> MediaFileType("image/vvic", "vvic")
                    "heic", "heix", "hevc", "hevx" -> MediaFileType("image/heic", "heic")
                    "mif1", "msf1" -> MediaFileType("image/heif", "heif")
                    "avif", "avis" -> MediaFileType("image/avif", "avif")
                    "M4A ", "M4B ", "mp4a" -> MediaFileType("audio/mp4", "m4a")
                    else -> if (audio) MediaFileType("audio/mp4", "m4a") else MediaFileType("video/mp4", "mp4")
                }
                else -> error("Unsupported media format")
            }
        }
    }
}

// Media addresses can be literal or base64-encoded; only http(s) ones are kept.
internal fun mediaUrls(values: List<String>): List<String> = values.mapNotNull { raw ->
    var value = raw.trim()
    if (!value.startsWith("http://") && !value.startsWith("https://") && !value.startsWith("//")) {
        value = try { String(Base64.getDecoder().decode(value), Charsets.UTF_8).trim() } catch (_: IllegalArgumentException) { return@mapNotNull null }
    }
    if (value.startsWith("//")) value = "https:$value"
    value.takeIf { it.startsWith("https://") || it.startsWith("http://") }
}.distinct()

// Host composers pass one path or nested lists of paths, depending on the scene.
internal fun mediaPaths(value: Any?, depth: Int = 0): List<String> = when {
    depth > 8 -> emptyList()
    value is String -> listOf(value).filter(String::isNotBlank)
    value is List<*> -> value.flatMap { mediaPaths(it, depth + 1) }.distinct()
    else -> emptyList()
}

// Where a saved image goes under Pictures/douyin.
internal enum class ImageFolder(val directory: String) { COMMENT("comment"), AVATAR("avatar") }

// Files the media features write or convert. Everything here blocks, so callers run it off the main thread.
internal interface MediaFiles {
    // Asks for storage access when this Android version needs it and it is missing; true when it asked, so the caller
    // stops and the user taps again.
    fun requestStoragePermission(activity: Any): Boolean

    // Downloads the first address that answers (context is any host Context) and saves it to the gallery (Pictures/douyin/<folder>) or the music
    // folder (Music/douyin/audio). Video stickers become GIFs and VVIC images become PNGs. Throws on failure.
    fun save(context: Any, urls: List<String>, audio: Boolean, folder: ImageFolder = ImageFolder.COMMENT)

    // Writes the text to Downloads/Nuke (or the app's own documents folder before Android 10) and returns where it went.
    fun saveText(context: Any, name: String, text: String): String

    // Replaces a VVIC image file by a PNG with the host's own decoder; anything else is left alone.
    fun convertVvic(file: File)
}
