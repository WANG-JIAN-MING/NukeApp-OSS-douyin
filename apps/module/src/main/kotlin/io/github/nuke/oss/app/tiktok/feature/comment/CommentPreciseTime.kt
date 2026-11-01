package io.github.nuke.oss.app.tiktok.feature.comment

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.TextOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Shows the exact time of each comment instead of "3分钟前" or "昨天" ("显示精确时间") in the new
// comment panel: the host's relative time formatter answers with the time in the chosen pattern. The formatter lives in
// the host's shared code, so any other place that uses it shows exact times as well.
@HookEntry
internal class CommentPreciseTime(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(CommentQueries.RELATIVE_TIME)
    private val pattern = options.text(PATTERN).ifBlank { DEFAULT_PATTERN }

    override fun install(scope: FeatureScope) {
        val formatter = try { DateTimeFormatter.ofPattern(pattern) } catch (_: IllegalArgumentException) { error("$pattern is not a time pattern") }
        val relative = resolver.methods(hostClassLoader, CommentQueries.RELATIVE_TIME).single().apply { isAccessible = true }
        check(relative.returnType == String::class.java) { "${relative.declaringClass.name}.${relative.name} does not return a String" }
        val millis = relative.parameterTypes.indexOfFirst { it == Long::class.javaPrimitiveType }
        check(millis >= 0) { "${relative.declaringClass.name}.${relative.name} takes no time" }
        scope.hooks.install(relative, HookSpec(
            id = "ExactTime",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { call ->
                val at = call.argument(millis) as? Long ?: return@HookSpec
                call.returnEarly(formatter.format(Instant.ofEpochMilli(at).atZone(zone())))
            },
        ))
    }

    companion object : TikTokFeature {
        const val ID = "CommentPreciseTime"
        const val PATTERN = "pattern"
        const val DEFAULT_PATTERN = "yyyy-MM-dd HH:mm:ss"
        val OPTIONS = listOf(TextOption(PATTERN, default = DEFAULT_PATTERN))

        override fun create(services: TikTokFeatureServices) = with(services) { listOf(CommentPreciseTime(resolver, hostClassLoader, options(ID, OPTIONS))) }
    }
}
