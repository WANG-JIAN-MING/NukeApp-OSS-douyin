package io.github.nuke.oss.app.fixture

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.core.HostDescriptor
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.*

// Hooks only the explicitly scoped NukeApp-OSS test host. No third-party app methods are touched.
class FixtureHostAdapter : HostAdapter {
    override val descriptor = HostDescriptor("fixture", "io.github.nuke.oss.fixture", implemented = true)

    override fun register(environment: HostEnvironment, manager: FeatureManager) {
        val target = "io.github.nuke.oss.fixture.ProbeTarget".toClass(environment.event.hostClassLoader)
        val members = target.resolve()
        val greet = members.firstMethod { name = "greet"; parameters(String::class) }.self
        val describe = members.firstMethod { name = "describe"; emptyParameters() }.self
        val setEnabled = members.firstMethod { name = "setEnabled"; parameters(Boolean::class) }.self

        manager.register(object : RuntimeFeature {
            override val id = "fixture.greeting"
            override fun install(scope: FeatureScope) {
                scope.hooks.install(greet, HookSpec(
                    id = "RewriteGreeting",
                    before = { it.setArgument(0, "NukeApp") },
                    after = { call ->
                        val returned = call.outcome as? CallOutcome.Returned
                        if (returned != null) call.returnValue("hooked:${returned.value}")
                    },
                ))
            }
        })
        manager.register(object : RuntimeFeature {
            override val id = "fixture.controls"
            override fun install(scope: FeatureScope) {
                scope.hooks.install(describe, HookSpec(id = "Describe", before = { call ->
                    call.returnEarly(buildString {
                        appendLine("backend=${environment.backendId}")
                        appendLine("process=${environment.event.process.processName}")
                        manager.snapshot().forEach { appendLine("${it.id}: ${it.state} ${it.reason.orEmpty()}") }
                    })
                }))
                scope.hooks.install(setEnabled, HookSpec(id = "Toggle", before = { call ->
                    val state = manager.setEnabled("fixture.greeting", call.argument(0) as Boolean)
                    call.returnEarly("${state.id}: ${state.state}")
                }))
            }
        })
        manager.setEnabled("fixture.controls", true)
        manager.setEnabled("fixture.greeting", true)
    }
}
