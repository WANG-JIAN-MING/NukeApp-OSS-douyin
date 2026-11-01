package io.github.nuke.oss.app.tiktok.protocol

// JNI binding of the Rust protocol core (apps/module/src/main/rust). The library is loaded through the class
// loader that defined this class, so the module's own loader must expose its native library directory.
// In Zygisk mode the payload loader only finds it once the installed module APK of the same build has been added to its
// library path; otherwise the load fails and callers get NATIVE_UNAVAILABLE.
// Methods throw NativeProtocolException; byte arrays are copied across the boundary and never retained by native code.
object NativeProtocol {
    const val ABI_VERSION = 1
    private const val LIBRARY = "nuke_protocol_core"

    // Present flags of correlate(), mirrored in bridge.rs.
    internal const val HAS_REQUEST_SEQUENCE = 1
    internal const val HAS_RESPONSE_CMD = 2
    internal const val HAS_RESPONSE_SEQUENCE = 4

    @Volatile private var loaded = false

    @Synchronized internal fun ensureLoaded() {
        if (loaded) return
        try {
            System.loadLibrary(LIBRARY)
            val actual = abiVersion()
            if (actual != ABI_VERSION) {
                throw ProtocolException(ProtocolError(ProtocolErrorCode.NATIVE_UNAVAILABLE,
                    "Native protocol core has ABI $actual, expected $ABI_VERSION"))
            }
        } catch (error: UnsatisfiedLinkError) {
            throw ProtocolException(ProtocolError(ProtocolErrorCode.NATIVE_UNAVAILABLE,
                "Native protocol core is not loadable: ${error.message}", error))
        }
        loaded = true
    }

    external fun abiVersion(): Int
    external fun decode(bytes: ByteArray): ByteArray
    external fun encode(table: ByteArray): ByteArray
    external fun wrapPayload(bodyTag: Int, payload: ByteArray): ByteArray
    external fun validateRequest(cmd: Int, inboxType: Int, bodyLength: Int)
    external fun correlate(expectedCmd: Int, present: Int, requestSequence: Long, responseCmd: Int, responseSequence: Long)
}

// Thrown by the native core; the constructor is called from JNI and must keep this exact signature.
class NativeProtocolException(val code: Int, message: String) : RuntimeException(message)

// Runs a native call and reports every native failure as a ProtocolException with a stable code.
internal inline fun <T> native(block: NativeProtocol.() -> T): T {
    NativeProtocol.ensureLoaded()
    try {
        return NativeProtocol.block()
    } catch (error: NativeProtocolException) {
        val code = when (error.code) {
            1 -> ProtocolErrorCode.INVALID_ARGUMENT
            2 -> ProtocolErrorCode.SERIALIZE_FAILED
            3 -> ProtocolErrorCode.RESPONSE_DECODE_FAILED
            else -> ProtocolErrorCode.INTERNAL
        }
        throw ProtocolException(ProtocolError(code, error.message ?: "Native protocol failure", error))
    }
}
