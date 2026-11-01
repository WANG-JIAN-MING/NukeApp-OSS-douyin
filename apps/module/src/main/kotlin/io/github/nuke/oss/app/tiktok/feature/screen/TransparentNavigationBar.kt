package io.github.nuke.oss.app.tiktok.feature.screen

import io.github.nuke.oss.app.shared.activity.SystemBars
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Lets the video show through the gesture bar: the navigation bar background is made transparent whenever the video
// changes, so it also holds after Douyin resets it.
@HookEntry
internal class TransparentNavigationBar(
    private val events: FeedEvents,
    private val bars: SystemBars,
) : RuntimeFeature, UsesDexQueries {
    override val id = "TransparentNavigationBar"
    override val dexQueries = FeedEvents.VIDEO_CHANGED

    override fun install(scope: FeatureScope) {
        bars.install(scope, id)
        events.onVideoChanged(scope, id) { bars.setNavigationBarTransparent() }
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(TransparentNavigationBar(events, systemBars()))
        }
    }
}
