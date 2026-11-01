package io.github.nuke.oss.script

// The script API level this engine implements; a package declares the level it was built against and the SDK's major
// version follows it.
const val SCRIPT_API_VERSION = 1

// A script ready to run: the package was already validated and the user has granted what [permissions] lists, so the
// engine treats every permission here as effective.
data class ScriptDefinition(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val entrySource: String,
    val entryName: String = "main.js",
    val permissions: ScriptPermissions = ScriptPermissions(),
    val config: Map<String, Any?> = emptyMap(),
) {
    init {
        require(SCRIPT_ID.matches(id)) { "Invalid script id: $id" }
        require(versionCode > 0) { "Script versionCode must be positive" }
    }

    private companion object {
        val SCRIPT_ID = Regex("^[a-z][a-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*)+$")
    }
}

// Network origins are matched by the HTTP module; host capabilities name host-specific modules such as
// "douyin.messaging" and are checked by the module that offers them.
data class ScriptPermissions(
    val networkOrigins: List<String> = emptyList(),
    val anyNetwork: Boolean = false,
    val privateNetwork: Boolean = false,
    val java: Boolean = false,
    val hook: Boolean = false,
    val externalFiles: Boolean = false,
    val hostFiles: Boolean = false,
    val host: Set<String> = emptySet(),
) {
    val network: Boolean get() = anyNetwork || networkOrigins.isNotEmpty()

    // The names scripts pass to runtime.hasPermission and see in PermissionError messages.
    fun has(name: String): Boolean = when (name) {
        NETWORK -> network
        JAVA -> java
        HOOK -> hook
        EXTERNAL_FILES -> externalFiles
        HOST_FILES -> hostFiles
        else -> name in host
    }

    fun names(): List<String> = buildList {
        if (network) add(NETWORK)
        if (java) add(JAVA)
        if (hook) add(HOOK)
        if (externalFiles) add(EXTERNAL_FILES)
        if (hostFiles) add(HOST_FILES)
        addAll(host.sorted())
    }

    companion object {
        const val NETWORK = "network"
        const val JAVA = "java"
        const val HOOK = "hook"
        const val EXTERNAL_FILES = "filesystem.external"
        const val HOST_FILES = "filesystem.host"
    }
}

// Per-session bounds. A task is one uninterrupted stretch of work on the script thread: running JavaScript or a
// binding it called synchronously.
data class ScriptLimits(
    val memoryBytes: Long = 64L * 1024 * 1024,
    val stackBytes: Long = 1024L * 1024,
    val taskMillis: Long = 3_000,
    val startMillis: Long = 10_000,
    val pendingEvents: Int = 4_096,
    val timers: Int = 1_024,
)

enum class ScriptLogLevel { DEBUG, INFO, WARN, ERROR }

class ScriptException(val code: String, message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    companion object {
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
        const val UNAVAILABLE = "UNAVAILABLE"
        const val LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
        const val TIMEOUT = "TIMEOUT"
        const val SCRIPT_ERROR = "SCRIPT_ERROR"
        const val JAVA_ERROR = "JAVA_ERROR"
        const val HOST_ERROR = "HOST_ERROR"
        const val STOPPED = "STOPPED"

        fun permission(name: String) = ScriptException(PERMISSION_DENIED, "The script did not declare the '$name' permission")
        fun argument(message: String) = ScriptException(INVALID_ARGUMENT, message)
    }
}
