package io.github.nuke.oss.app.shared.integrity

// JNI binding of the module signer check in the Rust core (src/main/rust/src/signer/verify.rs), which shares the protocol
// core's library. The codes are mirrored in signer/bridge.rs.
internal object NativeSignature {
    const val MATCHES = 0
    const val MISMATCH = 1
    const val UNCONFIGURED = 2
    const val UNREADABLE = 3
    private const val LIBRARY = "nuke_protocol_core"

    @Volatile private var loaded = false

    @Synchronized fun ensureLoaded() {
        if (!loaded) System.loadLibrary(LIBRARY)
        loaded = true
    }

    external fun verify(apkPath: String): Int
    external fun signerDigest(apkPath: String): String?
}

// What comparing a module APK's signer with the release certificate compiled into the native library found.
internal sealed interface SignatureCheck {
    // The APK is signed with the release certificate.
    data object Matches : SignatureCheck

    // The build had no release key, so nothing is enforced.
    data object Unconfigured : SignatureCheck

    // Another certificate signed the APK; actual is its SHA-256 when it could be read.
    data class Mismatch(val actual: String?) : SignatureCheck

    // The APK has no readable v2/v3 signer, or could not be opened.
    data class Unreadable(val path: String) : SignatureCheck

    // The native library is not loadable here, so the check cannot run at all.
    data class Unavailable(val reason: String) : SignatureCheck
}

// The module signer check: the APK's own signing block names its certificate (the native code reads it, see
// src/main/rust/src/signer/verify.rs), and the native code compares it with the release certificate the library was built
// with. Where the module code came from is the APK of
// its class loader (its AndroidManifest.xml resource), or the path the entry hands over when that loader is built from
// memory (Zygisk).
internal object ModuleSignature {
    interface Natives {
        fun load()
        fun verify(apkPath: String): Int
        fun signerDigest(apkPath: String): String?
    }

    private val native = object : Natives {
        override fun load() = NativeSignature.ensureLoaded()
        override fun verify(apkPath: String) = NativeSignature.verify(apkPath)
        override fun signerDigest(apkPath: String) = NativeSignature.signerDigest(apkPath)
    }

    fun check(apkPath: String, natives: Natives = native): SignatureCheck {
        try {
            natives.load()
        } catch (error: UnsatisfiedLinkError) {
            return SignatureCheck.Unavailable(error.message ?: "native library missing")
        }
        return when (natives.verify(apkPath)) {
            NativeSignature.MATCHES -> SignatureCheck.Matches
            NativeSignature.UNCONFIGURED -> SignatureCheck.Unconfigured
            NativeSignature.MISMATCH -> SignatureCheck.Mismatch(natives.signerDigest(apkPath))
            else -> SignatureCheck.Unreadable(apkPath)
        }
    }

    // Why the module may not run from apkPath, or null when it may: only a different or missing signer refuses. An
    // unconfigured build and a missing native library are logged and let through, since there is nothing to compare.
    fun refusal(apkPath: String, log: (String) -> Unit, natives: Natives = native): String? = when (val check = check(apkPath, natives)) {
        SignatureCheck.Matches -> null.also { log("module_signature matches") }
        SignatureCheck.Unconfigured -> null.also { log("module_signature unconfigured") }
        is SignatureCheck.Unavailable -> null.also { log("module_signature unavailable ${check.reason}") }
        is SignatureCheck.Mismatch -> "The module APK is not signed with the release certificate (signer ${check.actual ?: "unknown"})"
        is SignatureCheck.Unreadable -> "The module APK has no readable v2 or v3 signer"
    }

    // The APK a class loader reads its resources from (jar:file:<apk>!/AndroidManifest.xml), for loaders backed by an
    // APK file; null for in-memory loaders.
    fun apkOf(loader: ClassLoader?): String? {
        val manifest = loader?.getResource("AndroidManifest.xml") ?: return null
        if (manifest.protocol != "jar") return null
        return manifest.path.removePrefix("file:").substringBefore("!/").takeIf { it.endsWith(".apk") }
    }
}
