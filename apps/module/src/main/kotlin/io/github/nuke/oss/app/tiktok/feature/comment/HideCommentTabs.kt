package io.github.nuke.oss.app.tiktok.feature.comment

import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.LinesOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.util.concurrent.ConcurrentHashMap

// Hides tabs of the comment panel by page type. The panel asks a boolean question about each page it could show, and
// the answer is false for the chosen types. Page types are numbers the host does not name, so each one is written to the
// module log the first time it is asked about, to be copied into the list.
@HookEntry
internal class HideCommentTabs(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val logger: Logger,
    options: OptionValues,
    private val pageName: String = PAGE,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(CommentQueries.PANEL_TABS)
    private val hidden = options.lines(PAGE_TYPES).mapNotNull { it.trim().toIntOrNull() }.toSet()
    private val pageType = NamedCall("getPageType")
    private val seen: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    override fun install(scope: FeatureScope) {
        val anchor = resolver.methods(hostClassLoader, CommentQueries.PANEL_TABS).single()
        val ask = anchor.declaringClass.resolve().optional(silent = true).method {
            returnType = Boolean::class
            parameters { it.size == 1 && it[0].name == pageName }
            isSynthetic = false
        }.map { it.self }
        check(ask.size == 1) { "Expected one boolean question about a page in ${anchor.declaringClass.name}, found ${ask.size}" }
        val question = ask.single()
        question.isAccessible = true
        scope.hooks.install(question, HookSpec(
            id = "HideTabs",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { call ->
                val page = call.argument(0)
                val type = page?.let { pageType.of(it)?.invoke(it) as? Int }
                if (type != null) {
                    if (seen.size < MAX_LOGGED && seen.add(type)) logger.log("feature=$ID page_seen type=$type hidden=${type in hidden}")
                    if (type in hidden) call.returnEarly(false)
                }
            },
        ))
    }

    companion object : TikTokFeature {
        const val ID = "HideCommentTabs"
        const val PAGE_TYPES = "page-types"
        const val PAGE = "com.ss.android.ugc.aweme.page.api.IPage"
        private const val MAX_LOGGED = 32
        val OPTIONS = listOf(LinesOption(PAGE_TYPES))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(HideCommentTabs(resolver, hostClassLoader, logger, options(ID, OPTIONS)))
        }
    }
}
