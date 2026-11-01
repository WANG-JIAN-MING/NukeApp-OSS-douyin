package io.github.nuke.oss.app.shared.activity

import android.content.Context
import android.content.pm.PackageManager
import io.github.nuke.oss.core.BuildIdentity

// Opens the installed module package from a host Context, for module strings and drawables shown inside the host.
// Only the build this process loaded qualifies: another build's resource IDs would point at different resources.
internal object ModulePackage {
    sealed interface Opened {
        class Ready(val context: Context) : Opened
        class Unavailable(val reason: String) : Opened
    }

    fun open(host: Context, build: BuildIdentity): Opened {
        val installed = try {
            host.packageManager.getPackageInfo(build.moduleId, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return Opened.Unavailable("module package is not installed or not visible to the host")
        }
        if (installed.versionName != build.buildId) {
            return Opened.Unavailable("installed=${installed.versionName} loaded=${build.buildId}")
        }
        return try {
            Opened.Ready(host.createPackageContext(build.moduleId, 0))
        } catch (_: PackageManager.NameNotFoundException) {
            Opened.Unavailable("module resources are not accessible from the host")
        }
    }
}
