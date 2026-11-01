package io.github.nuke.oss.patcher.patch

import pxb.android.axml.AxmlReader
import pxb.android.axml.AxmlVisitor
import pxb.android.axml.NodeVisitor
import java.io.File
import java.util.zip.ZipFile

internal data class PatchManifest(val packageName: String, val versionName: String?, val minSdk: Int)

internal object PatchManifestReader {
    fun read(apk: File): PatchManifest {
        val metadata = readIdentity(apk)
        require(metadata.packageName !in setOf("io.github.nuke.oss", "io.github.nuke.oss.patcher")) { "self_apk" }
        return metadata
    }

    internal fun readIdentity(apk: File): PatchManifest {
        val bytes = ZipFile(apk).use { zip ->
            readApkEntry(zip, "AndroidManifest.xml", 4 * 1024 * 1024)
        }
        var packageName: String? = null
        var versionName: String? = null
        var minimum = 1
        var split = false
        fun visitor(tag: String): NodeVisitor = object : NodeVisitor() {
            private var metadataName: String? = null
            private var metadataValue: Any? = null

            override fun attr(ns: String?, name: String?, resourceId: Int, type: Int, value: Any?) {
                when {
                    tag == "manifest" && name == "package" -> packageName = value as? String
                    tag == "manifest" && name == "versionName" -> versionName = value as? String
                    tag == "manifest" && name in setOf("split", "requiredSplitTypes") && !value?.toString().isNullOrBlank() -> split = true
                    name == "isSplitRequired" && (value == true || value is Number && value.toInt() != 0) -> split = true
                    tag == "uses-sdk" && name == "minSdkVersion" -> minimum = (value as? Number)?.toInt() ?: error("preview_sdk")
                    tag == "meta-data" && name == "name" -> metadataName = value as? String
                    tag == "meta-data" && name == "value" -> metadataValue = value
                }
            }
            override fun end() {
                if (metadataName == "com.android.vending.splits.required" &&
                    (metadataValue == true || metadataValue is Number && (metadataValue as Number).toInt() != 0)) split = true
            }
            override fun child(ns: String?, name: String?): NodeVisitor {
                if (name == "uses-split") split = true
                return visitor(name.orEmpty())
            }
        }
        AxmlReader(bytes).accept(object : AxmlVisitor() {
            override fun child(ns: String?, name: String?): NodeVisitor = visitor(name.orEmpty())
        })
        require(!split) { "split_apk" }
        val identity = requireNotNull(packageName) { "package_missing" }
        require(identity.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "package_invalid" }
        return PatchManifest(identity, versionName, minimum)
    }
}

internal fun readApkEntry(zip: ZipFile, name: String, limit: Int): ByteArray {
    val entry = requireNotNull(zip.getEntry(name)) { "apk_entry_missing" }
    require(!entry.isDirectory && entry.size in 1..limit.toLong()) { "apk_entry_size" }
    return zip.getInputStream(entry).use { input ->
        val output = java.io.ByteArrayOutputStream(entry.size.toInt())
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
            if (count < 0) break
            total += count
            require(total <= limit) { "apk_entry_size" }
            output.write(buffer, 0, count)
        }
        require(total.toLong() == entry.size) { "apk_entry_size" }
        output.toByteArray()
    }
}
