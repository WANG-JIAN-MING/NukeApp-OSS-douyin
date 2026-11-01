package io.github.nuke.oss.app.shared.script

import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.script.ScriptPermissions
import java.security.MessageDigest

// A script package's manifest as the Rust package reader normalized it: every rule was checked there, so reading it
// back only fails on a corrupted install.
internal data class ScriptManifest(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val apiVersion: Int,
    val entry: String,
    val description: String?,
    val author: String?,
    val homepage: String?,
    val hosts: List<String>,
    val configSchema: String?,
    val readme: String?,
    val icon: String?,
    val permissions: ManifestPermissions,
) {
    val featureId: String get() = featureIdOf(id)

    companion object {
        fun featureIdOf(scriptId: String) = "$FEATURE_PREFIX$scriptId"

        fun parse(value: Any?): ScriptManifest {
            val root = MiniJson.obj(value) ?: throw IllegalArgumentException("The manifest is not an object")
            val permissions = MiniJson.obj(root["permissions"]) ?: throw IllegalArgumentException("The manifest has no permissions")
            val network = MiniJson.obj(permissions["network"])
            val files = MiniJson.obj(permissions["filesystem"]) ?: emptyMap<String, Any?>()
            return ScriptManifest(
                id = root.string("id"),
                name = root.string("name"),
                version = root.string("version"),
                versionCode = root.number("versionCode").toLong(),
                apiVersion = root.number("apiVersion").toInt(),
                entry = root.string("entry"),
                description = root["description"] as? String,
                author = root["author"] as? String,
                homepage = root["homepage"] as? String,
                hosts = root.strings("hosts"),
                configSchema = root["configSchema"] as? String,
                readme = root["readme"] as? String,
                icon = root["icon"] as? String,
                permissions = ManifestPermissions(
                    anyNetwork = network?.get("any") == true,
                    networkOrigins = network?.strings("origins").orEmpty(),
                    privateNetwork = network?.get("privateNetwork") == true,
                    java = permissions["java"] == true,
                    hook = permissions["hook"] == true,
                    externalFiles = files["external"] == true,
                    hostFiles = files["host"] == true,
                    filesReason = files["reason"] as? String,
                    host = permissions.strings("host"),
                ),
            )
        }

        private fun Map<String, Any?>.string(key: String) =
            this[key] as? String ?: throw IllegalArgumentException("The manifest has no $key")

        private fun Map<String, Any?>.number(key: String) =
            this[key] as? Number ?: throw IllegalArgumentException("The manifest has no $key")

        private fun Map<String, Any?>.strings(key: String): List<String> =
            (this[key] as? List<*>)?.map { it as? String ?: throw IllegalArgumentException("$key holds a non-string") }.orEmpty()
    }
}

// Scripts' features share this prefix so the runtime can tell them from the host's own features.
internal const val FEATURE_PREFIX = "script."

internal data class ManifestPermissions(
    val anyNetwork: Boolean,
    val networkOrigins: List<String>,
    val privateNetwork: Boolean,
    val java: Boolean,
    val hook: Boolean,
    val externalFiles: Boolean,
    val hostFiles: Boolean,
    val filesReason: String?,
    val host: List<String>,
) {
    fun toScript() = ScriptPermissions(
        networkOrigins = networkOrigins,
        anyNetwork = anyNetwork,
        privateNetwork = privateNetwork,
        java = java,
        hook = hook,
        externalFiles = externalFiles,
        hostFiles = hostFiles,
        host = host.toSet(),
    )

    // Everything the user is shown before installing, in a fixed order.
    fun declared(): List<String> = toScript().names()

    // Capabilities that reach into the host itself need a grant; network and picked files are only shown. Every
    // host-specific capability counts as such, whatever it is called.
    fun dangerous(): List<String> = buildList {
        if (java) add(ScriptPermissions.JAVA)
        if (hook) add(ScriptPermissions.HOOK)
        if (hostFiles) add(ScriptPermissions.HOST_FILES)
        addAll(host.sorted())
    }

    // A grant is bound to this digest, so an update asking for a different set of dangerous capabilities asks again.
    fun grantDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(dangerous().joinToString("\n").toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
