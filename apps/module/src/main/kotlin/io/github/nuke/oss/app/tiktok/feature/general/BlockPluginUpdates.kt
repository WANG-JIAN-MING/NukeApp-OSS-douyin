package io.github.nuke.oss.app.tiktok.feature.general

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.LinesOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.util.concurrent.ConcurrentHashMap

// Stops Douyin from loading the hot-update plugins the user lists. Plugin package names are only known once the host
// loads them, so each one is logged the first time it is seen; the list is then filled in from the log.
@HookEntry
internal class BlockPluginUpdates(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(OptionQueries.PLUGIN_LOAD)
    private val blocked = options.lines(BLOCKED).toSet()
    private val seen = ConcurrentHashMap.newKeySet<String>()

    override fun install(scope: FeatureScope) {
        val load = resolver.methods(hostClassLoader, OptionQueries.PLUGIN_LOAD).single()
        val packageAt = load.parameterTypes.indexOf(String::class.java)
        check(load.returnType == Boolean::class.javaPrimitiveType && load.parameterCount == 2 && packageAt >= 0) {
            "${load.declaringClass.name}.${load.name} is not a boolean (package, ...) loader"
        }
        load.isAccessible = true
        scope.hooks.install(load, HookSpec(
            id = "BlockPlugins",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { call ->
                val name = call.argument(packageAt) as? String
                if (name != null) {
                    if (seen.size < MAX_LOGGED && seen.add(name)) logger.log("feature=$ID plugin_seen package=$name blocked=${name in blocked}")
                    if (name in blocked) call.returnEarly(false)
                }
            },
        ))
    }

    companion object : TikTokFeature {
        const val ID = "BlockPluginUpdates"
        const val BLOCKED = "blocked"
        private const val MAX_LOGGED = 256
        val OPTIONS = listOf(LinesOption(BLOCKED))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(BlockPluginUpdates(resolver, hostClassLoader, options(ID, OPTIONS), logger))
        }
    }
}
