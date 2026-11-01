package io.github.nuke.oss.app.tiktok.feature.feed

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methodLists
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedGetter
import io.github.nuke.oss.app.shared.reflect.SerializedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.LinesOption
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

// What the rules look at in one video. A value the model did not have reads as null or false, so a rule never matches
// on a field the host build does not carry.
internal data class VideoFacts(
    val awemeType: Int? = null,
    val isAd: Boolean = false,
    val aiGenerated: Boolean = false,
    val story: Boolean = false,
    val article: Boolean = false,
    val externalVideoType: Int? = null,
    val livePhoto: Boolean = false,
    val panorama: Boolean = false,
    val kind: String? = null,
    val description: String = "",
    val createdAtSeconds: Long = 0,
    val likes: Long? = null,
    val comments: Long? = null,
    val collects: Long? = null,
    val shares: Long? = null,
    val durationMillis: Long? = null,
)

// The rules for dropping videos from a feed, driven by the feature's options. Zero turns a numeric rule off.
internal class FeedFilter(options: OptionValues, private val now: () -> Long) {
    private val toggles = TOGGLES.associateWith(options::toggle)
    private val min = METRICS.associateWith { options.number("min-$it") }
    private val max = METRICS.associateWith { options.number("max-$it") }
    private val titleContains = options.lines(TITLE_CONTAINS)
    private val titleMissing = options.lines(TITLE_MISSING)
    private val olderThanDays = options.number(OLDER_THAN_DAYS)
    private val newerThanDays = options.number(NEWER_THAN_DAYS)

    val active: Boolean = toggles.values.any { it } || min.values.any { it > 0 } || max.values.any { it > 0 } ||
        titleContains.isNotEmpty() || titleMissing.isNotEmpty() || olderThanDays > 0 || newerThanDays > 0

    // Text and note posts are exempt from the picture rules, so those rules (and the text rule) need the kind.
    val needsKind: Boolean = toggles.getValue("images") || toggles.getValue("live-photos") || toggles.getValue("text-posts")

    fun excludes(facts: VideoFacts): Boolean {
        val type = facts.awemeType
        val textual = facts.kind == "文字" || facts.kind == "笔记"
        return when {
            on("ads") && facts.isAd -> true
            on("ai") && facts.aiGenerated -> true
            on("live") && type == LIVE_TYPE -> true
            on("plain-videos") && (type == 0 || type == 4) -> true
            on("live-photos") && !textual && facts.livePhoto -> true
            on("images") && !textual && (type == 2 || type == 68) && !facts.livePhoto -> true
            on("music-promo") && (type == 117 || type == 155) -> true
            on("panorama") && facts.panorama -> true
            on("xigua") && (facts.externalVideoType == 1 || type == 107) -> true
            outOfRange(facts) -> true
            on("story") && facts.story -> true
            titleRule(facts.description) -> true
            on("articles") && facts.article -> true
            on("text-posts") && facts.kind == "文字" -> true
            tooOldOrTooNew(facts.createdAtSeconds) -> true
            else -> false
        }
    }

    // Removes the excluded videos from the host's own list. The host never gets a list of one emptied: filtering stops
    // when a single video is left.
    fun filter(items: MutableList<Any?>, factsOf: (Any) -> VideoFacts) {
        for (item in ArrayList(items)) {
            if (item == null) continue
            if (items.size == 1) return
            if (excludes(factsOf(item))) items.remove(item)
        }
    }

    private fun on(name: String) = toggles.getValue(name)

    // Counts and length only apply when the model has both the video and its statistics.
    private fun outOfRange(facts: VideoFacts): Boolean {
        val values = mapOf(
            "likes" to facts.likes, "comments" to facts.comments, "collects" to facts.collects,
            "shares" to facts.shares, "seconds" to facts.durationMillis?.let { it / 1000 },
        )
        if (facts.likes == null || facts.comments == null || facts.collects == null || facts.shares == null || facts.durationMillis == null) return false
        return METRICS.any { metric ->
            val value = values.getValue(metric) ?: return@any false
            (min.getValue(metric) > 0 && value < min.getValue(metric)) || (max.getValue(metric) > 0 && value > max.getValue(metric))
        }
    }

    private fun titleRule(description: String): Boolean {
        val title = description.replace("\n", "")
        return titleContains.any { title.contains(it) } || titleMissing.any { !title.contains(it) }
    }

    private fun tooOldOrTooNew(createdAtSeconds: Long): Boolean {
        if (createdAtSeconds == 0L) return false
        val created = createdAtSeconds * 1000
        val day = 24L * 60 * 60 * 1000
        return (olderThanDays > 0 && created < now() - olderThanDays * day) || (newerThanDays > 0 && created > now() - newerThanDays * day)
    }

    companion object {
        const val LIVE_TYPE = 101
        val TOGGLES = listOf(
            "ads", "ai", "live", "plain-videos", "images", "live-photos", "music-promo", "panorama", "xigua", "story", "articles", "text-posts",
        )
        val METRICS = listOf("likes", "comments", "collects", "shares", "seconds")
        const val TITLE_CONTAINS = "title-contains"
        const val TITLE_MISSING = "title-missing"
        const val OLDER_THAN_DAYS = "older-than-days"
        const val NEWER_THAN_DAYS = "newer-than-days"
        private const val COUNT_LIMIT = 2_000_000_000L

        val OPTIONS = TOGGLES.map { ToggleOption(it, default = it == "ads") } +
            METRICS.flatMap { listOf(NumberOption("min-$it", 0, 0, COUNT_LIMIT), NumberOption("max-$it", 0, 0, COUNT_LIMIT)) } +
            listOf(
                LinesOption(TITLE_CONTAINS), LinesOption(TITLE_MISSING),
                NumberOption(OLDER_THAN_DAYS, 0, 0, 36_500), NumberOption(NEWER_THAN_DAYS, 0, 0, 36_500),
            )
    }
}

// Reads the model fields by the names the host serializes them under (the SerializedName of the field, whatever the
// field is called in the build). A missing field, an exception from a getter or a
// null along the way reads as null.
internal class VideoReader(private val kindOf: (Any) -> String?) {
    private val fields = ConcurrentHashMap<String, SerializedField>()
    private val getters = ConcurrentHashMap<String, NamedGetter>()

    private fun field(owner: Any?, name: String): Any? {
        if (owner == null) return null
        return runCatching { fields.getOrPut(name) { SerializedField(name) }.of(owner)?.get(owner) }.getOrNull()
    }

    private fun call(owner: Any?, name: String): Any? {
        if (owner == null) return null
        return runCatching { getters.getOrPut(name) { NamedGetter(name) }.of(owner)?.invoke(owner) }.getOrNull()
    }

    private fun number(value: Any?): Long? = (value as? Number)?.toLong()

    fun facts(aweme: Any): VideoFacts {
        val type = number(field(aweme, "aweme_type"))?.toInt()
        val author = field(aweme, "author")
        val status = field(aweme, "status")
        val video = call(aweme, "getVideo")
        val stats = call(aweme, "getStatistics")
        val adInfo = field(aweme, "commerce_info")
        val story = field(aweme, "story_interactive_label") as? String
        val risk = field(field(aweme, "risk_infos"), "content") as? String
        val kind = kindOf(aweme)
        return VideoFacts(
            awemeType = type,
            isAd = type == 9001 || field(aweme, "is_ads") == true || call(aweme, "getAd") == true || call(aweme, "getAwemeRawAd") != null ||
                field(status, "with_fusion_goods") == true || field(status, "with_goods") == true ||
                field(adInfo, "is_ad") == true || author == null || field(author, "is_ad_fake") == true,
            aiGenerated = risk in AI_LABELS,
            story = story == "限时日常" || story == "日常",
            article = field(aweme, "article_info") != null,
            externalVideoType = number(field(aweme, "external_video_type"))?.toInt(),
            livePhoto = number(field(aweme, "is_live_photo")) == 1L,
            panorama = type == 135 && field(video, "format") == "vr",
            kind = kind,
            description = field(aweme, "desc") as? String ?: "",
            createdAtSeconds = number(field(aweme, "create_time")) ?: 0,
            likes = number(field(stats, "digg_count")),
            comments = number(field(stats, "comment_count")),
            collects = number(field(stats, "collect_count")),
            shares = number(field(stats, "share_count")),
            durationMillis = number(field(video, "duration")),
        )
    }

    private companion object {
        val AI_LABELS = setOf("作者声明：内容由 AI 生成", "疑似使用了 AI 生成技术，请谨慎甄别", "作品含 AI 生成内容")
    }
}

// Drops videos from the lists the home feed panel receives on refresh and on load-more, before they are shown. The
// incoming list is copied, filtered and handed on in its place: the host's own list may not be modifiable.
@HookEntry
internal class FilterFeedVideos(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    now: () -> Long = System::currentTimeMillis,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(FeedQueries.FEED_RESULTS, FeedQueries.AWEME_KIND)
    private val filter = FeedFilter(options, now)

    override fun install(scope: FeatureScope) {
        if (!filter.active) return
        val receivers = resolver.methodLists(hostClassLoader, FeedQueries.FEED_RESULTS).single()
        for (receiver in receivers) {
            check(receiver.parameterTypes.firstOrNull() == List::class.java) { "${receiver.declaringClass.name}.${receiver.name} does not take the video list first" }
        }
        val reader = VideoReader(if (filter.needsKind) kindReader() else { _ -> null })
        for (receiver in receivers) {
            receiver.isAccessible = true
            scope.hooks.install(receiver, HookSpec(id = "FilterVideos:${receiver.name}", before = { call ->
                val items = call.argument(0) as? List<*>
                // A single video is never filtered: the feed would be left with nothing to show.
                if (items != null && items.size != 1) {
                    val kept = ArrayList<Any?>(items)
                    filter.filter(kept, reader::facts)
                    if (kept.size != items.size) call.setArgument(0, kept)
                }
            }))
        }
    }

    private fun kindReader(): (Any) -> String? {
        val kind: Method = resolver.methods(hostClassLoader, FeedQueries.AWEME_KIND).single()
        check(Modifier.isStatic(kind.modifiers) && kind.parameterCount == 1 && kind.returnType == String::class.java) {
            "${kind.declaringClass.name}.${kind.name} is not a static (video) -> String helper"
        }
        kind.isAccessible = true
        return { aweme -> runCatching { kind.invoke(null, aweme) as? String }.getOrNull() }
    }

    companion object : TikTokFeature {
        const val ID = "FilterFeedVideos"
        val OPTIONS = FeedFilter.OPTIONS

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(FilterFeedVideos(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
