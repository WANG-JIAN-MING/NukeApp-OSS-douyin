package io.github.nuke.oss.app.shared.environment

import android.content.Context
import io.github.nuke.oss.app.shared.dex.DexStatus
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.runtime.FrameworkInfo

// The version of the module code that is running; only the app knows it, so the screen that exports passes it in.
internal data class ModuleVersion(val name: String, val code: Long)

// Gathers the environment of one host process. What the entry knew at load time is fixed here; the platform and the
// Dex analysis are read when the report is made, so it shows the state at that moment.
internal class EnvironmentCollector(
    private val loaderId: String,
    private val framework: FrameworkInfo?,
    private val process: ProcessFacts,
    private val build: BuildIdentity,
    private val dex: () -> DexStatus?,
) {
    fun collect(context: Context, version: ModuleVersion): EnvironmentInfo = EnvironmentBuilder.build(
        device = AndroidFacts.device(),
        system = AndroidFacts.system(),
        loaderId = loaderId,
        framework = framework,
        host = AndroidFacts.packageOf(context, context.packageName),
        process = process,
        loaded = LoadedModule(build.moduleId, version.name, version.code, build.buildId, build.generationId),
        installed = AndroidFacts.packageOf(context, build.moduleId),
        dex = dex(),
    )
}
