package io.github.nuke.oss.app.tiktok.feature.feed

import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.fields
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.chat.threadRunner
import io.github.nuke.oss.app.tiktok.query.DownloadQueries
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles
import io.github.nuke.oss.app.tiktok.sdk.media.mediaUrls
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// Adds a save entry to the long-press menu of voice comments (audio) or sticker comments (emoji). Both ride on the
// host's "save image" menu item: it is shown for a comment that has something to save, its white-list gains
// save_image, and its click saves the media instead of the host's image save. A comment with nothing for this kind is
// left to the host and to the other kind. One save per media address runs at a time.
@HookEntry
internal class SaveCommentMedia(
    override val id: String,
    private val audio: Boolean,
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val files: () -> MediaFiles,
    private val prompts: HostPrompts,
    private val logger: Logger,
    private val runner: BackgroundRunner = threadRunner,
) : RuntimeFeature, UsesDexQueries {
    override val dexQueries = listOf(
        DownloadQueries.SAVE_VISIBLE, DownloadQueries.SAVE_CLICK, DownloadQueries.COMMENT_WHITE_LIST,
        DownloadQueries.SAVE_PARAMS, DownloadQueries.PARAMS_COMMENT, DownloadQueries.COMMENT_AUDIO_CONTENT,
    )
    private val audioField = NamedField("commentAudio")
    private val emojiField = NamedField("emoji")
    private val animateUrl = NamedField("animateUrl")
    private val staticUrl = NamedField("staticUrl")
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val active = AtomicBoolean()

    override fun install(scope: FeatureScope) {
        prompts.install(scope)
        val (visible, click, whiteList) = resolver.methods(hostClassLoader, DownloadQueries.SAVE_VISIBLE, DownloadQueries.SAVE_CLICK, DownloadQueries.COMMENT_WHITE_LIST)
        val (paramsField, commentField, contentField) = resolver.fields(hostClassLoader, DownloadQueries.SAVE_PARAMS, DownloadQueries.PARAMS_COMMENT, DownloadQueries.COMMENT_AUDIO_CONTENT)
        listOf(visible, click, whiteList).forEach { it.isAccessible = true }
        listOf(paramsField, commentField, contentField).forEach { it.isAccessible = true }
        val media = files()
        active.set(true)
        scope.own(AutoCloseable { active.set(false) })
        fun commentOf(item: Any) = paramsField.get(item)?.let(commentField::get)
        scope.hooks.install(visible, HookSpec(id = "Visible", requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE), after = { call ->
            val comment = call.receiver?.let(::commentOf) ?: return@HookSpec
            if (call.outcome is CallOutcome.Returned && urls(comment, contentField).isNotEmpty()) call.returnValue(true)
        }))
        scope.hooks.install(whiteList, HookSpec(id = "WhiteList", requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE), after = { call ->
            val comment = call.argument(0)?.let(commentField::get) ?: return@HookSpec
            val original = (call.outcome as? CallOutcome.Returned)?.value as? Set<*> ?: return@HookSpec
            if (urls(comment, contentField).isNotEmpty()) call.returnValue(LinkedHashSet(original).apply { add("save_image") })
        }))
        scope.hooks.install(click, HookSpec(id = "Click", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
            val item = call.receiver ?: return@HookSpec
            val addresses = commentOf(item)?.let { urls(it, contentField) }.orEmpty()
            if (addresses.isEmpty()) return@HookSpec
            call.returnEarly(null)
            val activity = item.callByName("getActivity")
            if (activity != null && media.requestStoragePermission(activity)) return@HookSpec prompts.toast(R.string.host_notice_media_permission)
            save(media, activity, addresses)
        }))
    }

    private fun save(media: MediaFiles, activity: Any?, addresses: List<String>) {
        val context = activity ?: return prompts.toast(R.string.host_notice_media_failed, "no activity")
        val key = addresses.first()
        if (!pending.add(key)) return
        prompts.toast(R.string.host_notice_media_saving)
        runner.run(0) {
            try {
                media.save(context, addresses, audio)
                logger.log("feature=$id saved")
                prompts.toast(R.string.host_notice_media_saved)
            } catch (failure: java.io.IOException) {
                fail(failure)
            } catch (failure: IllegalStateException) {
                fail(failure)
            } catch (failure: IllegalArgumentException) {
                fail(failure)
            } finally {
                pending.remove(key)
            }
        }
    }

    private fun fail(failure: Exception) {
        logger.log("feature=$id save_failed ${failure.javaClass.simpleName}")
        prompts.toast(R.string.host_notice_media_failed, (failure.message ?: failure.javaClass.simpleName).take(REASON_LENGTH))
    }

    // The addresses this kind can save for the comment, best first; empty when it has nothing for this kind.
    private fun urls(comment: Any, content: java.lang.reflect.Field): List<String> {
        if (audio) {
            val struct = audioField.of(comment)?.get(comment) ?: return emptyList()
            val json = MiniJson.obj(MiniJson.parse(content.get(struct) as? String ?: return emptyList())) ?: return emptyList()
            val first = MiniJson.obj(MiniJson.array(json["video_list"])?.firstOrNull()) ?: return emptyList()
            return mediaUrls(listOf(first["main_url"], first["backup_url"]).filterIsInstance<String>())
        }
        val emoji = emojiField.of(comment)?.get(comment) ?: return emptyList()
        fun list(field: NamedField) = field.of(emoji)?.get(emoji)?.let { (it.callByName("getUrlList") as? List<*>)?.filterIsInstance<String>() }.orEmpty()
        return mediaUrls(list(animateUrl)).ifEmpty { mediaUrls(list(staticUrl)) }
    }

    companion object : TikTokFeature {
        const val AUDIO_ID = "SaveCommentAudio"
        const val EMOJI_ID = "SaveCommentEmoji"
        private const val REASON_LENGTH = 200

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(
                SaveCommentMedia(AUDIO_ID, true, resolver, hostClassLoader, mediaFiles, prompts(AUDIO_ID), logger),
                SaveCommentMedia(EMOJI_ID, false, resolver, hostClassLoader, mediaFiles, prompts(EMOJI_ID), logger),
            )
        }
    }
}

// Lets the "Listen to Douyin" feed through: the local content filter always accepts, and whether a video is available
// there is still the host's decision on the server.
@HookEntry
internal class BypassListenFilter(private val resolver: DexResolver, private val hostClassLoader: ClassLoader) : RuntimeFeature, UsesDexQueries {
    override val id = "BypassListenFilter"
    override val dexQueries = listOf(DownloadQueries.LISTEN_FILTER)

    override fun install(scope: FeatureScope) {
        val filter = resolver.methods(hostClassLoader, DownloadQueries.LISTEN_FILTER).single().apply { isAccessible = true }
        scope.hooks.install(filter, HookSpec(id = "Accept", requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE), after = { call ->
            if (call.outcome is CallOutcome.Returned) call.returnValue(true)
        }))
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) { listOf(BypassListenFilter(resolver, hostClassLoader)) }
    }
}
