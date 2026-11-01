package io.github.nuke.oss.app.tiktok.feature.general

import android.app.Activity
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.PermissionQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// The two Activity facts the system permission request needs: whether a request is the lone notification request that
// would still ask, and how to answer it as denied.
internal interface NotificationPermission {
    fun isPendingNotificationRequest(activity: Any, permissions: Array<*>): Boolean
    fun deny(activity: Any, requestCode: Int)
}

// Closes the notification-permission prompts the way the host's own failure path does, so its callers carry on as if
// the prompt had been dismissed; no permission is granted or changed, and requests that ask for other permissions as
// well pass through untouched. Each suppressed request is finished on the main thread even if the feature is turned
// off before that runs.
@HookEntry
internal class BlockNotificationPrompts(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val scheduler: TaskScheduler,
    private val permission: () -> NotificationPermission,
    private val logger: Logger,
    private val requestOwner: Class<*> = Activity::class.java,
) : RuntimeFeature, UsesDexQueries {
    override val id = "BlockNotificationPrompts"
    override val dexQueries = listOf(PermissionQueries.LOAD_GUIDE, PermissionQueries.GOOGLE_ALERT)

    override fun install(scope: FeatureScope) {
        val (guide, alert) = resolver.methods(hostClassLoader, PermissionQueries.LOAD_GUIDE, PermissionQueries.GOOGLE_ALERT)
        check(guide.parameterCount > 2 && alert.parameterCount > 6) { "The notification prompt methods have the wrong shape" }
        val failureType = FAILED_TYPE.toClass(hostClassLoader)
        val suppressed = failureType.resolve().firstField { name = "REVERSE_EXPERIMENT" }.get()
            ?: error("$FAILED_TYPE has no REVERSE_EXPERIMENT")
        val failed = alert.parameterTypes[6].resolve().optional(silent = true).method {
            returnType = Void.TYPE
            parameters(String::class, Boolean::class)
        }.singleOrNull()?.self?.apply { isAccessible = true }
            ?: error("${alert.parameterTypes[6].name} has no single (String, boolean) failure callback")
        val request = requestOwner.resolve().firstMethod {
            name = "requestPermissions"
            parameters(Array<String>::class, Int::class)
        }.self.apply { isAccessible = true }
        val ui = permission()
        guide.isAccessible = true
        alert.isAccessible = true
        scope.hooks.install(guide, HookSpec(id = "Guide", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
            val callback = call.argument(2)
            call.returnEarly(null)
            logger.log("feature=$id guide_suppressed")
            if (callback != null) scheduler.schedule(0) { callback.callByName("onLoadFailed", suppressed) }
        }))
        scope.hooks.install(alert, HookSpec(id = "GoogleAlert", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
            val callback = call.argument(6)
            call.returnEarly(null)
            logger.log("feature=$id alert_suppressed")
            if (callback != null) scheduler.schedule(0) { failed.invoke(callback, "Nuke notification permission guide disabled", false) }
        }))
        // Both the ActivityResult and the older fragment request APIs end in Activity.requestPermissions.
        scope.hooks.install(request, HookSpec(id = "SystemRequest", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
            val activity = call.receiver
            val permissions = call.argument(0) as? Array<*>
            val code = call.argument(1) as? Int
            if (activity != null && permissions != null && code != null && ui.isPendingNotificationRequest(activity, permissions)) {
                call.returnEarly(null)
                logger.log("feature=$id system_request_suppressed")
                // The answer comes later like a normal permission result; nothing is granted or faked as enabled.
                scheduler.schedule(0) { ui.deny(activity, code) }
            }
        }))
    }

    companion object : TikTokFeature {
        private const val FAILED_TYPE = "com.ss.android.ugc.awemepushapi.FailedType"

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(BlockNotificationPrompts(resolver, hostClassLoader, scheduler, notificationPermission, logger))
        }
    }
}
