package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.feed.*
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import org.junit.Assert.*
import org.junit.Test

// Contract tests for the profile cover lines, with a stand-in holder and a recording decorator. They prove which
// lines are asked for and when, not how a Douyin cover looks.
class CoverInfoTest : HookHarness() {
    class Counts(
        @JvmField val digg_count: Long, @JvmField val share_count: Long,
        @JvmField val collect_count: Long, @JvmField val comment_count: Long,
    )
    class CoverVideo(@JvmField val create_time: Long, @JvmField val statistics: Counts?)
    open class BaseHolder(@JvmField val itemView: Any)
    class CoverHolder(view: Any) : BaseHolder(view) {
        var binds = 0
        fun marker() {}
        fun bind(video: CoverVideo?) { binds++ }
        fun LJIILL(video: CoverVideo?) {}
        fun showCover(video: CoverVideo?) { binds++ }
        fun showCover(video: CoverVideo?, extra: Int) {}
    }
    class NoBinder(view: Any) : BaseHolder(view) { fun marker() {} }

    private val covers = RecordingCovers()

    private fun cover(
        show: Set<String>, hideLike: Boolean = false, holder: Class<*> = CoverHolder::class.java,
        located: List<java.lang.reflect.Method> = listOf(method(holder, "marker")),
    ) = CoverInfo(
        resolver(FeedQueries.COVER_HOLDER to located), loader,
        values(CoverInfo.ID, CoverInfo.OPTIONS, "show" to show, "hide-like-count" to hideLike), { covers }, CoverVideo::class.java.name,
    )

    private val showCover = method(CoverHolder::class.java, "showCover", CoverVideo::class.java)

    @Test fun `the chosen lines are asked for when a video is bound`() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        enable(cover(setOf("time", "digg", "comment")))
        val view = Any()
        backend.call(showCover, CoverHolder(view), CoverVideo(1_700_000_000, Counts(12_345, 7, 99_999, 250_000)))
        val call = covers.calls.single()
        assertSame(view, call.view)
        assertEquals("2023-11-14 22:13:20", call.createdAt)
        assertEquals(listOf(CoverStat.DIGG to "1万", CoverStat.COMMENT to "25.0万"), call.stats)
        assertFalse(call.hideLike)
    }

    @Test fun `only the hidden like count can be asked for and every bind method of the holder decorates`() {
        enable(cover(emptySet(), hideLike = true))
        val holder = CoverHolder(Any())
        backend.call(showCover, holder, CoverVideo(1, null))
        assertEquals(1, covers.calls.size)
        assertNull(covers.calls[0].createdAt)
        assertTrue(covers.calls[0].stats.isEmpty())
        assertTrue(covers.calls[0].hideLike)
        backend.call(method(CoverHolder::class.java, "bind", CoverVideo::class.java), holder, CoverVideo(1, null))
        backend.call(method(CoverHolder::class.java, "LJIILL", CoverVideo::class.java), holder, CoverVideo(1, null))
        assertEquals(3, covers.calls.size)
    }

    // The statistics of Douyin 40.7.0: obfuscated field names, with the model names only in toString.
    class ObfuscatedCounts(@JvmField val a: String, @JvmField val b: Long, @JvmField val d: Long, @JvmField val i: Long, @JvmField val collectCount: Long) {
        override fun toString() = "AwemeStatistics{aid='$a', commentCount=$b, diggCount=$d, friendDiggCount=99, shareCount=$i, collectCount$collectCount}"
    }
    class HostCounts(@JvmField val diggCount: Long, @JvmField val shareCount: Long)

    @Test fun `counts of obfuscated statistics are read back from their text`() {
        val digg = StatCount("diggCount", "digg_count")
        val source = ObfuscatedCounts("1", 7, 12_345, 40, 3)
        assertEquals(12_345L, digg.of(source))
        assertEquals(7L, StatCount("commentCount", "comment_count").of(source))
        assertEquals(40L, StatCount("shareCount", "share_count").of(source))
        assertEquals(3L, StatCount("collectCount", "collect_count").of(source))
    }

    @Test fun `counts named like the host model are read from the fields and unknown ones are null`() {
        assertEquals(5L, StatCount("diggCount", "digg_count").of(HostCounts(5, 6)))
        assertNull(StatCount("commentCount", "comment_count").of(HostCounts(5, 6)))
    }

    @Test fun `a video without statistics still gets its time`() {
        enable(cover(setOf("time", "share")))
        backend.call(showCover, CoverHolder(Any()), CoverVideo(1_700_000_000, null))
        assertNotNull(covers.calls.single().createdAt)
        assertTrue(covers.calls.single().stats.isEmpty())
    }

    @Test fun `nothing chosen hooks nothing`() {
        enable(cover(emptySet()))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `a holder with no bind method fails the install`() {
        failsToInstall(cover(setOf("time"), holder = NoBinder::class.java), "No bind method taking")
    }

    @Test fun `several methods of one holder class locate that holder`() {
        enable(cover(setOf("time"), located = listOf(method(CoverHolder::class.java, "marker"), showCover)))
        backend.call(showCover, CoverHolder(Any()), CoverVideo(1_700_000_000, null))
        assertNotNull(covers.calls.single().createdAt)
    }

    @Test fun `methods of two holder classes fail the install`() {
        failsToInstall(
            cover(setOf("time"), located = listOf(method(CoverHolder::class.java, "marker"), method(NoBinder::class.java, "marker"))),
            "Expected one cover holder class, found 2",
        )
    }

    @Test fun `counts are shown the way the grid shows them`() {
        assertEquals("9999", compactCount(9_999))
        assertEquals("1万", compactCount(10_000))
        assertEquals("10万", compactCount(99_999))
        assertEquals("10.0万", compactCount(100_000))
        assertEquals("123.5万", compactCount(1_234_567))
    }
}
