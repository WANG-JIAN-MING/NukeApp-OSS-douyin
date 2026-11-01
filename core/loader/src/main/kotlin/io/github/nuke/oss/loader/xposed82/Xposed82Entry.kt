package io.github.nuke.oss.loader.xposed82

import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nuke.oss.common.ProcessEntryGate
import io.github.nuke.oss.core.*
import io.github.nuke.oss.hook.api.HookBackend
import io.github.nuke.oss.backend.xposed82.Xposed82Backend
import io.github.nuke.oss.loader.LoaderProfile
import io.github.nuke.oss.loader.LoaderProfiles
import io.github.nuke.oss.runtime.*
import java.util.UUID

// A delivered callback is the availability signal; another API's classes do not prove its entry runs.
class Xposed82Entry(
    private val profile: LoaderProfile,
    private val processIds: () -> Pair<Int, Int>,
    bootstrapFactory: () -> RuntimeBootstrap,
    backendFactory: () -> HookBackend,
    private val logger: Logger,
    private val claim: () -> Boolean,
    private val frameworkName: () -> String = ::legacyFrameworkName,
    private val reportSelf: (ClassLoader, String) -> Unit = LoaderProfiles::reportLegacyFramework,
    private val bridgeVersion: () -> Int? = ::legacyBridgeVersion,
) : IXposedHookLoadPackage {
    constructor() : this(LoaderProfiles.application())

    private constructor(profile: LoaderProfile) : this(
        profile,
        { Process.myPid() to Process.myUid() },
        profile::createBootstrap,
        { Xposed82Backend.forFramework() },
        Logger { XposedBridge.log("[NukeApp-OSS] $it") },
        ProcessEntryGate().let { gate -> { gate.claim("xposed82") } },
    )

    private val bootstrap by lazy(bootstrapFactory)
    private val backend by lazy(backendFactory)
    private var attempted = false

    @Synchronized override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName == profile.applicationId) {
            if (param.processName == param.packageName) reportActivation(param.classLoader)
            return
        }
        if (!profile.isTargetProcess(param.packageName, param.processName)) return
        if (attempted) return
        if (!claim()) {
            logger.log("xposed82_skipped entry already claimed")
            return
        }
        // Claim and mark the attempt before constructing anything that can install a Hook or reenter.
        attempted = true
        val (pid, uid) = processIds()
        val event = LoadEvent(
            process = ProcessIdentity(pid, uid, param.processName, param.packageName),
            build = BuildIdentity(profile.applicationId, profile.buildId, UUID.randomUUID().toString()),
            loaderId = "xposed82",
            stage = LoadStage.HOST_CLASSLOADER_READY,
            hostClassLoader = param.classLoader,
            hostPaths = param.appInfo?.let { HostPaths(it.dataDir, it.sourceDir) },
            framework = FrameworkInfo(frameworkName(), apiVersion = bridgeVersion()),
        )
        bootstrap.accept(event, BootstrapServices(backend, logger))
    }

    // The module's own process is never a host: no entry is claimed and no runtime starts. The framework loading the
    // module there is reported to the module screen, the way it learns that XP82 is active.
    private fun reportActivation(classLoader: ClassLoader) {
        try {
            reportSelf(classLoader, frameworkName())
        } catch (error: ReflectiveOperationException) {
            logger.log("xposed82_status_failed ${error.javaClass.simpleName}")
        } catch (error: LinkageError) {
            logger.log("xposed82_status_failed ${error.javaClass.simpleName}")
        }
    }
}

// The API level of the bridge, which is all the legacy API says about the framework's version.
private fun legacyBridgeVersion(): Int? = try {
    XposedBridge.getXposedVersion()
} catch (_: LinkageError) {
    null
}

// LSPosed renames the bridge class when it obfuscates the API; otherwise the bridge's TAG names the implementation.
private fun legacyFrameworkName(): String {
    val bridge = XposedBridge::class.java
    if (bridge.name != "de.robv.android.xposed.XposedBridge") return "LSPosed"
    val tag = try {
        bridge.getDeclaredField("TAG").apply { isAccessible = true }.get(null) as? String
    } catch (_: ReflectiveOperationException) {
        null
    }
    return when {
        tag == null -> "Xposed"
        tag.startsWith("LSPosed") -> "LSPosed"
        tag.startsWith("EdXposed") -> "EdXposed"
        else -> tag
    }
}
