package io.github.nuke.oss.app.tiktok.feature.feed

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methodLists
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.MultiOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class CoverStat(val option: String) { DIGG("digg"), SHARE("share"), COLLECT("collect"), COMMENT("comment") }

// One count of the video's statistics. The host's model calls the field diggCount, shareCount and so on, or by the
// serialized snake_case name; Douyin 40.7.0 obfuscates them, but its toString prints every count under its model name,
// so the name is read back from that text when no field has it. A count that is in neither place reads as null.
internal class StatCount(private val camel: String, snake: String) {
    private val field = NamedFieldAlias(camel, snake)
    private val printed = Regex("""\b$camel=?(-?\d+)""")

    fun of(source: Any): Long? {
        (field.of(source)?.get(source) as? Number)?.let { return it.toLong() }
        return printed.find(source.toString())?.groupValues?.get(1)?.toLongOrNull()
    }
}

// Counts the way the grid shows them: plain below ten thousand, then whole or one-decimal ten-thousands.
internal fun compactCount(count: Long): String {
    if (count < 10_000) return count.toString()
    val tenThousands = count / 10_000.0
    return if (count < 100_000) String.format(Locale.ROOT, "%.0f万", tenThousands) else String.format(Locale.ROOT, "%.1f万", tenThousands)
}

// Puts extra lines on a profile video cover. decorate is called with the cover's holder View whenever a video is bound.
internal interface CoverDecorator {
    fun decorate(itemView: Any, createdAt: String?, stats: List<Pair<CoverStat, String>>, hideLikeCount: Boolean)
}

// Shows the publish time and the counts on a video's cover in the profile grid.
@HookEntry
internal class CoverInfo(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val decorator: () -> CoverDecorator,
    private val awemeName: String = AWEME,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(FeedQueries.COVER_HOLDER)
    private val shown = options.multi(SHOW)
    private val hideLike = options.toggle(HIDE_LIKE)
    private val createTime = NamedFieldAlias("createTime", "create_time")
    private val statistics = NamedField("statistics")
    private val counts = CoverStat.values().associateWith { StatCount("${it.option}Count", "${it.option}_count") }
    private val itemView = NamedField("itemView")

    override fun install(scope: FeatureScope) {
        if (shown.isEmpty() && !hideLike) return
        val aweme = awemeName.toClass(hostClassLoader)
        val holders = resolver.methodLists(hostClassLoader, FeedQueries.COVER_HOLDER).single().map { it.declaringClass }.distinct()
        check(holders.size == 1) { "Expected one cover holder class, found ${holders.size}: ${holders.joinToString { it.name }}" }
        val holder = holders.single()
        // Every void method that takes the video: the cover is rebuilt by bind, and by a second step on some updates. The
        // decorator keeps one line of each kind, so a cover that both reach is decorated once.
        val binders = holder.resolve().optional(silent = true).method {
            returnType = Void.TYPE
            parameters(aweme)
            isSynthetic = false
        }.map { it.self }
        check(binders.isNotEmpty()) { "No bind method taking ${aweme.simpleName} in ${holder.name}" }
        val decorate = decorator()
        binders.forEachIndexed { index, method ->
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(id = "DecorateCover$index", after = { call ->
                val view = call.receiver?.let { itemView.of(it)?.get(it) }
                val video = call.argument(0)
                if (call.outcome is CallOutcome.Returned && view != null && video != null) describe(view, video, decorate)
            }))
        }
    }

    private fun describe(view: Any, video: Any, decorate: CoverDecorator) {
        val created = if ("time" in shown) {
            (createTime.of(video)?.get(video) as? Number)?.toLong()?.takeIf { it > 0 }?.let { SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date(it * 1000)) }
        } else null
        val stats = statistics.of(video)?.get(video)?.let { source ->
            CoverStat.values().filter { it.option in shown }.mapNotNull { stat ->
                counts.getValue(stat).of(source)?.let { stat to compactCount(it) }
            }
        }.orEmpty()
        if (created != null || stats.isNotEmpty() || hideLike) decorate.decorate(view, created, stats, hideLike)
    }

    companion object : TikTokFeature {
        const val ID = "CoverInfo"
        const val SHOW = "show"
        const val HIDE_LIKE = "hide-like-count"
        const val AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"
        private const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
        val OPTIONS = listOf(
            MultiOption(SHOW, listOf("time") + CoverStat.values().map { it.option }),
            ToggleOption(HIDE_LIKE),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CoverInfo(resolver, hostClassLoader, options(ID, OPTIONS), covers))
        }
    }
}
