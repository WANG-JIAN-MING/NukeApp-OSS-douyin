package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.feed.*
import io.github.nuke.oss.app.tiktok.feature.general.*
import io.github.nuke.oss.app.tiktok.feature.navigation.*
import io.github.nuke.oss.app.tiktok.feature.playback.*
import io.github.nuke.oss.app.tiktok.feature.screen.*
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import org.junit.Assert.*
import org.junit.Test

// Contract tests for the features that read their behavior from options. Like MigratedFeaturesTest, they run against
// stand-ins for the host classes, so they prove the Hook semantics and the option handling, not a Douyin build.
class OptionFeaturesTest : HookHarness() {
    class TabBuilder {
        var builds = 0
        var top: List<String>? = listOf("rec", "featured", "tips", "live", "nearby", "follow", "mall")
        var bottom: List<String>? = listOf("home", "mall", "publish", "messages", "me")
        fun buildTop(): ArrayList<String> { builds++; return ArrayList(top ?: error("top unavailable")) }
        fun buildBottom(): ArrayList<String>? { builds++; return bottom?.let { ArrayList(it) } }
    }

    class BadTabBuilder { fun build(page: Int): ArrayList<String> = ArrayList() }
    class WrongTabBuilder { fun build(): java.util.LinkedList<String> = java.util.LinkedList() }

    class PrepareData {
        @JvmField var speed = 1f
        private var volume = 1f
        fun volume() = volume
    }

    class TwoSpeeds {
        @JvmField var speed = 1f
        @JvmField var rate = 1f
    }

    class Player {
        var prepared = 0
        fun prepare(data: Any?) { prepared++ }
    }

    class CachePage { fun cacheCount(): Int = 5 }

    class Bean { @JvmField var blocking_word_list: String? = """["x"]""" }
    class DanmakuConfig {
        var applied = 0
        fun apply(bean: Bean?) { applied++ }
        fun other(count: Int) {}
    }

    class FakeResources
    class FakeContext
    class DisplayConfigurator {
        var configured = 0
        fun configure(resources: FakeResources?, context: FakeContext?) { configured++ }
    }
    class ReversedConfigurator { fun configure(context: FakeContext?, resources: FakeResources?) {} }
    class TwoConfigurators {
        fun first(resources: FakeResources?, context: FakeContext?) {}
        fun second(resources: FakeResources?, context: FakeContext?) {}
    }

    class ProfileHost {
        val shown = ArrayList<List<Any?>>()
        fun replaceFragments(user: Any?, tabTypes: List<Any?>?) { if (tabTypes != null) shown += tabTypes }
    }
    class OneArgProfileHost { fun replaceFragments(tabTypes: List<Any?>) {} }

    class RingAuthor(@JvmField var room_id: Long)
    class RingAweme(@JvmField var author: Any?)
    open class RingPresenter {
        @JvmField var aweme: RingAweme? = null
        var setups = 0
        fun setup() { setups++ }
    }
    class RingDrawer {
        var draws = 0
        fun onSyncBind() { draws++ }
        fun onSyncBind(flag: Boolean): Boolean = flag
    }
    class TwoVideoPresenter {
        @JvmField var first: RingAweme? = null
        @JvmField var second: RingAweme? = null
        fun setup() {}
    }
    class NotAPresenter { fun setup() {} }

    class PanelItem(@JvmField val itemId: String)
    class PanelModule(@JvmField val moduleId: String, @JvmField val moduleItems: MutableList<Any?>)
    class PanelConfig(val resultType: Int, val modules: MutableList<Any?>, val version: Long) {
        override fun toString() = "config"
    }
    class PanelConfigCopy(val resultType: Int, val modules: MutableList<Any?>, val version: Long) {
        override fun toString() = "config copy"
    }
    class PlainConfig(val value: Int) {
        override fun toString() = "plain"
    }

    private fun tabs(vararg located: java.lang.reflect.Method) = OptionQueries.TAB_LISTS to located.toList()

    private fun bottomFeature(vararg hidden: String, builders: List<java.lang.reflect.Method>) = HideHomeTabs(
        HideHomeTabs.BOTTOM_ID, resolver(OptionQueries.TAB_LISTS to builders), loader,
        values(HideHomeTabs.BOTTOM_ID, HideHomeTabs.BOTTOM_OPTIONS, "hidden" to hidden.toSet()), HideHomeTabs.BOTTOM_SIZES,
    )

    @Test fun `the bottom feature removes chosen tabs from short lists only`() {
        val buildTop = method(TabBuilder::class.java, "buildTop")
        val buildBottom = method(TabBuilder::class.java, "buildBottom")
        val feature = HideHomeTabs(
            HideHomeTabs.BOTTOM_ID, resolver(tabs(buildTop, buildBottom)), loader,
            values(HideHomeTabs.BOTTOM_ID, HideHomeTabs.BOTTOM_OPTIONS, "hidden" to setOf("homepage_mall", "homepage_home")),
            HideHomeTabs.BOTTOM_SIZES,
        )
        enable(feature)
        val builder = TabBuilder().apply { bottom = listOf("homepage_home", "homepage_mall", "homepage_publish", "homepage_profile") }
        assertEquals(listOf("homepage_publish", "homepage_profile"), backend.call(buildBottom, builder))
        // Seven entries is a top strip: the bottom feature leaves it alone, even where ids match.
        builder.top = listOf("a", "homepage_mall", "c", "d", "e", "f", "g")
        assertEquals(builder.top, backend.call(buildTop, builder))
        disable(feature)
        assertEquals(4, (backend.call(buildBottom, builder) as List<*>).size)
    }

    @Test fun `the top feature removes chosen tabs from long lists only`() {
        val buildTop = method(TabBuilder::class.java, "buildTop")
        val buildBottom = method(TabBuilder::class.java, "buildBottom")
        val feature = HideHomeTabs(
            HideHomeTabs.TOP_ID, resolver(tabs(buildTop, buildBottom)), loader,
            values(HideHomeTabs.TOP_ID, HideHomeTabs.TOP_OPTIONS, "hidden" to setOf("homepage_tablive", "homepage_mall")),
            HideHomeTabs.TOP_SIZES,
        )
        enable(feature)
        val builder = TabBuilder().apply {
            top = listOf("homepage_hot_container", "homepage_tablive", "homepage_nearby", "homepage_follow", "homepage_mall", "homepage_groupon")
            bottom = listOf("homepage_mall", "homepage_home")
        }
        assertEquals(
            listOf("homepage_hot_container", "homepage_nearby", "homepage_follow", "homepage_groupon"),
            backend.call(buildTop, builder),
        )
        assertEquals(listOf("homepage_mall", "homepage_home"), backend.call(buildBottom, builder))
    }

    @Test fun `the list the host built is never changed and hiding everything keeps the last tab`() {
        val buildBottom = method(TabBuilder::class.java, "buildBottom")
        val feature = bottomFeature("homepage_home", "homepage_publish", builders = listOf(buildBottom))
        enable(feature)
        val original = listOf("homepage_home", "homepage_publish")
        val builder = TabBuilder().apply { bottom = original }
        assertEquals(listOf("homepage_publish"), backend.call(buildBottom, builder))
        assertEquals(listOf("homepage_home", "homepage_publish"), original)
    }

    @Test fun `a builder that returns nothing or throws is left to the host`() {
        val buildTop = method(TabBuilder::class.java, "buildTop")
        val buildBottom = method(TabBuilder::class.java, "buildBottom")
        val feature = HideHomeTabs(
            HideHomeTabs.BOTTOM_ID, resolver(tabs(buildTop, buildBottom)), loader,
            values(HideHomeTabs.BOTTOM_ID, HideHomeTabs.BOTTOM_OPTIONS, "hidden" to setOf("homepage_mall")), HideHomeTabs.BOTTOM_SIZES,
        )
        enable(feature)
        assertNull(backend.call(buildBottom, TabBuilder().apply { bottom = null }))
        val error = assertThrows(IllegalStateException::class.java) { backend.call(buildTop, TabBuilder().apply { top = null }) }
        assertEquals("top unavailable", error.message)
    }

    @Test fun `with nothing selected the tab features install no hook`() {
        val feature = bottomFeature(builders = listOf(method(TabBuilder::class.java, "buildBottom")))
        enable(feature)
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `a tab builder with the wrong shape fails the install`() {
        failsToInstall(bottomFeature("homepage_mall", builders = listOf(method(BadTabBuilder::class.java, "build", Int::class.javaPrimitiveType!!))), "is not a list builder")
    }

    @Test fun `a builder that cannot return a new list fails the install`() {
        failsToInstall(bottomFeature("homepage_mall", builders = listOf(method(WrongTabBuilder::class.java, "build"))), "is not a list builder")
    }

    @Test fun `a prepare method whose data class has no speed field fails the install`() {
        val prepare = method(Player::class.java, "prepare", Any::class.java)
        failsToInstall(
            CustomPlaybackSpeed(
                resolver(OptionQueries.PLAYER_PREPARE to listOf(prepare)), loader,
                values(CustomPlaybackSpeed.ID, CustomPlaybackSpeed.OPTIONS, "speed" to "1.5"),
            ),
            "Expected one public float field in java.lang.Object, found 0",
        )
    }

    @Test fun `playback speed is applied to a typed prepare data`() {
        val prepare = method(TypedPlayer::class.java, "prepare", PrepareData::class.java)
        val feature = CustomPlaybackSpeed(
            resolver(OptionQueries.PLAYER_PREPARE to listOf(prepare)), loader,
            values(CustomPlaybackSpeed.ID, CustomPlaybackSpeed.OPTIONS, "speed" to "1.5"),
        )
        enable(feature)
        val data = PrepareData()
        backend.call(prepare, TypedPlayer(), data)
        assertEquals(1.5f, data.speed, 0f)
        assertEquals(1f, data.volume(), 0f)
        backend.call(prepare, TypedPlayer(), null)
        disable(feature)
        data.speed = 1f
        backend.call(prepare, TypedPlayer(), data)
        assertEquals(1f, data.speed, 0f)
    }

    class TypedPlayer { fun prepare(data: PrepareData?) {} }
    class AmbiguousPlayer { fun prepare(data: TwoSpeeds?) {} }

    @Test fun `host speed installs nothing`() {
        val prepare = method(TypedPlayer::class.java, "prepare", PrepareData::class.java)
        enable(CustomPlaybackSpeed(resolver(OptionQueries.PLAYER_PREPARE to listOf(prepare)), loader, values(CustomPlaybackSpeed.ID, CustomPlaybackSpeed.OPTIONS)))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `two float fields make the speed ambiguous`() {
        val prepare = method(AmbiguousPlayer::class.java, "prepare", TwoSpeeds::class.java)
        failsToInstall(
            CustomPlaybackSpeed(
                resolver(OptionQueries.PLAYER_PREPARE to listOf(prepare)), loader,
                values(CustomPlaybackSpeed.ID, CustomPlaybackSpeed.OPTIONS, "speed" to "2"),
            ),
            "found 2",
        )
    }

    @Test fun `every offered speed is a quarter step and parses`() {
        assertEquals(12, CustomPlaybackSpeed.SPEEDS.size)
        assertEquals(listOf("0.25", "0.5", "0.75", "1"), CustomPlaybackSpeed.SPEEDS.take(4))
        assertEquals("3", CustomPlaybackSpeed.SPEEDS.last())
        assertTrue(CustomPlaybackSpeed.SPEEDS.all { it.toFloatOrNull() != null })
    }

    @Test fun `the offline cache limit is answered with the chosen count`() {
        val count = method(CachePage::class.java, "cacheCount")
        val feature = OfflineCacheCount(
            resolver(OptionQueries.CACHE_COUNT to listOf(count)), loader,
            values(OfflineCacheCount.ID, OfflineCacheCount.OPTIONS, "count" to 20L),
        )
        enable(feature)
        assertEquals(20, backend.call(count, CachePage()))
        disable(feature)
        assertEquals(5, backend.call(count, CachePage()))
    }

    @Test fun `a zero cache count keeps the host limit and hooks nothing`() {
        val count = method(CachePage::class.java, "cacheCount")
        enable(OfflineCacheCount(resolver(OptionQueries.CACHE_COUNT to listOf(count)), loader, values(OfflineCacheCount.ID, OfflineCacheCount.OPTIONS)))
        assertEquals(0, backend.attachments.get())
        assertEquals(5, backend.call(count, CachePage()))
    }

    private fun danmaku(words: List<String>) = DanmakuBlockWords(
        resolver(OptionQueries.DANMAKU_CONFIG to listOf(method(DanmakuConfig::class.java, "other", Int::class.javaPrimitiveType!!))),
        loader, values(DanmakuBlockWords.ID, DanmakuBlockWords.OPTIONS, "words" to words), Bean::class.java.name,
    )

    @Test fun `block words are merged into the configuration the host applies`() {
        val apply = method(DanmakuConfig::class.java, "apply", Bean::class.java)
        enable(danmaku(listOf("a", "x", "b\"c")))
        val bean = Bean()
        backend.call(apply, DanmakuConfig(), bean)
        assertEquals("""["x","a","b\"c"]""", bean.blocking_word_list)
        bean.blocking_word_list = null
        backend.call(apply, DanmakuConfig(), bean)
        assertEquals("""["a","x","b\"c"]""", bean.blocking_word_list)
        bean.blocking_word_list = "not json"
        backend.call(apply, DanmakuConfig(), bean)
        assertEquals("not json", bean.blocking_word_list)
        assertNull(backend.call(apply, DanmakuConfig(), null))
    }

    @Test fun `no block words installs nothing`() {
        enable(danmaku(emptyList()))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `the block list codec reads and writes flat string arrays only`() {
        val tricky = listOf("plain", "with \"quote\"", "back\\slash", "tab\tline\nbreak", "ünï 中文", "\u0001")
        assertEquals(tricky, StringArrayJson.decode(StringArrayJson.encode(tricky)))
        assertEquals(emptyList<String>(), StringArrayJson.decode("[]"))
        assertEquals(emptyList<String>(), StringArrayJson.decode(" [ ] "))
        assertEquals(listOf("a", "b"), StringArrayJson.decode(""" [ "a" , "b" ] """))
        assertEquals(listOf("é/"), StringArrayJson.decode("""["é\/"]"""))
        for (bad in listOf("", "{}", "[1]", """["a",]""", """["a" "b"]""", """["a"] x""", """["a""", """["\q"]""", """["\u12"]""", "[")) {
            assertNull(bad, StringArrayJson.decode(bad))
        }
    }

    private class RecordingAdjuster : ResourcesAdjuster {
        val calls = ArrayList<Pair<Any, Int>>()
        override fun adjust(resources: Any, dpi: Int) { calls += resources to dpi }
    }

    private fun dpi(dpi: Long, adjuster: ResourcesAdjuster, vararg configurators: java.lang.reflect.Method) = CustomDpi(
        resolver(OptionQueries.DISPLAY_CONFIG to configurators.toList()), loader,
        values(CustomDpi.ID, CustomDpi.OPTIONS, "dpi" to dpi), adjuster, FakeResources::class.java, FakeContext::class.java,
    )

    @Test fun `chosen profile tab types are removed from a copy of the list`() {
        val replace = method(ProfileHost::class.java, "replaceFragments", Any::class.java, List::class.java)
        val feature = TrimProfileTabs(
            resolver(OptionQueries.PROFILE_TABS to listOf(replace)), loader,
            values(TrimProfileTabs.ID, TrimProfileTabs.OPTIONS, "hidden" to setOf("2", "10", "-1")),
        )
        enable(feature)
        val host = ProfileHost()
        val types = listOf(0, 2, 3, 10, -1)
        backend.call(replace, host, null, types)
        assertEquals(listOf<Any?>(0, 3), host.shown.single())
        assertEquals(listOf(0, 2, 3, 10, -1), types)
        backend.call(replace, host, null, listOf(0, 3))
        assertEquals(listOf<Any?>(0, 3), host.shown.last())
        backend.call(replace, host, null, null)
        disable(feature)
        backend.call(replace, host, null, types)
        assertEquals(types, host.shown.last())
    }

    @Test fun `no chosen tab types hooks nothing and a wrong shape fails`() {
        val replace = method(ProfileHost::class.java, "replaceFragments", Any::class.java, List::class.java)
        enable(TrimProfileTabs(resolver(OptionQueries.PROFILE_TABS to listOf(replace)), loader, values(TrimProfileTabs.ID, TrimProfileTabs.OPTIONS)))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `a profile tab method that does not take the type list fails the install`() {
        val oneArg = method(OneArgProfileHost::class.java, "replaceFragments", List::class.java)
        failsToInstall(
            TrimProfileTabs(
                resolver(OptionQueries.PROFILE_TABS to listOf(oneArg)), loader,
                values(TrimProfileTabs.ID, TrimProfileTabs.OPTIONS, "hidden" to setOf("2")),
            ),
            "does not take (user, List)",
        )
    }

    private fun rings(
        vararg located: Pair<io.github.nuke.oss.app.shared.dex.DexQuery, List<java.lang.reflect.Method>>,
        daily: Boolean = true, live: Boolean = true, presenter: Class<*> = RingPresenter::class.java,
    ) = HideAvatarRings(
        resolver(*located), loader,
        values(HideAvatarRings.ID, HideAvatarRings.OPTIONS, "daily" to daily, "live" to live),
        presenter.name, RingAweme::class.java.name,
    )

    @Test fun `the daily ring is not drawn and only the void overload is skipped`() {
        val draw = method(RingDrawer::class.java, "onSyncBind")
        val feature = rings(OptionQueries.DAILY_RING to listOf(draw), live = false)
        enable(feature)
        val drawer = RingDrawer()
        backend.call(draw, drawer)
        assertEquals(0, drawer.draws)
        assertEquals(true, backend.call(method(RingDrawer::class.java, "onSyncBind", Boolean::class.javaPrimitiveType!!), drawer, true))
        disable(feature)
        backend.call(draw, drawer)
        assertEquals(1, drawer.draws)
    }

    @Test fun `the live ring is removed by zeroing the room id before the avatar is set up`() {
        val setup = method(RingPresenter::class.java, "setup")
        val feature = rings(OptionQueries.LIVE_RING to listOf(setup), daily = false)
        enable(feature)
        val author = RingAuthor(12345L)
        val presenter = RingPresenter().apply { aweme = RingAweme(author) }
        backend.call(setup, presenter)
        assertEquals(0L, author.room_id)
        assertEquals(1, presenter.setups)
        backend.call(setup, RingPresenter())
        backend.call(setup, RingPresenter().apply { aweme = RingAweme(null) })
        backend.call(setup, RingPresenter().apply { aweme = RingAweme(Any()) })
        disable(feature)
        author.room_id = 7L
        backend.call(setup, presenter)
        assertEquals(7L, author.room_id)
    }

    @Test fun `both rings off hooks nothing`() {
        enable(rings(daily = false, live = false))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `a live ring method outside the presenter fails the install`() {
        failsToInstall(rings(OptionQueries.LIVE_RING to listOf(method(NotAPresenter::class.java, "setup")), daily = false), "is not a RingPresenter")
    }

    @Test fun `two video fields make the presenter ambiguous`() {
        failsToInstall(
            rings(OptionQueries.LIVE_RING to listOf(method(TwoVideoPresenter::class.java, "setup")), daily = false, presenter = TwoVideoPresenter::class.java),
            "found 2",
        )
    }

    private fun panel(hiddenModules: Set<String>, hiddenItems: Set<String>, vararg anchors: java.lang.reflect.Method) = TrimLongPressPanel(
        resolver(OptionQueries.LONG_PRESS_CONFIG to anchors.toList()), loader,
        values(TrimLongPressPanel.ID, TrimLongPressPanel.OPTIONS, "hidden-modules" to hiddenModules, "hidden-items" to hiddenItems),
    )

    @Test fun `chosen groups and items are removed once the configuration is built`() {
        val constructor = PanelConfig::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType, List::class.java, Long::class.javaPrimitiveType)
        val feature = panel(setOf("story"), setOf("report", "duet"), method(PanelConfig::class.java, "toString"))
        enable(feature)
        val share = PanelModule("share", mutableListOf(PanelItem("report"), PanelItem("save"), PanelItem("duet")))
        val story = PanelModule("story", mutableListOf(PanelItem("2_story")))
        val modules = mutableListOf<Any?>(share, story, null)
        backend.call(constructor, null, 1, modules, 5L)
        assertEquals(listOf<Any?>(share, null), modules)
        assertEquals(listOf("save"), share.moduleItems.map { (it as PanelItem).itemId })
        disable(feature)
    }

    @Test fun `both copies of the configuration class are trimmed`() {
        val first = PanelConfig::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType, List::class.java, Long::class.javaPrimitiveType)
        val second = PanelConfigCopy::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType, List::class.java, Long::class.javaPrimitiveType)
        enable(panel(setOf("story"), emptySet(), method(PanelConfig::class.java, "toString"), method(PanelConfigCopy::class.java, "toString")))
        for (constructor in listOf(first, second)) {
            val share = PanelModule("share", mutableListOf(PanelItem("save")))
            val modules = mutableListOf<Any?>(share, PanelModule("story", mutableListOf()))
            backend.call(constructor, null, 1, modules, 5L)
            assertEquals(listOf<Any?>(share), modules)
        }
    }

    @Test fun `items alone can be chosen and an untouched panel stays whole`() {
        val constructor = PanelConfig::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType, List::class.java, Long::class.javaPrimitiveType)
        enable(panel(emptySet(), setOf("save"), method(PanelConfig::class.java, "toString")))
        val share = PanelModule("share", mutableListOf(PanelItem("report"), PanelItem("save")))
        val modules = mutableListOf<Any?>(share)
        backend.call(constructor, null, 1, modules, 5L)
        assertEquals(1, modules.size)
        assertEquals(listOf("report"), share.moduleItems.map { (it as PanelItem).itemId })
    }

    @Test fun `no chosen groups or items hooks nothing`() {
        enable(panel(emptySet(), emptySet(), method(PanelConfig::class.java, "toString")))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `a configuration class without a module list constructor fails the install`() {
        failsToInstall(panel(setOf("story"), emptySet(), method(PlainConfig::class.java, "toString")), "No constructor taking a module list")
    }

    class ProgressGate {
        var hidden = 0
        fun hideForShortVideo() { hidden++ }
        fun hideForShortVideo(seconds: Int): Boolean = seconds > 0
    }

    @Test fun `the progress bar gate is skipped in its void overload only`() {
        val gate = method(ProgressGate::class.java, "hideForShortVideo")
        val feature = AlwaysShowProgress(resolver(FeedQueries.PROGRESS_BAR_GATE to listOf(gate)), loader)
        enable(feature)
        val host = ProgressGate()
        backend.call(gate, host)
        assertEquals(0, host.hidden)
        assertEquals(true, backend.call(method(ProgressGate::class.java, "hideForShortVideo", Int::class.javaPrimitiveType!!), host, 3))
        disable(feature)
        backend.call(gate, host)
        assertEquals(1, host.hidden)
    }

    @Test fun `the option lists have unique stored choices`() {
        for (option in HideHomeTabs.BOTTOM_OPTIONS + HideHomeTabs.TOP_OPTIONS + TrimProfileTabs.OPTIONS + TrimLongPressPanel.OPTIONS) {
            option as io.github.nuke.oss.app.shared.settings.MultiOption
            assertEquals(option.key, option.choices.size, option.choices.toSet().size)
        }
        assertEquals(32, TrimProfileTabs.TABS.size)
        assertEquals(32, TrimLongPressPanel.ITEMS.size)
    }

    @Test fun `the density is applied to the resources the host passes`() {
        val configure = method(DisplayConfigurator::class.java, "configure", FakeResources::class.java, FakeContext::class.java)
        val adjuster = RecordingAdjuster()
        val feature = dpi(320, adjuster, configure)
        enable(feature)
        val resources = FakeResources()
        val host = DisplayConfigurator()
        backend.call(configure, host, resources, FakeContext())
        assertEquals(listOf<Pair<Any, Int>>(resources to 320), adjuster.calls)
        assertEquals(1, host.configured)
        backend.call(configure, host, null, FakeContext())
        assertEquals(1, adjuster.calls.size)
        disable(feature)
        backend.call(configure, host, resources, FakeContext())
        assertEquals(1, adjuster.calls.size)
    }

    @Test fun `the resources argument is found in either position`() {
        val configure = method(ReversedConfigurator::class.java, "configure", FakeContext::class.java, FakeResources::class.java)
        val adjuster = RecordingAdjuster()
        enable(dpi(300, adjuster, configure))
        val resources = FakeResources()
        backend.call(configure, ReversedConfigurator(), FakeContext(), resources)
        assertEquals(listOf<Pair<Any, Int>>(resources to 300), adjuster.calls)
    }

    @Test fun `a zero density keeps the host`() {
        val configure = method(DisplayConfigurator::class.java, "configure", FakeResources::class.java, FakeContext::class.java)
        enable(dpi(0, RecordingAdjuster(), configure))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `a density below the limit fails the install`() {
        val configure = method(DisplayConfigurator::class.java, "configure", FakeResources::class.java, FakeContext::class.java)
        failsToInstall(dpi(100, RecordingAdjuster(), configure), "at least 170")
    }

    @Test fun `two configuration methods make the density ambiguous`() {
        val first = method(TwoConfigurators::class.java, "first", FakeResources::class.java, FakeContext::class.java)
        failsToInstall(dpi(320, RecordingAdjuster(), first), "found 2")
    }

    @Test fun `a new density rescales the dp sizes and keeps unset ones`() {
        val applied = densityFor(320, 2.75f, 440, 1.2f, 392, 800, 0)
        assertEquals(320, applied.densityDpi)
        assertEquals(2f, applied.density, 0f)
        assertEquals(2.4f, applied.scaledDensity, 1e-6f)
        assertEquals(539, applied.screenWidthDp)
        assertEquals(1100, applied.screenHeightDp)
        assertEquals(0, applied.smallestScreenWidthDp)
        val derived = densityFor(160, 0f, 320, 0f, 100, 100, 100)
        assertEquals(1f, derived.density, 0f)
        assertEquals(200, derived.screenWidthDp)
        assertEquals(1f, derived.scaledDensity, 0f)
    }

    class ShareAction(private val id: String) {
        @Suppress("FunctionName") private fun LJIIIZ(): String = id
    }
    class ShareItem(@JvmField val LIZ: Any?)
    class SharePanel {
        val shown = ArrayList<List<Any?>>()
        fun setData(items: MutableList<Any?>) { shown += ArrayList(items) }
    }
    class WrongSharePanel { fun setData(items: MutableList<Any?>): Boolean = true }

    private fun share(hidden: Set<String>, receiver: java.lang.reflect.Method) = TrimSharePanel(
        resolver(OptionQueries.SHARE_ACTIONS to listOf(receiver)), loader,
        values(TrimSharePanel.ID, TrimSharePanel.OPTIONS, "hidden" to hidden),
    )

    @Test fun `chosen share actions are removed from the list the panel receives`() {
        val setData = method(SharePanel::class.java, "setData", MutableList::class.java)
        val feature = share(setOf("report", "dou_plus"), setData)
        enable(feature)
        val panel = SharePanel()
        val keep = ShareItem(ShareAction("copy"))
        val unknown = ShareItem(null)
        backend.call(setData, panel, mutableListOf<Any?>(ShareItem(ShareAction("report")), keep, null, unknown, ShareItem(ShareAction("dou_plus"))))
        assertEquals(listOf(keep, null, unknown), panel.shown.single())
        disable(feature)
        val report = ShareItem(ShareAction("report"))
        backend.call(setData, panel, mutableListOf<Any?>(report))
        assertEquals(listOf<Any?>(report), panel.shown.last())
    }

    @Test fun `no hidden share actions hooks nothing`() {
        enable(share(emptySet(), method(SharePanel::class.java, "setData", MutableList::class.java)))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `a share receiver that returns a value fails the install`() {
        failsToInstall(share(setOf("report"), method(WrongSharePanel::class.java, "setData", MutableList::class.java)), "does not take a list of share actions")
    }

    class RemindInfo { @JvmField var ban_type = 3 }
    class ProfileTabInfo { @JvmField var remind: RemindInfo? = RemindInfo() }
    class ProfileUser {
        @JvmField var profile_tab_info: ProfileTabInfo? = ProfileTabInfo()
        @JvmField var punish_remind_info: RemindInfo? = RemindInfo()
    }
    class ProfileEntry { var opened = 0; fun open(user: ProfileUser?) { opened++ } }
    class WrongProfileEntry { fun open(user: Any?, flag: Boolean) {} }

    private fun profileTab(target: java.lang.reflect.Method) = KeepProfileTab(
        resolver(FeedQueries.PROFILE_ENTRY to listOf(target)), loader, ProfileUser::class.java.name, RemindInfo::class.java.name,
    )

    @Test fun `the ban type is cleared before the profile opens and the page still runs`() {
        val open = method(ProfileEntry::class.java, "open", ProfileUser::class.java)
        val feature = profileTab(open)
        enable(feature)
        val user = ProfileUser()
        val page = ProfileEntry()
        backend.call(open, page, user)
        assertEquals(0, user.profile_tab_info!!.remind!!.ban_type)
        assertEquals(3, user.punish_remind_info!!.ban_type)
        assertEquals(1, page.opened)
        val older = ProfileUser().apply { profile_tab_info = null }
        backend.call(open, page, older)
        assertEquals(0, older.punish_remind_info!!.ban_type)
        backend.call(open, page, null)
        disable(feature)
        val untouched = ProfileUser()
        backend.call(open, page, untouched)
        assertEquals(3, untouched.profile_tab_info!!.remind!!.ban_type)
    }

    @Test fun `an entry that does not take a user fails the install`() {
        failsToInstall(profileTab(method(WrongProfileEntry::class.java, "open", Any::class.java, Boolean::class.javaPrimitiveType!!)), "does not take a")
    }
}
