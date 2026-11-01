package io.github.nuke.oss.app.tiktok

import com.ss.android.ugc.aweme.im.sdk.relations.IMTeenModeManager
import io.github.nuke.oss.app.tiktok.feature.general.HideTeenModeDialog
import io.github.nuke.oss.app.tiktok.feature.miniapp.DialogArguments
import io.github.nuke.oss.app.tiktok.feature.miniapp.RewardAdSession
import io.github.nuke.oss.app.tiktok.feature.miniapp.SkipMiniAppRealNameDialog
import io.github.nuke.oss.app.tiktok.feature.miniapp.SkipMiniAppRewardAds
import io.github.nuke.oss.app.tiktok.feature.miniapp.canCancelRealName
import io.github.nuke.oss.app.tiktok.query.MiniAppQueries
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the features of the mini-app processes and the simplify features, with stand-in host classes.
class MiniAppFeaturesTest : HookHarness() {
    private val scheduler = ManualScheduler()
    private val logged = ArrayList<String>()
    private val logger = Logger { logged += it }

    // ---- real-name dialog
    class VerifyDialog { @JvmField var state = 1; fun onStart() {} }

    private val dialogs = RecordingRealNameDialogs()
    private val onStart = method(VerifyDialog::class.java, "onStart")

    private fun realName() = SkipMiniAppRealNameDialog(
        resolverOf(MiniAppQueries.REAL_NAME_STARTED to onStart, MiniAppQueries.REAL_NAME_STATE to VerifyDialog::class.java.getDeclaredField("state")),
        loader, scheduler, { dialogs }, logger,
    )

    @Test fun `the permitted real-name prompt is closed once through its own button`() {
        enable(realName())
        dialogs.arguments = DialogArguments("api", 4)
        dialogs.button = "button"
        val dialog = VerifyDialog()
        backend.call(onStart, dialog)
        assertTrue(dialogs.pressed.isEmpty())
        scheduler.advance(0)
        assertEquals(listOf<Any>("button"), dialogs.pressed)
        backend.call(onStart, dialog)
        scheduler.advance(0)
        assertEquals(1, dialogs.pressed.size)
    }

    @Test fun `a forced, foreign or not yet waiting prompt is left alone`() {
        enable(realName())
        dialogs.button = "button"
        for ((arguments, state) in listOf(DialogArguments("api", 0) to 1, DialogArguments("other", 4) to 1, DialogArguments("api", 4) to 0)) {
            dialogs.arguments = arguments
            backend.call(onStart, VerifyDialog().apply { this.state = state })
        }
        dialogs.arguments = null
        backend.call(onStart, VerifyDialog())
        dialogs.arguments = DialogArguments("api", 4)
        dialogs.button = null
        backend.call(onStart, VerifyDialog())
        scheduler.advance(0)
        assertTrue(dialogs.pressed.isEmpty())
        assertEquals(0, scheduler.pending())
    }

    @Test fun `a press that was queued does nothing once the feature is off`() {
        val feature = realName()
        enable(feature)
        dialogs.arguments = DialogArguments("api", 4)
        dialogs.button = "button"
        backend.call(onStart, VerifyDialog())
        disable(feature)
        scheduler.advance(0)
        assertTrue(dialogs.pressed.isEmpty())
    }

    @Test fun `the real-name rule needs an API origin, a waiting state and the close bit`() {
        assertTrue(canCancelRealName("api", 1, 4))
        assertTrue(canCancelRealName("api", 1, 7))
        assertFalse(canCancelRealName("api", 1, 3))
        assertFalse(canCancelRealName("api", 2, 4))
        assertFalse(canCancelRealName(null, 1, 4))
    }

    // ---- teen mode dialog
    @Test fun `the teen mode dialog is skipped only on the show branch of void methods`() {
        enable(HideTeenModeDialog(loader))
        val manager = IMTeenModeManager()
        val show = method(IMTeenModeManager::class.java, "show", Any::class.java, Boolean::class.javaPrimitiveType!!)
        backend.call(show, manager, null, true)
        backend.call(show, manager, null, false)
        assertEquals(listOf("show"), manager.shown)
        backend.call(method(IMTeenModeManager::class.java, "onStateChanged", Any::class.java, Boolean::class.javaPrimitiveType!!), manager, null, true)
        assertEquals(listOf("show", "state"), manager.shown)
        assertEquals(7, backend.call(method(IMTeenModeManager::class.java, "count", Any::class.java, Boolean::class.javaPrimitiveType!!), manager, null, true))
    }

    // ---- reward video
    class AppRequest { @JvmField var unit: String? = "ad1"; @JvmField var act: String? = "show"; val events = ArrayList<Pair<String, Any?>>(); fun event(name: String, payload: Any?) { events += name to payload } }
    class AppCallback { val log = ArrayList<String>(); fun success() { log += "ok" }; fun failure(a: Int, b: Int, c: String, d: String) { log += "fail:$a:$b:$c:$d" } }
    class AppService : Contexts() { var originals = 0; fun create(request: AppRequest, callback: AppCallback) { originals++ }; fun operate(request: AppRequest, callback: AppCallback) { originals++ } }
    class GameRequest { @JvmField var unit: String? = "g1"; @JvmField var act: String? = "show"; val events = ArrayList<Pair<String, Any?>>(); fun event(name: String, payload: Any?) { events += name to payload } }
    class GameCallback { val log = ArrayList<String>(); fun success(value: Any?) { log += "ok" }; fun failure(a: Int, b: Int, c: String, d: String) { log += "fail:$a:$b:$c:$d" } }
    class GameService : Contexts() { var originals = 0; fun create(request: GameRequest, callback: GameCallback) { originals++ }; fun operate(request: GameRequest, callback: GameCallback) { originals++ } }
    class Ctx { fun getCurrentActivity(): Any? = "activity" }
    open class Contexts { fun context(): Ctx = Ctx() }

    private val ads = RecordingRewardAdUi()
    private val appCreate = method(AppService::class.java, "create", AppRequest::class.java, AppCallback::class.java)
    private val appOperate = method(AppService::class.java, "operate", AppRequest::class.java, AppCallback::class.java)
    private val gameCreate = method(GameService::class.java, "create", GameRequest::class.java, GameCallback::class.java)
    private val gameOperate = method(GameService::class.java, "operate", GameRequest::class.java, GameCallback::class.java)

    private fun rewardAds() = SkipMiniAppRewardAds(
        resolverOf(
            MiniAppQueries.APP_CONTEXT to method(Contexts::class.java, "context"),
            MiniAppQueries.APP_CREATE to appCreate, MiniAppQueries.APP_OPERATE to appOperate,
            MiniAppQueries.APP_EVENT to method(AppRequest::class.java, "event", String::class.java, Any::class.java),
            MiniAppQueries.APP_SUCCESS to method(AppCallback::class.java, "success"),
            MiniAppQueries.APP_FAILURE to method(AppCallback::class.java, "failure", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java, String::class.java),
            MiniAppQueries.APP_AD_ID to AppRequest::class.java.getDeclaredField("unit"), MiniAppQueries.APP_ACTION to AppRequest::class.java.getDeclaredField("act"),
            MiniAppQueries.GAME_CREATE to gameCreate, MiniAppQueries.GAME_OPERATE to gameOperate,
            MiniAppQueries.GAME_EVENT to method(GameRequest::class.java, "event", String::class.java, Any::class.java),
            MiniAppQueries.GAME_SUCCESS to method(GameCallback::class.java, "success", Any::class.java),
            MiniAppQueries.GAME_FAILURE to method(GameCallback::class.java, "failure", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java, String::class.java),
            MiniAppQueries.GAME_AD_ID to GameRequest::class.java.getDeclaredField("unit"), MiniAppQueries.GAME_ACTION to GameRequest::class.java.getDeclaredField("act"),
        ),
        loader, { ads }, logger,
    )

    @Test fun `creating, showing and showing again are answered without the host's own ad`() {
        enable(rewardAds())
        val service = AppService()
        val callback = AppCallback()
        val request = AppRequest()
        backend.call(appCreate, service, request, callback)
        assertEquals(listOf("ok"), callback.log)
        assertEquals(listOf<Pair<String, Any?>>("loaded" to "{}"), request.events)
        backend.call(appOperate, service, request, callback)
        assertEquals(listOf<Pair<String, Any?>>("loaded" to "{}", "close" to "{close}"), request.events)
        backend.call(appOperate, service, request, callback)
        assertEquals(listOf("ok", "ok", "fail:1003:8:Reward video is not loaded; call load before show:D"), callback.log)
        assertEquals(0, service.originals)
    }

    @Test fun `an ad the host made itself and an app out of front keep to the right path`() {
        enable(rewardAds())
        val service = AppService()
        val callback = AppCallback()
        backend.call(appOperate, service, AppRequest().apply { unit = "native" }, callback)
        assertEquals(1, service.originals)
        assertTrue(callback.log.isEmpty())
        backend.call(appCreate, service, AppRequest(), callback)
        ads.foreground = false
        backend.call(appOperate, service, AppRequest(), callback)
        assertEquals("fail:1003:94:App is not in foreground:D", callback.log.last())
        backend.call(appOperate, service, AppRequest().apply { act = "report" }, callback)
        assertEquals(2, service.originals)
    }

    @Test fun `the mini-game variant answers with its own callback shape`() {
        enable(rewardAds())
        val service = GameService()
        val callback = GameCallback()
        val request = GameRequest()
        backend.call(gameCreate, service, request, callback)
        backend.call(gameOperate, service, request, callback)
        assertEquals(listOf("ok", "ok"), callback.log)
        assertEquals(listOf("loaded", "close"), request.events.map { it.first })
        backend.call(gameOperate, service, request, callback)
        assertEquals("fail:1003:8:D:Reward video is not loaded; call load before show", callback.log.last())
    }

    @Test fun `the feature does nothing once it is closed`() {
        val feature = rewardAds()
        enable(feature)
        disable(feature)
        val service = AppService()
        backend.call(appCreate, service, AppRequest(), AppCallback())
        assertEquals(1, service.originals)
    }

    @Test fun `the session tracks each ad id and forgets destroyed ones`() {
        val session = RewardAdSession(capacity = 2)
        assertNull(session.handle("a", "show"))
        assertEquals(RewardAdSession.Result.LOADED, session.handle("a", "create"))
        assertEquals(RewardAdSession.Result.NOT_FOREGROUND, session.handle("a", "show", foreground = false))
        assertEquals(RewardAdSession.Result.CLOSED, session.handle("a", "show"))
        assertEquals(RewardAdSession.Result.NOT_LOADED, session.handle("a", "show"))
        assertEquals(RewardAdSession.Result.LOADED, session.handle("a", "load"))
        assertEquals(RewardAdSession.Result.DESTROYED, session.handle("a", "destroy"))
        assertNull(session.handle("a", "load"))
        assertNull(session.handle(" ", "create"))
    }

    @Test fun `a full session drops a closed ad for a new one and otherwise refuses`() {
        val session = RewardAdSession(capacity = 2)
        session.handle("a", "create")
        session.handle("b", "create")
        assertNull(session.handle("c", "create"))
        session.handle("a", "show")
        assertEquals(RewardAdSession.Result.LOADED, session.handle("c", "create"))
        assertNull(session.handle("a", "load"))
    }
}
