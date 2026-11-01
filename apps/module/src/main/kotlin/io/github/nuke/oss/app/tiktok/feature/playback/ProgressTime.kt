package io.github.nuke.oss.app.tiktok.feature.playback

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.sdk.clockText
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// The time label under the feed's progress bar. attach is called with the bar whenever it moves; show sets the text of
// the label of the last attached bar.
internal interface ProgressLabel {
    fun attach(seekBar: Any)
    fun show(text: String)
}

// Shows the played and total time under the progress bar.
@HookEntry
internal class ProgressTime(
    private val hostClassLoader: ClassLoader,
    private val events: FeedEvents,
    private val label: () -> ProgressLabel,
    private val seekBarNames: List<String> = SEEK_BARS,
) : RuntimeFeature, UsesDexQueries {
    override val id = "ProgressTime"
    override val dexQueries = FeedEvents.PROGRESS

    override fun install(scope: FeatureScope) {
        val bars = seekBarNames.mapNotNull { name -> try { name.toClass(hostClassLoader) } catch (_: ClassNotFoundException) { null } }
        check(bars.isNotEmpty()) { "No known progress bar class: ${seekBarNames.joinToString()}" }
        val setters = bars.flatMap { type ->
            type.resolve().optional(silent = true).method {
                name = "setProgress"
                isSynthetic = false
                isBridge = false
            }.map { it.self }
        }
        check(setters.isNotEmpty()) { "No progress bar declares setProgress" }
        val shown = label()
        setters.forEachIndexed { index, method ->
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(id = "AttachTime$index", after = { call ->
                val bar = call.receiver
                if (call.outcome is CallOutcome.Returned && bar != null) shown.attach(bar)
            }))
        }
        events.onProgress(scope, id) { _, position, duration ->
            if (duration > 0) shown.show("${clockText(position)}/${clockText(duration)}")
        }
    }

    companion object : TikTokFeature {
        val SEEK_BARS = listOf(
            "com.ss.android.ugc.aweme.feed.ui.seekbar.CustomizedUISeekBar",
            "com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView",
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(ProgressTime(hostClassLoader, events, progressLabel))
        }
    }
}
