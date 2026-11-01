package io.github.nuke.oss.app.tiktok.feature.miniapp

import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.fields
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.registry.HostProcess
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.MiniAppQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

// The Android parts of the real-name dialog. The dialog is the SDK's DialogFragment, whose arguments carry where it was
// opened from and whether it may be closed.
internal interface RealNameDialogs {
    // The origin ("api" when a mini app's API asked) and the close setting flags from the dialog's arguments.
    fun arguments(dialog: Any): DialogArguments?

    // The visible, enabled close button of a dialog that is showing, with a click listener; null otherwise.
    fun closeButton(dialog: Any): Any?

    // Presses the button the way a tap does, so the SDK's own reject and cleanup path runs.
    fun press(button: Any): Boolean
}

internal class DialogArguments(val origin: String?, val closeFlags: Int) {
    override fun toString() = "DialogArguments(origin=$origin, closeFlags=$closeFlags)"
}

// A dialog may be closed for the user when a mini app's API opened it, it is waiting for input (state 1) and its close
// setting allows it (bit 4). A forced verification has no such bit and is left alone.
internal fun canCancelRealName(origin: String?, state: Int, closeFlags: Int): Boolean =
    origin == "api" && state == 1 && (closeFlags and 4) != 0

// Presses the permitted close button of the real-name prompt a mini app raises, so the SDK treats it as a normal
// refusal and releases what it holds. One attempt per dialog; a prompt that cannot be closed stays as it is. This runs
// in the mini-app processes as well as the main one.
@HookEntry
internal class SkipMiniAppRealNameDialog(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val scheduler: TaskScheduler,
    private val dialogs: () -> RealNameDialogs,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = "SkipMiniAppRealName"
    override val dexQueries = listOf(MiniAppQueries.REAL_NAME_STARTED, MiniAppQueries.REAL_NAME_STATE)
    private val scheduled = WeakHashMap<Any, Boolean>()
    private val active = AtomicBoolean()

    override fun install(scope: FeatureScope) {
        val started = resolver.methods(hostClassLoader, MiniAppQueries.REAL_NAME_STARTED).single().apply { isAccessible = true }
        val state = resolver.fields(hostClassLoader, MiniAppQueries.REAL_NAME_STATE).single().apply { isAccessible = true }
        check(state.declaringClass == started.declaringClass && state.type == Int::class.javaPrimitiveType) { "The real-name dialog state changed" }
        val ui = dialogs()
        active.set(true)
        scope.own(AutoCloseable { active.set(false) })
        // An unknown subclass of the SDK dialog is not touched, so a shadowed field is never read.
        fun eligible(dialog: Any): Boolean {
            if (dialog.javaClass != started.declaringClass) return false
            val arguments = ui.arguments(dialog) ?: return false
            return canCancelRealName(arguments.origin, state.getInt(dialog), arguments.closeFlags)
        }
        scope.hooks.install(started, HookSpec(id = "Started", after = { call ->
            val dialog = call.receiver
            if (call.outcome is CallOutcome.Returned && dialog != null && eligible(dialog) && ui.closeButton(dialog) != null) {
                if (synchronized(scheduled) { scheduled.putIfAbsent(dialog, true) } != null) return@HookSpec
                // The button may not be laid out yet, so the press waits for the main thread's next turn and checks again.
                scheduler.schedule(0) {
                    if (!active.get() || !eligible(dialog)) return@schedule
                    val button = ui.closeButton(dialog) ?: return@schedule
                    if (ui.press(button)) logger.log("feature=$id close_requested")
                }
            }
        }))
    }

    companion object : TikTokFeature {
        override val processes = HostProcess.MAIN_AND_MINI_APP

        override fun create(services: TikTokFeatureServices) =
            with(services) { listOf(SkipMiniAppRealNameDialog(resolver, hostClassLoader, scheduler, realNameDialogs, logger)) }
    }
}

// What a video ad does in the mini-app SDK, tracked per service by ad ID so nothing is shared between apps.
internal class RewardAdSession(private val capacity: Int = 128) {
    private enum class State { LOADED, CLOSED }
    enum class Result { LOADED, CLOSED, DESTROYED, NOT_LOADED, NOT_FOREGROUND }
    private val ads = linkedMapOf<String, State>()

    // The outcome to deliver for the call, or null to leave it to the host (an ad the host created itself keeps its own
    // callbacks, and a full session refuses new ones).
    @Synchronized
    fun handle(id: String, action: String, foreground: Boolean = true): Result? {
        if (id.isBlank()) return null
        if (action == "create") {
            if (id !in ads && ads.size >= capacity) {
                val old = ads.entries.firstOrNull { it.value == State.CLOSED }?.key ?: return null
                ads.remove(old)
            }
            ads[id] = State.LOADED
            return Result.LOADED
        }
        val state = ads[id] ?: return null
        return when (action) {
            "load" -> { ads[id] = State.LOADED; Result.LOADED }
            "show" -> if (!foreground) Result.NOT_FOREGROUND else if (state == State.LOADED) {
                // Committed before the script is told, so a reentrant show cannot deliver another close.
                ads[id] = State.CLOSED
                Result.CLOSED
            } else Result.NOT_LOADED
            "destroy" -> { ads.remove(id); Result.DESTROYED }
            else -> null
        }
    }
}

// The Android parts of the reward video skip.
internal interface RewardAdUi {
    // Whether the mini app's Activity is in front and has focus.
    fun isForeground(activity: Any?): Boolean
    fun emptyJson(): Any
    fun closeJson(): Any
}

// Skips the reward video of mini apps and mini games: creating, loading and showing a video ad is answered at once with
// the loaded and closed events the app's script expects. Only the video API is touched; the actual reward is settled by
// the app, and a reward the server checks is not guaranteed to arrive. No watch time or reward request is made up. This
// runs in the mini-app processes as well as the main one.
@HookEntry
internal class SkipMiniAppRewardAds(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val ui: () -> RewardAdUi,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = "SkipMiniAppRewardAds"
    override val dexQueries = listOf(
        MiniAppQueries.APP_CONTEXT, MiniAppQueries.APP_CREATE, MiniAppQueries.APP_OPERATE, MiniAppQueries.APP_EVENT,
        MiniAppQueries.APP_SUCCESS, MiniAppQueries.APP_FAILURE, MiniAppQueries.APP_AD_ID, MiniAppQueries.APP_ACTION,
        MiniAppQueries.GAME_CREATE, MiniAppQueries.GAME_OPERATE, MiniAppQueries.GAME_EVENT, MiniAppQueries.GAME_SUCCESS,
        MiniAppQueries.GAME_FAILURE, MiniAppQueries.GAME_AD_ID, MiniAppQueries.GAME_ACTION,
    )
    private val sessions = WeakHashMap<Any, RewardAdSession>()
    private val active = AtomicBoolean()
    private val currentActivity = NamedCall("getCurrentActivity")

    private class Sdk(
        val label: String, val create: Method, val operate: Method, val event: Method, val success: Method, val failure: Method,
        val adId: java.lang.reflect.Field, val action: java.lang.reflect.Field,
    )

    override fun install(scope: FeatureScope) {
        val appContext = resolver.methods(hostClassLoader, MiniAppQueries.APP_CONTEXT).single().apply { isAccessible = true }
        val sdks = listOf(
            sdk("MiniApp", MiniAppQueries.APP_CREATE, MiniAppQueries.APP_OPERATE, MiniAppQueries.APP_EVENT, MiniAppQueries.APP_SUCCESS,
                MiniAppQueries.APP_FAILURE, MiniAppQueries.APP_AD_ID, MiniAppQueries.APP_ACTION),
            sdk("MiniGame", MiniAppQueries.GAME_CREATE, MiniAppQueries.GAME_OPERATE, MiniAppQueries.GAME_EVENT, MiniAppQueries.GAME_SUCCESS,
                MiniAppQueries.GAME_FAILURE, MiniAppQueries.GAME_AD_ID, MiniAppQueries.GAME_ACTION),
        )
        val view = ui()
        active.set(true)
        scope.own(AutoCloseable { active.set(false) })
        for (sdk in sdks) {
            for ((entry, creating) in listOf(sdk.create to true, sdk.operate to false)) {
                scope.hooks.install(entry, HookSpec(
                    id = "${sdk.label}${if (creating) "Create" else "Operate"}",
                    requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                    before = { call ->
                        if (!active.get()) return@HookSpec
                        val service = call.receiver ?: return@HookSpec
                        val request = call.argument(0) ?: return@HookSpec
                        val callback = call.argument(1) ?: return@HookSpec
                        val unit = sdk.adId.get(request) as? String ?: return@HookSpec
                        val operation = if (creating) "create" else sdk.action.get(request) as? String ?: return@HookSpec
                        if (!creating && operation !in OPERATIONS) return@HookSpec
                        val foreground = if (operation == "show") {
                            val context = appContext.invoke(service) ?: return@HookSpec
                            view.isForeground(currentActivity.of(context)?.invoke(context))
                        } else true
                        val session = synchronized(sessions) { sessions.getOrPut(service) { RewardAdSession() } }
                        val decision = session.handle(unit, operation, foreground) ?: return@HookSpec
                        // From here the callbacks belong to this answer, and the host's call is not made.
                        call.returnEarly(null)
                        try {
                            deliver(view, request, callback, decision, sdk)
                            logger.log("feature=$id sdk=${sdk.label} operation=$operation result=$decision")
                        } catch (failure: ReflectiveOperationException) {
                            logger.log("feature=$id callback_failed ${(failure.cause ?: failure).javaClass.simpleName}")
                        }
                    },
                ))
            }
        }
    }

    private fun sdk(label: String, create: io.github.nuke.oss.app.shared.dex.DexQuery, operate: io.github.nuke.oss.app.shared.dex.DexQuery,
                    event: io.github.nuke.oss.app.shared.dex.DexQuery, success: io.github.nuke.oss.app.shared.dex.DexQuery,
                    failure: io.github.nuke.oss.app.shared.dex.DexQuery, adId: io.github.nuke.oss.app.shared.dex.DexQuery,
                    action: io.github.nuke.oss.app.shared.dex.DexQuery): Sdk {
        val (createMethod, operateMethod, eventMethod, successMethod, failureMethod) = resolver.methods(hostClassLoader, create, operate, event, success, failure)
        val (idField, actionField) = resolver.fields(hostClassLoader, adId, action)
        listOf(createMethod, operateMethod, eventMethod, successMethod, failureMethod).forEach { it.isAccessible = true }
        idField.isAccessible = true
        actionField.isAccessible = true
        check(createMethod.parameterTypes.contentEquals(operateMethod.parameterTypes) && idField.declaringClass == createMethod.parameterTypes[0] &&
            actionField.declaringClass == idField.declaringClass && eventMethod.declaringClass == idField.declaringClass &&
            successMethod.declaringClass == createMethod.parameterTypes[1] && failureMethod.declaringClass == successMethod.declaringClass) {
            "Video API binding changed: $label"
        }
        return Sdk(label, createMethod, operateMethod, eventMethod, successMethod, failureMethod, idField, actionField)
    }

    // A refusal (not loaded, or the app is not in front) goes through the failure callback; an answer through the success
    // one, followed by the loaded or closed event. The mini-game success callback takes one argument, the mini-app one none.
    private fun deliver(view: RewardAdUi, request: Any, callback: Any, decision: RewardAdSession.Result, sdk: Sdk) {
        if (decision == RewardAdSession.Result.NOT_LOADED || decision == RewardAdSession.Result.NOT_FOREGROUND) {
            val background = decision == RewardAdSession.Result.NOT_FOREGROUND
            val message = if (background) "App is not in foreground" else "Reward video is not loaded; call load before show"
            val game = sdk.success.parameterCount == 1
            sdk.failure.invoke(callback, 1003, if (background) 94 else 8, if (game) "D" else message, if (game) message else "D")
            return
        }
        if (sdk.success.parameterCount == 0) sdk.success.invoke(callback) else sdk.success.invoke(callback, null)
        when (decision) {
            RewardAdSession.Result.LOADED -> sdk.event.invoke(request, "loaded", view.emptyJson())
            RewardAdSession.Result.CLOSED -> sdk.event.invoke(request, "close", view.closeJson())
            else -> Unit
        }
    }

    companion object : TikTokFeature {
        private val OPERATIONS = listOf("load", "show", "destroy")
        override val processes = HostProcess.MAIN_AND_MINI_APP

        override fun create(services: TikTokFeatureServices) = with(services) { listOf(SkipMiniAppRewardAds(resolver, hostClassLoader, rewardAdUi, logger)) }
    }
}
