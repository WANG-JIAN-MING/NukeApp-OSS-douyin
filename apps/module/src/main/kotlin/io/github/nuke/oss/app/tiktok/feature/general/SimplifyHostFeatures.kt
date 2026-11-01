package io.github.nuke.oss.app.tiktok.feature.general

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.registry.HostProcess
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.MiniAppQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.util.concurrent.ConcurrentHashMap

// Stops the code patch loader (Reparo) from initializing and from asking the server for patches. It runs in every
// non-isolated process of the host, since each loads patches on its own. Patches that are already applied are not
// reverted, and Gecko pages, plugins and server configuration are not touched. The hooks go in when the host's
// ClassLoader is ready, which for the Zygisk entry is after the Application was created, so a patch loaded earlier
// than that is not stopped.
@HookEntry
internal class DisableHotUpdates(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = "DisableHotUpdates"
    override val dexQueries = listOf(MiniAppQueries.REPARO_INIT, MiniAppQueries.REPARO_FETCH)
    private val logged = ConcurrentHashMap.newKeySet<String>()

    override fun install(scope: FeatureScope) {
        val (init, fetch) = resolver.methods(hostClassLoader, MiniAppQueries.REPARO_INIT, MiniAppQueries.REPARO_FETCH)
        check(init.returnType == Void.TYPE && init.parameterTypes.singleOrNull()?.name == "com.bytedance.reparo.IReparoConfig") { "Reparo initialization changed shape" }
        check(fetch.returnType == Void.TYPE && fetch.parameterCount == 0) { "Reparo patch check changed shape" }
        for ((label, method) in listOf("initialization" to init, "patch check" to fetch)) {
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(id = label.replace(' ', '-'), requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
                call.returnEarly(null)
                if (logged.add(label)) logger.log("feature=$id blocked $label")
            }))
        }
    }

    companion object : TikTokFeature {
        override val processes = HostProcess.ALL

        override fun create(services: TikTokFeatureServices) = with(services) { listOf(DisableHotUpdates(resolver, hostClassLoader, logger)) }
    }
}

// Hides the teen-mode dialog: the manager's methods that take a flag as their second argument do nothing when it is true,
// which is the branch that shows the dialog. Only void methods are taken over, since a value cannot be made up for
// the others.
@HookEntry
internal class HideTeenModeDialog(private val hostClassLoader: ClassLoader) : RuntimeFeature {
    override val id = "HideTeenModeDialog"

    override fun install(scope: FeatureScope) {
        val manager = MANAGER.toClass(hostClassLoader)
        val showing = manager.resolve().optional(silent = true).method {
            name { it != "onStateChanged" }
            parameterCount = 2
            returnType = Void.TYPE
            isSynthetic = false
            isBridge = false
        }.map { it.self }
        check(showing.isNotEmpty()) { "No two-argument void method to take over in $MANAGER" }
        showing.forEachIndexed { index, method ->
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(id = "Hide$index", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
                if (call.argument(1) == true) call.returnEarly(null)
            }))
        }
    }

    companion object : TikTokFeature {
        private const val MANAGER = "com.ss.android.ugc.aweme.im.sdk.relations.IMTeenModeManager"

        override fun create(services: TikTokFeatureServices) = with(services) { listOf(HideTeenModeDialog(hostClassLoader)) }
    }
}
