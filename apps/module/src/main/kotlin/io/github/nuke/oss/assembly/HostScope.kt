package io.github.nuke.oss.assembly

import io.github.nuke.oss.app.shared.registry.HostProcess

// Authoritative list of hosts whose processes may start the runtime, and of which processes. Xposed scope metadata, the
// Zygisk native filter and tools/check_apks.py must list the same packages and the same child process rule.
object HostScope {
    const val FIXTURE = "io.github.nuke.oss.fixture"
    // The TikTok host family is scoped to Douyin only; the international TikTok package is not in scope.
    const val TIKTOK = "com.ss.android.ugc.aweme"

    val packages: Set<String> = linkedSetOf(FIXTURE, TIKTOK)

    // Douyin's mini-app and mini-game processes are named <package>:miniapp<N> and <package>:minigame<N>, with N a number or X.
    private val MINI_APP_SUFFIX = Regex("mini(?:app|game)(?:[0-9]+|X)")

    // Child processes with this name prefix are isolated sandboxes and are never entered.
    private val SANDBOX_PREFIX = "sandboxed_process"

    // The main process of every host package, and for Douyin also its non-isolated child processes.
    fun processOf(packageName: String, processName: String): HostProcess? {
        if (packageName !in packages) return null
        if (processName == packageName) return HostProcess.MAIN
        if (packageName != TIKTOK || !processName.startsWith("$packageName:")) return null
        val suffix = processName.removePrefix("$packageName:")
        return when {
            suffix.isEmpty() || suffix.startsWith(SANDBOX_PREFIX) -> null
            MINI_APP_SUFFIX.matches(suffix) -> HostProcess.MINI_APP
            else -> HostProcess.OTHER
        }
    }

    fun isTargetProcess(packageName: String, processName: String): Boolean = processOf(packageName, processName) != null
}
