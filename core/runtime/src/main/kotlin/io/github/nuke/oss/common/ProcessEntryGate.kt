package io.github.nuke.oss.common

import java.util.Properties
import java.util.UUID

// Properties belongs to the boot runtime, so separate module ClassLoaders share the same monitor.
class ProcessEntryGate(
    private val properties: Properties = System.getProperties(),
    private val token: String = UUID.randomUUID().toString(),
) {
    fun claim(mode: String): Boolean = synchronized(properties) {
        require(mode in setOf("xposed82", "libxposed102", "zygisk"))
        // Treat any existing value as an owner; never overwrite malformed or foreign claims.
        val current = properties[KEY]
        val owner = "$mode:$token"
        if (current != null) return current == owner
        properties.setProperty(KEY, owner)
        true
    }

    companion object {
        const val KEY = "io.github.nuke.oss.entry.owner.v1"
    }
}
