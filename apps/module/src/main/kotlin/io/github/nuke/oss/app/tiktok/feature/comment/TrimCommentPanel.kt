package io.github.nuke.oss.app.tiktok.feature.comment

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.declaredMethodsNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.LinesOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

// Removes entries from the menu that opens when a comment is long-pressed. The menu is a list of groups; each group
// keeps its entries in SparseArrays of lists, and every entry has a tag naming it. The tags are only visible in the
// host, so each one is written to the module log the first time it shows up, to be copied into the list of hidden tags.
@HookEntry
internal class TrimCommentPanel(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val logger: Logger,
    options: OptionValues,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(CommentQueries.LONG_PRESS_PANEL)
    private val hidden = options.lines(HIDDEN).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private val tag = NamedCall("getTag")
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun install(scope: FeatureScope) {
        val anchor = resolver.methods(hostClassLoader, CommentQueries.LONG_PRESS_PANEL).single()
        val builders = anchor.declaringClass.declaredMethodsNamed(anchor.name).filter { List::class.java.isAssignableFrom(it.returnType) }
        check(builders.isNotEmpty()) { "${anchor.declaringClass.name}.${anchor.name} builds no list" }
        builders.forEachIndexed { index, builder ->
            scope.hooks.install(builder, HookSpec(id = "TrimPanel$index", after = { call ->
                val groups = (call.outcome as? CallOutcome.Returned)?.value as? List<*>
                groups?.forEach { group -> if (group != null) trim(group) }
            }))
        }
    }

    private fun trim(group: Any) {
        for (field in generateSequence(group.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }) {
            if (field.type.simpleName != "SparseArray" || Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            val sparse = field.get(group) ?: continue
            val members = sparse.asResolver()
            val size = members.firstMethod { name = "size"; emptyParameters(); superclass() }.invoke() as Int
            val valueAt = members.firstMethod { name = "valueAt"; parameters(Int::class); superclass() }
            for (position in 0 until size) {
                val entries = valueAt.invoke(position) as? MutableList<*> ?: continue
                for (entry in entries.toList()) {
                    val name = entry?.let { tag.of(it)?.invoke(it) as? String } ?: continue
                    if (seen.size < MAX_LOGGED && seen.add(name)) logger.log("feature=$ID tag_seen name=$name hidden=${name in hidden}")
                    if (name in hidden) entries.remove(entry)
                }
            }
        }
    }

    companion object : TikTokFeature {
        const val ID = "TrimCommentPanel"
        const val HIDDEN = "hidden"
        private const val MAX_LOGGED = 64
        val OPTIONS = listOf(LinesOption(HIDDEN))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(TrimCommentPanel(resolver, hostClassLoader, logger, options(ID, OPTIONS)))
        }
    }
}
