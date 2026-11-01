package io.github.nuke.oss.loader

import io.github.nuke.oss.runtime.RuntimeBootstrap

// What the application supplies to the entries. The loader module never links an application class.
interface LoaderProfile {
    val applicationId: String
    val buildId: String
    val hostPackages: Set<String>
    fun createBootstrap(): RuntimeBootstrap

    // Whether the entries start the runtime in this process of the package. The default is the main process only.
    fun isTargetProcess(packageName: String, processName: String): Boolean =
        packageName in hostPackages && processName == packageName
}

// The framework instantiates entries with no arguments and the Zygisk payload has no Application, so the
// profile is found by name in the entry's own ClassLoader instead of being registered by the application.
internal object LoaderProfiles {
    private const val APPLICATION_PROFILE = "io.github.nuke.oss.assembly.ApplicationLoaderProfile"
    // Keep in step with io.github.nuke.oss.status.ModuleStatus and proguard-rules.pro.
    private const val MODULE_STATUS = "io.github.nuke.oss.status.ModuleStatus"

    fun application(): LoaderProfile =
        Class.forName(APPLICATION_PROFILE, true, LoaderProfile::class.java.classLoader)
            .getField("INSTANCE").get(null) as LoaderProfile

    // Inside the module's own process the framework loads the entry in its own ClassLoader, so the application's
    // copy of the status holder is reached through the application ClassLoader the callback supplies.
    fun reportLegacyFramework(applicationClassLoader: ClassLoader, framework: String) {
        Class.forName(MODULE_STATUS, true, applicationClassLoader)
            .getMethod("reportLegacyFramework", String::class.java).invoke(null, framework)
    }
}
