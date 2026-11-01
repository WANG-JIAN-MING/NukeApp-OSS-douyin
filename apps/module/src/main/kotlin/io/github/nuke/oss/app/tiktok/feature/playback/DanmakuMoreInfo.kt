package io.github.nuke.oss.app.tiktok.feature.playback

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatMessageFinder
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// The "More" entry in the danmaku bubble menu. attach adds it to a popover the host has just shown; the entry's tap
// calls onClick with the entry's Context. copy puts text on the clipboard.
internal interface DanmakuMenu {
    fun attach(popover: Any, onClick: (context: Any) -> Unit)
    fun copy(context: Any, text: String)
}

// Finds the sender's ID in the danmaku's printed form.
internal fun danmakuUserId(printed: String): String? = Regex("""\buserId=([^,)\s]+)""").find(printed)?.groupValues?.get(1)

// Adds "More" to the menu that opens on a danmaku. The danmaku the menu is for is the one the bubble's action handler
// last received; "More" shows what the host prints for it and offers to copy the sender's ID. Coloring danmaku, which
// rewrites the config's constructor arguments, is a separate feature.
@HookEntry
internal class DanmakuMoreInfo(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val menu: () -> DanmakuMenu,
    private val prompts: HostPrompts,
    private val logger: Logger,
    private val popoverName: String = POPOVER,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(FeedQueries.DANMAKU_ACTION, FeedQueries.DANMAKU_STRUCT)

    override fun install(scope: FeatureScope) {
        prompts.install(scope)
        val (action, structMethod) = resolver.methods(hostClassLoader, FeedQueries.DANMAKU_ACTION, FeedQueries.DANMAKU_STRUCT)
        val struct = structMethod.declaringClass
        // Older hosts hand the handler the danmaku itself; Douyin 40.7.0 hands it an item that wraps the danmaku, typed as
        // an interface of the host. Any parameter of a host type may carry the danmaku, so each is searched for it.
        val finder = ChatMessageFinder(struct)
        val handlers = action.declaringClass.resolve().optional(silent = true).method { name = action.name; isSynthetic = false }
            .map { it.self }
            .filter { method -> method.parameterTypes.any { it == struct || it.isHostType() } }
        check(handlers.isNotEmpty()) { "${action.declaringClass.name}.${action.name} takes no ${struct.name} or item that holds one" }
        val show = popoverName.toClass(hostClassLoader).resolve().optional(silent = true).method {
            name = "show"
            parameters { it.size == 3 && it[1] == Int::class.javaPrimitiveType && it[2] == Boolean::class.javaPrimitiveType }
            isSynthetic = false
        }.map { it.self }
        check(show.isNotEmpty()) { "$popoverName declares no show(View, int, boolean)" }
        val ui = menu()
        val latest = AtomicReference<String?>()
        // One line per kind of outcome keeps the log bounded while still saying which step the feature reached.
        val handlerFound = AtomicBoolean()
        val handlerEmpty = AtomicBoolean()
        val shownOnce = AtomicBoolean()
        handlers.forEachIndexed { index, method ->
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(id = "DanmakuAction$index", before = { call ->
                val danmaku = (0 until method.parameterCount).firstNotNullOfOrNull { finder.find(call.argument(it)) }
                latest.set(danmaku?.toString())
                if (danmaku != null && handlerFound.compareAndSet(false, true)) logger.log("feature=$id handler_seen danmaku=found")
                if (danmaku == null && handlerEmpty.compareAndSet(false, true)) logger.log("feature=$id handler_seen danmaku=none")
            }))
        }
        show.forEachIndexed { index, method ->
            method.isAccessible = true
            // The popover measures and places itself inside show, so the entry goes in first.
            scope.hooks.install(method, HookSpec(id = "DanmakuPopover$index", before = { call ->
                val popover = call.receiver
                if (popover != null) {
                    if (shownOnce.compareAndSet(false, true)) logger.log("feature=$id popover_shown danmaku=${latest.get() != null}")
                    ui.attach(popover) { context -> open(ui, context, latest.get()) }
                }
            }))
        }
    }

    private fun Class<*>.isHostType() = !isPrimitive && !isArray && !name.startsWith("java.") && !name.startsWith("android.") && !name.startsWith("kotlin.")

    // Runs on the main thread when the entry is tapped.
    private fun open(ui: DanmakuMenu, context: Any, printed: String?) {
        if (printed == null) return prompts.toast(R.string.host_danmaku_unavailable)
        val uid = danmakuUserId(printed)
        prompts.confirm(R.string.host_danmaku_title, R.string.host_danmaku_info, arrayOf(printed), R.string.host_danmaku_copy_uid, onUnavailable = {}) {
            ui.copy(context, uid ?: printed)
        }
    }

    companion object : TikTokFeature {
        const val ID = "DanmakuMoreInfo"
        const val POPOVER = "com.bytedance.dux.popover.DuxPopover"

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(DanmakuMoreInfo(resolver, hostClassLoader, danmakuMenu, prompts(ID), logger))
        }
    }
}
