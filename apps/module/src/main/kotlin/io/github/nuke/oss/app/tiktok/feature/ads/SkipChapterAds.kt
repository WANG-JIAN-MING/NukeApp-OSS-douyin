package io.github.nuke.oss.app.tiktok.feature.ads

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.makeAccessible
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.Foreground
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.sdk.clockText
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Method

// A chapter that is an ad, with the time range it covers in milliseconds.
internal data class AdChapter(val index: Int, val startMs: Int, val endMs: Int)

// Finds the chapter the position is in and answers it only if the host marked it as an ad. A chapter ends where the
// next one starts; the last one ends with the video. indexShift is how far the host's ad indexes sit ahead of this
// list, which is one when the host counts a first chapter at time zero that the list here leaves out.
internal fun adChapterAt(starts: List<Int>, adIndexes: Set<Int>, durationMs: Int, positionMs: Long, indexShift: Int): AdChapter? {
    for (i in starts.indices) {
        val start = starts[i]
        val next = starts.getOrElse(i + 1) { start }
        val end = if (next != start) next else durationMs
        if (positionMs in start..end) {
            val index = i + indexShift
            return if (index in adIndexes) AdChapter(index, start, end) else null
        }
    }
    return null
}

// Seeks past the ad chapters of a video, once per chapter. Those are the chapters the video's own chapter data lists as
// ads, such as the "new phone?" breaks inside long videos.
@HookEntry
internal class SkipChapterAds(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val prompts: HostPrompts,
    private val foreground: Foreground,
    private val detailActivity: String = DETAIL_ACTIVITY,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(FeedQueries.CHAPTER_PROGRESS)
    private val getVideo = NamedCall("getVideo")
    private val getChapters = NamedCall("getMoreChapterList")
    private val chapterData = NamedField("chapterData")
    private val adIndexes = NamedField("adChapterIndexList")
    private val duration = NamedField("duration")
    private val awemeId = NamedField("aweme_id")
    private val timestamp = NamedField("timestamp")
    private val lock = Any()
    private val skipped = object : LinkedHashMap<String, MutableSet<Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<Int>>) = size > MAX_VIDEOS
    }

    override fun install(scope: FeatureScope) {
        prompts.install(scope)
        foreground.install(scope, ID)
        val progress = resolver.methods(hostClassLoader, FeedQueries.CHAPTER_PROGRESS).single()
        val types = progress.parameterTypes
        check(types.size == 7 && types[1] == Long::class.javaPrimitiveType && types[5] == Int::class.javaPrimitiveType) {
            "${progress.declaringClass.name}.${progress.name} does not take (video, long, ..., int, player)"
        }
        val seek = seekOf(types[6])
        progress.isAccessible = true
        scope.hooks.install(progress, HookSpec(id = "SkipAdChapter", before = { call ->
            val aweme = call.argument(0)
            val position = call.argument(1) as? Long
            val player = call.argument(6)
            if (aweme != null && position != null && player != null) skipIfAd(aweme, position, player, seek)
        }))
    }

    private fun skipIfAd(aweme: Any, position: Long, player: Any, seek: Method) {
        val videoId = awemeId.of(aweme)?.get(aweme) as? String ?: return
        val video = getVideo.of(aweme)?.invoke(aweme) ?: return
        val durationMs = duration.of(video)?.get(video) as? Int ?: return
        if (durationMs <= 0) return
        val data = chapterData.of(aweme)?.get(aweme) ?: return
        val ads = (adIndexes.of(data)?.get(data) as? List<*>)?.filterIsInstance<Int>()?.toSet() ?: return
        val chapters = getChapters.of(aweme)?.invoke(aweme) as? List<*> ?: return
        val starts = chapters.mapNotNull { chapter -> chapter?.let { timestamp.of(it)?.get(it) as? Int } }
        val kept = starts.filter { it != 0 }
        val shift = if (kept.size != starts.size && foreground.activityName() != detailActivity) 1 else 0
        val ad = adChapterAt(kept, ads, durationMs, position, shift) ?: return
        val first = synchronized(lock) { skipped.getOrPut(videoId) { HashSet() }.add(ad.index) }
        if (!first) return
        seek.invoke(player, (ad.endMs * 100.0 / durationMs).toFloat())
        prompts.toast(R.string.host_notice_chapter_ad, clockText(ad.startMs.toLong()), clockText(ad.endMs.toLong()))
    }

    // The player's seek takes a percentage of the video as a float.
    private fun seekOf(player: Class<*>): Method =
        player.resolve().optional(silent = true).firstMethodOrNull { name = "seek"; parameters(Float::class); superclass() }
            ?.self?.apply { makeAccessible() }
            ?: error("${player.name} has no seek(float)")

    companion object : TikTokFeature {
        const val ID = "SkipChapterAds"
        const val DETAIL_ACTIVITY = "com.ss.android.ugc.aweme.detail.ui.DetailActivity"
        private const val MAX_VIDEOS = 64

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(SkipChapterAds(resolver, hostClassLoader, prompts(ID), foreground()))
        }
    }
}
