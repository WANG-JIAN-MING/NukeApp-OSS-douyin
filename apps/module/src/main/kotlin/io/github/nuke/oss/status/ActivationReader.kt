package io.github.nuke.oss.status

import android.content.pm.PackageManager
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.nuke.oss.BuildConfig
import io.github.nuke.oss.assembly.HostScope
import java.io.File

// Reads the activation state. Every service call is a binder transaction and the package manager is queried too, so
// this runs off the main thread.
internal class ActivationReader(private val packages: PackageManager) {
    fun read(): Activation = activationOf(
        ModuleStatus.legacyFramework,
        ModuleStatus.service.value?.let(::snapshot),
        HostScope.packages.map(::installed),
        BuildConfig.NATIVE_ABIS.toSet(),
    )

    // A service whose binder died mid-read reports nothing, the same as no service.
    private fun snapshot(service: XposedService): FrameworkService? = try {
        val api = service.apiVersion
        FrameworkService(
            name = service.frameworkName,
            version = service.frameworkVersion,
            versionCode = service.frameworkVersionCode,
            api = api,
            scope = service.scope.toSet(),
            targets = if (api >= XposedService.API_102) service.runningTargets.map { RunningTarget(it.processName, it.state.toTargetState()) } else null,
        )
    } catch (_: XposedService.ServiceException) {
        null
    }

    // The native library directory ends in the instruction set the package was installed for.
    private fun installed(packageName: String): InstalledHost = try {
        val info = packages.getApplicationInfo(packageName, 0)
        InstalledHost(packageName, installed = true, abi = info.nativeLibraryDir?.let { ABIS[File(it).name] })
    } catch (_: PackageManager.NameNotFoundException) {
        InstalledHost(packageName, installed = false, abi = null)
    }

    private fun HookedTarget.State.toTargetState() = when (this) {
        HookedTarget.State.UP_TO_DATE -> TargetState.CURRENT
        HookedTarget.State.STALE -> TargetState.STALE
        HookedTarget.State.RELOADING -> TargetState.RELOADING
        HookedTarget.State.FAILED -> TargetState.FAILED
    }

    private companion object {
        val ABIS = mapOf("arm64" to "arm64-v8a", "arm" to "armeabi-v7a", "x86_64" to "x86_64", "x86" to "x86")
    }
}
