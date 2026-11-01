package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.ads.*
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import org.junit.Assert.*
import org.junit.Test

// Contract tests for skipping ad chapters, against stand-ins for the video model and player. They prove the skip rule
// and the Hook semantics, not that any Douyin build has the target.
class ChapterAdsTest : HookHarness() {
    class Chapter(@JvmField val timestamp: Int)
    class ChapterData(@JvmField val adChapterIndexList: List<Int>?)
    class VideoInfo(@JvmField val duration: Int)
    class FakeAweme(
        @JvmField val aweme_id: String,
        private val chapters: List<Any?>,
        @JvmField val chapterData: ChapterData?,
        private val video: VideoInfo = VideoInfo(100_000),
    ) {
        fun getVideo() = video
        fun getMoreChapterList() = chapters
    }
    class FakePlayer {
        val seeks = ArrayList<Float>()
        fun seek(percent: Float) { seeks += percent }
    }
    class Ticker {
        fun tick(video: Any?, position: Long, a: Boolean, b: Boolean, c: Boolean, count: Int, player: FakePlayer?) {}
    }
    class WrongTicker { fun tick(video: Any?, position: Int, player: FakePlayer?) {} }
    class SeeklessTicker { fun tick(video: Any?, position: Long, a: Boolean, b: Boolean, c: Boolean, count: Int, player: Any?) {} }

    private val tick = method(
        Ticker::class.java, "tick", Any::class.java, Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
        Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, FakePlayer::class.java,
    )
    private val prompts = RecordingPrompts()
    private val foreground = FakeForeground()

    private fun skipper(target: java.lang.reflect.Method = tick) =
        SkipChapterAds(resolver(FeedQueries.CHAPTER_PROGRESS to listOf(target)), loader, prompts, foreground)

    private fun call(aweme: FakeAweme, position: Long, player: FakePlayer) =
        backend.call(tick, Ticker(), aweme, position, false, false, false, 0, player)

    private fun video(ads: List<Int>, vararg starts: Int, id: String = "v") =
        FakeAweme(id, starts.map(::Chapter), ChapterData(ads))

    @Test fun `an ad chapter is skipped once to its end with a notice`() {
        enable(skipper())
        val aweme = video(listOf(1), 0, 20_000, 60_000)
        val player = FakePlayer()
        call(aweme, 30_000, player)
        call(aweme, 31_000, player)
        assertEquals(listOf(60f), player.seeks)
        assertEquals(listOf(R.string.host_notice_chapter_ad to listOf<Any>("0:20", "1:00")), prompts.toasts)
    }

    @Test fun `chapters that are not ads and other positions leave the player alone`() {
        enable(skipper())
        val aweme = video(listOf(2), 0, 20_000, 60_000)
        val player = FakePlayer()
        call(aweme, 10_000, player)
        call(aweme, 30_000, player)
        assertTrue(player.seeks.isEmpty())
        assertTrue(prompts.toasts.isEmpty())
    }

    @Test fun `the last ad chapter runs to the end of the video`() {
        enable(skipper())
        val player = FakePlayer()
        call(video(listOf(2), 0, 20_000, 60_000), 70_000, player)
        assertEquals(listOf(100f), player.seeks)
    }

    @Test fun `a time-zero chapter shifts the host's ad indexes except on the detail page`() {
        enable(skipper())
        val player = FakePlayer()
        call(video(listOf(1), 0, 20_000, 60_000, id = "shifted"), 30_000, player)
        assertEquals(listOf(60f), player.seeks)
        foreground.name = SkipChapterAds.DETAIL_ACTIVITY
        val detail = FakePlayer()
        call(video(listOf(1), 0, 20_000, 60_000, id = "detail"), 30_000, detail)
        assertTrue(detail.seeks.isEmpty())
    }

    @Test fun `a video without chapter data or a duration is left alone`() {
        enable(skipper())
        val player = FakePlayer()
        call(FakeAweme("a", listOf(Chapter(0)), null), 1_000, player)
        call(FakeAweme("b", listOf(Chapter(5_000)), ChapterData(null)), 6_000, player)
        call(FakeAweme("c", listOf(Chapter(5_000)), ChapterData(listOf(0)), VideoInfo(0)), 6_000, player)
        assertTrue(player.seeks.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test fun `turning the feature off stops the skipping`() {
        val feature = skipper()
        enable(feature)
        disable(feature)
        val player = FakePlayer()
        call(video(listOf(1), 0, 20_000, 60_000), 30_000, player)
        assertTrue(player.seeks.isEmpty())
    }

    @Test fun `a method with another shape fails the install`() {
        failsToInstall(skipper(method(WrongTicker::class.java, "tick", Any::class.java, Int::class.javaPrimitiveType!!, FakePlayer::class.java)), "does not take")
    }

    @Test fun `a player that cannot seek fails the install`() {
        failsToInstall(
            skipper(method(
                SeeklessTicker::class.java, "tick", Any::class.java, Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Any::class.java,
            )),
            "has no seek(float)",
        )
    }

    @Test fun `chapter lookup follows the ranges`() {
        assertNull(adChapterAt(emptyList(), setOf(0), 1000, 5, 0))
        assertEquals(AdChapter(0, 0, 400), adChapterAt(listOf(0, 400), setOf(0), 1000, 400, 0))
        assertEquals(AdChapter(1, 400, 1000), adChapterAt(listOf(0, 400), setOf(1), 1000, 401, 0))
        assertNull(adChapterAt(listOf(0, 400), setOf(0), 1000, 1500, 0))
    }
}
