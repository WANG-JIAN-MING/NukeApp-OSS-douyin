package io.github.nuke.oss.patcher.patch

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.Properties
import java.util.UUID
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class KeyMaterial internal constructor(
    val keyFile: File,
    val password: String,
    val alias: String,
    val certificateSha256: String,
) {
    override fun toString(): String = "KeyMaterial(alias=$alias, certificateSha256=$certificateSha256)"
}

object LocalPatchKey {
    private const val DIRECTORY_NAME = "patch-signing"
    private const val KEY_FILE_NAME = "signing.keystore"
    private const val CONFIG_FILE_NAME = "signing.properties"
    private const val INITIALIZED_FILE_NAME = "patch-signing.initialized"
    private const val KEY_ALIAS = "nuke-patch"

    @Synchronized
    fun loadOrCreate(privateDirectory: File): KeyMaterial {
        check(privateDirectory.isDirectory || privateDirectory.mkdirs()) { "Cannot create private signing directory" }
        val destination = File(privateDirectory, DIRECTORY_NAME)
        val initialized = File(privateDirectory, INITIALIZED_FILE_NAME)
        if (destination.exists()) {
            check(initialized.isFile) { "Local signing state is incomplete; automatic replacement is disabled" }
            return load(destination)
        }
        check(!initialized.exists()) { "Local signing key is missing; automatic replacement is disabled" }
        val temporary = File(privateDirectory, ".$DIRECTORY_NAME-${UUID.randomUUID()}")
        check(temporary.mkdir()) { "Cannot create temporary signing directory" }
        makePrivate(temporary, directory = true)
        try {
            create(temporary)
            // A retained marker also detects removal of the complete published key directory.
            check(initialized.createNewFile()) { "Cannot reserve local signing identity" }
            FileOutputStream(initialized).use { it.fd.sync() }
            makePrivate(initialized)
            // Publish the store and its credentials together on the same filesystem.
            check(!destination.exists() && temporary.renameTo(destination)) { "Cannot publish local signing key" }
            return load(destination)
        } finally {
            if (temporary.exists()) temporary.deleteRecursively()
        }
    }

    private fun create(directory: File) {
        val random = SecureRandom()
        val passwordBytes = ByteArray(32).also(random::nextBytes)
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(passwordBytes)
        passwordBytes.fill(0)
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val subject = X500Name("CN=NukeApp-OSS Local Patch, O=NukeApp-OSS")
        val now = System.currentTimeMillis()
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                subject,
                BigInteger(160, random).add(BigInteger.ONE),
                Date(now - 24L * 60 * 60 * 1000),
                Date(now + 30L * 365 * 24 * 60 * 60 * 1000),
                subject,
                keyPair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)),
        )
        certificate.verify(keyPair.public)
        val passwordChars = password.toCharArray()
        val storeType = KeyStore.getDefaultType()
        try {
            val store = KeyStore.getInstance(storeType).apply {
                load(null, passwordChars)
                setKeyEntry(KEY_ALIAS, keyPair.private, passwordChars, arrayOf(certificate))
            }
            val keyFile = File(directory, KEY_FILE_NAME)
            FileOutputStream(keyFile).use { fileOutput ->
                // BKS providers close the supplied stream; keep our descriptor open until sync.
                val storeOutput = object : FilterOutputStream(fileOutput) {
                    override fun close() = flush()
                }
                store.store(storeOutput, passwordChars)
                storeOutput.flush()
                fileOutput.fd.sync()
            }
            makePrivate(keyFile)
            val config = Properties().apply {
                setProperty("format", "1")
                setProperty("storeType", storeType)
                setProperty("alias", KEY_ALIAS)
                setProperty("password", password)
                setProperty("certificateSha256", sha256(certificate.encoded))
            }
            val configFile = File(directory, CONFIG_FILE_NAME)
            FileOutputStream(configFile).use {
                config.store(it, null)
                it.fd.sync()
            }
            makePrivate(configFile)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun load(directory: File): KeyMaterial {
        check(directory.isDirectory) { "Local signing material is damaged; automatic replacement is disabled" }
        val keyFile = File(directory, KEY_FILE_NAME)
        val configFile = File(directory, CONFIG_FILE_NAME)
        check(keyFile.isFile && configFile.isFile) { "Local signing material is incomplete; automatic replacement is disabled" }
        try {
            val config = Properties().apply { configFile.inputStream().use { load(it) } }
            check(config.getProperty("format") == "1") { "Unsupported signing material format" }
            check(config.getProperty("storeType") == KeyStore.getDefaultType()) { "Signing store type does not match this platform" }
            val alias = config.getProperty("alias")
            val password = config.getProperty("password")
            check(alias == KEY_ALIAS && password != null && password.length >= 40) { "Invalid signing credentials" }
            val passwordChars = password.toCharArray()
            try {
                val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    keyFile.inputStream().use { load(it, passwordChars) }
                }
                val key = store.getKey(alias, passwordChars)
                val certificate = store.getCertificate(alias) as? X509Certificate
                check(key != null && key.algorithm == "RSA" && certificate != null) { "Missing local RSA signing key" }
                check((certificate.publicKey as? java.security.interfaces.RSAPublicKey)?.modulus?.bitLength() == 2048) {
                    "Unexpected local signing key size"
                }
                certificate.verify(certificate.publicKey)
                val fingerprint = sha256(certificate.encoded)
                check(fingerprint == config.getProperty("certificateSha256")) { "Signing certificate fingerprint mismatch" }
                return KeyMaterial(keyFile, password, alias, fingerprint)
            } finally {
                passwordChars.fill('\u0000')
            }
        } catch (failure: Exception) {
            throw IllegalStateException("Local signing material cannot be loaded; automatic replacement is disabled", failure)
        }
    }

    private fun makePrivate(file: File, directory: Boolean = false) {
        check(file.setReadable(false, false) && file.setWritable(false, false) &&
            file.setReadable(true, true) && file.setWritable(true, true)) { "Cannot protect local signing material" }
        if (directory) {
            check(file.setExecutable(false, false) && file.setExecutable(true, true)) { "Cannot protect local signing directory" }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
