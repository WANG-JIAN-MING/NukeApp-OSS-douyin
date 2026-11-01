package io.github.nuke.oss.app.tiktok.feature.feed

import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.declaredMethodsNamed
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
import java.lang.reflect.Modifier

// Takes the rings off feed avatars. The daily ring's drawing method is skipped. The live ring shows while the author has
// a live room, so the room id is zeroed on the video's author just before the avatar is set up.
@HookEntry
internal class HideAvatarRings(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val presenterName: String = PRESENTER,
    private val awemeName: String = AWEME,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    private val daily = options.toggle(DAILY)
    private val live = options.toggle(LIVE)
    // Only what the chosen rings resolve.
    override val dexQueries: List<DexQuery> = listOfNotNull(OptionQueries.DAILY_RING.takeIf { daily }, OptionQueries.LIVE_RING.takeIf { live })
    private val author = NamedField("author")
    private val roomId = NamedField("room_id")

    override fun install(scope: FeatureScope) {
        if (daily) {
            val draw = resolver.methods(hostClassLoader, OptionQueries.DAILY_RING).single()
            val skipped = draw.declaringClass.declaredMethodsNamed(draw.name).filter { it.returnType == Void.TYPE }
            check(skipped.isNotEmpty()) { "No void ${draw.name} in ${draw.declaringClass.name}" }
            skipped.forEachIndexed { index, method ->
                scope.hooks.install(method, HookSpec(
                    id = "SkipDailyRing$index",
                    requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                    before = { it.returnEarly(null) },
                ))
            }
        }
        if (live) {
            val setup = resolver.methods(hostClassLoader, OptionQueries.LIVE_RING).single()
            val presenterType = presenterName.toClass(hostClassLoader)
            val awemeType = awemeName.toClass(hostClassLoader)
            check(presenterType.isAssignableFrom(setup.declaringClass)) { "${setup.declaringClass.name} is not a ${presenterType.simpleName}" }
            // The presenter holds the current video in its only public field of the video type.
            val videoField = generateSequence(presenterType) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter { it.type == awemeType && Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
                .toList()
                .also { check(it.size == 1) { "Expected one public ${awemeType.simpleName} field in ${presenterType.name}, found ${it.size}" } }
                .single()
            videoField.isAccessible = true
            setup.isAccessible = true
            scope.hooks.install(setup, HookSpec(id = "ZeroRoomId", before = { call ->
                val video = call.receiver?.let { videoField.get(it) }
                val owner = video?.let { author.of(it)?.get(it) }
                val room = owner?.let { roomId.of(it) }
                if (owner != null && room != null && room.type == Long::class.javaPrimitiveType && room.getLong(owner) != 0L) room.setLong(owner, 0L)
            }))
        }
    }

    companion object : TikTokFeature {
        const val ID = "HideAvatarRings"
        const val DAILY = "daily"
        const val LIVE = "live"
        const val PRESENTER = "com.ss.android.ugc.aweme.feed.quick.presenter.FeedAsyncPresenter"
        const val AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"
        val OPTIONS = listOf(ToggleOption(DAILY, default = true), ToggleOption(LIVE, default = true))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(HideAvatarRings(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
