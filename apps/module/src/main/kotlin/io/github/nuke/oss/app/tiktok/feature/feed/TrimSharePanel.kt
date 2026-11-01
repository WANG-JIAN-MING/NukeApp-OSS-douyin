package io.github.nuke.oss.app.tiktok.feature.feed

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.MultiOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Removes the chosen actions from the share panel. Each item the panel receives wraps its action in a field whose
// getter names the action; the member names are those of the obfuscated host build.
@HookEntry
internal class TrimSharePanel(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(OptionQueries.SHARE_ACTIONS)
    private val hidden = options.multi(HIDDEN)
    private val action = NamedField(ACTION_FIELD)
    private val actionId = NamedCall(ACTION_ID)

    override fun install(scope: FeatureScope) {
        if (hidden.isEmpty()) return
        val receiver = resolver.methods(hostClassLoader, OptionQueries.SHARE_ACTIONS).single()
        check(receiver.returnType == Void.TYPE && receiver.parameterCount == 1 && List::class.java.isAssignableFrom(receiver.parameterTypes[0])) {
            "${receiver.declaringClass.name}.${receiver.name} does not take a list of share actions"
        }
        receiver.isAccessible = true
        scope.hooks.install(receiver, HookSpec(id = "RemoveShareActions", before = { call ->
            @Suppress("UNCHECKED_CAST")
            val items = call.argument(0) as? MutableList<Any?>
            if (items != null && items.any { idOf(it) in hidden }) items.removeAll { idOf(it) in hidden }
        }))
    }

    private fun idOf(item: Any?): String? {
        val wrapped = item?.let { action.of(it)?.get(it) } ?: return null
        return actionId.of(wrapped)?.invoke(wrapped) as? String
    }

    companion object : TikTokFeature {
        const val ID = "TrimSharePanel"
        const val HIDDEN = "hidden"
        private const val ACTION_FIELD = "LIZ"
        private const val ACTION_ID = "LJIIIZ"
        val ACTIONS = listOf(
            "create_group_and_share", "share_to_daily", "familiar_recommend", "chat_merge", "create_group_share",
            "dou_plus", "copy", "f2f", "dislike", "download", "duet", "play_remote", "qr_code", "live_photo",
            "aweme_desktop", "report", "feedback", "reward", "dou_shop", "ad_like", "ad_dislike", "share_copy_token",
        )
        val OPTIONS = listOf(MultiOption(HIDDEN, ACTIONS))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(TrimSharePanel(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
