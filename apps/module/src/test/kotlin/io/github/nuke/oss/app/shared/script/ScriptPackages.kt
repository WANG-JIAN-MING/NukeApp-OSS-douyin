package io.github.nuke.oss.app.shared.script

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Builds script packages for tests; files maps package paths to their text.
internal fun scriptPackage(files: Map<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, content) in files) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

internal fun manifest(
    id: String = "com.example.hello",
    versionCode: Int = 1,
    permissions: String = "{}",
    extra: String = "",
) = """{"manifestVersion":1,"apiVersion":1,"id":"$id","name":"Hello","version":"1.0.$versionCode","versionCode":$versionCode,"permissions":$permissions$extra}"""

internal fun simplePackage(
    source: String = "import { info } from \"nuke:log\"; info(\"hello\");",
    id: String = "com.example.hello",
    versionCode: Int = 1,
    permissions: String = "{}",
    extra: String = "",
    files: Map<String, String> = emptyMap(),
) = scriptPackage(mapOf("manifest.json" to manifest(id, versionCode, permissions, extra), "main.js" to source) + files)
