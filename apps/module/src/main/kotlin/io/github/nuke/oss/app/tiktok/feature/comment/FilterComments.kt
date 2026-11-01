package io.github.nuke.oss.app.tiktok.feature.comment

import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methodLists
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.LinesOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Method

// Drops comments that contain any of the chosen words from a fetched page before the panel receives it. The host has
// an older and a newer comment panel; every step that takes a page is hooked, and the page is found by shape: the
// argument that has an "items" list. The newer reply handler takes an untyped argument, so there the page is
// recognized by its class.
@HookEntry
internal class FilterComments(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val pageName: String = PAGE,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(CommentQueries.LOADED_PAGES)
    private val words = options.lines(WORDS).map { it.trim() }.filter { it.isNotEmpty() }
    private val items = NamedField("items")
    private val text = NamedField("text")

    override fun install(scope: FeatureScope) {
        if (words.isEmpty()) return
        val steps = resolver.methodLists(hostClassLoader, CommentQueries.LOADED_PAGES).single()
        // Same-named overloads in a class are the same step for different pages, so they are hooked together.
        val methods = steps.flatMap { step ->
            step.declaringClass.resolve().optional(silent = true).method { name = step.name; isSynthetic = false }.map { it.self }
        }.distinct()
        val plans = methods.mapNotNull { method -> pageIndex(method)?.let { method to it } }
        check(plans.isNotEmpty()) { "None of the ${methods.size} comment steps takes a page of comments" }
        plans.forEachIndexed { index, (method, argument) ->
            method.isAccessible = true
            val untyped = method.parameterTypes[argument] == Any::class.java
            scope.hooks.install(method, HookSpec(id = "FilterPage$index", before = { call ->
                call.argument(argument)?.let { filter(it, untyped) }
            }))
        }
    }

    // The argument that carries the page: a typed parameter with an items list, else the one untyped parameter of a
    // single-parameter step, which is only filtered when it turns out to be a page.
    private fun pageIndex(method: Method): Int? {
        val typed = method.parameterTypes.indexOfFirst { type ->
            type.resolve().optional(silent = true).firstFieldOrNull {
                name = "items"
                type { List::class.java.isAssignableFrom(it) }
                superclass()
            } != null
        }
        if (typed >= 0) return typed
        return method.parameterTypes.indexOfFirst { it == Any::class.java }.takeIf { it >= 0 && method.parameterCount == 1 }
    }

    private fun filter(page: Any, untyped: Boolean) {
        if (untyped && page.javaClass.name != pageName) return
        val list = items.of(page)?.get(page) as? MutableList<*> ?: return
        for (comment in list.toList()) {
            val body = comment?.let { text.of(it)?.get(it) as? String } ?: continue
            if (words.any { it in body }) list.remove(comment)
        }
    }

    companion object : TikTokFeature {
        const val ID = "FilterComments"
        const val WORDS = "words"
        const val PAGE = "com.ss.android.ugc.aweme.comment.model.CommentItemList"
        val OPTIONS = listOf(LinesOption(WORDS))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(FilterComments(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
