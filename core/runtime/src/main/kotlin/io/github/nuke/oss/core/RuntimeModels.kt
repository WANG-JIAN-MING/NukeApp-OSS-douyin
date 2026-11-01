package io.github.nuke.oss.core

data class ProcessIdentity(val pid: Int, val uid: Int, val processName: String, val packageName: String)

data class BuildIdentity(val moduleId: String, val buildId: String, val generationId: String)

data class HostDescriptor(val id: String, val packageName: String, val implemented: Boolean)

enum class FeatureState { DISABLED, INSTALLING, ACTIVE, FAILED, CLOSED }

data class FeatureStatus(val id: String, val state: FeatureState, val reason: String? = null)

data class RuntimeSnapshot(
    val build: BuildIdentity,
    val process: ProcessIdentity,
    val hostId: String,
    val backendId: String,
    val features: List<FeatureStatus>,
)

fun interface Logger {
    fun log(message: String)
}
