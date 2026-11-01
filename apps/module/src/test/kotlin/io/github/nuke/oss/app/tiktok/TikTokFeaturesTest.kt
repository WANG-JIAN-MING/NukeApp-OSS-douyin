package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.query.TikTokQueries
import io.github.nuke.oss.app.shared.dex.mapSearch
import com.ss.android.ugc.aweme.requesttask.idle.CheckUpdateRequest
import com.ss.android.ugc.aweme.simkit.SimKitService
import io.github.nuke.oss.app.shared.dex.DexCache
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.tiktok.feature.comment.*
import io.github.nuke.oss.app.tiktok.feature.feed.*
import io.github.nuke.oss.app.tiktok.feature.general.*
import io.github.nuke.oss.app.tiktok.feature.playback.*
import io.github.nuke.oss.core.FeatureState
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookFailure
import io.github.nuke.oss.hook.api.HookFailureSink
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.FeatureManager
import io.github.nuke.oss.runtime.RuntimeFeature
import org.junit.Assert.*
import org.junit.Test
import org.luckypray.dexkit.wrap.DexMethod

class TikTokFeaturesTest {
    class Aweme
    open class BaseVideoEvent(@JvmField val type: Int)
    class VideoEvent(type: Int, val aweme: Aweme?) : BaseVideoEvent(type)
    class PlayerEvent(private val code: Int) { fun getStatus(): Int = code }

    // Stands in for BaseListFragmentPanel; the String parameter replaces MotionEvent, which needs Android.
    class Panel {
        val events = ArrayList<VideoEvent>()
        val pauses = ArrayList<Boolean>()
        var current: Aweme? = Aweme()
        var broken = false
        fun onDoubleTap(event: String) {}
        fun handleVideoEvent(event: VideoEvent) { events += event }
        fun onVideoPlayerEvent(event: PlayerEvent) {}
        fun handlePause(fromUser: Boolean) { pauses += fromUser }
        fun getCurrentAweme(): Aweme? = if (broken) error("host state unavailable") else current
    }

    class AvatarPresenter {
        val states = ArrayList<Int>()
        fun updateFollowView(state: Int, animate: Boolean) { states += state }
    }

    private val panelType = Panel::class.java
    private val methods = mapOf(
        TikTokQueries.DOUBLE_TAP to panelType.getDeclaredMethod("onDoubleTap", String::class.java),
        TikTokQueries.VIDEO_EVENT to panelType.getDeclaredMethod("handleVideoEvent", VideoEvent::class.java),
        TikTokQueries.PLAYER_EVENT to panelType.getDeclaredMethod("onVideoPlayerEvent", PlayerEvent::class.java),
        TikTokQueries.PAUSE to panelType.getDeclaredMethod("handlePause", Boolean::class.javaPrimitiveType),
        TikTokQueries.CURRENT_AWEME to panelType.getDeclaredMethod("getCurrentAweme"),
        TikTokQueries.AVATAR_FOLLOW to AvatarPresenter::class.java.getDeclaredMethod(
            "updateFollowView", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        ),
    )
    private val loader: ClassLoader = checkNotNull(javaClass.classLoader)
    private val backend = FakeHookBackend()
    private val failures = ArrayList<HookFailure>()
    private val manager = FeatureManager("g", DefaultHookService(backend, HookFailureSink { failures += it }), Logger {})
    private val resolver = DexResolver(object : DexCache {
        val entries = HashMap<String, String>()
        override fun get(key: String) = entries[key]
        override fun put(key: String, descriptor: String) { entries[key] = descriptor }
    }, "scope", mapSearch { queries -> queries.mapValues { (_, query) -> DexMethod(methods.getValue(query)).serialize() } }, generatedDexQueries)

    private fun enable(feature: RuntimeFeature) {
        manager.register(feature)
        val status = manager.setEnabled(feature.id, true)
        assertEquals(status.reason, FeatureState.ACTIVE, status.state)
    }

    private fun disable(feature: RuntimeFeature) = manager.setEnabled(feature.id, false)

    private fun call(query: DexQuery, receiver: Any, vararg arguments: Any?) =
        backend.call(methods.getValue(query), receiver, *arguments)

    @Test fun `double tap posts the open-comment event for the current video`() {
        val feature = DoubleTapOpenComment(resolver, loader)
        enable(feature)
        val panel = Panel()
        call(TikTokQueries.DOUBLE_TAP, panel, "tap")
        val event = panel.events.single()
        assertEquals(7, event.type)
        assertSame(panel.current, event.aweme)

        panel.current = null
        call(TikTokQueries.DOUBLE_TAP, panel, "tap")
        assertEquals(1, panel.events.size)
        disable(feature)
        panel.current = Aweme()
        call(TikTokQueries.DOUBLE_TAP, panel, "tap")
        assertEquals(1, panel.events.size)
        assertTrue(failures.isEmpty())
    }

    @Test fun `only the completion status pauses playback`() {
        val feature = PauseOnPlaybackCompleted(resolver, loader)
        enable(feature)
        val panel = Panel()
        call(TikTokQueries.PLAYER_EVENT, panel, PlayerEvent(3))
        assertTrue(panel.pauses.isEmpty())
        call(TikTokQueries.PLAYER_EVENT, panel, PlayerEvent(7))
        assertEquals(listOf(true), panel.pauses)
        disable(feature)
        call(TikTokQueries.PLAYER_EVENT, panel, PlayerEvent(7))
        assertEquals(1, panel.pauses.size)
    }

    @Test fun `resume events are dropped and other video events pass`() {
        val feature = BlockForegroundPlaybackResume(resolver, loader)
        enable(feature)
        val panel = Panel()
        call(TikTokQueries.VIDEO_EVENT, panel, VideoEvent(0, null))
        call(TikTokQueries.VIDEO_EVENT, panel, VideoEvent(7, null))
        assertEquals(listOf(7), panel.events.map { it.type })
        disable(feature)
        call(TikTokQueries.VIDEO_EVENT, panel, VideoEvent(0, null))
        assertEquals(listOf(7, 0), panel.events.map { it.type })
    }

    @Test fun `the not-followed state is reported as followed`() {
        val feature = HideFeedAvatarFollow(resolver, loader)
        enable(feature)
        val presenter = AvatarPresenter()
        call(TikTokQueries.AVATAR_FOLLOW, presenter, 0, false)
        call(TikTokQueries.AVATAR_FOLLOW, presenter, 2, false)
        assertEquals(listOf(1, 2), presenter.states)
        disable(feature)
        call(TikTokQueries.AVATAR_FOLLOW, presenter, 0, false)
        assertEquals(listOf(1, 2, 0), presenter.states)
    }

    @Test fun `update requests are skipped while enabled`() {
        val feature = DisableUpdateChecks(loader)
        enable(feature)
        val request = CheckUpdateRequest()
        val method = CheckUpdateRequest::class.java.getDeclaredMethod("request")
        backend.call(method, request)
        assertEquals(0, request.requests)
        disable(feature)
        backend.call(method, request)
        assertEquals(1, request.requests)
    }

    @Test fun `bitrate quality is written and reported as high`() {
        val feature = ForceHighVideoQuality(loader)
        enable(feature)
        val service = SimKitService()
        val set = SimKitService::class.java.getDeclaredMethod("setBitrateQuality", Int::class.javaPrimitiveType)
        val get = SimKitService::class.java.getDeclaredMethod("getBitrateQuality")
        backend.call(set, service, 0)
        assertEquals(1, service.quality)
        service.quality = 0
        assertEquals(1, backend.call(get, service))
        disable(feature)
        assertEquals(0, backend.call(get, service))
    }

    @Test fun `a host event without the expected member fails the install`() {
        val missing = DexResolver(object : DexCache {
            override fun get(key: String): String? = null
            override fun put(key: String, descriptor: String) {}
        }, "scope", mapSearch { queries -> queries.mapValues { DexMethod(methods.getValue(TikTokQueries.DOUBLE_TAP)).serialize() } }, generatedDexQueries)
        val feature = BlockForegroundPlaybackResume(missing, loader)
        manager.register(feature)
        val status = manager.setEnabled(feature.id, true)
        assertEquals(FeatureState.FAILED, status.state)
        assertTrue(status.reason!!.contains("type"))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `a failing host call inside a callback is reported and the tap still completes`() {
        enable(DoubleTapOpenComment(resolver, loader))
        val panel = Panel().apply { broken = true }
        assertNull(call(TikTokQueries.DOUBLE_TAP, panel, "tap"))
        assertTrue(panel.events.isEmpty())
        val failure = failures.single()
        assertEquals("OpenComment", failure.subscriptionId)
        assertEquals("after", failure.phase)
    }
}
