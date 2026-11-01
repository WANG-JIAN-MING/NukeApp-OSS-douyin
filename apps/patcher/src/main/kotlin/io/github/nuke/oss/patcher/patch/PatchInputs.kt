package io.github.nuke.oss.patcher.patch

import java.io.File
import java.util.zip.ZipFile

internal enum class ApkRole { TARGET, MODULE }

internal data class SelectedApk(
    val file: File,
    val displayName: String,
    val metadata: PatchManifest,
    val sha256: String,
)

internal class PatchInputs {
    var target: SelectedApk? = null
        private set
    var module: SelectedApk? = null
        private set
    var output: File? = null
        private set

    fun invalidate(role: ApkRole) {
        output = null
        when (role) {
            ApkRole.TARGET -> target = null
            ApkRole.MODULE -> module = null
        }
    }

    fun replace(role: ApkRole, file: File, displayName: String, sha256: String): SelectedApk {
        // Clear the old selection before validation so a failed replacement cannot reuse its output.
        invalidate(role)
        val metadata = when (role) {
            ApkRole.TARGET -> PatchApkValidator.target(file)
            ApkRole.MODULE -> PatchApkValidator.module(file)
        }
        return SelectedApk(file, displayName, metadata, sha256).also {
            when (role) {
                ApkRole.TARGET -> target = it
                ApkRole.MODULE -> module = it
            }
        }
    }

    fun requireReady(): Pair<SelectedApk, SelectedApk> =
        checkNotNull(target) { "target_missing" } to checkNotNull(module) { "module_missing" }

    fun beginPatch() {
        output = null
        requireReady()
    }

    fun recordOutput(file: File) {
        requireReady()
        output = file
    }
}

internal object PatchApkValidator {
    private const val MODULE_ID = "io.github.nuke.oss"
    private const val MODERN_ENTRY = "io.github.nuke.oss.loader.libxposed102.Libxposed102Entry"
    private val EXPECTED_PROPERTIES = setOf(
        "minApiVersion=102", "targetApiVersion=102", "staticScope=true", "autoHotReload=false",
    )

    fun target(file: File): PatchManifest {
        rejectPatchedOrDuplicate(file)
        return PatchManifestReader.read(file)
    }

    fun module(file: File): PatchManifest {
        rejectPatchedOrDuplicate(file)
        val metadata = PatchManifestReader.readIdentity(file)
        require(metadata.packageName == MODULE_ID) { "module_identity" }
        ZipFile(file).use { zip ->
            val entries = lines(zip, "META-INF/xposed/java_init.list")
            require(entries == listOf(MODERN_ENTRY)) { "module_entry" }
            val properties = lines(zip, "META-INF/xposed/module.prop")
            require(properties.size == EXPECTED_PROPERTIES.size && properties.toSet() == EXPECTED_PROPERTIES) { "module_api" }
        }
        return metadata
    }

    private fun rejectPatchedOrDuplicate(file: File) {
        ZipFile(file).use { zip ->
            val names = HashSet<String>()
            zip.entries().asSequence().forEach {
                require(names.add(it.name)) { "duplicate_entry" }
                require(it.name !in setOf("assets/lspatch/origin.apk", "assets/lspatch/config.json")) { "already_patched" }
            }
        }
    }

    private fun lines(zip: ZipFile, name: String): List<String> {
        val bytes = readApkEntry(zip, name, 4096)
        return bytes.toString(Charsets.UTF_8).lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
    }
}
