package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.query.GateQueries
import io.github.nuke.oss.app.shared.dex.mapSearch
import com.bytedance.ies.bullet.kit.web.SSWebView
import com.bytedance.ies.ugc.aweme.commercialize.splash.show.SplashAdActivity
import com.bytedance.im.core.model.Message
import com.ss.android.ugc.aweme.main.MainActivity
import com.ss.android.ugc.aweme.o.c2feed.core.C2Adapter
import io.github.nuke.oss.app.shared.dex.DESCRIPTOR_SEPARATOR
import io.github.nuke.oss.app.shared.dex.DexCache
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.tiktok.feature.*
import io.github.nuke.oss.app.tiktok.feature.ads.*
import io.github.nuke.oss.app.tiktok.feature.chat.*
import io.github.nuke.oss.app.tiktok.feature.comment.*
import io.github.nuke.oss.app.tiktok.feature.feed.*
import io.github.nuke.oss.app.tiktok.feature.general.*
import io.github.nuke.oss.app.tiktok.feature.miniapp.SkipInterstitialAds
import io.github.nuke.oss.app.tiktok.feature.miniapp.SkipMinorCheck
import io.github.nuke.oss.app.shared.registry.HostProcess
import io.github.nuke.oss.app.tiktok.sdk.FixedResult
import io.github.nuke.oss.core.FeatureState
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookFailure
import io.github.nuke.oss.hook.api.HookFailureSink
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.FeatureManager
import io.github.nuke.oss.runtime.RuntimeFeature
import io.github.nuke.oss.ui.hostsettings.featureLabels
import java.lang.reflect.Method
import org.junit.Assert.*
import org.junit.Test
import org.luckypray.dexkit.wrap.DexMethod

// Contract tests for the later batch of features. They run against stand-ins for the host
// classes with a fake Hook backend, so they prove the hook semantics, not that any Douyin build matches.
class MigratedFeaturesTest {
    // Chat and message stand-ins.
    class OnceState {
        var checks = 0
        fun canShow(id: String): Boolean { checks++; return true }
        fun canShow(id: String, force: Boolean): Boolean { checks++; return true }
        fun canShow(flag: Int) { checks++ }
    }

    class ReadTracker {
        var marks = 0
        fun markRead(msgId: Long) { marks++ }
        fun markRead(msgId: Long, silent: Boolean): Boolean { marks++; return silent }
    }

    class TypingNotifier {
        var sent = 0
        fun refresh() { sent++ }
    }

    class EmojiFeed {
        var fed = 0
        fun feed(items: MutableList<String>) { fed++ }
        fun feed(items: List<String>, big: Boolean) { fed++ }
        fun feed(count: Int) { fed++ }
    }

    class EmojiEntry {
        @JvmField var hide = 1
        override fun toString() = "entry"
    }

    class EmojiResponse {
        val items: List<Any> = listOf(EmojiEntry(), "other", EmojiEntry())
        fun entries(): List<Any> = items
        override fun toString() = "response"
    }

    open class Adapter { open fun getItemCount() = 5 }
    class HeaderAdapter : Adapter() {
        override fun getItemCount() = 3
        fun log() {}
    }

    class EffectsComponent {
        var setups = 0
        fun onViewCreated(view: Any?) { setups++ }
    }

    class LegacyEffects {
        var setups = 0
        fun initHandlers() { setups++ }
    }

    class ForwardSettings { fun limit(a: Any?, b: Any?, c: Any?): Int = 30 }

    open class LongPressItem { open fun getTag(): String = "item" }
    class CopiedComment(@JvmField val text: String?)
    class LongPressModel(@JvmField val comment: CopiedComment?)
    // As in Douyin 40.7.0, the copy action also overrides the long-press item's String tag.
    class CommentCopier(@JvmField val model: LongPressModel? = null) : LongPressItem() {
        fun copied(): String = "@alice: nice one"
        override fun getTag(): String = "copy"
        fun anchor() {}
    }

    // The tag may come from an interface of the long-press item instead of a superclass.
    interface TaggedItem { fun getTag(): String }
    class InterfaceCopier(@JvmField val model: LongPressModel? = null) : TaggedItem {
        fun copied(): String = "@alice: nice one"
        override fun getTag(): String = "copy"
        fun anchor() {}
    }

    class AmbiguousCopier {
        fun copied(): String = "a"
        fun other(): String = "b"
    }

    // Feed and playback stand-ins.
    class FeedFragment {
        var refreshes = 0
        fun onResume(visible: Boolean): Boolean { refreshes++; return false }
        fun wrongShape(visible: Boolean): Int = 0
    }

    class Model
    class Aweme
    class SkipHandlerA { var skips = 0; fun onItem(model: Model, aweme: Aweme) { skips++ } }
    class SkipHandlerB { var skips = 0; fun onItem(model: Model, aweme: Aweme) { skips++ } }

    class CopyrightSettings {
        fun hasCopyright(): Boolean = true
        fun broken(): Boolean = error("settings unavailable")
    }

    class VisitRecorder {
        var recorded = 0
        fun invoke(a: Any?, b: Any?): Any? { recorded++; return "done" }
    }

    class MinorGuard {
        var dispatched = 0
        fun realDispatch(task: Any?): Boolean { dispatched++; return false }
        fun realDispatch(task: Any?, attempt: Int): Boolean { dispatched++; return false }
    }

    class InterstitialView {
        var shown = 0
        fun show(position: Int, source: String, muted: Boolean, auto: Boolean) { shown++ }
        fun show(position: Int) { shown++ }
    }

    class BadMinorGuard {
        fun realDispatch(task: Any?): Int = 0
    }

    class DramaItem(@JvmField val is_ads: Boolean)
    class PlainItem
    class DramaLoader {
        val received = ArrayList<List<Any?>>()
        fun onLoaded(items: MutableList<Any?>, more: Boolean) { received += items.toList() }
    }

    class AlbumScanner {
        fun isBlocked(path: String): Boolean = true
        fun isBlocked(path: String, depth: Int): Int = depth
    }

    private val loader: ClassLoader = checkNotNull(javaClass.classLoader)
    private val backend = FakeHookBackend()
    private val failures = ArrayList<HookFailure>()
    private val manager = FeatureManager("g", DefaultHookService(backend, HookFailureSink { failures += it }), Logger {})

    private fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method = type.getDeclaredMethod(name, *parameters)

    // Answers each target with the stand-in methods the test names, joined the way the real search caches them.
    private fun resolver(vararg located: Pair<DexQuery, List<Method>>) = DexResolver(object : DexCache {
        override fun get(key: String): String? = null
        override fun put(key: String, descriptor: String) {}
    }, "scope", mapSearch { queries ->
        queries.mapValues { (_, query) ->
            located.first { it.first === query }.second.joinToString(DESCRIPTOR_SEPARATOR) { DexMethod(it).serialize() }
        }
    }, generatedDexQueries)

    private fun enable(feature: RuntimeFeature) {
        manager.register(feature)
        val status = manager.setEnabled(feature.id, true)
        assertEquals(status.reason, FeatureState.ACTIVE, status.state)
    }

    private fun disable(feature: RuntimeFeature) = manager.setEnabled(feature.id, false)

    private fun failsToInstall(feature: RuntimeFeature, fragment: String) {
        manager.register(feature)
        val status = manager.setEnabled(feature.id, true)
        assertEquals(FeatureState.FAILED, status.state)
        assertTrue(status.reason, status.reason!!.contains(fragment))
        assertEquals(0, backend.attachments.get())
    }

    private fun fixed(
        id: String, located: List<Pair<DexQuery, List<Method>>>, returnType: Class<*>, value: Any?,
        mode: FixedResult.Mode = FixedResult.Mode.SKIP,
    ) = FixedResult(id, resolver(*located.toTypedArray()), loader, located.map { it.first }, returnType, value, mode)

    @Test fun `the feed is kept when the resume check is answered handled`() {
        val resume = method(FeedFragment::class.java, "onResume", Boolean::class.javaPrimitiveType!!)
        val feature = fixed("t.keep", listOf(FeedQueries.RESUME_GATE to listOf(resume)), Boolean::class.javaPrimitiveType!!, true)
        enable(feature)
        val fragment = FeedFragment()
        assertEquals(true, backend.call(resume, fragment, true))
        assertEquals(0, fragment.refreshes)
        disable(feature)
        assertEquals(false, backend.call(resume, fragment, true))
        assertEquals(1, fragment.refreshes)
    }

    @Test fun `a host method that returns another type fails the install without hooking`() {
        val wrong = method(FeedFragment::class.java, "wrongShape", Boolean::class.javaPrimitiveType!!)
        val feature = fixed("t.wrong", listOf(FeedQueries.RESUME_GATE to listOf(wrong)), Boolean::class.javaPrimitiveType!!, true)
        failsToInstall(feature, "returns int")
    }

    @Test fun `every unavailable-video handler is skipped`() {
        val a = method(SkipHandlerA::class.java, "onItem", Model::class.java, Aweme::class.java)
        val b = method(SkipHandlerB::class.java, "onItem", Model::class.java, Aweme::class.java)
        val feature = fixed("t.skip", listOf(FeedQueries.UNAVAILABLE_VIDEO_SKIP to listOf(a, b)), Void.TYPE, null)
        enable(feature)
        val first = SkipHandlerA()
        val second = SkipHandlerB()
        backend.call(a, first, Model(), Aweme())
        backend.call(b, second, Model(), Aweme())
        assertEquals(0, first.skips + second.skips)
        disable(feature)
        backend.call(a, first, Model(), Aweme())
        assertEquals(1, first.skips)
    }

    @Test fun `the copyright check is reported clear and a host failure is left alone`() {
        val has = method(CopyrightSettings::class.java, "hasCopyright")
        val broken = method(CopyrightSettings::class.java, "broken")
        val feature = fixed(
            "t.copyright", listOf(FeedQueries.COPYRIGHT_GATE to listOf(has, broken)), Boolean::class.javaPrimitiveType!!, false,
            FixedResult.Mode.REPLACE,
        )
        enable(feature)
        val settings = CopyrightSettings()
        assertEquals(false, backend.call(has, settings))
        val error = assertThrows(IllegalStateException::class.java) { backend.call(broken, settings) }
        assertEquals("settings unavailable", error.message)
        disable(feature)
        assertEquals(true, backend.call(has, settings))
    }

    @Test fun `the profile visit record never runs`() {
        val invoke = method(VisitRecorder::class.java, "invoke", Any::class.java, Any::class.java)
        val feature = fixed(FixedResultIds.HIDE_PROFILE_VISITS, listOf(FeedQueries.PROFILE_VISIT to listOf(invoke)), Any::class.java, null)
        enable(feature)
        val recorder = VisitRecorder()
        assertNull(backend.call(invoke, recorder, 1, 2))
        assertEquals(0, recorder.recorded)
        disable(feature)
        assertEquals("done", backend.call(invoke, recorder, 1, 2))
    }

    @Test fun `the forward picker reports the lifted limit`() {
        val limit = method(ForwardSettings::class.java, "limit", Any::class.java, Any::class.java, Any::class.java)
        val feature = fixed(
            FixedResultIds.LIFT_FORWARD_LIMIT, listOf(ChatQueries.FORWARD_LIMIT to listOf(limit)), Int::class.javaPrimitiveType!!, 1000,
        )
        enable(feature)
        assertEquals(1000, backend.call(limit, ForwardSettings(), 1, 2, 3))
        disable(feature)
        assertEquals(30, backend.call(limit, ForwardSettings(), 1, 2, 3))
    }

    @Test fun `the minor check is answered as passed without running`() {
        val dispatch = method(MinorGuard::class.java, "realDispatch", Any::class.java)
        val feature = SkipMinorCheck(resolver(GateQueries.MINOR_CHECK to listOf(dispatch)), loader, HostProcess.MAIN)
        enable(feature)
        val guard = MinorGuard()
        assertEquals(true, backend.call(dispatch, guard, "task"))
        assertEquals(0, guard.dispatched)
        disable(feature)
        assertEquals(false, backend.call(dispatch, guard, "task"))
    }

    @Test fun `every realDispatch of the mini game manager is answered passed`() {
        val single = method(MinorGuard::class.java, "realDispatch", Any::class.java)
        val pair = method(MinorGuard::class.java, "realDispatch", Any::class.java, Int::class.javaPrimitiveType!!)
        // A mini-game process searches no Dex; the manager is found by name.
        val feature = SkipMinorCheck(resolver(), loader, HostProcess.MINI_APP, MinorGuard::class.java.name)
        enable(feature)
        val guard = MinorGuard()
        assertEquals(true, backend.call(single, guard, "task"))
        assertEquals(true, backend.call(pair, guard, "task", 2))
        assertEquals(0, guard.dispatched)
        assertEquals(2, backend.attachments.get())
        disable(feature)
        assertEquals(false, backend.call(pair, guard, "task", 2))
    }

    @Test fun `the interstitial ad view never shows`() {
        val show = method(InterstitialView::class.java, "show", Int::class.javaPrimitiveType!!, String::class.java,
            Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
        val other = method(InterstitialView::class.java, "show", Int::class.javaPrimitiveType!!)
        val feature = SkipInterstitialAds(loader, Logger {}, InterstitialView::class.java.name)
        enable(feature)
        val view = InterstitialView()
        assertNull(backend.call(show, view, 1, "game", false, true))
        backend.call(other, view, 1)
        assertEquals(1, view.shown)
        disable(feature)
        backend.call(show, view, 1, "game", false, true)
        assertEquals(2, view.shown)
    }

    @Test fun `a mini game manager whose check is not boolean fails to install`() {
        failsToInstall(SkipMinorCheck(resolver(), loader, HostProcess.MINI_APP, BadMinorGuard::class.java.name), "no longer returns boolean")
    }

    @Test fun `typing status refreshes are not sent`() {
        val typing = method(TypingNotifier::class.java, "refresh")
        val feature = fixed(FixedResultIds.BLOCK_TYPING_STATUS_UPLOAD, listOf(ChatQueries.TYPING_STATUS to listOf(typing)), Void.TYPE, null)
        enable(feature)
        val notifier = TypingNotifier()
        backend.call(typing, notifier)
        assertEquals(0, notifier.sent)
        disable(feature)
        backend.call(typing, notifier)
        assertEquals(1, notifier.sent)
    }

    @Test fun `ad entries are removed from the episode list in place`() {
        val onLoaded = method(DramaLoader::class.java, "onLoaded", MutableList::class.java, Boolean::class.javaPrimitiveType!!)
        val feature = SkipShortDramaAds(resolver(FeedQueries.SHORT_DRAMA_FEED to listOf(onLoaded)), loader)
        enable(feature)
        val loaderFake = DramaLoader()
        val keep = DramaItem(false)
        val plain = PlainItem()
        val items = mutableListOf<Any?>(DramaItem(true), keep, null, DramaItem(true), plain)
        backend.call(onLoaded, loaderFake, items, true)
        assertEquals(listOf(keep, null, plain), loaderFake.received.single())
        assertEquals(listOf(keep, null, plain), items)
        disable(feature)
        val untouched = mutableListOf<Any?>(DramaItem(true))
        backend.call(onLoaded, loaderFake, untouched, true)
        assertEquals(1, loaderFake.received.last().size)
    }

    @Test fun `the splash ad screen opens the main screen`() {
        val opened = ArrayList<Pair<Any, Class<*>>>()
        val feature = SkipSplashAd(loader) { splash, main -> opened += splash to main }
        enable(feature)
        val splash = SplashAdActivity()
        val onCreate = method(SplashAdActivity::class.java, "onCreate", Any::class.java)
        backend.call(onCreate, splash, null)
        assertEquals(1, splash.created)
        assertEquals(listOf<Pair<Any, Class<*>>>(splash to MainActivity::class.java), opened)
        disable(feature)
        backend.call(onCreate, splash, null)
        assertEquals(1, opened.size)
    }

    @Test fun `the quick emoji list is emptied and only list overloads are hooked`() {
        val feed = method(EmojiFeed::class.java, "feed", MutableList::class.java)
        val feature = HideChatQuickEmoji(resolver(ChatQueries.QUICK_EMOJI to listOf(feed)), loader)
        enable(feature)
        val fake = EmojiFeed()
        val items = mutableListOf("a", "b")
        backend.call(feed, fake, items)
        assertTrue(items.isEmpty())
        assertEquals(0, fake.fed)
        val sized = method(EmojiFeed::class.java, "feed", List::class.java, Boolean::class.javaPrimitiveType!!)
        backend.call(sized, fake, mutableListOf("c"), true)
        assertEquals(0, fake.fed)
        backend.call(method(EmojiFeed::class.java, "feed", Int::class.javaPrimitiveType!!), fake, 3)
        assertEquals(1, fake.fed)
        disable(feature)
        val kept = mutableListOf("a")
        backend.call(feed, fake, kept)
        assertEquals(listOf("a"), kept)
        assertEquals(2, fake.fed)
    }

    @Test fun `hidden emoji entries are made visible after the host reads the list`() {
        val entries = method(EmojiResponse::class.java, "entries")
        val feature = ShowHiddenEmoji(
            resolver(
                FeedQueries.EMOJI_ENTRY to listOf(method(EmojiEntry::class.java, "toString")),
                FeedQueries.EMOJI_RESPONSE to listOf(method(EmojiResponse::class.java, "toString")),
            ),
            loader,
        )
        enable(feature)
        val response = EmojiResponse()
        backend.call(entries, response)
        assertEquals(listOf(0, 0), response.items.filterIsInstance<EmojiEntry>().map { it.hide })
        disable(feature)
        response.items.filterIsInstance<EmojiEntry>().forEach { it.hide = 1 }
        backend.call(entries, response)
        assertEquals(listOf(1, 1), response.items.filterIsInstance<EmojiEntry>().map { it.hide })
    }

    @Test fun `the message header reports no items`() {
        val feature = HideMessageTopFriends(resolver(ChatQueries.MESSAGE_HEADER to listOf(method(HeaderAdapter::class.java, "log"))), loader)
        enable(feature)
        val count = method(HeaderAdapter::class.java, "getItemCount")
        assertEquals(0, backend.call(count, HeaderAdapter()))
        disable(feature)
        assertEquals(3, backend.call(count, HeaderAdapter()))
    }

    @Test fun `the effects component skips its setup, or its handler setup on older hosts`() {
        val component = EffectsComponent()
        val setup = method(EffectsComponent::class.java, "onViewCreated", Any::class.java)
        val feature = DisableChatEffects(resolver(ChatQueries.CHAT_EFFECTS to listOf(setup)), loader)
        enable(feature)
        backend.call(setup, component, null)
        assertEquals(0, component.setups)
        disable(feature)
        backend.call(setup, component, null)
        assertEquals(1, component.setups)
    }

    @Test fun `the effects handler setup is used when there is no view setup`() {
        val handlers = method(LegacyEffects::class.java, "initHandlers")
        val feature = DisableChatEffects(resolver(ChatQueries.CHAT_EFFECTS to listOf(handlers)), loader)
        enable(feature)
        val legacy = LegacyEffects()
        backend.call(handlers, legacy)
        assertEquals(0, legacy.setups)
    }

    @Test fun `a class with neither effects setup fails the install`() {
        val feature = DisableChatEffects(resolver(ChatQueries.CHAT_EFFECTS to listOf(method(TypingNotifier::class.java, "refresh"))), loader)
        failsToInstall(feature, "No onViewCreated(view) or initHandlers()")
    }

    @Test fun `view-once checks answer false and marks are skipped for matching overloads only`() {
        val canShow = method(OnceState::class.java, "canShow", String::class.java)
        val markRead = method(ReadTracker::class.java, "markRead", Long::class.javaPrimitiveType!!)
        val feature = KeepViewOnceMessages(resolver(ChatQueries.VIEW_ONCE_TEXT to listOf(canShow), ChatQueries.VIEW_ONCE_MARK to listOf(markRead)), loader)
        enable(feature)
        val state = OnceState()
        assertEquals(false, backend.call(canShow, state, "m"))
        assertEquals(false, backend.call(method(OnceState::class.java, "canShow", String::class.java, Boolean::class.javaPrimitiveType!!), state, "m", true))
        assertEquals(0, state.checks)
        backend.call(method(OnceState::class.java, "canShow", Int::class.javaPrimitiveType!!), state, 1)
        assertEquals(1, state.checks)
        val tracker = ReadTracker()
        backend.call(markRead, tracker, 9L)
        assertEquals(0, tracker.marks)
        assertEquals(true, backend.call(method(ReadTracker::class.java, "markRead", Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!), tracker, 9L, true))
        assertEquals(1, tracker.marks)
        disable(feature)
        assertEquals(true, backend.call(canShow, state, "m"))
    }

    private fun commentCopy() = CleanCommentCopy(
        resolver(FeedQueries.COMMENT_COPY to listOf(method(CommentCopier::class.java, "anchor"))), loader, CopiedComment::class.java.name,
    )

    @Test fun `the author prefix is removed from copied comment text`() {
        val copied = method(CommentCopier::class.java, "copied")
        val feature = commentCopy()
        enable(feature)
        assertEquals("nice one", backend.call(copied, CommentCopier()))
        assertEquals("nice one", backend.call(copied, CommentCopier(LongPressModel(CopiedComment("")))))
        assertEquals(1, backend.attachments.get())
        disable(feature)
        assertEquals("@alice: nice one", backend.call(copied, CommentCopier()))
    }

    @Test fun `a tag declared by an interface is not mistaken for the copied text`() {
        val copied = method(InterfaceCopier::class.java, "copied")
        val feature = CleanCommentCopy(resolver(FeedQueries.COMMENT_COPY to listOf(method(InterfaceCopier::class.java, "anchor"))), loader, CopiedComment::class.java.name)
        enable(feature)
        assertEquals("nice one", backend.call(copied, InterfaceCopier()))
    }

    @Test fun `the comment's own text is copied when the action holds the comment`() {
        val copied = method(CommentCopier::class.java, "copied")
        enable(commentCopy())
        assertEquals("nice one @bob: really", backend.call(copied, CommentCopier(LongPressModel(CopiedComment("nice one @bob: really")))))
    }

    @Test fun `only a leading mention is stripped`() {
        assertEquals("hi", CleanCommentCopy.stripAuthorPrefix("@alice: hi"))
        assertEquals("b", CleanCommentCopy.stripAuthorPrefix("@a:b"))
        assertEquals("hello @a: b", CleanCommentCopy.stripAuthorPrefix("hello @a: b"))
        assertEquals("plain", CleanCommentCopy.stripAuthorPrefix("plain"))
    }

    @Test fun `two string getters make the comment copy ambiguous`() {
        val feature = CleanCommentCopy(resolver(FeedQueries.COMMENT_COPY to listOf(method(AmbiguousCopier::class.java, "copied"))), loader)
        failsToInstall(feature, "Expected one copied-text getter")
    }

    @Test fun `album scanning is allowed and only boolean checks are hooked`() {
        val blocked = method(AlbumScanner::class.java, "isBlocked", String::class.java)
        val feature = AllowAlbumQrScan(resolver(GateQueries.ALBUM_SCAN to listOf(blocked)), loader)
        enable(feature)
        assertEquals(false, backend.call(blocked, AlbumScanner(), "/a.png"))
        assertEquals(4, backend.call(method(AlbumScanner::class.java, "isBlocked", String::class.java, Int::class.javaPrimitiveType!!), AlbumScanner(), "/a.png", 4))
        disable(feature)
        assertEquals(true, backend.call(blocked, AlbumScanner(), "/a.png"))
    }

    @Test fun `a scanner without a boolean check fails the install`() {
        val feature = AllowAlbumQrScan(resolver(GateQueries.ALBUM_SCAN to listOf(method(TypingNotifier::class.java, "refresh"))), loader)
        failsToInstall(feature, "No boolean refresh")
    }

    @Test fun `the security interstitial is unwrapped and other links pass`() {
        val feature = OpenExternalLinks(loader)
        enable(feature)
        val view = SSWebView()
        val loadUrl = method(SSWebView::class.java, "loadUrl", String::class.java)
        val wrapped = "https://seclink.bytedance.com?app_version=40.7.0&target=https%3A%2F%2Fexample.com%2Fpage%3Fid%3D5&x=1"
        backend.call(loadUrl, view, wrapped)
        backend.call(loadUrl, view, "https://www.douyin.com/video/1")
        backend.call(loadUrl, view, "https://seclink.bytedance.com?app_version=1&x=%zz")
        assertEquals(
            listOf("https://example.com/page", "https://www.douyin.com/video/1", "https://seclink.bytedance.com?app_version=1&x=%zz"),
            view.loaded,
        )
        disable(feature)
        backend.call(loadUrl, view, wrapped)
        assertEquals(wrapped, view.loaded.last())
    }

    @Test fun `an interstitial without a target is left alone`() {
        assertNull(OpenExternalLinks.unwrapSecurityLink("https://seclink.bytedance.com?app_version=1"))
        assertNull(OpenExternalLinks.unwrapSecurityLink("https://seclink.bytedance.com?app_version=1&target=&x=2"))
    }

    @Test fun `the related list adapter reports no items while enabled`() {
        val feature = HideRelatedRecommendations(loader)
        enable(feature)
        val count = method(C2Adapter::class.java, "getItemCount")
        assertEquals(0, backend.call(count, C2Adapter(7)))
        disable(feature)
        assertEquals(7, backend.call(count, C2Adapter(7)))
    }

    @Test fun `every report feature has a unique id and a settings label`() {
        val features = generatedHookEntries.flatMap { it.create(testServices(resolver(), loader)) }
        assertEquals(features.size, features.map { it.id }.toSet().size)
        for (feature in features) assertTrue("${feature.id} has no label", feature.id in featureLabels)
        assertTrue(failures.isEmpty())
    }
}
