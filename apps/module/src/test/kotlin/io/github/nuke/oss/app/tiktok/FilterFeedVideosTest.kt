package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.feed.*
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import org.junit.Assert.*
import org.junit.Test

// Contract tests for the feed filter: the rules on their own, the model reading, and the hook that applies them.
class FilterFeedVideosTest : HookHarness() {
    class Video(@JvmField val duration: Int = 30_000, @JvmField val format: String? = null)
    class Stats(@JvmField val digg_count: Long = 100, @JvmField val comment_count: Long = 10, @JvmField val collect_count: Long = 5, @JvmField val share_count: Long = 3)
    class Author(@JvmField val is_ad_fake: Boolean = false)
    class Status(@JvmField val with_fusion_goods: Boolean = false, @JvmField val with_goods: Boolean? = false)
    class Commerce(@JvmField val is_ad: Boolean)
    class Risk(@JvmField val content: String?)

    class Aweme(
        @JvmField val aweme_id: String = "id",
        @JvmField val aweme_type: Int = 0,
        @JvmField val is_ads: Boolean = false,
        @JvmField val author: Any? = Author(),
        @JvmField val status: Any? = Status(),
        @JvmField val commerce_info: Any? = null,
        @JvmField val risk_infos: Any? = null,
        @JvmField val desc: String? = "hello",
        @JvmField val create_time: Long = NOW_SECONDS - 10 * DAY_SECONDS,
        @JvmField val story_interactive_label: String? = null,
        @JvmField val article_info: Any? = null,
        @JvmField val external_video_type: Int = 0,
        @JvmField val is_live_photo: Int = 0,
        private val video: Video? = Video(),
        private val stats: Stats? = Stats(),
        private val rawAd: Any? = null,
    ) {
        fun getVideo() = video
        fun getStatistics() = stats
        fun getAwemeRawAd() = rawAd
    }

    // The home feed panel: lists arrive on refresh and on load-more, and the host may hand over a read-only list.
    // The shape in Douyin 40.7.0: camelCase or obfuscated field names, the serialized name only in the annotation.
    class HostStats(@JvmField @com.google.gson.annotations.SerializedName("digg_count") val a: Long, @JvmField @com.google.gson.annotations.SerializedName("comment_count") val b: Long,
        @JvmField @com.google.gson.annotations.SerializedName("collect_count") val collectCount: Long, @JvmField @com.google.gson.annotations.SerializedName("share_count") val c: Long)
    class HostAweme(
        @JvmField @com.google.gson.annotations.SerializedName("aweme_type") val awemeType: Int,
        @JvmField @com.google.gson.annotations.SerializedName("create_time") val createTime: Long,
        @JvmField @com.google.gson.annotations.SerializedName("is_live_photo") val isLivePhoto: Int,
        @JvmField @com.google.gson.annotations.SerializedName("desc") val text: String,
        @JvmField val author: Any? = Author(),
        private val ad: Boolean = false,
        private val stats: HostStats? = HostStats(100, 10, 5, 3),
    ) {
        fun getAd() = ad
        fun getStatistics() = stats
    }

    class Panel {
        val received = ArrayList<List<Any?>>()
        fun refresh(items: List<Any?>, fromPull: Boolean) { received += items.toList() }
        fun loadMore(items: List<Any?>, fromPull: Boolean) { received += items.toList() }
    }

    class WrongReceiver { fun refresh(count: Int, items: List<Any?>) {} }

    object KindHelper {
        @JvmStatic fun kind(aweme: Any?): String? = (aweme as? Aweme)?.desc?.takeIf { it in setOf("文字", "笔记") }
    }

    class InstanceKind { fun kind(aweme: Any?): String? = null }

    private fun filter(vararg overrides: Pair<String, Any>) = FeedFilter(
        values(FilterFeedVideos.ID, FilterFeedVideos.OPTIONS, "ads" to false, *overrides), { NOW_SECONDS * 1000 },
    )

    private fun facts(aweme: Aweme, kind: String? = null) = VideoReader { kind }.facts(aweme)

    private val refresh = method(Panel::class.java, "refresh", List::class.java, Boolean::class.javaPrimitiveType!!)
    private val loadMore = method(Panel::class.java, "loadMore", List::class.java, Boolean::class.javaPrimitiveType!!)
    private fun receivers() = listOf(refresh, loadMore)

    private fun feature(vararg overrides: Pair<String, Any>, kindHelper: java.lang.reflect.Method? = null): FilterFeedVideos {
        val located = mutableListOf(FeedQueries.FEED_RESULTS to receivers())
        if (kindHelper != null) located += FeedQueries.AWEME_KIND to listOf(kindHelper)
        return FilterFeedVideos(
            resolver(*located.toTypedArray()), loader,
            values(FilterFeedVideos.ID, FilterFeedVideos.OPTIONS, *overrides), { NOW_SECONDS * 1000 },
        )
    }

    @Test fun `every ad signal marks a video as an ad`() {
        val rules = filter("ads" to true)
        val ads = listOf(
            Aweme(aweme_type = 9001), Aweme(is_ads = true), Aweme(rawAd = Any()), Aweme(status = Status(with_fusion_goods = true)),
            Aweme(status = Status(with_goods = true)), Aweme(commerce_info = Commerce(true)), Aweme(author = null),
            Aweme(author = Author(is_ad_fake = true)),
        )
        for (ad in ads) assertTrue(rules.excludes(facts(ad)))
        assertFalse(rules.excludes(facts(Aweme(commerce_info = Commerce(false)))))
        assertFalse(rules.excludes(facts(Aweme())))
    }

    @Test fun `ads are the only rule on by default`() {
        val defaults = FeedFilter(values(FilterFeedVideos.ID, FilterFeedVideos.OPTIONS), { NOW_SECONDS * 1000 })
        assertTrue(defaults.active)
        assertTrue(defaults.excludes(facts(Aweme(is_ads = true))))
        assertFalse(defaults.excludes(facts(Aweme(aweme_type = 101))))
        assertFalse(filter().active)
    }

    @Test fun `type rules match the host type numbers`() {
        assertTrue(filter("live" to true).excludes(facts(Aweme(aweme_type = 101))))
        assertFalse(filter("live" to true).excludes(facts(Aweme(aweme_type = 0))))
        assertTrue(filter("plain-videos" to true).excludes(facts(Aweme(aweme_type = 4))))
        assertTrue(filter("music-promo" to true).excludes(facts(Aweme(aweme_type = 155))))
        assertTrue(filter("xigua" to true).excludes(facts(Aweme(external_video_type = 1))))
        assertTrue(filter("xigua" to true).excludes(facts(Aweme(aweme_type = 107))))
        assertTrue(filter("panorama" to true).excludes(facts(Aweme(aweme_type = 135, video = Video(format = "vr")))))
        assertFalse(filter("panorama" to true).excludes(facts(Aweme(aweme_type = 135, video = Video(format = "mp4")))))
        assertTrue(filter("articles" to true).excludes(facts(Aweme(article_info = Any()))))
        assertTrue(filter("story" to true).excludes(facts(Aweme(story_interactive_label = "限时日常"))))
        assertTrue(filter("story" to true).excludes(facts(Aweme(story_interactive_label = "日常"))))
        assertFalse(filter("story" to true).excludes(facts(Aweme(story_interactive_label = "其他"))))
        assertTrue(filter("ai" to true).excludes(facts(Aweme(risk_infos = Risk("作品含 AI 生成内容")))))
        assertFalse(filter("ai" to true).excludes(facts(Aweme(risk_infos = Risk("别的")))))
    }

    @Test fun `picture rules spare text and note posts`() {
        val pictures = filter("images" to true, "live-photos" to true)
        assertTrue(pictures.excludes(facts(Aweme(aweme_type = 2))))
        assertTrue(pictures.excludes(facts(Aweme(aweme_type = 68))))
        assertTrue(pictures.excludes(facts(Aweme(is_live_photo = 1))))
        assertFalse(pictures.excludes(facts(Aweme(aweme_type = 2), kind = "笔记")))
        assertFalse(pictures.excludes(facts(Aweme(is_live_photo = 1), kind = "文字")))
        // A live photo is not a picture post: with only the picture rule on, it stays.
        assertFalse(filter("images" to true).excludes(facts(Aweme(aweme_type = 2, is_live_photo = 1))))
        assertTrue(filter("text-posts" to true).excludes(facts(Aweme(), kind = "文字")))
        assertFalse(filter("text-posts" to true).excludes(facts(Aweme(), kind = "笔记")))
    }

    @Test fun `count and length rules apply below the minimum and above the maximum`() {
        assertTrue(filter("min-likes" to 200L).excludes(facts(Aweme())))
        assertFalse(filter("min-likes" to 50L).excludes(facts(Aweme())))
        assertTrue(filter("max-comments" to 5L).excludes(facts(Aweme())))
        assertFalse(filter("max-comments" to 10L).excludes(facts(Aweme())))
        assertTrue(filter("min-collects" to 6L).excludes(facts(Aweme())))
        assertTrue(filter("max-shares" to 2L).excludes(facts(Aweme())))
        assertTrue(filter("min-seconds" to 31L).excludes(facts(Aweme())))
        assertFalse(filter("min-seconds" to 30L).excludes(facts(Aweme())))
        assertTrue(filter("max-seconds" to 29L).excludes(facts(Aweme())))
        // Without statistics or a video the rule has nothing to compare, so the video stays.
        assertFalse(filter("min-likes" to 200L).excludes(facts(Aweme(stats = null))))
        assertFalse(filter("min-seconds" to 31L).excludes(facts(Aweme(video = null))))
    }

    @Test fun `title rules look at the description without line breaks`() {
        val rules = filter("title-contains" to listOf("spoiler", "ad"), "title-missing" to listOf("must"))
        assertTrue(rules.excludes(facts(Aweme(desc = "must see spoiler"))))
        assertTrue(rules.excludes(facts(Aweme(desc = "no keyword"))))
        assertFalse(rules.excludes(facts(Aweme(desc = "mu\nst have"))))
        assertFalse(filter().excludes(facts(Aweme(desc = null))))
        assertTrue(filter("title-missing" to listOf("x")).excludes(facts(Aweme(desc = null))))
    }

    @Test fun `age rules compare the creation time with the clock`() {
        val old = Aweme(create_time = NOW_SECONDS - 40 * DAY_SECONDS)
        val fresh = Aweme(create_time = NOW_SECONDS - 2 * DAY_SECONDS)
        assertTrue(filter("older-than-days" to 30L).excludes(facts(old)))
        assertFalse(filter("older-than-days" to 30L).excludes(facts(fresh)))
        assertTrue(filter("newer-than-days" to 7L).excludes(facts(fresh)))
        assertFalse(filter("newer-than-days" to 7L).excludes(facts(old)))
        assertFalse(filter("older-than-days" to 30L).excludes(facts(Aweme(create_time = 0))))
    }

    @Test fun `fields are found by the name the host serializes them under`() {
        val facts = VideoReader { null }.facts(HostAweme(awemeType = 135, createTime = 1_700_000_000L, isLivePhoto = 1, text = "hi", ad = true))
        assertEquals(135, facts.awemeType)
        assertEquals(1_700_000_000L, facts.createdAtSeconds)
        assertTrue(facts.livePhoto)
        assertEquals("hi", facts.description)
        assertTrue("the host's own ad check counts", facts.isAd)
        assertEquals(listOf(100L, 10L, 5L, 3L), listOf(facts.likes, facts.comments, facts.collects, facts.shares))
        assertFalse(VideoReader { null }.facts(HostAweme(0, 0, 0, "")).isAd)
    }

    @Test fun `a model without the expected fields reads as nothing to match`() {
        val facts = VideoReader { null }.facts(Any())
        assertTrue("an object with no author counts as an ad", facts.isAd)
        assertNull(facts.awemeType)
        assertNull(facts.likes)
        assertEquals("", facts.description)
        assertFalse(filter("live" to true, "story" to true, "articles" to true).excludes(facts))
    }

    @Test fun `videos are dropped from the lists the panel receives on refresh and on load-more`() {
        val resolved = FilterFeedVideos(
            resolver(FeedQueries.FEED_RESULTS to receivers()), loader,
            values(FilterFeedVideos.ID, FilterFeedVideos.OPTIONS), { NOW_SECONDS * 1000 },
        )
        enable(resolved)
        val panel = Panel()
        val keep = Aweme(aweme_id = "keep")
        for (receiver in receivers()) {
            val items = listOf<Any?>(Aweme(aweme_id = "ad", is_ads = true), keep, null, Aweme(aweme_id = "ad2", aweme_type = 9001))
            backend.call(receiver, panel, items, true)
        }
        assertEquals(listOf(listOf(keep, null), listOf(keep, null)), panel.received)
        disable(resolved)
        panel.received.clear()
        backend.call(refresh, panel, listOf(Aweme(is_ads = true), keep), true)
        assertEquals(2, panel.received.single().size)
    }

    @Test fun `a list nothing is removed from is passed on as it came`() {
        enable(feature("ads" to true))
        val panel = Panel()
        val items = listOf<Any?>(Aweme(aweme_id = "a"), Aweme(aweme_id = "b"))
        backend.call(refresh, panel, items, false)
        assertEquals(items, panel.received.single())
    }

    @Test fun `a single video is never filtered and the feed is never emptied`() {
        enable(feature("ads" to true))
        val panel = Panel()
        backend.call(refresh, panel, listOf<Any?>(Aweme(is_ads = true)), true)
        assertEquals(1, panel.received.last().size)
        backend.call(loadMore, panel, listOf<Any?>(Aweme(aweme_id = "1", is_ads = true), Aweme(aweme_id = "2", is_ads = true), Aweme(aweme_id = "3", is_ads = true)), true)
        assertEquals(1, panel.received.last().size)
    }

    @Test fun `without a single rule on nothing is hooked`() {
        enable(feature("ads" to false))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `both receivers are hooked`() {
        enable(feature("ads" to true))
        assertEquals(2, backend.attachments.get())
    }

    @Test fun `a receiver that does not take the video list first fails the install`() {
        val wrong = method(WrongReceiver::class.java, "refresh", Int::class.javaPrimitiveType!!, List::class.java)
        failsToInstall(
            FilterFeedVideos(resolver(FeedQueries.FEED_RESULTS to listOf(wrong)), loader, values(FilterFeedVideos.ID, FilterFeedVideos.OPTIONS), { 0 }),
            "does not take the video list first",
        )
    }

    @Test fun `the kind helper is only resolved when a rule needs it, and it must be static`() {
        val staticKind = method(KindHelper::class.java, "kind", Any::class.java)
        val feature = feature("images" to true, kindHelper = staticKind)
        enable(feature)
        val note = Aweme(aweme_id = "note", aweme_type = 2, desc = "笔记")
        val picture = Aweme(aweme_id = "pic", aweme_type = 2)
        val video = Aweme(aweme_id = "video")
        val panel = Panel()
        backend.call(refresh, panel, listOf<Any?>(picture, note, video), true)
        assertEquals(listOf(note, video), panel.received.single())
    }

    @Test fun `a non-static kind helper fails the install`() {
        val instance = method(InstanceKind::class.java, "kind", Any::class.java)
        failsToInstall(feature("images" to true, kindHelper = instance), "is not a static (video) -> String helper")
    }

    private companion object {
        const val NOW_SECONDS = 1_700_000_000L
        const val DAY_SECONDS = 24L * 60 * 60
    }
}
