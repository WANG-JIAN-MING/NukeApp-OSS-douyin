package io.github.nuke.oss.app.tiktok.feature.playback

import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.sdk.clockText
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.util.concurrent.atomic.AtomicBoolean

// Warns when the feed moves to a video at least as long as the limit. The video shown at start-up
// is not checked, only the ones the user moves to.
@HookEntry
internal class LongVideoNotice(
    private val events: FeedEvents,
    private val prompts: HostPrompts,
    options: OptionValues,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = FeedEvents.VIDEO_CHANGED
    private val limitMillis = options.number(MINUTES) * 60_000
    private val duration = NamedField("duration")

    override fun install(scope: FeatureScope) {
        prompts.install(scope)
        val first = AtomicBoolean(true)
        events.onVideoChanged(scope, ID) { aweme ->
            val millis = (duration.of(aweme)?.get(aweme) as? Int)?.toLong() ?: 0
            if (!first.getAndSet(false) && millis >= limitMillis) prompts.toast(R.string.host_notice_long_video, clockText(millis))
        }
    }

    companion object : TikTokFeature {
        const val ID = "LongVideoNotice"
        const val MINUTES = "minutes"
        val OPTIONS = listOf(NumberOption(MINUTES, default = 10, min = 1, max = 600))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(LongVideoNotice(events, prompts(ID), options(ID, OPTIONS)))
        }
    }
}
