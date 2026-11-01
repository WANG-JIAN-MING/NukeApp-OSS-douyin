package io.github.nuke.oss.app.tiktok.feature.general

import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.reflect.declaredMethodNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Skips the idle-time update request, so Douyin does not check for a new version in the background.
@HookEntry
internal class DisableUpdateChecks(private val hostClassLoader: ClassLoader) : RuntimeFeature {
    override val id = "DisableUpdateChecks"

    override fun install(scope: FeatureScope) {
        val request = CHECK_UPDATE_REQUEST.toClass(hostClassLoader).declaredMethodNamed("request")
        // Returning null early is only valid for void or reference results.
        check(request.returnType == Void.TYPE || !request.returnType.isPrimitive) {
            "$CHECK_UPDATE_REQUEST.request returns ${request.returnType.name}"
        }
        scope.hooks.install(request, HookSpec(
            id = "SkipUpdateRequest",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { it.returnEarly(null) },
        ))
    }

    companion object : TikTokFeature {
        private const val CHECK_UPDATE_REQUEST = "com.ss.android.ugc.aweme.requesttask.idle.CheckUpdateRequest"

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(DisableUpdateChecks(hostClassLoader))
        }
    }
}
