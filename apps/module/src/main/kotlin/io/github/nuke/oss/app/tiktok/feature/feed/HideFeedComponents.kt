package io.github.nuke.oss.app.tiktok.feature.feed

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.activity.Timers
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.LinesOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanPlan
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanScreen
import io.github.nuke.oss.app.tiktok.sdk.screen.planRightButtons
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.util.concurrent.ConcurrentHashMap

// Keeps chosen components out of the feed. The feed is built from a set of components the host passes along in its
// component data actions and lifecycle step; the chosen names are removed from that set before it is used. The comment
// and share buttons are not part of the set, so they are hidden as Views after the right-edge bar is built. Names are the
// host's own enum names; each one seen is written to the module log once, so the list can be filled in from it.
@HookEntry
internal class HideFeedComponents(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val screen: CleanScreen,
    private val scheduler: TaskScheduler,
    private val logger: Logger,
    private val actionNames: List<String> = ACTIONS,
    private val rightBarName: String = RIGHT_BAR,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(FeedQueries.COMPONENT_LIFECYCLE)
    private val names = options.lines(COMPONENTS).toSet()
    private val hideComment = options.toggle(COMMENT)
    private val hideShare = options.toggle(SHARE)
    private val seen = ConcurrentHashMap.newKeySet<String>()

    override fun install(scope: FeatureScope) {
        if (names.isNotEmpty()) installComponentSets(scope)
        if (hideComment || hideShare) installRightButtons(scope)
    }

    private fun installComponentSets(scope: FeatureScope) {
        val set = java.util.Set::class.java
        // The lifecycle step is checked before anything is hooked, so a changed host installs nothing.
        val lifecycle = resolver.methods(hostClassLoader, FeedQueries.COMPONENT_LIFECYCLE).single()
        check(lifecycle.parameterCount == 2) { "${lifecycle.declaringClass.name}.${lifecycle.name} does not take two parameters" }
        val setField = lifecycle.parameterTypes[0].resolve().optional(silent = true).firstFieldOrNull {
            type = set
            modifiers(Modifiers.PUBLIC, Modifiers.FINAL)
        }?.self?.apply { isAccessible = true }
        lifecycle.isAccessible = true
        for (name in actionNames) {
            val type = try { name.toClass(hostClassLoader) } catch (_: ClassNotFoundException) { continue }
            val constructors = type.resolve().optional(silent = true).constructor { parameters { it.firstOrNull() == set } }
            for ((index, constructor) in constructors.map { it.self }.withIndex()) {
                constructor.isAccessible = true
                // A constructor can only be observed once it has finished, so the set it was given is trimmed then, and so
                // is the set the new action holds, in case the host copied it.
                scope.hooks.install(constructor, HookSpec(
                    id = "Components${type.simpleName}$index",
                    requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
                    after = { call ->
                        if (call.outcome is CallOutcome.Returned) {
                            trim(call.argument(0))
                            call.receiver?.let(::trimHeld)
                        }
                    },
                ))
            }
        }
        scope.hooks.install(lifecycle, HookSpec(id = "ComponentsLifecycle", before = { call ->
            val data = call.argument(0)
            if (data != null && setField != null) trim(setField.get(data))
        }))
    }

    private fun trimHeld(action: Any) {
        action.asResolver().optional(silent = true).field {
            type = java.util.Set::class.java
            modifiersNot(Modifiers.STATIC)
        }.forEach { trim(it.get()) }
    }

    private fun trim(set: Any?) {
        val components = set as? MutableSet<*> ?: return
        for (component in components.toList()) {
            val name = component?.toString() ?: continue
            if (seen.size < MAX_LOGGED && seen.add(name)) logger.log("feature=$ID component_seen name=$name chosen=${name in names}")
            if (name in names && name !in KEPT) components.remove(component)
        }
    }

    // The bar is built before its children exist, so the buttons are looked for again for a few seconds.
    private fun installRightButtons(scope: FeatureScope) {
        screen.install(scope, ID)
        val timers = scope.own(Timers(scheduler))
        val bar = rightBarName.toClass(hostClassLoader)
        val constructors = bar.resolve().optional(silent = true).constructor { isSynthetic = false }.map { it.self }
        check(constructors.isNotEmpty()) { "${bar.name} declares no constructor" }
        constructors.forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            scope.hooks.install(constructor, HookSpec(
                id = "RightBar$index",
                requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
                after = { call ->
                    if (call.outcome is CallOutcome.Returned) for ((step, delay) in RETRY_MS.withIndex()) timers.replace("buttons-$step", delay) { sweep() }
                },
            ))
        }
    }

    private fun sweep() {
        val root = screen.snapshot() ?: return
        val views = planRightButtons(root, hideComment, hideShare)
        if (views.isNotEmpty()) screen.hide(CleanPlan(emptyList(), views), keepClickable = false)
    }

    companion object : TikTokFeature {
        const val ID = "HideFeedComponents"
        const val COMPONENTS = "components"
        const val COMMENT = "comment"
        const val SHARE = "share"
        const val RIGHT_BAR = "com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView"
        private const val MAX_LOGGED = 128
        private val RETRY_MS = listOf(300L, 800L, 1500L, 3000L)
        // The comment and share entries are never removed from the set: they are left to the View step.
        private val KEPT = setOf("comment", "share")
        val ACTIONS = listOf(
            "com.ss.android.ugc.aweme.flux.core.data.FluxComponentDataAction\$CreateComponentsData",
            "com.ss.android.ugc.aweme.flux.core.data.FluxComponentDataAction",
            "com.ss.android.ugc.aweme.flux.core.data.FluxComponentDataAction\$BindComponentsData",
            "com.ss.android.ugc.aweme.flux.core.data.FluxComponentDataAction\$DisplayComponentsData",
        )
        val OPTIONS = listOf(LinesOption(COMPONENTS), ToggleOption(COMMENT), ToggleOption(SHARE))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(HideFeedComponents(resolver, hostClassLoader, options(ID, OPTIONS), cleanScreen(), scheduler, logger))
        }
    }
}
