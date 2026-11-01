package io.github.nuke.oss.app.tiktok.feature.feed

import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.activity.InfoField
import io.github.nuke.oss.app.shared.activity.InfoSection
import io.github.nuke.oss.app.shared.activity.InfoSheet
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.chat.threadRunner
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntries
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntry
import io.github.nuke.oss.app.tiktok.sdk.feed.VideoFieldsMetadata
import io.github.nuke.oss.app.tiktok.sdk.feed.VideoFieldsReader
import io.github.nuke.oss.app.tiktok.sdk.feed.VideoInfoDisplaySection
import io.github.nuke.oss.app.tiktok.sdk.feed.displaySections
import io.github.nuke.oss.app.tiktok.sdk.feed.filterVideoInfo
import io.github.nuke.oss.app.tiktok.sdk.feed.videoInfoText
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.util.Locale
import java.util.TimeZone

// Adds "post details" to the share panel. Choosing it closes the panel and opens NukeApp's details sheet with the post's
// author, time, engagement numbers, video or image parameters and resource links, read from the host's own objects
// without changing them. A row copies its value; everything extracted can be copied or saved to Downloads/Nuke, and the
// search only filters what is shown.
@HookEntry
internal class ShowVideoFields(
    private val entries: SharePanelEntries,
    private val files: () -> MediaFiles,
    private val prompts: HostPrompts,
    private val logger: Logger,
    private val runner: BackgroundRunner = threadRunner,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = SharePanelEntries.QUERIES

    override fun install(scope: FeatureScope) {
        prompts.install(scope)
        val media = files()
        entries.add(scope, ID, SharePanelEntry(ID, R.string.host_video_fields_entry, android.R.drawable.ic_menu_info_details) { activity, aweme, dismiss ->
            dismiss()
            val metadata = try {
                VideoFieldsReader.read(aweme)
            } catch (failure: RuntimeException) {
                logger.log("feature=$ID read_failed ${failure.javaClass.simpleName}")
                return@SharePanelEntry prompts.toast(R.string.host_video_fields_error, failure.javaClass.simpleName)
            }
            prompts.sheet({ string -> sheetOf(metadata, string) }) { text, done ->
                val name = "post_${metadata.aid.filter { it.isLetterOrDigit() }.ifEmpty { "video" }}_${System.currentTimeMillis()}.txt"
                runner.run(0) {
                    val (message, argument) = try {
                        R.string.host_video_fields_saved to media.saveText(activity, name, text)
                    } catch (failure: java.io.IOException) {
                        R.string.host_video_fields_error to failure.javaClass.simpleName
                    } catch (failure: IllegalStateException) {
                        R.string.host_video_fields_error to (failure.message ?: failure.javaClass.simpleName)
                    }
                    done(message, argument)
                }
            }
        })
    }

    companion object : TikTokFeature {
        const val ID = "ShowVideoFields"
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(ShowVideoFields(sharePanelEntries(), mediaFiles, prompts(ID), logger))
        }
    }
}

// The sheet of one post, with every label resolved from the module strings and times in the phone's time zone.
internal fun sheetOf(metadata: VideoFieldsMetadata, string: (Int, Array<out Any>) -> String): InfoSheet {
    fun text(id: Int) = string(id, emptyArray())
    val sections = metadata.displaySections(::text, Locale.getDefault(), TimeZone.getDefault())
    fun shared(list: List<VideoInfoDisplaySection>) = list.map { section -> InfoSection(section.title, section.fields.map { InfoField(it.label, it.value) }) }
    return InfoSheet(
        title = text(R.string.host_video_fields_title),
        sections = shared(sections),
        fullText = sections.videoInfoText(),
        search = { query -> shared(sections.filterVideoInfo(query)) },
        searchHint = text(R.string.host_video_fields_search),
        hint = text(R.string.host_video_fields_hint),
        empty = text(R.string.host_video_info_empty),
        noMatch = text(R.string.host_video_fields_no_match),
        footer = metadata.omittedImages.takeIf { it > 0 }?.let { string(R.string.host_video_info_image_limit, arrayOf(it)) },
    )
}
