package io.github.nuke.oss.loader.libxposed102

import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.nuke.oss.common.ProcessEntryGate
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.core.ProcessIdentity
import io.github.nuke.oss.backend.libxposed102.Libxposed102Backend
import io.github.nuke.oss.loader.LoaderProfile
import io.github.nuke.oss.loader.LoaderProfiles
import io.github.nuke.oss.runtime.*
import java.util.UUID

// Only the framework attaches this entry. Initialization starts at onModuleLoaded.
class Libxposed102Entry internal constructor(
    private val profile: LoaderProfile,
    private val processIds: () -> Pair<Int, Int>,
    bootstrapFactory: () -> RuntimeBootstrap,
    private val claim: () -> Boolean = ProcessEntryGate().let { gate -> { gate.claim("libxposed102") } },
) : XposedModule() {
    constructor() : this(LoaderProfiles.application())

    private constructor(profile: LoaderProfile) : this(profile, { Process.myPid() to Process.myUid() }, profile::createBootstrap)

    private val bootstrap by lazy(bootstrapFactory)
    private var processName: String? = null
    private var identity: BuildIdentity? = null
    private var services: BootstrapServices? = null
    private var moduleAttempted = false
    private var packageAttempted = false

    @Synchronized override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (param.isSystemServer || !profile.isTargetProcess(param.processName.substringBefore(':'), param.processName)) {
            detach()
            return
        }
        if (moduleAttempted) return
        check(apiVersion >= 102) { "libxposed API 102 is required" }
        if (!claim()) {
            log(Log.INFO, "NukeApp-OSS", "libxposed102_skipped entry already claimed")
            detach()
            return
        }
        // Retain both the claim and attempt after failures; partial hooks must not start another runtime.
        moduleAttempted = true
        val createdIdentity = BuildIdentity(profile.applicationId, profile.buildId, UUID.randomUUID().toString())
        val createdServices = BootstrapServices(Libxposed102Backend(this), Logger { log(Log.INFO, "NukeApp-OSS", it) })
        identity = createdIdentity
        services = createdServices
        processName = param.processName
    }

    @Synchronized override fun onPackageReady(param: PackageReadyParam) {
        val name = processName ?: return
        if (!param.isFirstPackage || !profile.isTargetProcess(param.packageName, name)) return
        if (packageAttempted) return
        packageAttempted = true
        val (pid, uid) = processIds()
        bootstrap.accept(
            LoadEvent(
                ProcessIdentity(pid, uid, name, param.packageName), requireNotNull(identity),
                "libxposed102", LoadStage.HOST_CLASSLOADER_READY, param.classLoader,
                param.applicationInfo.let { HostPaths(it.dataDir, it.sourceDir) },
                framework = frameworkInfo(),
            ),
            requireNotNull(services),
        )
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean = false

    // What the framework says about itself. A member an older framework does not implement reads as unknown.
    private fun frameworkInfo() = FrameworkInfo(
        name = known { frameworkName } ?: "libxposed",
        version = known { frameworkVersion },
        versionCode = known { frameworkVersionCode },
        apiVersion = known { apiVersion },
    )

    private inline fun <T> known(read: () -> T): T? = try {
        read()
    } catch (_: LinkageError) {
        null
    }
}
