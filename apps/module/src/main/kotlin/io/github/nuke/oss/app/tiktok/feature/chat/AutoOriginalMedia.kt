package io.github.nuke.oss.app.tiktok.feature.chat

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ChatMediaQueries
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// The two View facts the e-commerce picker needs.
internal interface PickerViews {
    // The Activity a View belongs to, through its wrapped Contexts; null when there is none.
    fun activityOf(view: Any): Any?

    // The first View of that type in the tree under root.
    fun find(root: Any, type: Class<*>): Any?
}

// Ticks "original" by default when a media picker opens, while the manual switch keeps working. The chat album's view
// model is set after its own initialization resets the state, and the e-commerce picker's check box is ticked through
// the host's setChecked so its listener records the choice.
@HookEntry
internal class AutoOriginalMedia(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val views: () -> PickerViews,
) : RuntimeFeature, UsesDexQueries {
    override val id = "AutoOriginalMedia"
    override val dexQueries = listOf(
        ChatMediaQueries.ORIGINAL_SETTER, ChatMediaQueries.ALBUM_INITIALIZATION, ChatMediaQueries.ALBUM_VIEW_MODEL,
        ChatMediaQueries.ECOMMERCE_ORIGINAL_BIND,
    )

    override fun install(scope: FeatureScope) {
        val (setter, initialization, albumModel, bind) = resolver.methods(
            hostClassLoader, ChatMediaQueries.ORIGINAL_SETTER, ChatMediaQueries.ALBUM_INITIALIZATION,
            ChatMediaQueries.ALBUM_VIEW_MODEL, ChatMediaQueries.ECOMMERCE_ORIGINAL_BIND,
        )
        listOf(setter, initialization, albumModel, bind).forEach { it.isAccessible = true }
        val modelType = ChatMediaQueries.MEDIA_VIEW_MODEL.toClass(hostClassLoader)
        val constructor = modelType.resolve().firstConstructor { emptyParameters() }.self
        val picker = ECOMMERCE_PICKER.toClass(hostClassLoader)
        val checkbox = CHECKBOX.toClass(hostClassLoader)
        val setChecked = checkbox.resolve().firstMethod { name = "setChecked"; parameters(Boolean::class) }.self.apply { isAccessible = true }
        val ui = views()
        scope.hooks.install(constructor, HookSpec(
            id = "ViewModel",
            requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
            after = { call ->
                val model = call.receiver
                if (call.outcome is CallOutcome.Returned && model != null) setter.invoke(model, true)
            },
        ))
        // The album's initialization sets original to off even for a fresh view model, so the default goes in after it.
        scope.hooks.install(initialization, HookSpec(id = "Album", after = { call ->
            val album = call.receiver
            if (call.outcome is CallOutcome.Returned && album != null) albumModel.invoke(album)?.let { setter.invoke(it, true) }
        }))
        scope.hooks.install(bind, HookSpec(id = "Ecommerce", after = { call ->
            val root = (call.outcome as? CallOutcome.Returned)?.value ?: return@HookSpec
            val activity = ui.activityOf(root) ?: return@HookSpec
            if (!picker.isInstance(activity)) return@HookSpec
            // setChecked tells the installed listener, which updates the picker's is_original.
            ui.find(root, checkbox)?.let { setChecked.invoke(it, true) }
        }))
    }

    companion object : TikTokFeature {
        private const val ECOMMERCE_PICKER = "com.ss.android.ugc.aweme.ecommerce.im.choosemedia.ECommerceIMMediaChooseActivity"
        private const val CHECKBOX = "com.bytedance.dux.widget.DuxCheckBox"

        override fun create(services: TikTokFeatureServices) = with(services) { listOf(AutoOriginalMedia(resolver, hostClassLoader, pickerViews)) }
    }
}
