package io.github.nuke.oss.app.tiktok.feature.screen

import io.github.nuke.oss.app.shared.activity.SystemBars
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.activity.Timers
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Hides the status bar whenever a video starts playing. With "only while playing" it comes back when playback pauses,
// unless playback resumes within a moment, as it does between two videos.
@HookEntry
internal class HideStatusBar(
    private val events: FeedEvents,
    private val bars: SystemBars,
    private val scheduler: TaskScheduler,
    options: OptionValues,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = FeedEvents.PLAYBACK
    private val onlyWhilePlaying = options.toggle(ONLY_WHILE_PLAYING)
    @Volatile private var lastPlay = Long.MIN_VALUE / 2

    override fun install(scope: FeatureScope) {
        bars.install(scope, ID)
        val timers = scope.own(Timers(scheduler))
        events.onPlayback(scope, ID,
            onPlay = {
                lastPlay = clock()
                timers.cancel(SHOW)
                bars.setStatusBarVisible(false)
            },
            onPause = {
                if (onlyWhilePlaying) timers.replace(SHOW, PAUSE_SETTLE_MS) {
                    if (clock() - lastPlay >= RESUME_WINDOW_MS) bars.setStatusBarVisible(true)
                }
            },
        )
    }

    companion object : TikTokFeature {
        const val ID = "HideStatusBar"
        const val ONLY_WHILE_PLAYING = "only-while-playing"
        private const val SHOW = "show"
        private const val PAUSE_SETTLE_MS = 150L
        private const val RESUME_WINDOW_MS = 300L
        val OPTIONS = listOf(ToggleOption(ONLY_WHILE_PLAYING))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(HideStatusBar(events, systemBars(), scheduler, options(ID, OPTIONS)))
        }
    }
}
