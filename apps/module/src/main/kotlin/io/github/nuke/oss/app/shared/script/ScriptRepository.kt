package io.github.nuke.oss.app.shared.script

import io.github.nuke.oss.app.shared.data.MiniJson
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

// JNI binding of the package reader in the module's Rust core (script/package.rs). It answers with JSON and never
// throws across the boundary; see NativeProtocol for how the library is found in each loading mode.
internal object NativeScriptPackage {
    private const val LIBRARY = "nuke_protocol_core"
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        try {
            System.loadLibrary(LIBRARY)
        } catch (error: UnsatisfiedLinkError) {
            throw ScriptInstallException("NATIVE_UNAVAILABLE", "The package reader is not loadable: ${error.message}")
        }
        loaded = true
    }

    external fun install(packagePath: String, destination: String, maxApi: Int): String?
}

internal class ScriptInstallException(val code: String, message: String) : IOException(message)

internal data class InstalledScript(
    val manifest: ScriptManifest,
    val sha256: String,
    val installedAt: Long,
    val directory: File,
) {
    fun file(path: String): File = File(directory, path)
}

// A package that passed validation and waits for the user to confirm its permissions.
internal data class StagedScript(val token: String, val manifest: ScriptManifest, val sha256: String, val previous: InstalledScript?)

// Installed scripts in the host's private storage, one directory per script id holding the package's files and the
// install record. Installing goes through a staging directory that replaces the old one only once confirmed, so a
// failed or abandoned install never leaves a half-written script behind.
internal class ScriptRepository(
    private val root: File,
    private val host: String,
    private val maxApi: Int,
    private val reader: (packagePath: String, destination: String, maxApi: Int) -> String? = { packagePath, destination, api ->
        NativeScriptPackage.ensureLoaded()
        NativeScriptPackage.install(packagePath, destination, api)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val installed = File(root, "installed")
    private val staging = File(root, "staging")
    private val lock = Any()

    init {
        // Leftovers of an install the process did not live to confirm.
        File(root, "staging").deleteRecursively()
        File(root, "incoming").deleteRecursively()
        File(root, "trash").deleteRecursively()
    }

    fun list(): List<InstalledScript> = synchronized(lock) {
        installed.listFiles().orEmpty().filter(File::isDirectory).mapNotNull(::readRecord).sortedBy { it.manifest.name.lowercase() }
    }

    fun get(id: String): InstalledScript? = synchronized(lock) { readRecord(File(installed, id)) }

    // Validates the package read from source; nothing is installed until commit.
    fun stage(source: InputStream): StagedScript = synchronized(lock) {
        val token = UUID.randomUUID().toString()
        val incoming = File(root, "incoming/$token.nks")
        val destination = File(staging, token)
        try {
            incoming.parentFile!!.mkdirs()
            copyBounded(source, incoming)
            if (!destination.mkdirs()) throw ScriptInstallException("IO", "Cannot create the staging directory")
            val answer = MiniJson.obj(MiniJson.parse(reader(incoming.path, destination.path, maxApi) ?: ""))
                ?: throw ScriptInstallException("INTERNAL", "The package reader gave no answer")
            if (answer["ok"] != true) {
                throw ScriptInstallException(answer["code"] as? String ?: "INVALID_PACKAGE", answer["message"] as? String ?: "Invalid package")
            }
            val manifest = ScriptManifest.parse(answer["manifest"])
            if (manifest.hosts.isNotEmpty() && host !in manifest.hosts) {
                throw ScriptInstallException("WRONG_HOST", "The script is for ${manifest.hosts.joinToString()}, not $host")
            }
            val sha256 = answer["sha256"] as String
            File(destination, RECORD).writeText(
                MiniJson.write(mapOf("manifest" to answer["manifest"], "sha256" to sha256, "installedAt" to clock())),
            )
            StagedScript(token, manifest, sha256, readRecord(File(installed, manifest.id)))
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        } finally {
            incoming.delete()
        }
    }

    fun commit(token: String): InstalledScript = synchronized(lock) {
        val staged = File(staging, token)
        val record = readRecord(staged) ?: throw ScriptInstallException("EXPIRED", "The staged package is gone")
        val target = File(installed, record.manifest.id)
        val trash = File(root, "trash/${UUID.randomUUID()}")
        installed.mkdirs()
        trash.parentFile!!.mkdirs()
        if (target.exists() && !target.renameTo(trash)) throw ScriptInstallException("IO", "Cannot move the old version aside")
        if (!staged.renameTo(target)) {
            trash.renameTo(target)
            throw ScriptInstallException("IO", "Cannot move the new version in place")
        }
        trash.deleteRecursively()
        readRecord(target) ?: throw ScriptInstallException("IO", "The installed script cannot be read back")
    }

    fun discard(token: String) {
        synchronized(lock) { File(staging, token).deleteRecursively() }
    }

    // The script's own files for nuke:fs; they survive updates and go with the script when it is uninstalled.
    fun dataDir(id: String): File = File(root, "data/$id")

    fun uninstall(id: String): Boolean = synchronized(lock) {
        val target = File(installed, id)
        if (!target.exists()) return false
        dataDir(id).deleteRecursively()
        val trash = File(root, "trash/${UUID.randomUUID()}")
        trash.parentFile!!.mkdirs()
        (if (target.renameTo(trash)) trash else target).deleteRecursively()
    }

    private fun readRecord(directory: File): InstalledScript? {
        val record = File(directory, RECORD)
        if (!record.isFile) return null
        return try {
            val root = MiniJson.obj(MiniJson.parse(record.readText())) ?: return null
            InstalledScript(
                ScriptManifest.parse(root["manifest"]),
                root["sha256"] as String,
                (root["installedAt"] as Number).toLong(),
                directory,
            )
        } catch (_: RuntimeException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun copyBounded(source: InputStream, target: File) {
        var total = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_PACKAGE_BYTES) throw ScriptInstallException("TOO_LARGE", "The package is larger than 16 MiB")
                output.write(buffer, 0, read)
            }
        }
    }

    companion object {
        // The package reader rejects package files starting with a dot, so the record can never collide with one.
        const val RECORD = ".nuke-install.json"
        const val MAX_PACKAGE_BYTES = 16L * 1024 * 1024
    }
}
