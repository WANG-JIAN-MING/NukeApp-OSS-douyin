package io.github.nuke.oss.app.tiktok.feature.playback

import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.reflect.declaredMethodNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Keeps the SimKit bitrate quality mode at high, so the player never switches to the data-saving mode.
@HookEntry
internal class ForceHighVideoQuality(private val hostClassLoader: ClassLoader) : RuntimeFeature {
    override val id = "ForceHighVideoQuality"

    override fun install(scope: FeatureScope) {
        val service = SIM_KIT_SERVICE.toClass(hostClassLoader)
        val set = service.declaredMethodNamed("setBitrateQuality")
        val get = service.declaredMethodNamed("getBitrateQuality")
        check(set.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType && get.returnType == Int::class.javaPrimitiveType) {
            "SimKitService bitrate quality signatures changed"
        }
        scope.hooks.install(set, HookSpec(
            id = "SetHighQuality",
            requiredCapabilities = setOf(HookCapability.ARGUMENT_REWRITE),
            before = { it.setArgument(0, HIGH_QUALITY) },
        ))
        scope.hooks.install(get, HookSpec(
            id = "ReportHighQuality",
            requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE),
            // A thrown getter is the host's own failure and is left as is.
            after = { call -> if (call.outcome is CallOutcome.Returned) call.returnValue(HIGH_QUALITY) },
        ))
    }

    companion object : TikTokFeature {
        private const val SIM_KIT_SERVICE = "com.ss.android.ugc.aweme.simkit.SimKitService"
        private const val HIGH_QUALITY = 1

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(ForceHighVideoQuality(hostClassLoader))
        }
    }
}
