package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.feed.BottomInfoLabels
import io.github.nuke.oss.app.tiktok.feature.feed.VideoBottomInfo
import io.github.nuke.oss.app.tiktok.feature.feed.renderBottomInfo
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.query.ViewQueries
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// The publish time of the bottom line with a video model that names its fields the way Douyin 40.7.0 does: createTime
// and aid, not the serialized create_time and aweme_id.
class VideoBottomInfoTest : HookHarness() {
    class HostVideo(@JvmField val aid: String, @JvmField val createTime: Long, @JvmField val duration: Int?, @JvmField val ipAttribution: String? = null)
    class Player { fun change(video: HostVideo?, from: String?) {} }
    class Intro

    private class Labels : BottomInfoLabels {
        val shown = ArrayList<String>()
        override fun attach(layout: Any) {}
        override fun show(text: String) { shown += text }
    }

    private val zone = java.util.TimeZone.getDefault()
    private val change = method(Player::class.java, "change", HostVideo::class.java, String::class.java)
    private val select = method(
        FeedEventFeaturesTest.Pager::class.java, "setCurrentItemInternal",
        Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
    )
    private val progress = method(
        FeedEventFeaturesTest.Progress::class.java, "onProgress",
        String::class.java, Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
    )
    private val labels = Labels()

    @Before fun utc() = java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    @After fun restore() = java.util.TimeZone.setDefault(zone)

    class Label { fun visibility(model: Any?): Int = 8; fun mode(): Int = 0 }
    private val visibility = method(Label::class.java, "visibility", Any::class.java)
    private val mode = method(Label::class.java, "mode")

    private fun hostLabelFeature(vararg saved: Pair<String, Any>) = VideoBottomInfo(
        resolver(ViewQueries.TIME_VISIBILITY to listOf(visibility), ViewQueries.TIME_IP_MODE to listOf(mode)), loader,
        FeedEvents(resolver(), loader, HostVideo::class.java.name),
        values(VideoBottomInfo.ID, VideoBottomInfo.OPTIONS, *saved), { labels }, Intro::class.java.name,
    )

    @Test fun `the host label is off by default and the added line is on`() {
        assertEquals(true, VideoBottomInfo.OPTIONS.filterIsInstance<io.github.nuke.oss.app.shared.settings.ToggleOption>().single { it.key == "line" }.default)
        assertEquals(false, VideoBottomInfo.OPTIONS.filterIsInstance<io.github.nuke.oss.app.shared.settings.ToggleOption>().single { it.key == "host-label" }.default)
    }

    @Test fun `with only the host label on the host's label is revealed and no line is added`() {
        enable(hostLabelFeature("line" to false, "host-label" to true))
        assertEquals(1, backend.call(mode, Label()))
        assertEquals(2, backend.attachments.get())
        assertEquals(emptyList<String>(), labels.shown)
    }

    @Test fun `with neither the line nor the host label on the install fails`() {
        failsToInstall(hostLabelFeature("line" to false, "host-label" to false), "Neither the added line nor the host label")
    }

    private fun install(template: String = "{published}|{duration}", hostLabel: Boolean = false) {
        val located = resolver(
            FeedQueries.AWEME_CHANGED to listOf(change), FeedQueries.PAGER_SELECT to listOf(select), FeedQueries.PLAY_PROGRESS to listOf(progress),
            ViewQueries.TIME_VISIBILITY to listOf(visibility), ViewQueries.TIME_IP_MODE to listOf(mode),
        )
        val events = FeedEvents(located, loader, HostVideo::class.java.name)
        enable(VideoBottomInfo(located, loader, events, values(VideoBottomInfo.ID, VideoBottomInfo.OPTIONS, "template" to template, "time-pattern" to "yyyy-MM-dd", "host-label" to hostLabel), { labels }, Intro::class.java.name))
    }

    @Test fun `the publish time comes from createTime in seconds`() {
        install()
        backend.call(change, Player(), HostVideo("a", 1_700_000_000, 90_000), "")
        assertEquals(listOf("2023-11-14|1:30"), labels.shown)
    }

    @Test fun `the location placeholder shows the video's IP location and is empty without one`() {
        install("{published} {location}|{duration}")
        backend.call(change, Player(), HostVideo("a", 1_700_000_000, 90_000, "广东"), "")
        backend.call(change, Player(), HostVideo("b", 1_700_000_000, 90_000), "")
        assertEquals(listOf("2023-11-14 广东|1:30", "2023-11-14 |1:30"), labels.shown)
        assertEquals("广东", renderBottomInfo("{location}", 0, "yyyy", 0, 0, "广东"))
    }

    @Test fun `with the line on the host label is not revealed and the line carries the time and the place`() {
        install("{published} {location} {duration}|{played}", hostLabel = true)
        backend.call(change, Player(), HostVideo("a", 1_700_000_000, 90_000, "广东"), "")
        assertEquals(listOf("2023-11-14 广东 1:30|0:00"), labels.shown)
        // The host's mode getter is left as the host has it, so only one control shows the time.
        assertEquals(0, backend.call(mode, Label()))
    }

    @Test fun `the played position sits in the same text as the time`() {
        assertEquals("2023 1:30|0:45", renderBottomInfo("{published} {duration}|{played}", 1_700_000_000_000, "yyyy", 90_000, 45_000))
    }

    @Test fun `a video without a publish time shows no date instead of the epoch`() {
        install()
        backend.call(change, Player(), HostVideo("a", 0, 90_000), "")
        assertEquals(listOf("|1:30"), labels.shown)
        assertEquals("|0:00", renderBottomInfo("{published}|{duration}", 0, "yyyy-MM-dd", 0, 0))
    }

    @Test fun `one video reported by the player and the pager is shown once`() {
        install()
        val video = HostVideo("a", 1_700_000_000, 90_000)
        backend.call(change, Player(), video, "")
        backend.call(select, FeedEventFeaturesTest.Pager(FeedEventFeaturesTest.PagerAdapter(listOf(video))), 0, true, false, 0)
        assertEquals(1, labels.shown.size)
    }
}
