package io.github.nuke.oss.app.shared.integrity

import java.io.File
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the module signer check: how verdicts become refusals, where the module APK is found, and the JNI
// binding against the Rust core built for the host. That library is built without a release digest, so it reports
// every APK as unconfigured; the digest itself is still read from the APK.
class ModuleSignatureTest {
    private class FakeNatives(private val code: Int, private val digest: String? = "ab12") : ModuleSignature.Natives {
        var loadFailure: UnsatisfiedLinkError? = null
        override fun load() { loadFailure?.let { throw it } }
        override fun verify(apkPath: String) = code
        override fun signerDigest(apkPath: String) = digest
    }

    private val logged = ArrayList<String>()

    private fun refusal(natives: FakeNatives) = ModuleSignature.refusal("/data/app/module/base.apk", { logged += it }, natives)

    @Test fun `only another or a missing signer refuses`() {
        assertNull(refusal(FakeNatives(NativeSignature.MATCHES)))
        assertNull(refusal(FakeNatives(NativeSignature.UNCONFIGURED)))
        assertTrue(refusal(FakeNatives(NativeSignature.MISMATCH))!!.contains("signer ab12"))
        assertTrue(refusal(FakeNatives(NativeSignature.MISMATCH, digest = null))!!.contains("signer unknown"))
        assertTrue(refusal(FakeNatives(NativeSignature.UNREADABLE))!!.contains("no readable"))
        assertEquals(listOf("module_signature matches", "module_signature unconfigured"), logged)
    }

    @Test fun `a missing native library is logged and lets the module run`() {
        val natives = FakeNatives(NativeSignature.MISMATCH).apply { loadFailure = UnsatisfiedLinkError("no nuke_protocol_core") }
        assertEquals(SignatureCheck.Unavailable("no nuke_protocol_core"), ModuleSignature.check("/x.apk", natives))
        assertNull(refusal(natives))
        assertTrue(logged.single().startsWith("module_signature unavailable"))
    }

    @Test fun `the module APK is the archive a loader reads its manifest from`() {
        val directory = Files.createTempDirectory("module-apk").toFile()
        val apk = File(directory, "base.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(3, 0, 8, 0))
            zip.closeEntry()
        }
        URLClassLoader(arrayOf(apk.toURI().toURL()), null).use { loader ->
            assertEquals(apk.path, ModuleSignature.apkOf(loader))
        }
        URLClassLoader(arrayOf(directory.toURI().toURL()), null).use { loader -> assertNull(ModuleSignature.apkOf(loader)) }
        assertNull(ModuleSignature.apkOf(null))
    }

    @Test fun `the Rust core reads the signer digest of an APK`() {
        val apk = File(Files.createTempDirectory("signed").toFile(), "module.apk").apply { writeBytes(signedApk(certificate = "certificate".toByteArray())) }
        NativeSignature.ensureLoaded()
        val expected = MessageDigest.getInstance("SHA-256").digest("certificate".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, NativeSignature.signerDigest(apk.path))
        assertEquals(NativeSignature.UNCONFIGURED, NativeSignature.verify(apk.path))
        assertNull(NativeSignature.signerDigest(File(apk.parentFile, "missing.apk").path))
    }

    @Test fun `an APK whose central directory does not end where the end record begins is not read`() {
        val apk = File(Files.createTempDirectory("signed").toFile(), "module.apk").apply { writeBytes(signedApk("certificate".toByteArray(), directorySize = 0)) }
        NativeSignature.ensureLoaded()
        assertNull(NativeSignature.signerDigest(apk.path))
    }

    // The layout signer/verify.rs reads: local data, an APK Signing Block with a v3 pair, a central directory and the EOCD,
    // whose size and offset fields place the directory directly in front of it, as the signature schemes require.
    // directorySize is what the EOCD claims for the central directory; the real one is 46 bytes.
    private fun signedApk(certificate: ByteArray, directorySize: Int = 46): ByteArray {
        fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        fun prefixed(content: ByteArray) = le(4).putInt(content.size).array() + content
        val signedData = prefixed(prefixed("digest".toByteArray())) + prefixed(prefixed(certificate))
        val signer = prefixed(prefixed(signedData) + prefixed("signatures".toByteArray()) + prefixed("key".toByteArray()))
        val value = prefixed(signer)
        val pair = le(8).putLong(value.size + 4L).array() + le(4).putInt(0xf05368c0.toInt()).array() + value
        val size = pair.size + 24L
        val block = le(8).putLong(size).array() + pair + le(8).putLong(size).array() + "APK Sig Block 42".toByteArray()
        val local = "PK\u0003\u0004local file data".toByteArray()
        val directory = local.size + block.size
        val central = le(4).putInt(0x02014b50).array() + ByteArray(42)
        val end = le(22).putInt(0x06054b50).put(ByteArray(8)).putInt(directorySize).putInt(directory).putShort(0).array()
        return local + block + central + end
    }
}
