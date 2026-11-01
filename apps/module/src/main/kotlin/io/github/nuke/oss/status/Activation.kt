package io.github.nuke.oss.status

// One read of the API 102 framework service. Running targets are reported from service API 102 on; an older service
// leaves them null rather than empty.
data class FrameworkService(
    val name: String,
    val version: String,
    val versionCode: Long,
    val api: Int,
    val scope: Set<String>,
    val targets: List<RunningTarget>?,
)

// A host process the framework has loaded the module into, and whether that copy is the installed build.
data class RunningTarget(val processName: String, val state: TargetState)

enum class TargetState { CURRENT, STALE, RELOADING, FAILED }

// What the package manager says about a scoped host. A null ABI means it could not be told.
data class InstalledHost(val packageName: String, val installed: Boolean, val abi: String?)

enum class ActivationLevel { ACTIVE, PARTIAL, INACTIVE }

// The scope is the configured intent; running targets are what the framework actually loaded. Both stay null when
// no service reports them, which is not the same as empty.
data class HostActivation(
    val packageName: String,
    val installed: Boolean,
    val abi: String?,
    val abiSupported: Boolean,
    val inScope: Boolean?,
    val running: Int?,
    val restartNeeded: Int?,
)

data class Activation(
    val level: ActivationLevel,
    val legacyFramework: String?,
    val service: FrameworkService?,
    val hosts: List<HostActivation>,
)

// A framework that loaded the module into its own process, or a service whose scope names a host, activates it. An installed host whose ABI the module ships no native code for leaves it partly active.
fun activationOf(legacyFramework: String?, service: FrameworkService?, hosts: List<InstalledHost>, nativeAbis: Set<String>): Activation {
    val activations = hosts.map { host ->
        val targets = service?.targets?.filter { it.processName == host.packageName || it.processName.startsWith("${host.packageName}:") }
        HostActivation(
            packageName = host.packageName,
            installed = host.installed,
            abi = host.abi,
            abiSupported = host.abi == null || host.abi in nativeAbis,
            inScope = service?.let { host.packageName in it.scope },
            running = targets?.size,
            restartNeeded = targets?.count { it.state != TargetState.CURRENT },
        )
    }
    val loaded = legacyFramework != null || activations.any { it.inScope == true }
    val level = when {
        !loaded -> ActivationLevel.INACTIVE
        activations.any { it.installed && !it.abiSupported } -> ActivationLevel.PARTIAL
        else -> ActivationLevel.ACTIVE
    }
    return Activation(level, legacyFramework, service, activations)
}
