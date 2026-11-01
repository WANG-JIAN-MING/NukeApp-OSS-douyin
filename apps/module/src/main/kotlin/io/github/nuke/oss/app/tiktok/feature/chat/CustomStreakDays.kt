package io.github.nuke.oss.app.tiktok.feature.chat

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.LinesOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Parses lines of "conversationId=days" ("*" stands for every conversation). A line that is not that shape is an
// error the user should see, so the first one is reported instead of being skipped.
internal fun parseStreakDays(lines: List<String>): Map<String, Int> {
    val days = LinkedHashMap<String, Int>()
    for (line in lines) {
        val (id, count) = line.split('=', limit = 2).map(String::trim).takeIf { it.size == 2 && it[0].isNotEmpty() }
            ?: error("Not a conversation=days line: $line")
        days[id] = count.toIntOrNull()?.takeIf { it in 0..MAX_DAYS } ?: error("Days must be 0 to $MAX_DAYS: $line")
    }
    return days
}

private const val MAX_DAYS = 99_999

// Shows another streak day count on the chat list and in the chat. Only the badge you see changes; nothing is sent.
@HookEntry
internal class CustomStreakDays(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(ChatQueries.STREAK_RENDER)
    private val lines = options.lines(DAYS)
    private val conversationId = NamedCall("getId")
    private val dayNumber = NamedField("LIZLLL")
    private val dayText = NamedField("LJ")
    private val dayLabel = NamedField("LJFF")

    override fun install(scope: FeatureScope) {
        if (lines.isEmpty()) return
        val days = parseStreakDays(lines)
        val render = resolver.methods(hostClassLoader, ChatQueries.STREAK_RENDER).single()
        check(render.parameterCount == 3) { "${render.declaringClass.name}.${render.name} does not take three parameters" }
        render.isAccessible = true
        scope.hooks.install(render, HookSpec(id = "SetStreakDays", before = { call ->
            val conversation = call.argument(1)
            val state = call.argument(2)
            val key = conversation?.let { conversationId.of(it)?.invoke(it) } as? String
            val count = key?.let { days[it] } ?: days["*"]
            if (count != null && state != null) apply(state, count)
        }))
    }

    private fun apply(state: Any, count: Int) {
        dayNumber.of(state)?.takeIf { it.type == Int::class.javaPrimitiveType }?.setInt(state, count)
        dayText.of(state)?.takeIf { it.type == String::class.java }?.set(state, count.toString())
        dayLabel.of(state)?.takeIf { it.type == String::class.java }?.set(state, count.toString())
    }

    companion object : TikTokFeature {
        const val ID = "CustomStreakDays"
        const val DAYS = "days"
        val OPTIONS = listOf(LinesOption(DAYS))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CustomStreakDays(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
