package io.github.nuke.oss.app.tiktok.feature.navigation

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Opens a profile on its own first tab. The page jumps to another tab when the user carries a publish reminder with a
// ban type, so that type is zeroed on the User just before the page takes it. Hosts that keep the reminder on the
// profile tab info use that, older ones keep it on the User itself.
@HookEntry
internal class KeepProfileTab(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val userName: String = USER,
    private val remindName: String = REMIND_INFO,
) : RuntimeFeature, UsesDexQueries {
    override val id = "KeepProfileTab"
    override val dexQueries = listOf(FeedQueries.PROFILE_ENTRY)
    private val tabInfo = NamedField("profile_tab_info")
    private val punishInfo = NamedField("punish_remind_info")
    private val banType = NamedField("ban_type")

    override fun install(scope: FeatureScope) {
        val entry = resolver.methods(hostClassLoader, FeedQueries.PROFILE_ENTRY).single()
        check(entry.parameterCount == 1 && entry.parameterTypes[0].name == userName) {
            "${entry.declaringClass.name}.${entry.name} does not take a $userName"
        }
        entry.isAccessible = true
        scope.hooks.install(entry, HookSpec(id = "ZeroBanType", before = { call -> call.argument(0)?.let(::clearBanType) }))
    }

    private fun clearBanType(user: Any) {
        val info = tabInfo.of(user)?.get(user)
        val reminder = info?.asResolver()?.optional(silent = true)?.firstFieldOrNull { type { it.name == remindName } }?.get()
            ?: punishInfo.of(user)?.get(user)
            ?: return
        banType.of(reminder)?.takeIf { it.type == Int::class.javaPrimitiveType }?.setInt(reminder, 0)
    }

    companion object : TikTokFeature {
        const val USER = "com.ss.android.ugc.aweme.profile.model.User"
        const val REMIND_INFO = "com.ss.android.ugc.aweme.profile.model.PublishRemindInfo"

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(KeepProfileTab(resolver, hostClassLoader))
        }
    }
}
