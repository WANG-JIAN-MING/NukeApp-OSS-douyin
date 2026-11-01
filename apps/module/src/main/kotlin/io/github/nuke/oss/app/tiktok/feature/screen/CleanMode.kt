package io.github.nuke.oss.app.tiktok.feature.screen

import io.github.nuke.oss.app.shared.activity.Foreground
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
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanOptions
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanScreen
import io.github.nuke.oss.app.tiktok.sdk.screen.planCleanScreen
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Clears the controls off the video while it plays and puts them back when it pauses. Controls are found by class name,
// label text and layout, not by ID.
@HookEntry
internal class CleanMode(
    private val events: FeedEvents,
    private val screen: CleanScreen,
    private val foreground: Foreground,
    private val bars: SystemBars,
    private val scheduler: TaskScheduler,
    options: OptionValues,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = FeedEvents.PLAYBACK
    private val rules = CleanOptions(options.toggle(KEEP_SEEK_BAR), options.toggle(HIDE_DANMAKU), options.toggle(KEEP_VIDEO_INFO))
    private val hideStatusBar = options.toggle(HIDE_STATUS_BAR)
    private val keepClickable = options.toggle(KEEP_CLICKABLE)
    @Volatile private var lastPlay = Long.MIN_VALUE / 2

    override fun install(scope: FeatureScope) {
        screen.install(scope, ID)
        foreground.install(scope, ID)
        if (hideStatusBar) bars.install(scope, ID)
        val timers = scope.own(Timers(scheduler))
        scope.own(AutoCloseable { if (hideStatusBar) bars.setStatusBarVisible(true) })
        events.onPlayback(scope, ID,
            onPlay = {
                lastPlay = clock()
                timers.cancel(RESTORE)
                if (applies()) timers.replace(HIDE, 0) { hide() }
            },
            onPause = {
                timers.replace(RESTORE, SETTLE_MS) {
                    // A pause right after a play is a video change, not the user stopping it.
                    if (clock() - lastPlay >= RESUME_WINDOW_MS) {
                        screen.restore()
                        if (hideStatusBar) bars.setStatusBarVisible(true)
                    }
                }
            },
        )
    }

    // Only on the screens that play the feed; search results and the flow page keep their controls.
    private fun applies(): Boolean {
        val name = foreground.activityName()?.substringAfterLast('.') ?: return false
        return name in PLAYING_SCREENS
    }

    private fun hide() {
        val root = screen.snapshot() ?: return
        screen.hide(planCleanScreen(root, rules), keepClickable)
        if (hideStatusBar) bars.setStatusBarVisible(false)
    }

    companion object : TikTokFeature {
        const val ID = "CleanMode"
        const val KEEP_SEEK_BAR = "keep-seek-bar"
        const val HIDE_DANMAKU = "hide-danmaku"
        const val KEEP_VIDEO_INFO = "keep-video-info"
        const val HIDE_STATUS_BAR = "hide-status-bar"
        const val KEEP_CLICKABLE = "keep-clickable"
        private const val HIDE = "hide"
        private const val RESTORE = "restore"
        private const val SETTLE_MS = 150L
        private const val RESUME_WINDOW_MS = 300L
        private val PLAYING_SCREENS = setOf("MainActivity", "DetailActivity", "UltraDetailActivity")
        val OPTIONS = listOf(
            ToggleOption(KEEP_SEEK_BAR), ToggleOption(HIDE_DANMAKU), ToggleOption(KEEP_VIDEO_INFO),
            ToggleOption(HIDE_STATUS_BAR), ToggleOption(KEEP_CLICKABLE),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CleanMode(events, cleanScreen(), foreground(), systemBars(), scheduler, options(ID, OPTIONS)))
        }
    }
}
