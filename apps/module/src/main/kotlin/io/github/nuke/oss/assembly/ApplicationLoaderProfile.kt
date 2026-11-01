package io.github.nuke.oss.assembly

import io.github.nuke.oss.BuildConfig
import io.github.nuke.oss.loader.LoaderProfile
import io.github.nuke.oss.runtime.RuntimeBootstrap

// The loader module finds this object by name; keep the name in step with LoaderProfiles and proguard-rules.pro.
object ApplicationLoaderProfile : LoaderProfile {
    override val applicationId = BuildConfig.APPLICATION_ID
    override val buildId = BuildConfig.BUILD_ID
    override val hostPackages = HostScope.packages
    override fun isTargetProcess(packageName: String, processName: String) = HostScope.isTargetProcess(packageName, processName)
    override fun createBootstrap(): RuntimeBootstrap = RuntimeAssembly()
}
