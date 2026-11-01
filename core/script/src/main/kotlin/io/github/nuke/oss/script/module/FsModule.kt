package io.github.nuke.oss.script.module

import io.github.nuke.oss.script.ScriptException
import io.github.nuke.oss.script.ScriptModule
import io.github.nuke.oss.script.ScriptModuleContext
import io.github.nuke.oss.script.ScriptPermissions
import io.github.nuke.oss.script.bytesAt
import io.github.nuke.oss.script.stringAt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// nuke:fs: files under two roots. data is the script's own directory, needs no permission and has a quota; host is the
// host app's private directory and needs filesystem.host. Paths are relative with forward slashes and never leave
// their root, checked on the canonical path so symbolic links cannot lead out either. Files picked by the user
// (filesystem.external) are not offered yet.
class FsModule(
    private val dataDir: File,
    private val hostDir: File?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val quotaBytes: Long = 64L * 1024 * 1024,
) : ScriptModule {
    override val name = "nuke:fs"
    override val source = """
const n = globalThis;
const c = __nuke.check;
function root(name) {
  const call = async (operation, ...args) => c(await n.__nk_fs(name, operation, ...args));
  return Object.freeze({
    exists: (path) => call("exists", String(path)),
    stat: (path) => call("stat", String(path)),
    list: (path) => call("list", path === undefined ? "" : String(path)),
    readText: (path) => call("readText", String(path)),
    readBytes: (path) => call("readBytes", String(path)),
    writeText: (path, text) => call("write", String(path), String(text), false),
    writeBytes: (path, bytes) => call("write", String(path), bytes, false),
    appendText: (path, text) => call("write", String(path), String(text), true),
    mkdir: (path) => call("mkdir", String(path)),
    remove: (path) => call("remove", String(path)),
  });
}
export const data = root("data");
export const host = root("host");
"""

    override fun bind(context: ScriptModuleContext) {
        context.asyncFunction("fs") { args ->
            val root = when (args.stringAt(0, "root")) {
                "data" -> dataDir
                "host" -> {
                    context.requirePermission(ScriptPermissions.HOST_FILES)
                    hostDir ?: throw ScriptException(ScriptException.UNAVAILABLE, "The host directory is unknown")
                }
                else -> throw ScriptException.argument("Unknown root")
            }
            val quota = root === dataDir
            val operation = args.stringAt(1, "operation")
            val path = args.stringAt(2, "path")
            withContext(io) {
                try {
                    if (quota) dataDir.mkdirs()
                    val file = resolve(root, path, allowRoot = operation == "list" || operation == "exists" || operation == "stat")
                    when (operation) {
                        "exists" -> file.exists()
                        "stat" -> if (file.exists()) stat(file) else null
                        "list" -> {
                            if (!file.isDirectory) throw ScriptException.argument("$path is not a directory")
                            file.listFiles().orEmpty().sortedBy { it.name }.map(::stat)
                        }
                        "readText" -> read(file).decodeToString()
                        "readBytes" -> read(file)
                        "write" -> {
                            val bytes = args.bytesAt(3, "content")
                            val append = args.getOrNull(4) == true
                            if (bytes.size > MAX_FILE) throw ScriptException.argument("Content larger than ${MAX_FILE / 1024 / 1024} MiB")
                            if (quota) {
                                val after = usage(dataDir) - (if (append) 0 else file.length()) + bytes.size
                                if (after > quotaBytes) {
                                    throw ScriptException(ScriptException.LIMIT_EXCEEDED, "The script's data would exceed ${quotaBytes / 1024 / 1024} MiB")
                                }
                            }
                            file.parentFile?.mkdirs()
                            if (append) file.appendBytes(bytes) else file.writeBytes(bytes)
                            null
                        }
                        "mkdir" -> {
                            if (!file.isDirectory && !file.mkdirs()) throw ScriptException(ScriptException.HOST_ERROR, "Cannot create $path")
                            null
                        }
                        "remove" -> {
                            if (file.exists() && !file.deleteRecursively()) throw ScriptException(ScriptException.HOST_ERROR, "Cannot remove $path")
                            null
                        }
                        else -> throw ScriptException.argument("Unknown operation $operation")
                    }
                } catch (error: IOException) {
                    throw ScriptException(ScriptException.HOST_ERROR, "$operation $path failed: ${error.message}")
                }
            }
        }
    }

    private fun resolve(root: File, path: String, allowRoot: Boolean): File {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (path.startsWith("/") || '\\' in path || '\u0000' in path || segments.any { it == "." || it == ".." }) {
            throw ScriptException.argument("Invalid path: $path")
        }
        if (segments.isEmpty() && !allowRoot) throw ScriptException.argument("A file path is required")
        val base = root.canonicalFile
        val file = File(base, segments.joinToString(File.separator))
        val canonical = file.canonicalFile
        if (canonical != base && !canonical.path.startsWith(base.path + File.separator)) {
            throw ScriptException(ScriptException.PERMISSION_DENIED, "$path leads outside its root")
        }
        return file
    }

    private fun read(file: File): ByteArray {
        if (!file.isFile) throw ScriptException.argument("${file.name} is not a file")
        if (file.length() > MAX_FILE) throw ScriptException.argument("${file.name} is larger than ${MAX_FILE / 1024 / 1024} MiB")
        return file.readBytes()
    }

    private fun stat(file: File) = mapOf(
        "name" to file.name,
        "directory" to file.isDirectory,
        "size" to if (file.isFile) file.length() else 0L,
        "modified" to file.lastModified(),
    )

    private fun usage(directory: File): Long = directory.walkTopDown().filter(File::isFile).sumOf(File::length)

    private companion object {
        const val MAX_FILE = 16 * 1024 * 1024
    }
}
