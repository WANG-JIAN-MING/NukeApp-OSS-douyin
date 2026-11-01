package io.github.nuke.oss.app.tiktok.feature.miniapp

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methodLists
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.registry.HostProcess
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.GateQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Method

// Answers the mini game anti-addiction check as passed without running it. The main process carries one dispatcher,
// found by its log strings; a mini-game process loads the game SDK's own manager, whose realDispatch overloads all get
// the same answer.
@HookEntry
internal class SkipMinorCheck(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val process: HostProcess,
    private val managerName: String = MANAGER,
) : RuntimeFeature, UsesDexQueries {
    override val id = "SkipMinorCheck"
    // Other processes hook the game SDK's own manager by class name and resolve nothing.
    override val dexQueries: List<DexQuery> = if (process == HostProcess.MAIN) listOf(GateQueries.MINOR_CHECK) else emptyList()

    override fun install(scope: FeatureScope) {
        val methods = if (process == HostProcess.MAIN) {
            resolver.methodLists(hostClassLoader, GateQueries.MINOR_CHECK).flatten()
        } else {
            val manager = managerName.toClass(hostClassLoader)
            manager.resolve().optional(silent = true).method { name = "realDispatch" }.map { it.self }
                .also { check(it.isNotEmpty()) { "${manager.name} has no realDispatch" } }
        }
        // A different return type means the host changed shape; answering it anyway would corrupt its callers.
        methods.forEach { check(it.returnType == Boolean::class.javaPrimitiveType) { "${it.declaringClass.name}.${it.name} no longer returns boolean" } }
        methods.forEachIndexed { index, method: Method ->
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(
                id = "Passed$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { it.returnEarly(true) },
            ))
        }
    }

    companion object : TikTokFeature {
        const val MANAGER = "com.minigame.merge.miniapp.game.health.AntiAddictionMgr"
        override val processes = HostProcess.MAIN_AND_MINI_APP

        override fun create(services: TikTokFeatureServices) = with(services) { listOf(SkipMinorCheck(resolver, hostClassLoader, process)) }
    }
}
