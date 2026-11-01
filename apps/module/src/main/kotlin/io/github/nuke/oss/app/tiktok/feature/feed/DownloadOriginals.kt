package io.github.nuke.oss.app.tiktok.feature.feed

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.fields
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.DownloadQueries
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles
import io.github.nuke.oss.app.tiktok.sdk.media.mediaPaths
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.io.File

// Downloads a video from its playable address instead of the download address, which carries the watermark.
@HookEntry
internal class DownloadOriginalVideo(private val resolver: DexResolver, private val hostClassLoader: ClassLoader) : RuntimeFeature, UsesDexQueries {
    override val id = "DownloadOriginalVideo"
    override val dexQueries = listOf(DownloadQueries.VIDEO_ADDRESS, DownloadQueries.VIDEO_PLAY)
    private val getVideo = NamedCall("getVideo")

    override fun install(scope: FeatureScope) {
        val (address, play) = resolver.methods(hostClassLoader, DownloadQueries.VIDEO_ADDRESS, DownloadQueries.VIDEO_PLAY)
        address.isAccessible = true
        play.isAccessible = true
        scope.hooks.install(address, HookSpec(id = "PlayAddress", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
            val aweme = call.argument(0) ?: return@HookSpec
            val video = getVideo.of(aweme)?.invoke(aweme) ?: return@HookSpec
            play.invoke(video)?.let(call::returnEarly)
        }))
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) { listOf(DownloadOriginalVideo(resolver, hostClassLoader)) }
    }
}

// Saves image sets and live photos without the watermark: each image's download list becomes its plain list, its video
// uses the playable address with the watermark flags cleared, the local watermark switches read as off, and the files
// the host writes are converted from VVIC to PNG before downstream steps see them.
@HookEntry
internal class DownloadOriginalImages(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val files: () -> MediaFiles,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = "DownloadOriginalImages"
    override val dexQueries = listOf(
        DownloadQueries.IMAGE_DOWNLOAD, DownloadQueries.VIDEO_PLAY, DownloadQueries.IMAGE_EXECUTOR,
        DownloadQueries.LIVE_EXECUTOR, DownloadQueries.TASK_PATHS, DownloadQueries.LOCAL_MASK,
        DownloadQueries.LIVE_MASK, DownloadQueries.STORY_SINGLE, DownloadQueries.STORY_MULTI,
        DownloadQueries.DOWNLOAD_AWEME, DownloadQueries.VIDEO_DOWNLOAD, DownloadQueries.VIDEO_WATERMARK,
        DownloadQueries.VIDEO_SUFFIX_WATERMARK,
    )
    private val isMultiImage = NamedCall("isMultiImage")
    private val images = NamedField("images")
    private val urlList = NamedField("urlList")
    private val downloadUrlList = NamedField("downloadUrlList")
    private val video = NamedField("video")

    override fun install(scope: FeatureScope) {
        val methods = resolver.methods(
            hostClassLoader, DownloadQueries.IMAGE_DOWNLOAD, DownloadQueries.VIDEO_PLAY, DownloadQueries.IMAGE_EXECUTOR, DownloadQueries.LIVE_EXECUTOR,
            DownloadQueries.TASK_PATHS, DownloadQueries.LOCAL_MASK, DownloadQueries.LIVE_MASK, DownloadQueries.STORY_SINGLE, DownloadQueries.STORY_MULTI,
        )
        val imageDownload = methods[0]
        val play = methods[1]
        val taskPaths = methods[4]
        methods.forEach { it.isAccessible = true }
        val (awemeField, download, watermark, suffix) = resolver.fields(
            hostClassLoader, DownloadQueries.DOWNLOAD_AWEME, DownloadQueries.VIDEO_DOWNLOAD, DownloadQueries.VIDEO_WATERMARK, DownloadQueries.VIDEO_SUFFIX_WATERMARK,
        )
        listOf(awemeField, download, watermark, suffix).forEach { it.isAccessible = true }
        val media = files()
        scope.hooks.install(imageDownload, HookSpec(id = "ImageSets", before = { call ->
            val aweme = call.receiver?.let(awemeField::get) ?: return@HookSpec
            if (isMultiImage.of(aweme)?.invoke(aweme) != true) return@HookSpec
            for (image in images.of(aweme)?.get(aweme) as? List<*> ?: return@HookSpec) {
                image ?: continue
                val urls = urlList.of(image)?.get(image) as? List<*>
                if (!urls.isNullOrEmpty()) downloadUrlList.of(image)?.set(image, urls)
                val clip = video.of(image)?.get(image) ?: continue
                val address = play.invoke(clip) ?: continue
                download.set(clip, address)
                watermark.setBoolean(clip, false)
                suffix.setBoolean(clip, false)
            }
        }))
        for ((index, executor) in listOf(methods[2], methods[3]).withIndex()) {
            scope.hooks.install(executor, HookSpec(id = "ConvertTask$index", before = { call ->
                val task = call.argument(0) ?: return@HookSpec
                convert(media, taskPaths.invoke(task))
            }))
        }
        for ((index, mask) in listOf(methods[5], methods[6]).withIndex()) {
            scope.hooks.install(mask, HookSpec(id = "NoMask$index", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
                call.returnEarly(false)
            }))
        }
        for ((index, composer) in listOf(methods[7], methods[8]).withIndex()) {
            scope.hooks.install(composer, HookSpec(id = "ConvertStory$index", before = { call -> convert(media, call.argument(2)) }))
        }
    }

    // A conversion that fails leaves the original file in place and is logged; the save carries on.
    private fun convert(media: MediaFiles, paths: Any?) {
        for (path in mediaPaths(paths)) {
            val file = File(path)
            if (!file.isFile) continue
            try {
                media.convertVvic(file)
            } catch (failure: java.io.IOException) {
                logger.log("feature=$id convert_failed ${failure.javaClass.simpleName}")
            } catch (failure: IllegalStateException) {
                logger.log("feature=$id convert_failed ${failure.message}")
            } catch (failure: IllegalArgumentException) {
                logger.log("feature=$id convert_failed ${failure.message}")
            }
        }
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) { listOf(DownloadOriginalImages(resolver, hostClassLoader, mediaFiles, logger)) }
    }
}

// Saves a comment image from its original address instead of the watermarked download address.
@HookEntry
internal class SaveCommentOriginalImage(private val resolver: DexResolver, private val hostClassLoader: ClassLoader) : RuntimeFeature, UsesDexQueries {
    override val id = "CommentOriginalImage"
    override val dexQueries = listOf(DownloadQueries.COMMENT_IMAGE_DOWNLOAD, DownloadQueries.COMMENT_IMAGE_ORIGIN)

    override fun install(scope: FeatureScope) {
        val getter = resolver.methods(hostClassLoader, DownloadQueries.COMMENT_IMAGE_DOWNLOAD).single()
        val origin = resolver.fields(hostClassLoader, DownloadQueries.COMMENT_IMAGE_ORIGIN).single()
        getter.isAccessible = true
        origin.isAccessible = true
        scope.hooks.install(getter, HookSpec(id = "OriginAddress", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
            call.receiver?.let(origin::get)?.let(call::returnEarly)
        }))
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) { listOf(SaveCommentOriginalImage(resolver, hostClassLoader)) }
    }
}
