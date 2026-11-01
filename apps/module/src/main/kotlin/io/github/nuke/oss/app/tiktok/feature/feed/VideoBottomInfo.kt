package io.github.nuke.oss.app.tiktok.feature.feed

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.TextOption
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.sdk.clockText
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Fills the placeholders of the template: {published} the publish time in the chosen pattern, {location} the IP location
// the host reports for the video, {duration} the video length, {played} the position, {remaining} the time left. An
// invalid time pattern falls back to the default one. A video whose publish time or location is not known leaves that
// placeholder empty rather than showing the epoch.
internal fun renderBottomInfo(
    template: String, publishedMillis: Long, pattern: String, durationMillis: Long, playedMillis: Long, location: String = "",
): String {
    val published = if (publishedMillis <= 0) "" else try {
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(publishedMillis))
    } catch (_: IllegalArgumentException) {
        SimpleDateFormat(BottomInfoDefaults.PATTERN, Locale.getDefault()).format(Date(publishedMillis))
    }
    return template
        .replace("{published}", published)
        .replace("{location}", location)
        .replace("{duration}", clockText(durationMillis))
        .replace("{played}", clockText(playedMillis))
        .replace("{remaining}", clockText((durationMillis - playedMillis).coerceAtLeast(0)))
}

internal object BottomInfoDefaults {
    const val TEMPLATE = "{published} {duration}|{played}"
    const val PATTERN = "yyyy-MM-dd HH:mm:ss"
}

// The line under a feed video's description. attach is called with the intro layout the host built; show sets the text
// of every attached label that is on screen.
internal interface BottomInfoLabels {
    fun attach(layout: Any)
    fun show(text: String)
}

// Shows the time and place of the current feed video under its description, in either or both of two ways: the line this
// feature adds, whose template can hold the publish time, the IP location, the length and the position, and the host's
// own time and location label, which the host hides in some places and this reveals. The two used to be separate
// features; the line is on by default and the host label off, as they were. The line is one View that carries all of it,
// so the host label is only revealed while the line is off; with both on, the line wins and the host label stays as the
// host has it.
@HookEntry
internal class VideoBottomInfo(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val events: FeedEvents,
    options: OptionValues,
    private val labels: () -> BottomInfoLabels,
    private val layoutName: String = INTRO_LAYOUT,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    private val line = options.toggle(LINE)
    private val hostLabel = options.toggle(HOST_LABEL) && !line
    override val dexQueries = (if (line) FeedEvents.VIDEO_CHANGED + FeedEvents.PROGRESS else emptyList()) +
        (if (hostLabel) HostTimeLabel.QUERIES else emptyList())
    private val template = options.text(TEMPLATE).ifBlank { BottomInfoDefaults.TEMPLATE }
    private val pattern = options.text(TIME_PATTERN).ifBlank { BottomInfoDefaults.PATTERN }
    // The host's Aweme keeps the publish time, in seconds, in createTime; create_time is its serialized name.
    private val createTime = NamedFieldAlias("createTime", "create_time")
    private val duration = NamedField("duration")
    private val ipAttribution = NamedFieldAlias("ipAttribution", "ip_attribution")
    @Volatile private var current: WeakReference<Any>? = null

    override fun install(scope: FeatureScope) {
        check(line || hostLabel) { "Neither the added line nor the host label is turned on" }
        if (hostLabel) HostTimeLabel(resolver, hostClassLoader).install(scope)
        if (line) installLine(scope)
    }

    private fun installLine(scope: FeatureScope) {
        val shown = labels()
        val layout = layoutName.toClass(hostClassLoader)
        val constructors = layout.resolve().optional(silent = true).constructor { isSynthetic = false }.map { it.self }
        check(constructors.isNotEmpty()) { "${layout.name} declares no constructor" }
        current = null
        constructors.forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            scope.hooks.install(constructor, HookSpec(
                id = "AttachInfo$index",
                requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
                after = { call ->
                    val view = call.receiver
                    if (call.outcome is CallOutcome.Returned && view != null) shown.attach(view)
                },
            ))
        }
        events.onVideoChanged(scope, ID) { aweme ->
            current = WeakReference(aweme)
            shown.show(render(aweme, 0))
        }
        events.onProgress(scope, ID) { _, position, _ -> current?.get()?.let { shown.show(render(it, position)) } }
    }

    private fun render(aweme: Any, playedMillis: Long): String {
        val seconds = (createTime.of(aweme)?.get(aweme) as? Number)?.toLong() ?: 0L
        val length = (duration.of(aweme)?.get(aweme) as? Int)?.toLong() ?: 0L
        val location = (ipAttribution.of(aweme)?.get(aweme) as? String).orEmpty()
        return renderBottomInfo(template, seconds * 1000, pattern, length, playedMillis, location)
    }

    companion object : TikTokFeature {
        const val ID = "VideoBottomInfo"
        const val LINE = "line"
        const val HOST_LABEL = "host-label"
        const val TEMPLATE = "template"
        const val TIME_PATTERN = "time-pattern"
        const val INTRO_LAYOUT = "com.ss.android.ugc.aweme.feed.ui.AwemeIntroInfoLayout"
        val OPTIONS = listOf(
            ToggleOption(LINE, default = true),
            ToggleOption(HOST_LABEL),
            TextOption(TEMPLATE, default = BottomInfoDefaults.TEMPLATE, maxLength = 128),
            TextOption(TIME_PATTERN, default = BottomInfoDefaults.PATTERN, maxLength = 40),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(VideoBottomInfo(resolver, hostClassLoader, events, options(ID, OPTIONS), bottomInfoLabels))
        }
    }
}
