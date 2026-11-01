package io.github.nuke.oss.app.tiktok.feature.screen

import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.activity.Timers
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanNode
import io.github.nuke.oss.app.tiktok.sdk.screen.planOpacity
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// The screen in front, for the opacity setting: a snapshot to plan from and the means to set and undo opacities.
internal interface OpacityScreen {
    fun install(scope: FeatureScope, featureId: String)
    fun snapshot(): CleanNode?
    // Sets the opacity of the Views, remembering how each looked before the first time it is touched.
    fun apply(views: List<Any>, alpha: Float)
    fun restore()
}

// Makes the bottom, top and right controls of the feed more or less transparent. The host resets some of them when a
// video starts, so the setting is applied a few times after each video change.
@HookEntry
internal class ControlOpacity(
    private val events: FeedEvents,
    private val screen: OpacityScreen,
    private val scheduler: TaskScheduler,
    options: OptionValues,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = FeedEvents.VIDEO_CHANGED
    private val bottom = options.number(BOTTOM) / 100f
    private val top = options.number(TOP) / 100f
    private val right = options.number(RIGHT) / 100f

    override fun install(scope: FeatureScope) {
        if (bottom == 1f && top == 1f && right == 1f) return
        screen.install(scope, ID)
        val timers = scope.own(Timers(scheduler))
        events.onVideoChanged(scope, ID) {
            for ((index, delay) in REAPPLY_MS.withIndex()) timers.replace("apply-$index", delay) { sweep() }
        }
    }

    private fun sweep() {
        val root = screen.snapshot() ?: return
        val plan = planOpacity(root)
        if (top != 1f) screen.apply(plan.top, top)
        if (bottom != 1f) screen.apply(plan.bottom, bottom)
        if (right != 1f) screen.apply(plan.right, right)
    }

    companion object : TikTokFeature {
        const val ID = "ControlOpacity"
        const val BOTTOM = "bottom"
        const val TOP = "top"
        const val RIGHT = "right"
        private val REAPPLY_MS = listOf(0L, 300L, 800L, 1500L)
        val OPTIONS = listOf(
            NumberOption(BOTTOM, default = 100, min = 1, max = 100),
            NumberOption(TOP, default = 100, min = 1, max = 100),
            NumberOption(RIGHT, default = 100, min = 1, max = 100),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(ControlOpacity(events, opacityScreen(), scheduler, options(ID, OPTIONS)))
        }
    }
}
