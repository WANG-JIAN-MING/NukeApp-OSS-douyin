package io.github.nuke.oss.app.tiktok.protocol

import org.junit.Assert.*
import org.junit.Test

class RawPacketTest {
    class Bytes(val bytes: ByteArray)
    open class BuilderBase {
        protected var unknown = byteArrayOf()
        fun addUnknownFields(bytes: Bytes): BuilderBase { unknown += bytes.bytes; return this }
    }
    class Builder : BuilderBase() {
        fun finish(): Body = Body(unknown)
    }
    class ChangingBuilder : BuilderBase() {
        fun finish(): Body = Body(unknown.drop(1).toByteArray())
    }
    class Body(private val bytes: ByteArray) {
        fun encode(): ByteArray = bytes.copyOf()
    }

    @Test fun hostBuilderBridgeKeepsDuplicateKnownTagsAndUnknownFieldsByteExact() {
        val raw = byteArrayOf(0xa2.toByte(), 6, 0, 8, 0x81.toByte(), 0, 0xa2.toByte(), 6, 0)
        val body = OpaqueBody.create(Builder::class.java, Bytes::class.java,
            Builder::class.java.getDeclaredMethod("finish"), raw)
        assertArrayEquals(raw, (body as Body).encode())
        val empty = OpaqueBody.create(Builder::class.java, Bytes::class.java,
            Builder::class.java.getDeclaredMethod("finish"), byteArrayOf())
        assertArrayEquals(byteArrayOf(), (empty as Body).encode())
    }

    @Test fun codecThatChangesRawBodyFailsBeforeNativeDispatch() {
        assertThrows(IllegalStateException::class.java) {
            OpaqueBody.create(ChangingBuilder::class.java, Bytes::class.java,
                ChangingBuilder::class.java.getDeclaredMethod("finish"), byteArrayOf(8, 1))
        }
    }

    @Test fun requestAndResponseArraysAreDefensivelyCopied() {
        val input = byteArrayOf(8, 1)
        val request = PacketRequest(PacketEndpoint(1), input)
        val response = PacketResponse(PacketEndpoint(1), input)
        input[1] = 9
        request.requestBody[1] = 8
        response.response[1] = 7
        assertArrayEquals(byteArrayOf(8, 1), request.requestBody)
        assertArrayEquals(byteArrayOf(8, 1), response.response)
    }

    @Test fun payloadWrapperUsesExplicitBodyTagIndependentOfCmd() {
        val payload = byteArrayOf(8, 42)
        val request = PacketRequest.fromPayload(PacketEndpoint(12048), 777, payload)
        assertArrayEquals(payload, ProtoMessage.decode(request.requestBody).bytes(777))
        assertNull(ProtoMessage.decode(request.requestBody).bytes(12048))
    }

    private fun correlate(cmd: Int, requestSequence: Long?, responseCmd: Int?, responseSequence: Long?) = native {
        correlate(cmd,
            (if (requestSequence != null) NativeProtocol.HAS_REQUEST_SEQUENCE else 0) or
                (if (responseCmd != null) NativeProtocol.HAS_RESPONSE_CMD else 0) or
                (if (responseSequence != null) NativeProtocol.HAS_RESPONSE_SEQUENCE else 0),
            requestSequence ?: 0, responseCmd ?: 0, responseSequence ?: 0)
    }

    @Test fun callbackCorrelationRequiresBothCmdAndSequence() {
        // Transport matching is independent of protocol status and business response schema/size.
        correlate(100, 99, 100, 99)
        for (mismatch in listOf(
            { correlate(200, 99, 100, 99) },
            { correlate(100, 100, 100, 99) },
            { correlate(100, null, 100, 99) },
            { correlate(100, 99, null, 99) },
            { correlate(100, 99, 100, null) },
        )) {
            val error = assertThrows(ProtocolException::class.java) { mismatch() }
            assertEquals(ProtocolErrorCode.RESPONSE_DECODE_FAILED, error.error.code)
        }
    }

    @Test fun nativeCoreMatchesTheExpectedAbiAndRejectsBadTables() {
        NativeProtocol.ensureLoaded()
        assertEquals(NativeProtocol.ABI_VERSION, NativeProtocol.abiVersion())
        // Truncated field table: the native error crosses JNI as NativeProtocolException with a stable code.
        val error = assertThrows(NativeProtocolException::class.java) { NativeProtocol.encode(byteArrayOf(0, 0, 0, 1)) }
        assertEquals(1, error.code)
        val protocol = assertThrows(ProtocolException::class.java) { native { encode(byteArrayOf(0, 0, 0, 1)) } }
        assertEquals(ProtocolErrorCode.INVALID_ARGUMENT, protocol.error.code)
        assertEquals(ProtocolErrorCode.SERIALIZE_FAILED, assertThrows(ProtocolException::class.java) {
            ProtoMessage.encode(mapOf(0 to 1))
        }.error.code)
    }
}
