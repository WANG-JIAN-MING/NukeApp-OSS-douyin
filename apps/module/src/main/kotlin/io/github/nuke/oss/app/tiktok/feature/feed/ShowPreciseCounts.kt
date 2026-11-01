package io.github.nuke.oss.app.tiktok.feature.feed

import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methodLists
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Field

// A Latin unit letter counts only when no other letter follows it, so "12 watching" keeps its "w".
private val ROUNDED_PREFIX = Regex("^[0-9][0-9.,]*(?:\\s*[万亿]|[wWkKmMbB](?![A-Za-z]))?")

// Puts the exact count in place of the rounded number a host text starts with, keeping the rest of the host's own
// wording. A text that does not start with a number is left alone.
internal fun exactCountText(text: String?, count: Long): String? =
    text?.let { if (ROUNDED_PREFIX.containsMatchIn(it)) ROUNDED_PREFIX.replaceFirst(it, count.toString()) else it }

// Shows exact numbers where Douyin rounds them: the count formatters behind likes, comments, shares and followers
// answer with the plain number, and the live room's viewer texts are rewritten from the exact viewer count.
@HookEntry
internal class ShowPreciseCounts(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    private val counts = options.toggle(COUNTS)
    private val live = options.toggle(LIVE)
    // Only what the chosen options resolve.
    override val dexQueries: List<DexQuery> = (if (counts) listOf(OptionQueries.COUNT_FORMATTERS) else emptyList()) +
        (if (live) listOf(OptionQueries.AUDIENCE_STATS, OptionQueries.AUDIENCE_COUNT) else emptyList())
    private val stats = NamedField("room_view_stats")
    private val userCount = NamedField("user_count")
    private val displayTexts = listOf("display_long", "display_middle", "display_short").map(::NamedField)

    override fun install(scope: FeatureScope) {
        if (counts) {
            val formatters = resolver.methodLists(hostClassLoader, OptionQueries.COUNT_FORMATTERS).single()
            for (method in formatters) {
                check(method.returnType == String::class.java && method.parameterTypes.contentEquals(arrayOf(LONG))) {
                    "${method.declaringClass.name}.${method.name} is not a (long) String formatter"
                }
            }
            formatters.forEachIndexed { index, method ->
                method.isAccessible = true
                scope.hooks.install(method, HookSpec(
                    id = "ExactCount$index",
                    requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                    before = { call -> (call.argument(0) as? Long)?.let { call.returnEarly(it.toString()) } },
                ))
            }
        }
        if (live) {
            val (room, viewers) = resolver.methods(hostClassLoader, OptionQueries.AUDIENCE_STATS, OptionQueries.AUDIENCE_COUNT)
            check(room.parameterCount == 1) { "${room.declaringClass.name}.${room.name} does not take one room model" }
            check(viewers.parameterTypes.contentEquals(arrayOf(LONG, String::class.java, String::class.java, BOOLEAN))) {
                "${viewers.declaringClass.name}.${viewers.name} is not (long, String, String, boolean)"
            }
            room.isAccessible = true
            viewers.isAccessible = true
            scope.hooks.install(room, HookSpec(id = "ExactRoomViewers", before = { call -> call.argument(0)?.let(::exactRoomStats) }))
            scope.hooks.install(viewers, HookSpec(
                id = "ExactViewerCount",
                requiredCapabilities = setOf(HookCapability.ARGUMENT_REWRITE),
                before = { call ->
                    val count = call.argument(0) as? Long
                    if (count != null && count != 0L) {
                        call.setArgument(1, count.toString())
                        call.setArgument(2, exactCountText(call.argument(2) as? String, count))
                        // The count is also shown where the host would hide it.
                        call.setArgument(3, false)
                    }
                },
            ))
        }
    }

    // The room model carries the exact viewer count next to the stats object holding the texts the widget shows.
    private fun exactRoomStats(model: Any) {
        val countField = userCount.of(model)?.takeIf { it.type == LONG } ?: return
        val holder = stats.of(model)?.get(model) ?: return
        val count = countField.getLong(model)
        val (long, middle, short) = displayTexts.map { it.of(holder)?.takeIf(Field::isText) }
        long?.set(holder, exactCountText(long.get(holder) as String?, count))
        middle?.set(holder, count.toString())
        short?.set(holder, count.toString())
    }

    companion object : TikTokFeature {
        const val ID = "ShowPreciseCounts"
        const val COUNTS = "counts"
        const val LIVE = "live"
        val OPTIONS = listOf(ToggleOption(COUNTS, default = true), ToggleOption(LIVE, default = true))
        private val LONG = Long::class.javaPrimitiveType!!
        private val BOOLEAN = Boolean::class.javaPrimitiveType!!

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(ShowPreciseCounts(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}

private fun Field.isText() = type == String::class.java
