package io.github.nuke.oss.app.tiktok.feature.miniapp

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.registry.HostProcess
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Keeps the interstitial ad of mini apps and mini games from showing: the ad view's show step, the only void method
// taking (int, String, boolean, boolean), never runs. Reward videos are handled by SkipMiniAppRewardAds.
@HookEntry
internal class SkipInterstitialAds(
    private val hostClassLoader: ClassLoader,
    private val logger: Logger,
    private val viewName: String = AD_VIEW,
) : RuntimeFeature {
    override val id = "SkipMiniAppInterstitialAds"

    override fun install(scope: FeatureScope) {
        val view = viewName.toClass(hostClassLoader)
        val shows = view.resolve().optional(silent = true).method {
            parameters(Int::class, String::class, Boolean::class, Boolean::class)
            returnType = Void.TYPE
        }
        check(shows.size == 1) { "Expected one show step in ${view.name}, found ${shows.size}" }
        val show = shows.single().self.apply { isAccessible = true }
        scope.hooks.install(show, HookSpec(
            id = "Show",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { call ->
                call.returnEarly(null)
                logger.log("feature=$id skipped")
            },
        ))
    }

    companion object : TikTokFeature {
        const val AD_VIEW = "com.ss.android.excitingvideo.interstitial.InterstitialSheoAdView"
        override val processes = HostProcess.MAIN_AND_MINI_APP

        override fun create(services: TikTokFeatureServices) = with(services) { listOf(SkipInterstitialAds(hostClassLoader, logger)) }
    }
}
