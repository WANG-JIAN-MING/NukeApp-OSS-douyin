package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.feed.ShowVideoFields
import io.github.nuke.oss.app.tiktok.feature.feed.sheetOf
import io.github.nuke.oss.app.tiktok.sdk.feed.VideoFieldsReader
import io.github.nuke.oss.app.tiktok.sdk.feed.displaySections
import io.github.nuke.oss.app.tiktok.sdk.feed.filterVideoInfo
import io.github.nuke.oss.app.tiktok.sdk.feed.videoInfoText
import io.github.nuke.oss.core.Logger
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The post details: what is read from the host's objects, how it is shown, and the share panel entry that opens it.
class VideoFieldsTest : HookHarness() {
    class Url(@JvmField var url_list: List<String>?, @JvmField var data_size: Long = 0)
    class Video(
        @JvmField var duration: Long = 5000, @JvmField var width: Long = 1080, @JvmField var height: Long = 1920,
        @JvmField var ratio: String? = "720p", @JvmField var play_addr: Url? = Url(listOf("//cdn/play.mp4"), 2048),
        @JvmField var download_addr: Url? = Url(listOf("https://cdn/download.mp4")), @JvmField var has_watermark: Boolean = true,
    )
    class Author(@JvmField var nickname: String? = "Nick", @JvmField var uid: String? = "42", @JvmField var unique_id: String? = "nick42", @JvmField var sec_uid: String? = "sec")
    class Stats(@JvmField var digg_count: Long = 7, @JvmField var comment_count: Long = 3, @JvmField var share_count: Long = 0, @JvmField var play_count: Long = 0)
    class Image(@JvmField var width: Long = 100, @JvmField var height: Long = 200, @JvmField var urlList: List<String>? = listOf("https://img/1.jpg"))
    open class Base { @JvmField var desc: String? = "caption" }
    class Aweme(
        @JvmField var aweme_id: String? = "123", @JvmField var create_time: Long = 1_700_000_000, @JvmField var ip_attribution: String? = "广东",
        @JvmField var author: Author? = Author(), @JvmField var statistics: Stats? = Stats(), @JvmField var video: Video? = Video(),
        @JvmField var images: List<Any?>? = null, @JvmField var share_url: String? = "https://share/x",
    ) : Base()

    @Test fun `a video post is read into sections for the post, author, numbers, video and links`() {
        val metadata = VideoFieldsReader.read(Aweme())
        assertEquals("123", metadata.aid)
        assertEquals(
            listOf(R.string.host_video_info_basic, R.string.host_video_info_author, R.string.host_video_info_statistics,
                R.string.host_video_info_video, R.string.host_video_info_links),
            metadata.sections.map { it.title },
        )
        val basic = metadata.sections.first().fields.associate { it.label to it.value }
        assertEquals("video", basic[R.string.host_video_info_kind])
        assertEquals("caption", basic[R.string.host_video_info_caption])
        assertEquals("广东", basic[R.string.host_video_info_location])
        val stats = metadata.sections[2].fields.associate { it.label to it.value }
        assertEquals("7", stats[R.string.host_video_info_likes])
        // A zero count is a default, not a fact: zero shares are shown, zero plays are not.
        assertEquals("0", stats[R.string.host_video_info_shares])
        assertFalse(R.string.host_video_info_plays in stats)
        val links = metadata.sections.last().fields
        assertEquals(listOf("https://cdn/play.mp4", "https://cdn/download.mp4", "https://share/x"), links.map { it.value })
    }

    @Test fun `an image post lists its images and counts what was left out`() {
        val images = List(103) { Image() }
        val metadata = VideoFieldsReader.read(Aweme(video = null, images = images))
        assertEquals("images", metadata.sections.first().fields.first { it.label == R.string.host_video_info_kind }.value)
        assertEquals(100, metadata.sections.count { it.title == R.string.host_video_info_image })
        assertEquals(3, metadata.omittedImages)
        assertEquals(1, metadata.sections.first { it.title == R.string.host_video_info_image }.imageIndex)
    }

    @Test fun `an image post's details sheet fills in each image title with its own index`() {
        // Like Context.getString(id, args), every lookup is run through String.format, so a placeholder title looked up without its index throws.
        val templates = mapOf(R.string.host_video_info_image to "Image %1\$d")
        val formatted: (Int, Array<out Any>) -> String = { id, args -> String.format(templates[id] ?: "t$id", *args) }
        val sheet = sheetOf(VideoFieldsReader.read(Aweme(video = null, images = listOf(Image(), Image()))), formatted)
        assertEquals(listOf("Image 1", "Image 2"), sheet.sections.map { it.title }.filter { it.startsWith("Image ") })
    }

    @Test fun `unusable values are left out, not shown as zero`() {
        val metadata = VideoFieldsReader.read(Aweme(aweme_id = "0", create_time = -1, ip_attribution = " ", author = null, statistics = null, video = null, share_url = "javascript:x"))
        assertTrue(metadata.sections.none { it.title == R.string.host_video_info_author || it.title == R.string.host_video_info_links })
        assertEquals("", metadata.aid)
        assertNull(VideoFieldsReader.read(Any()).sections.firstOrNull())
    }

    @Test fun `values are formatted in the given locale and time zone and the filter only narrows what is shown`() {
        val metadata = VideoFieldsReader.read(Aweme())
        val sections = metadata.displaySections({ id, _ -> "t$id" }, Locale.ENGLISH, TimeZone.getTimeZone("UTC"))
        val all = sections.flatMap { it.fields }.associate { it.label to it.value }
        assertEquals("2023-11-14 22:13:20", all["t${R.string.host_video_info_created}"])
        assertEquals("5 t${R.string.host_video_info_seconds}", all["t${R.string.host_video_info_duration}"])
        assertEquals("2.00 KiB", all["t${R.string.host_video_info_size}"])
        assertEquals("t${R.string.host_video_info_yes}", all["t${R.string.host_video_info_watermark}"])
        assertEquals("t${R.string.host_video_info_kind_video}", all["t${R.string.host_video_info_kind}"])
        assertEquals(sections, sections.filterVideoInfo("  "))
        assertEquals(1, sections.filterVideoInfo("Nick").size)
        assertTrue(sections.filterVideoInfo("no such text").isEmpty())
        assertTrue(sections.videoInfoText().startsWith("[t${R.string.host_video_info_basic}]\n"))
    }

    // ---- the entry
    private val entries = RecordingSharePanelEntries()
    private val files = RecordingMediaFiles()
    private val prompts = RecordingPrompts()
    private val inline = io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner { _, task -> task() }
    private val string: (Int, Array<out Any>) -> String = { id, args -> "t$id" + args.joinToString("") { ":$it" } }

    private fun feature() = ShowVideoFields(entries, { files }, prompts, Logger {}, inline)

    @Test fun `the feature adds one entry that closes the panel and opens the details sheet`() {
        enable(feature())
        val entry = entries.added.single()
        assertEquals(ShowVideoFields.ID, entry.key)
        var dismissed = 0
        entry.onClick("activity", Aweme(), { dismissed++ })
        assertEquals(1, dismissed)
        val sheet = prompts.sheets.single().content(string)
        assertEquals("t${R.string.host_video_fields_title}", sheet.title)
        assertEquals("123", sheet.sections.first().fields.first().value)
        assertTrue(sheet.fullText.startsWith("[t${R.string.host_video_info_basic}]\n"))
        assertTrue(sheet.search("no such text").isEmpty())
    }

    @Test fun `saving writes the text to a file and reports where it went or why it failed`() {
        enable(feature())
        entries.added.single().onClick("activity", Aweme(), {})
        val save = prompts.sheets.single().onSave
        val results = ArrayList<Pair<Int, Any>>()
        save("body") { message, argument -> results += message to argument }
        assertEquals("body", files.texts.single().second)
        assertTrue(files.texts.single().first.startsWith("post_123_"))
        assertEquals(R.string.host_video_fields_saved, results.single().first)
        files.failure = IllegalStateException("No documents folder")
        files.textFailure = true
        save("body") { message, argument -> results += message to argument }
        assertEquals(R.string.host_video_fields_error to "No documents folder", results.last())
    }

    @Test fun `a post with nothing known still opens an empty details sheet`() {
        enable(feature())
        entries.added.single().onClick("activity", Any(), {})
        assertTrue(prompts.sheets.single().content(string).sections.isEmpty())
    }
}
