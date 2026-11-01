package io.github.nuke.oss.patcher.patch

import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.Security
import java.security.interfaces.RSAPublicKey
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.bouncycastle.jce.provider.BouncyCastleProvider

class LocalPatchKeyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun createsAndReloadsOnePersistentRsaIdentity() {
        val privateDirectory = temporary.newFolder()
        val first = LocalPatchKey.loadOrCreate(privateDirectory)
        val bytes = first.keyFile.readBytes()
        val second = LocalPatchKey.loadOrCreate(privateDirectory)
        assertEquals(first.certificateSha256, second.certificateSha256)
        assertEquals(first.password, second.password)
        assertTrue(bytes.contentEquals(second.keyFile.readBytes()))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            first.keyFile.inputStream().use { load(it, first.password.toCharArray()) }
        }
        val certificate = store.getCertificate(first.alias)
        assertEquals(2048, (certificate.publicKey as RSAPublicKey).modulus.bitLength())
        assertEquals("RSA", store.getKey(first.alias, first.password.toCharArray()).algorithm)
        assertEquals(64, first.certificateSha256.length)
    }

    @Test fun bksProviderClosesTheSuppliedStoreStream() = withBksProvider {
        val store = KeyStore.getInstance("BKS").apply { load(null, null) }
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }
        store.store(output, "probe-password".toCharArray())
        assertTrue("The regression must exercise a BKS provider which closes its caller stream", closed)
    }

    @Test fun createsAndReloadsPersistentBksIdentityDespiteProviderStreamClosure() = withBksProvider {
        val directory = temporary.newFolder()
        val first = LocalPatchKey.loadOrCreate(directory)
        val firstBytes = first.keyFile.readBytes()
        val second = LocalPatchKey.loadOrCreate(directory)
        assertEquals("BKS", KeyStore.getDefaultType())
        assertEquals(first.certificateSha256, second.certificateSha256)
        assertEquals(first.password, second.password)
        assertTrue(firstBytes.contentEquals(second.keyFile.readBytes()))
        val store = KeyStore.getInstance("BKS").apply {
            second.keyFile.inputStream().use { load(it, second.password.toCharArray()) }
        }
        assertEquals("RSA", store.getKey(second.alias, second.password.toCharArray()).algorithm)
        assertEquals(2048, (store.getCertificate(second.alias).publicKey as RSAPublicKey).modulus.bitLength())
    }

    @Test fun independentInstallationsUseDifferentKeysAndPasswords() {
        val first = LocalPatchKey.loadOrCreate(temporary.newFolder())
        val second = LocalPatchKey.loadOrCreate(temporary.newFolder())
        assertNotEquals(first.certificateSha256, second.certificateSha256)
        assertNotEquals(first.password, second.password)
        assertTrue(first.password.length >= 40)
    }

    @Test fun keyMaterialDescriptionDoesNotExposePassword() {
        val key = LocalPatchKey.loadOrCreate(temporary.newFolder())
        assertFalse(key.toString().contains(key.password))
        assertTrue(key.toString().contains(key.certificateSha256))
    }

    @Test fun missingPublishedStoreIsRejectedWithoutReplacement() {
        val privateDirectory = temporary.newFolder()
        val first = LocalPatchKey.loadOrCreate(privateDirectory)
        assertTrue(first.keyFile.delete())
        assertThrows(IllegalStateException::class.java) { LocalPatchKey.loadOrCreate(privateDirectory) }
        assertFalse(first.keyFile.exists())
    }

    @Test fun missingWholeIdentityDirectoryIsRejectedWithoutReplacement() {
        val privateDirectory = temporary.newFolder()
        val first = LocalPatchKey.loadOrCreate(privateDirectory)
        assertTrue(checkNotNull(first.keyFile.parentFile).deleteRecursively())
        assertThrows(IllegalStateException::class.java) { LocalPatchKey.loadOrCreate(privateDirectory) }
        assertFalse(first.keyFile.exists())
    }

    @Test fun missingCredentialsAreRejectedWithoutChangingStore() {
        val privateDirectory = temporary.newFolder()
        val first = LocalPatchKey.loadOrCreate(privateDirectory)
        val original = first.keyFile.readBytes()
        assertTrue(File(first.keyFile.parentFile, "signing.properties").delete())
        assertThrows(IllegalStateException::class.java) { LocalPatchKey.loadOrCreate(privateDirectory) }
        assertTrue(original.contentEquals(first.keyFile.readBytes()))
    }

    @Test fun corruptStoreIsRejectedWithoutReplacement() {
        val privateDirectory = temporary.newFolder()
        val first = LocalPatchKey.loadOrCreate(privateDirectory)
        first.keyFile.writeText("invalid keystore")
        assertThrows(IllegalStateException::class.java) { LocalPatchKey.loadOrCreate(privateDirectory) }
        assertEquals("invalid keystore", first.keyFile.readText())
    }

    @Test fun changedCertificateFingerprintIsRejected() {
        val privateDirectory = temporary.newFolder()
        val key = LocalPatchKey.loadOrCreate(privateDirectory)
        val file = File(key.keyFile.parentFile, "signing.properties")
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        properties.setProperty("certificateSha256", "0".repeat(64))
        file.outputStream().use { properties.store(it, null) }
        assertThrows(IllegalStateException::class.java) { LocalPatchKey.loadOrCreate(privateDirectory) }
    }

    private fun withBksProvider(operation: () -> Unit) = synchronized(Security::class.java) {
        val previousType = checkNotNull(Security.getProperty("keystore.type"))
        val provider = BouncyCastleProvider()
        val added = Security.addProvider(provider) >= 0
        try {
            Security.setProperty("keystore.type", "BKS")
            operation()
        } finally {
            Security.setProperty("keystore.type", previousType)
            if (added) Security.removeProvider(provider.name)
        }
    }
}
