package io.github.nuke.oss.app.shared.environment

import io.github.nuke.oss.app.shared.dex.DexStatus
import io.github.nuke.oss.runtime.FrameworkInfo

// What a bug report needs to know about where the module runs: the phone, Android, the hook framework and mode, the
// host and the module itself. This file turns plain facts into labelled sections; AndroidFacts reads the facts from
// the platform, so everything here runs and is tested without a device.

internal class EnvironmentSection(val title: String, val entries: List<Pair<String, String>>)

internal class EnvironmentInfo(val sections: List<EnvironmentSection>) {
    // "[Title]" then "Label: value" lines, English labels so a report reads the same whatever language is chosen.
    fun render(): String = buildString {
        for (section in sections) {
            if (isNotEmpty()) appendLine()
            appendLine("[${section.title}]")
            for ((label, value) in section.entries) appendLine("$label: $value")
        }
    }
}

internal data class DeviceFacts(
    val brand: String?,
    val manufacturer: String?,
    val model: String?,
    val device: String?,
    val hardware: String?,
    val soc: String?,
    val abis: List<String>,
    val is64Bit: Boolean,
)

internal data class SystemFacts(
    val release: String?,
    val sdk: Int,
    // Null on a release build; the codename of a preview.
    val codename: String?,
    val securityPatch: String?,
    // The ROM's own build label, such as "UP1A.231005.007" or "OS2.0.5.0.VNCCNXM".
    val display: String?,
    val fingerprint: String?,
    val buildType: String?,
    val kernel: String?,
    val locale: String?,
)

internal data class PackageFacts(val name: String, val versionName: String?, val versionCode: Long, val targetSdk: Int?)

// The module code that is running in this process, which is not always the module that is installed: a framework keeps
// loading the old code until the host restarts.
internal data class LoadedModule(val applicationId: String, val versionName: String, val versionCode: Long, val buildId: String, val generationId: String)

internal data class ProcessFacts(val name: String, val pid: Int)

internal object EnvironmentBuilder {
    fun build(
        device: DeviceFacts,
        system: SystemFacts,
        loaderId: String,
        framework: FrameworkInfo?,
        host: PackageFacts?,
        process: ProcessFacts,
        loaded: LoadedModule,
        installed: PackageFacts?,
        dex: DexStatus?,
    ): EnvironmentInfo = EnvironmentInfo(listOf(
        EnvironmentSection("Device", listOfNotNull(
            entry("Brand", device.brand),
            entry("Manufacturer", device.manufacturer),
            entry("Model", device.model),
            entry("Device", device.device),
            entry("Hardware", device.hardware),
            entry("SoC", device.soc),
            "ABIs" to device.abis.ifEmpty { listOf("N/A") }.joinToString(", "),
            "Process bitness" to if (device.is64Bit) "64-bit" else "32-bit",
        )),
        EnvironmentSection("Android", listOfNotNull(
            "Version" to "${system.release ?: "N/A"} (API ${system.sdk}${system.codename?.takeIf { it != "REL" }?.let { ", $it" }.orEmpty()})",
            entry("Security patch", system.securityPatch),
            entry("Build", system.display),
            entry("Fingerprint", system.fingerprint),
            entry("Build type", system.buildType),
            entry("Kernel", system.kernel),
            entry("Locale", system.locale),
        )),
        EnvironmentSection("Hook", listOfNotNull(
            "Mode" to mode(loaderId),
            "Entry" to loaderId,
            "Framework" to (framework?.let(::frameworkLabel) ?: "N/A"),
            framework?.apiVersion?.let { "Framework API" to it.toString() },
        )),
        EnvironmentSection("Host", listOfNotNull(
            "Package" to (host?.name ?: "N/A"),
            "Version" to (host?.let(::versionLabel) ?: "N/A"),
            host?.targetSdk?.let { "Target SDK" to it.toString() },
            "Process" to "${process.name} (pid ${process.pid})",
        )),
        EnvironmentSection("Module", listOfNotNull(
            "Package" to loaded.applicationId,
            "Running version" to "${loaded.versionName} (${loaded.versionCode})",
            "Installed version" to (installed?.let(::versionLabel) ?: "N/A"),
            "Build ID" to loaded.buildId,
            "Generation" to loaded.generationId,
        )),
    ) + listOfNotNull(dex?.let {
        EnvironmentSection("Dex", listOf(
            "Queries" to it.total.toString(),
            "Located" to it.located.toString(),
            "Failed" to it.failed.toString(),
            "Pending" to it.pending.toString(),
        ))
    }))

    // The three ways the module reaches a host. An entry this code does not know is shown as it was named.
    private fun mode(loaderId: String) = when (loaderId) {
        "xposed82" -> "Xposed (legacy API 82)"
        "libxposed102" -> "Xposed (libxposed API 102)"
        "zygisk" -> "Zygisk"
        else -> loaderId
    }

    private fun frameworkLabel(info: FrameworkInfo): String = buildString {
        append(info.name)
        info.version?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
        info.versionCode?.let { append(" (").append(it).append(')') }
    }

    private fun versionLabel(facts: PackageFacts) = "${facts.versionName ?: "N/A"} (${facts.versionCode})"

    private fun entry(label: String, value: String?): Pair<String, String>? = value?.takeIf { it.isNotBlank() }?.let { label to it }
}
