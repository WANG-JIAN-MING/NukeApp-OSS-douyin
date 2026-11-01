package io.github.nuke.oss.app.tiktok.feature.feed

import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methodLists
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.MultiOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Removes the chosen groups and items from the panel that opens on a long press of a video. The panel is described by
// a configuration object built from a list of module groups; the lists it was built from are trimmed as soon as the
// constructor has run, which is the only point the Hook model allows on a constructor. A configuration that copies the
// list while it is built would not be affected.
@HookEntry
internal class TrimLongPressPanel(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(OptionQueries.LONG_PRESS_CONFIG)
    private val hiddenModules = options.multi(HIDDEN_MODULES)
    private val hiddenItems = options.multi(HIDDEN_ITEMS)
    private val moduleId = NamedField("moduleId")
    private val moduleItems = NamedField("moduleItems")
    private val itemId = NamedField("itemId")

    override fun install(scope: FeatureScope) {
        if (hiddenModules.isEmpty() && hiddenItems.isEmpty()) return
        // Each copy of the configuration class is trimmed; a copy the host does not build is never constructed.
        val constructors = resolver.methodLists(hostClassLoader, OptionQueries.LONG_PRESS_CONFIG).single().map { it.declaringClass }.distinct().flatMap { owner ->
            val found = owner.resolve().optional(silent = true).constructor {
                isSynthetic = false
                parameters { it.size >= 2 && it[1] == List::class.java }
            }.map { it.self }
            check(found.isNotEmpty()) { "No constructor taking a module list in ${owner.name}" }
            found
        }
        constructors.forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            scope.hooks.install(constructor, HookSpec(
                id = "TrimPanel$index",
                requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
                after = { call -> trim(call.argument(1)) },
            ))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun trim(modules: Any?) {
        val groups = modules as? MutableList<Any?> ?: return
        groups.removeAll { group -> group != null && (moduleId.of(group)?.get(group) as? String) in hiddenModules }
        for (group in groups) {
            if (group == null) continue
            val items = moduleItems.of(group)?.get(group) as? MutableList<Any?> ?: continue
            items.removeAll { item -> item != null && (itemId.of(item)?.get(item) as? String) in hiddenItems }
        }
    }

    companion object : TikTokFeature {
        const val ID = "TrimLongPressPanel"
        const val HIDDEN_MODULES = "hidden-modules"
        const val HIDDEN_ITEMS = "hidden-items"
        val MODULES = listOf("share", "feedback_control", "play_control", "play_function", "auth", "story", "listen")
        val ITEMS = listOf(
            "familiar_recommend", "2_story", "duet", "dislike", "report", "speed", "clean_mode", "video_picture_search", "watch_later",
            "listen_later", "subtitle_and_translation", "save", "download", "cache_video", "cast", "video_syncplay",
            "long_press_panel_ai_note", "homepagehot_shortcut", "danmaku_switch", "danmaku_setting", "timing_close", "auto_feed",
            "speech_control", "auto_feed_pad", "bg_play_audio", "phonepip", "padpip", "feed_share_bg_play_settings", "auth", "bubble",
            "block_post", "bg_play",
        )
        val OPTIONS = listOf(MultiOption(HIDDEN_MODULES, MODULES), MultiOption(HIDDEN_ITEMS, ITEMS))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(TrimLongPressPanel(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
