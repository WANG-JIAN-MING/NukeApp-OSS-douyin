package io.github.nuke.oss.runtime

import io.github.nuke.oss.core.*
import io.github.nuke.oss.hook.api.HookBackend

enum class LoadStage { HOST_CLASSLOADER_READY }

// Both paths come from the framework or Application info, so no Application Context is needed at this stage.
data class HostPaths(val dataDir: String, val apkPath: String)

// The framework that delivered the entry, as far as its API says. Every field but the name is optional: an entry reports
// what it can read and leaves the rest null rather than guessing.
data class FrameworkInfo(
    val name: String,
    val version: String? = null,
    val versionCode: Long? = null,
    val apiVersion: Int? = null,
)

data class LoadEvent(
    val process: ProcessIdentity,
    val build: BuildIdentity,
    val loaderId: String,
    val stage: LoadStage,
    val hostClassLoader: ClassLoader,
    val hostPaths: HostPaths? = null,
    // The module APK the running code came from, when the entry knows it and the module loader cannot tell (Zygisk).
    val moduleApkPath: String? = null,
    val framework: FrameworkInfo? = null,
)

data class BootstrapServices(val backend: HookBackend, val logger: Logger)

interface RuntimeBootstrap {
    fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult
}

sealed interface BootstrapResult {
    data class Started(val session: RuntimeSessionView) : BootstrapResult
    data class Ignored(val reason: String) : BootstrapResult
}

interface RuntimeSessionView : AutoCloseable {
    fun snapshot(): RuntimeSnapshot
}

data class HostEnvironment(val event: LoadEvent, val backendId: String, val logger: Logger)

interface HostAdapter {
    val descriptor: HostDescriptor
    fun register(environment: HostEnvironment, manager: FeatureManager)
}
