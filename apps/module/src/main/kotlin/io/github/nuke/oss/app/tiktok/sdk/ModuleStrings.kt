package io.github.nuke.oss.app.tiktok.sdk

// Text from the module's own resources, for an entry the host draws itself. Null when the module package cannot be
// opened from this host.
internal fun interface ModuleStrings {
    fun get(context: Any, id: Int): String?
}
