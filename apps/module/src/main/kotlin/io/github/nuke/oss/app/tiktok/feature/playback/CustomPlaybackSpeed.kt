package io.github.nuke.oss.app.tiktok.feature.playback

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Modifier

// Writes the chosen speed into the prepare data every time the player is prepared for a video. "host" keeps the
// speed Douyin remembers.
@HookEntry
internal class CustomPlaybackSpeed(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(OptionQueries.PLAYER_PREPARE)
    private val speed = options.choice(SPEED).toFloatOrNull()

    override fun install(scope: FeatureScope) {
        val chosen = speed ?: return
        val prepare = resolver.methods(hostClassLoader, OptionQueries.PLAYER_PREPARE).single()
        val dataType = checkNotNull(prepare.parameterTypes.firstOrNull()) { "${prepare.name} takes no prepare data" }
        // The speed is the only public instance float of the prepare data; a second candidate would be a guess.
        val speedField = generateSequence(dataType) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.type == Float::class.javaPrimitiveType && Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .toList()
            .also { check(it.size == 1) { "Expected one public float field in ${dataType.name}, found ${it.size}" } }
            .single()
        speedField.isAccessible = true
        prepare.isAccessible = true
        scope.hooks.install(prepare, HookSpec(
            id = "SetSpeed",
            before = { call -> call.argument(0)?.let { speedField.setFloat(it, chosen) } },
        ))
    }

    companion object : TikTokFeature {
        const val ID = "CustomPlaybackSpeed"
        const val SPEED = "speed"
        const val HOST_SPEED = "host"
        // 0.25x to 3x in steps of a quarter.
        val SPEEDS = (1..12).map { step ->
            (step * 0.25f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
        }
        val OPTIONS = listOf(ChoiceOption(SPEED, listOf(HOST_SPEED) + SPEEDS, default = HOST_SPEED))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CustomPlaybackSpeed(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
