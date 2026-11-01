package io.github.nuke.oss.app.shared.process

import io.github.nuke.oss.app.shared.data.MiniJson
import java.io.File
import java.io.IOException

// What a child process of the host may know about the main process's settings. The settings store is not safe for two
// processes, so a child never opens it: the main process leaves this one file, written whole and moved into place, and
// a child only reads it once when it starts. scope is the module and host build it belongs to; a file of another scope
// reads as absent. switches holds every feature's switch, locked the features held off after a failure, and dex the
// located Dex descriptors by cache key.
internal data class MirrorState(
    val scope: String,
    val switches: Map<String, Boolean>,
    val safeMode: Boolean,
    val locked: Set<String>,
    val dex: Map<String, String>,
)

internal class ProcessMirror(private val file: File) {
    // Written by the main process only. A failure to write is reported to the caller, which logs it: the children then
    // keep what the previous launch left, or nothing.
    @Throws(IOException::class)
    fun write(state: MirrorState) {
        val text = MiniJson.write(linkedMapOf(
            "scope" to state.scope,
            "switches" to state.switches,
            "safe" to state.safeMode,
            "locked" to state.locked.sorted(),
            "dex" to state.dex,
        ))
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Cannot replace ${file.name}")
        }
    }

    // Changes one switch in the current file. Without a file for this scope there is nothing to change yet.
    @Throws(IOException::class)
    fun updateSwitch(scope: String, featureId: String, enabled: Boolean) {
        val current = read(scope) ?: return
        write(current.copy(switches = current.switches + (featureId to enabled)))
    }

    // A child that finds no file installs nothing, which is right until the main process writes a fresh one.
    fun delete() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    fun read(scope: String): MirrorState? {
        val text = try { file.takeIf { it.isFile }?.readText() } catch (_: IOException) { null } ?: return null
        val root = MiniJson.obj(MiniJson.parse(text)) ?: return null
        if (root["scope"] != scope) return null
        val switches = MiniJson.obj(root["switches"])?.mapNotNull { (key, value) -> (value as? Boolean)?.let { key to it } }?.toMap() ?: return null
        val dex = MiniJson.obj(root["dex"])?.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }?.toMap() ?: return null
        val locked = MiniJson.array(root["locked"])?.filterIsInstance<String>()?.toSet() ?: return null
        return MirrorState(scope, switches, root["safe"] == true, locked, dex)
    }
}
