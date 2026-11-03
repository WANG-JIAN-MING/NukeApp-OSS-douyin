package io.github.nuke.oss.app.tiktok.sdk.feed

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import io.github.nuke.oss.R
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

// The post details shown by the video fields dialog: read from the Aweme object graph without changing it, then
// turned into text. Taken from the legacy module.
// A presentation snapshot of the post; host and server models are never changed.
internal data class VideoFieldsMetadata(
    val aid: String,
    val sections: List<VideoInfoSection>,
    val omittedImages: Int = 0,
)

internal data class VideoInfoSection(
    val title: Int,
    val fields: List<VideoInfoField>,
    val imageIndex: Int? = null,
)

internal data class VideoInfoField(
    val label: Int,
    val value: String,
    val format: VideoInfoFormat = VideoInfoFormat.TEXT,
)

internal enum class VideoInfoFormat { TEXT, TIME, MILLISECONDS, SECONDS, BYTES, FLAG, KIND }

internal data class VideoInfoDisplayField(val label: String, val value: String)
internal data class VideoInfoDisplaySection(val title: String, val fields: List<VideoInfoDisplayField>)

// Reads only the known useful fields, not the whole Aweme object graph.
internal object VideoFieldsReader {
    private const val MAX_IMAGES = 100
    private const val MAX_TEXT_LENGTH = 32_000
    private val addresses = ConcurrentHashMap<Class<*>, Map<String, Field>>()

    fun read(aweme: Any, checkCancelled: () -> Unit = {}): VideoFieldsMetadata {
        fun field(value: Any?, key: String): Any? {
            checkCancelled()
            if (value == null) return null
            return addresses.computeIfAbsent(value.javaClass, ::indexFields)[key]?.get(value)
        }
        fun text(value: Any?, key: String): String? = (field(value, key) as? CharSequence)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_TEXT_LENGTH }?.toString()
        fun number(value: Any?, key: String): Long? = (field(value, key) as? Number)?.toLong()?.takeIf { it >= 0 }
        fun identifier(value: Any?, key: String): String? = when (val id = field(value, key)) {
            is CharSequence -> id.toString().trim().takeIf { it.isNotEmpty() && it != "0" && it.length <= 512 }
            is Long, is Int, is Short, is Byte -> id.toString().takeIf { it != "0" && !it.startsWith('-') }
            else -> null
        }
        fun url(value: Any?): String? {
            val list = (value as? List<*>) ?: (field(value, "url_list") ?: field(value, "urlList")) as? List<*>
            return list?.asSequence()?.filterIsInstance<String>()?.mapNotNull(::httpUrl)?.firstOrNull()
        }
        val sections = mutableListOf<VideoInfoSection>()
        val links = mutableListOf<VideoInfoField>()
        fun section(title: Int, index: Int? = null, block: MutableList<VideoInfoField>.() -> Unit) {
            buildList(block).takeIf { it.isNotEmpty() }?.let { sections += VideoInfoSection(title, it, index) }
        }
        fun MutableList<VideoInfoField>.addValue(label: Int, value: Any?, format: VideoInfoFormat = VideoInfoFormat.TEXT) {
            if (value != null && value.toString().isNotBlank()) add(VideoInfoField(label, value.toString(), format))
        }
        fun MutableList<VideoInfoField>.resolution(value: Any?) {
            val width = number(value, "width")?.takeIf { it > 0 }
            val height = number(value, "height")?.takeIf { it > 0 }
            if (width != null && height != null) addValue(R.string.host_video_info_resolution, "$width × $height")
        }
        fun link(label: Int, value: String?) { if (value != null) links += VideoInfoField(label, value) }

        val aid = identifier(aweme, "aweme_id") ?: identifier(aweme, "aid").orEmpty()
        val video = field(aweme, "video")
        val images = field(aweme, "images") as? List<*>
        val kind = when {
            !images.isNullOrEmpty() -> "images"
            video != null -> "video"
            else -> "other"
        }
        section(R.string.host_video_info_basic) {
            addValue(R.string.host_video_info_id, aid)
            if (aid.isNotEmpty() || video != null || !images.isNullOrEmpty()) {
                addValue(R.string.host_video_info_kind, kind, VideoInfoFormat.KIND)
            }
            addValue(R.string.host_video_info_caption, text(aweme, "desc"))
            val time = (number(aweme, "create_time") ?: number(aweme, "createTime"))?.takeIf { it in 1..253402300799L }
            addValue(R.string.host_video_info_created, time, VideoInfoFormat.TIME)
            addValue(R.string.host_video_info_location, text(aweme, "ip_attribution") ?: text(aweme, "ipAttribution"))
            if (!images.isNullOrEmpty()) addValue(R.string.host_video_info_image_count, images.size)
            if (field(aweme, "is_ads") == true || field(aweme, "isAd") == true) {
                addValue(R.string.host_video_info_ad, true, VideoInfoFormat.FLAG)
            }
        }
        field(aweme, "author")?.let { author ->
            section(R.string.host_video_info_author) {
                addValue(R.string.host_video_info_nickname, text(author, "nickname"))
                addValue(R.string.host_video_info_uid, identifier(author, "uid"))
                addValue(R.string.host_video_info_account, identifier(author, "unique_id") ?: identifier(author, "uniqueId")
                    ?: identifier(author, "short_id") ?: identifier(author, "shortId"))
                addValue(R.string.host_video_info_sec_uid, identifier(author, "sec_uid") ?: identifier(author, "secUid"))
            }
            link(R.string.host_video_info_avatar, url(field(author, "avatar_thumb") ?: field(author, "avatarThumb")))
        }
        field(aweme, "statistics")?.let { stats ->
            section(R.string.host_video_info_statistics) {
                addValue(R.string.host_video_info_likes, number(stats, "digg_count"))
                addValue(R.string.host_video_info_comments, number(stats, "comment_count"))
                addValue(R.string.host_video_info_collects, number(stats, "collect_count") ?: number(stats, "collectCount"))
                addValue(R.string.host_video_info_shares, number(stats, "share_count"))
                // A zero default is not evidence that a video has never been played.
                addValue(R.string.host_video_info_plays, number(stats, "play_count")?.takeIf { it > 0 })
            }
        }
        if (video != null && images.isNullOrEmpty()) {
            val primary = field(video, "play_addr")
            val h265 = field(video, "play_addr_265")
            val play = if (url(primary) != null) primary else h265
            section(R.string.host_video_info_video) {
                val duration = number(video, "duration")?.takeIf { it > 0 }
                    ?: number(aweme, "duration")?.takeIf { it > 0 }
                addValue(R.string.host_video_info_duration, duration, VideoInfoFormat.MILLISECONDS)
                resolution(video)
                addValue(R.string.host_video_info_quality, text(video, "ratio"))
                addValue(R.string.host_video_info_size, number(play, "data_size")?.takeIf { it > 0 }, VideoInfoFormat.BYTES)
                addValue(R.string.host_video_info_watermark, field(video, "has_watermark") as? Boolean, VideoInfoFormat.FLAG)
            }
            link(R.string.host_video_info_play_url, url(play))
            link(R.string.host_video_info_download_url, url(field(video, "download_addr")))
            link(R.string.host_video_info_cover, url(field(video, "cover")))
        }
        field(aweme, "music")?.let { music ->
            section(R.string.host_video_info_music) {
                addValue(R.string.host_video_info_music_title, text(music, "title") ?: text(music, "musicName"))
                addValue(R.string.host_video_info_music_author, text(music, "author") ?: text(music, "authorName"))
                addValue(R.string.host_video_info_music_id, identifier(music, "id_str") ?: identifier(music, "mid") ?: identifier(music, "id"))
                addValue(R.string.host_video_info_duration, number(music, "duration")?.takeIf { it > 0 }, VideoInfoFormat.SECONDS)
            }
            link(R.string.host_video_info_music_url, url(field(music, "play_url") ?: field(music, "playUrl")))
        }
        link(R.string.host_video_info_share_url, httpUrl(text(aweme, "share_url") ?: text(aweme, "shareUrl")))
        images?.take(MAX_IMAGES)?.forEachIndexed { index, image ->
            checkCancelled()
            if (image != null) section(R.string.host_video_info_image, index + 1) {
                resolution(image)
                val original = url(field(image, "watermarkFreeDownloadUrlList"))
                    ?: url(field(image, "downloadUrlList")) ?: url(field(image, "urlList"))
                addValue(R.string.host_video_info_image_url, original)
                if (field(image, "video") != null) addValue(R.string.host_video_info_live_photo, true, VideoInfoFormat.FLAG)
            }
        }
        if (links.isNotEmpty()) sections += VideoInfoSection(R.string.host_video_info_links, links)
        return VideoFieldsMetadata(aid, sections.toList(), ((images?.size ?: 0) - MAX_IMAGES).coerceAtLeast(0))
    }

    private fun httpUrl(value: String?): String? {
        val url = value?.trim()?.let { if (it.startsWith("//")) "https:$it" else it } ?: return null
        if (url.length > 8192) return null
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf { parsed.scheme?.lowercase() in listOf("http", "https") && !parsed.host.isNullOrBlank() }
    }

    // Every instance field by its Java name and, where it has one, its Gson @SerializedName, found through the class and
    // its superclasses. The first declaration of a name wins.
    private fun indexFields(type: Class<*>): Map<String, Field> {
        val named = linkedMapOf<String, Field>()
        val serialized = linkedMapOf<String, Field>()
        var owner: Class<*>? = type
        while (owner != null && owner != Any::class.java) {
            for (field in owner.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                field.isAccessible = true
                named.putIfAbsent(field.name, field)
                val annotation = field.annotations.firstOrNull { it.annotationClass.java.name == SERIALIZED_NAME } ?: continue
                val value = annotation.asResolver().firstMethod { name = "value"; emptyParameters(); superclass(interfaces = true) }.invoke()
                (value as? String)?.let { serialized.putIfAbsent(it, field) }
            }
            owner = owner.superclass
        }
        return named + serialized
    }

    private const val SERIALIZED_NAME = "com.google.gson.annotations.SerializedName"
}

internal fun VideoFieldsMetadata.displaySections(
    string: (Int, Array<out Any>) -> String,
    locale: Locale,
    timeZone: TimeZone,
): List<VideoInfoDisplaySection> {
    fun text(id: Int) = string(id, emptyArray())
    return sections.map { section ->
        // An image title is a template: its index goes into the same lookup, since the string lookup always formats and
        // a bare lookup would throw on the unfilled placeholder.
        val title = section.imageIndex?.let { string(section.title, arrayOf(it)) } ?: text(section.title)
        VideoInfoDisplaySection(title, section.fields.map { field ->
            val value = when (field.format) {
                VideoInfoFormat.TEXT -> field.value
                VideoInfoFormat.TIME -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).apply { this.timeZone = timeZone }
                    .format(Date(field.value.toLong() * 1000))
                VideoInfoFormat.MILLISECONDS -> BigDecimal.valueOf(field.value.toLong(), 3).stripTrailingZeros().toPlainString() +
                    " " + text(R.string.host_video_info_seconds)
                VideoInfoFormat.SECONDS -> field.value + " " + text(R.string.host_video_info_seconds)
                VideoInfoFormat.BYTES -> {
                    val size = field.value.toLong()
                    when {
                        size >= 1024L * 1024 * 1024 -> String.format(locale, "%.2f GiB", size / (1024.0 * 1024 * 1024))
                        size >= 1024L * 1024 -> String.format(locale, "%.2f MiB", size / (1024.0 * 1024))
                        size >= 1024L -> String.format(locale, "%.2f KiB", size / 1024.0)
                        else -> "$size B"
                    }
                }
                VideoInfoFormat.FLAG -> text(if (field.value == "true") R.string.host_video_info_yes else R.string.host_video_info_no)
                VideoInfoFormat.KIND -> text(when (field.value) {
                    "images" -> R.string.host_video_info_kind_images
                    "video" -> R.string.host_video_info_kind_video
                    else -> R.string.host_video_info_kind_other
                })
            }
            VideoInfoDisplayField(text(field.label), value)
        })
    }
}

internal fun List<VideoInfoDisplaySection>.filterVideoInfo(query: String): List<VideoInfoDisplaySection> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    return mapNotNull { section ->
        if (section.title.contains(needle, true)) section else {
            section.fields.filter { it.label.contains(needle, true) || it.value.contains(needle, true) }
                .takeIf { it.isNotEmpty() }?.let { section.copy(fields = it) }
        }
    }
}

// Copy and export use all extracted fields, whatever the search filter shows.
internal fun List<VideoInfoDisplaySection>.videoInfoText(): String = joinToString("\n\n") { section ->
    "[${section.title}]\n" + section.fields.joinToString("\n") { "${it.label}: ${it.value}" }
}
