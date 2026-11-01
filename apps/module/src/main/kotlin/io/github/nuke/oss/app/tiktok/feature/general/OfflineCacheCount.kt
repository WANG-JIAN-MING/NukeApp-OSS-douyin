package io.github.nuke.oss.app.tiktok.feature.general

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.app.tiktok.sdk.FixedResult
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Answers the offline cache page's limit with the chosen number. Zero keeps the host's own limit, so nothing is hooked.
@HookEntry
internal class OfflineCacheCount(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(OptionQueries.CACHE_COUNT)
    private val count = options.number(COUNT).toInt()

    override fun install(scope: FeatureScope) {
        if (count == 0) return
        FixedResult(ID, resolver, hostClassLoader, OptionQueries.CACHE_COUNT, Int::class.javaPrimitiveType!!, count).install(scope)
    }

    companion object : TikTokFeature {
        const val ID = "OfflineCacheCount"
        const val COUNT = "count"
        val OPTIONS = listOf(NumberOption(COUNT, default = 0, min = 0, max = 9999))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(OfflineCacheCount(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
