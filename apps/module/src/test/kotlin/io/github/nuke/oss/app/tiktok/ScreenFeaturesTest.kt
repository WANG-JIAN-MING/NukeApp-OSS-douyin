package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.feed.*
import io.github.nuke.oss.app.tiktok.feature.playback.*
import io.github.nuke.oss.app.tiktok.feature.screen.*
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import org.junit.Assert.*
import org.junit.Test

// Contract tests for the count, video size and bottom bar features, against stand-ins for the host classes. They prove
// the Hook semantics and the option handling, not that any Douyin build has these targets.
class ScreenFeaturesTest : HookHarness() {
    class CountFormat {
        companion object {
            @JvmStatic fun shorten(count: Long): String = "1.2w"
            @JvmStatic fun widen(count: Int): String = "1.2w"
        }
    }

    class RoomStats {
        @JvmField var display_long: String? = "1.2万在线观众"
        @JvmField var display_middle: String? = "1.2万"
        @JvmField var display_short: String? = "1.2万"
    }
    class RoomModel(@JvmField var user_count: Long, @JvmField var room_view_stats: Any?)
    class AudienceWidget {
        val counts = ArrayList<List<Any?>>()
        fun bind(model: RoomModel?) {}
        fun onCount(count: Long, middle: String?, long: String?, hidden: Boolean) { counts += listOf(count, middle, long, hidden) }
    }

    private fun counts(counts: Boolean, live: Boolean, vararg formatters: java.lang.reflect.Method) = ShowPreciseCounts(
        resolver(
            OptionQueries.COUNT_FORMATTERS to formatters.toList(),
            OptionQueries.AUDIENCE_STATS to listOf(method(AudienceWidget::class.java, "bind", RoomModel::class.java)),
            OptionQueries.AUDIENCE_COUNT to listOf(method(
                AudienceWidget::class.java, "onCount",
                Long::class.javaPrimitiveType!!, String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!,
            )),
        ),
        loader, values(ShowPreciseCounts.ID, ShowPreciseCounts.OPTIONS, "counts" to counts, "live" to live),
    )

    private val shorten = method(CountFormat::class.java, "shorten", Long::class.javaPrimitiveType!!)

    @Test fun `count formatters answer with the exact number`() {
        val feature = counts(counts = true, live = false, shorten)
        enable(feature)
        assertEquals("12345", backend.call(shorten, null, 12345L))
        disable(feature)
        assertEquals("1.2w", backend.call(shorten, null, 12345L))
    }

    @Test fun `a formatter that does not take a long fails the install`() {
        failsToInstall(counts(true, false, method(CountFormat::class.java, "widen", Int::class.javaPrimitiveType!!)), "is not a (long) String formatter")
    }

    @Test fun `live room texts are rewritten from the exact viewer count`() {
        enable(counts(counts = false, live = true, shorten))
        val widget = AudienceWidget()
        val stats = RoomStats()
        backend.call(method(AudienceWidget::class.java, "bind", RoomModel::class.java), widget, RoomModel(12345L, stats))
        assertEquals("12345在线观众", stats.display_long)
        assertEquals("12345", stats.display_middle)
        assertEquals("12345", stats.display_short)
        val onCount = method(
            AudienceWidget::class.java, "onCount",
            Long::class.javaPrimitiveType!!, String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!,
        )
        backend.call(onCount, widget, 678L, "0.1万", "0.1万在线观众", true)
        backend.call(onCount, widget, 0L, "x", "y", true)
        assertEquals(listOf(listOf(678L, "678", "678在线观众", false), listOf(0L, "x", "y", true)), widget.counts)
    }

    @Test fun `a room model without the expected fields is left alone`() {
        enable(counts(counts = false, live = true, shorten))
        val stats = RoomStats()
        backend.call(method(AudienceWidget::class.java, "bind", RoomModel::class.java), AudienceWidget(), RoomModel(5L, null))
        assertEquals("1.2万在线观众", stats.display_long)
        assertTrue(failures.isEmpty())
    }

    @Test fun `no chosen counts hooks nothing`() {
        enable(counts(counts = false, live = false, shorten))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `only a leading rounded number is replaced`() {
        assertEquals("12345在线观众", exactCountText("1.2万在线观众", 12345))
        assertEquals("12345", exactCountText("1.2w", 12345))
        assertEquals("12 watching", exactCountText("12 watching", 12))
        assertEquals("1234 viewers", exactCountText("1,234 viewers", 1234))
        assertEquals("观众 1.2万", exactCountText("观众 1.2万", 12000))
        assertNull(exactCountText(null, 1))
    }

    class Resizer {
        val sizes = ArrayList<Pair<Int, Int>>()
        fun resizeView(height: Int, width: Int) { sizes += height to width }
        fun resizeView(height: Int, width: Int, animate: Boolean) { sizes += height to width }
    }
    class BadResizer { fun resizeView(view: Any?, height: Int) {} }

    private fun videoSize(mode: String, anchor: java.lang.reflect.Method, screen: ScreenSize?) = PortraitVideoSize(
        resolver(OptionQueries.VIDEO_RESIZE to listOf(anchor)), loader,
        values(PortraitVideoSize.ID, PortraitVideoSize.OPTIONS, "mode" to mode), { screen },
    )

    private val resize = method(Resizer::class.java, "resizeView", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)

    @Test fun `fit scales a wide video down to the screen width in every overload`() {
        val feature = videoSize(PortraitVideoSize.FIT, resize, ScreenSize(1080, 2400))
        enable(feature)
        val host = Resizer()
        backend.call(resize, host, 1000, 1440)
        backend.call(method(Resizer::class.java, "resizeView", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!), host, 1000, 900, true)
        disable(feature)
        backend.call(resize, host, 1000, 1440)
        assertEquals(listOf(750 to 1080, 1000 to 900, 1000 to 1440), host.sizes)
    }

    @Test fun `fill stretches a tall video and leaves landscape and unknown screens alone`() {
        enable(videoSize(PortraitVideoSize.FILL, resize, ScreenSize(1080, 2400)))
        val host = Resizer()
        backend.call(resize, host, 2000, 1000)
        backend.call(resize, host, 600, 1080)
        assertEquals(listOf(2400 to 1080, 600 to 1080), host.sizes)
    }

    @Test fun `an unknown screen size leaves the video as it is`() {
        enable(videoSize(PortraitVideoSize.FIT, resize, null))
        val host = Resizer()
        backend.call(resize, host, 1000, 1440)
        assertEquals(listOf(1000 to 1440), host.sizes)
    }

    @Test fun `a resize method without the height and width first fails the install`() {
        failsToInstall(
            videoSize(PortraitVideoSize.FIT, method(BadResizer::class.java, "resizeView", Any::class.java, Int::class.javaPrimitiveType!!), ScreenSize(1, 1)),
            "does not take the height and width first",
        )
    }

    @Test fun `the size rules fit the video to the screen`() {
        val screen = ScreenSize(1080, 2400)
        assertNull(fitToWidth(VideoSize(1920, 1080), screen))
        assertEquals(VideoSize(540, 1080), fitToWidth(VideoSize(1080, 2160), screen))
        assertNull(fillScreen(VideoSize(1920, 1080), screen))
        assertEquals(VideoSize(2400, 1080), fillScreen(VideoSize(1921, 1080), screen))
        assertNull(fillScreen(VideoSize(1000, 1500), screen))
    }

    class FakeDrawable
    open class FakeView { var visibilitySets = 0 }
    class TabBar {
        var backgrounds = 0
        fun setBackground(drawable: FakeDrawable?) { backgrounds++ }
        fun changeColor() {}
    }
    class PlainBar { fun changeColor() {} }
    class BottomSpace : FakeView() {
        var updates = 0
        var layouts = 0
        fun update(visibility: Int, reason: String?) { updates++ }
        fun setLayoutParams(params: Any?) { layouts++ }
    }
    class PlainSpace : FakeView() { fun update(visibility: Int, reason: String?) {} }
    class NotAView { fun update(visibility: Int, reason: String?) {} }

    private class RecordingHider : ViewHider {
        val hidden = ArrayList<Any>()
        override fun hide(view: Any) { hidden += view }
    }

    private fun bar(barType: Class<*>, spaceType: Class<*>, hider: ViewHider) = TransparentBottomBar(
        resolver(
            FeedQueries.BOTTOM_TAB_BAR to listOf(method(barType, "changeColor")),
            FeedQueries.BOTTOM_SPACE to listOf(method(spaceType, "update", Int::class.javaPrimitiveType!!, String::class.java)),
        ),
        loader, hider, FakeView::class.java, FakeDrawable::class.java,
    )

    @Test fun `the bar keeps no background and the spacer stays hidden`() {
        val hider = RecordingHider()
        val feature = bar(TabBar::class.java, BottomSpace::class.java, hider)
        enable(feature)
        val tabs = TabBar()
        val space = BottomSpace()
        val update = method(BottomSpace::class.java, "update", Int::class.javaPrimitiveType!!, String::class.java)
        backend.call(method(TabBar::class.java, "setBackground", FakeDrawable::class.java), tabs, FakeDrawable())
        backend.call(update, space, 0, "show")
        backend.call(update, space, 8, "hide")
        backend.call(method(BottomSpace::class.java, "setLayoutParams", Any::class.java), space, null)
        assertEquals(0, tabs.backgrounds)
        assertEquals(1, space.updates)
        assertEquals(1, space.layouts)
        assertEquals(listOf<Any>(space, space), hider.hidden)
        disable(feature)
        backend.call(update, space, 0, "show")
        assertEquals(2, space.updates)
    }

    @Test fun `a spacer without its own setLayoutParams hooks only the bar and the update`() {
        enable(bar(TabBar::class.java, PlainSpace::class.java, RecordingHider()))
        assertEquals(2, backend.attachments.get())
    }

    @Test fun `a bar without its own setBackground fails the install`() {
        failsToInstall(bar(PlainBar::class.java, BottomSpace::class.java, RecordingHider()), "declares no setBackground")
    }

    @Test fun `a spacer that is not a View fails the install`() {
        failsToInstall(bar(TabBar::class.java, NotAView::class.java, RecordingHider()), "is not a FakeView")
    }

    class LinkConfig
    class LinkContext
    class ShareLinks {
        var copies = 0
        fun copy(config: LinkConfig?, context: LinkContext?, link: String?, done: Any?) { copies++ }
    }
    class LinkFirst { fun copy(link: String?, config: LinkConfig?, context: LinkContext?, done: Any?) {} }

    private class RecordingClipboard(private val accepts: Boolean = true) : LinkClipboard {
        val copied = ArrayList<String>()
        override fun copy(context: Any, link: String): Boolean { if (accepts) copied += link; return accepts }
    }

    private val linkCopy = method(ShareLinks::class.java, "copy", LinkConfig::class.java, LinkContext::class.java, String::class.java, Any::class.java)

    private fun links(clipboard: LinkClipboard, target: java.lang.reflect.Method = linkCopy) =
        CleanShareLink(resolver(FeedQueries.SHARE_LINK_COPY to listOf(target)), loader, clipboard)

    @Test fun `only the trimmed link is copied and the host copy is skipped`() {
        val clipboard = RecordingClipboard()
        val feature = links(clipboard)
        enable(feature)
        val host = ShareLinks()
        backend.call(linkCopy, host, LinkConfig(), LinkContext(), " https://v.douyin.com/abc/ ", null)
        assertEquals(listOf("https://v.douyin.com/abc/"), clipboard.copied)
        assertEquals(0, host.copies)
        disable(feature)
        backend.call(linkCopy, host, LinkConfig(), LinkContext(), "https://v.douyin.com/abc/", null)
        assertEquals(1, host.copies)
    }

    @Test fun `the host copies as usual without a link, a context or a clipboard`() {
        enable(links(RecordingClipboard(accepts = false)))
        val host = ShareLinks()
        backend.call(linkCopy, host, LinkConfig(), LinkContext(), "https://v.douyin.com/abc/", null)
        backend.call(linkCopy, host, LinkConfig(), null, "https://v.douyin.com/abc/", null)
        backend.call(linkCopy, host, LinkConfig(), LinkContext(), " ", null)
        assertEquals(3, host.copies)
    }

    @Test fun `a copy method without the link third fails the install`() {
        failsToInstall(
            links(RecordingClipboard(), method(LinkFirst::class.java, "copy", String::class.java, LinkConfig::class.java, LinkContext::class.java, Any::class.java)),
            "does not take the link third",
        )
    }
}
