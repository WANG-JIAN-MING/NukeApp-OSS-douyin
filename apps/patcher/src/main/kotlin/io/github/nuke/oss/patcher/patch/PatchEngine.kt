package io.github.nuke.oss.patcher.patch

import com.android.apksig.ApkVerifier
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.lsposed.patch.ApkPatcher
import org.lsposed.patch.KeystoreSpec
import org.lsposed.patch.PatchSpec
import org.lsposed.patch.util.Logger

class PatchEngine {
    fun patch(
        input: File,
        module: File,
        outputDirectory: File,
        key: KeyMaterial,
        onProgress: (String) -> Unit = {},
    ): File {
        require(input.isFile && input.length() > 0) { "Input APK is missing or empty" }
        require(module.isFile && module.length() > 0) { "Module APK is missing or empty" }
        require(key.keyFile.isFile) { "Local signing key is missing" }
        require(outputDirectory.isDirectory || outputDirectory.mkdirs()) { "Cannot create patch output directory" }
        require(outputDirectory.listFiles()?.isEmpty() == true) { "Patch output directory must be empty" }
        PatchApkValidator.target(input)
        PatchApkValidator.module(module)
        val logger = object : Logger() {
            override fun d(msg: String) = Unit
            override fun i(msg: String) = onProgress(msg.take(1024))
            override fun e(msg: String) = onProgress(msg.take(1024))
            override fun stage(stage: Stage, index: Int, total: Int) = onProgress("stage:${stage.name}")
        }
        val spec = PatchSpec.builder()
            .apk(input)
            .module(module)
            .outputDir(outputDirectory)
            .useManager(false)
            .sigBypassLevel(0)
            .injectDex(false)
            .keystore(KeystoreSpec.of(key.keyFile, key.password, key.alias, key.password))
            .build()
        val outputs = ApkPatcher(logger, spec).patch()
        check(outputs.size == 1) { "Patcher did not produce exactly one APK" }
        val output = checkNotNull(outputs.single()) { "Patcher returned a null output" }
        check(output.isFile && output.length() > 0 && output.parentFile.canonicalFile == outputDirectory.canonicalFile) {
            "Patcher returned an invalid output APK"
        }
        check(outputDirectory.listFiles()?.map { it.canonicalFile } == listOf(output.canonicalFile)) {
            "Patcher created unexpected output files"
        }
        onProgress("stage:VERIFYING")
        verifySignature(output, key)
        verifyPayloads(output)
        return output
    }

    private fun verifySignature(output: File, key: KeyMaterial) {
        val result = ApkVerifier.Builder(output).setMinCheckedPlatformVersion(28).build().verify()
        check(result.isVerified && result.isVerifiedUsingV2Scheme) { "Patched APK does not have a valid v2 signature" }
        check(result.signerCertificates.size == 1) { "Patched APK has an unexpected signer count" }
        val signerFingerprint = digest(result.signerCertificates.single().encoded.inputStream())
        check(signerFingerprint == key.certificateSha256) { "Patched APK signer does not match the local signing key" }
    }

    internal fun verifyPayloads(output: File) {
        val loader = checkNotNull(ApkPatcher::class.java.classLoader) { "Patch engine class loader is missing" }
        val payloads = linkedMapOf(
            "classes.dex" to "assets/lspatch/metaloader.dex",
            "assets/lspatch/loader.dex" to "assets/lspatch/loader.dex",
        ).apply {
            listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64").forEach { abi ->
                val path = "assets/lspatch/so/$abi/liblspatch.so"
                put(path, path)
            }
        }
        ZipFile(output).use { zip ->
            payloads.forEach { (entryName, resourceName) ->
                val entry = checkNotNull(zip.getEntry(entryName)) { "Missing loader payload: $entryName" }
                check(!entry.isDirectory) { "Invalid loader payload: $entryName" }
                val actual = zip.getInputStream(entry).use(::digest)
                val expected = checkNotNull(loader.getResourceAsStream(resourceName)) {
                    "Bundled patch engine payload is missing: $resourceName"
                }.use(::digest)
                check(actual == expected) { "Loader payload mismatch: $entryName" }
            }
        }
    }

    private fun digest(input: InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            hash.update(buffer, 0, read)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
