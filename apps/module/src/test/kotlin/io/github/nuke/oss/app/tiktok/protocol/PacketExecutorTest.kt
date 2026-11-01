package io.github.nuke.oss.app.tiktok.protocol

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PacketExecutorTest {
    private val endpoint = PacketEndpoint(54321, 2) // Intentionally absent from any local IM command enum.
    private fun packet(bytes: ByteArray = byteArrayOf()) = PacketRequest(endpoint, bytes)
    private fun response(status: Int = 0) = PacketResponse(endpoint,
        ProtoMessage.encode(mapOf(1 to endpoint.cmd, 2 to 123L, 3 to status)))

    @Test fun arbitraryCmdAndOpaqueBytesReachTransportWithoutNormalization() = runBlocking {
        // Noncanonical varint, repeated fields and deprecated groups are opaque to the dispatcher.
        val raw = byteArrayOf(8, 0x81.toByte(), 0, 8, 2, 0x13, 0x14)
        val executor = PacketExecutor(PacketTransport { request, callback ->
            assertEquals(endpoint, request.endpoint)
            assertArrayEquals(raw, request.requestBody)
            callback(ProtocolResult.Ok(response(403)))
            PacketCall {}
        })
        val result = executor.request(packet(raw)) as ProtocolResult.Ok
        // Server errors are caller-visible PB, not a fabricated transport failure/success code.
        assertEquals(403L, result.value.decode().varint(3))
    }

    @Test fun synchronousDuplicateCallbacksDeliverOnlyFirstResultAndCloseOnce() = runBlocking {
        val closes = AtomicInteger()
        val executor = PacketExecutor(PacketTransport { _, callback ->
            callback(ProtocolResult.Ok(response()))
            callback(ProtocolResult.Err(ProtocolError(ProtocolErrorCode.HOST_CALLBACK_FAILED, "late")))
            PacketCall { closes.incrementAndGet() }
        })
        assertTrue(executor.request(packet()) is ProtocolResult.Ok)
        assertEquals(1, closes.get())
    }

    @Test fun timeoutClosesWaitingWithoutRedispatchAndIgnoresLateCallback() = runBlocking {
        var late: ((ProtocolResult<PacketResponse>) -> Unit)? = null
        var sends = 0
        var closes = 0
        val executor = PacketExecutor(PacketTransport { _, callback ->
            sends++
            late = callback
            PacketCall { closes++ }
        })
        val result = executor.request(packet(), 25)
        assertEquals(ProtocolErrorCode.TIMEOUT, (result as ProtocolResult.Err).error.code)
        late!!(ProtocolResult.Ok(response()))
        assertEquals(1, sends)
        assertEquals(1, closes)
    }

    @Test fun cancellationPropagatesAndClosesWaiter() = runBlocking {
        val dispatched = CompletableDeferred<Unit>()
        var closed = false
        val executor = PacketExecutor(PacketTransport { _, _ ->
            dispatched.complete(Unit)
            PacketCall { closed = true }
        })
        val pending = async { executor.request(packet()) }
        dispatched.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertTrue(closed)
    }

    @Test fun invalidRewrittenRequestAndPipelineFailureNeverDispatch() = runBlocking {
        var sends = 0
        val transport = PacketTransport { _, _ -> sends++; PacketCall {} }
        val invalid = PacketPipeline().apply {
            add(object : PacketInterceptor {
                override fun onRequest(request: PacketRequest) = PacketRequest(PacketEndpoint(0), byteArrayOf())
            })
        }
        assertEquals(ProtocolErrorCode.INVALID_ARGUMENT,
            PacketExecutor(transport, invalid).request(packet()).errorOrNull()?.code)
        val broken = PacketPipeline().apply {
            add(object : PacketInterceptor {
                override fun onRequest(request: PacketRequest): PacketRequest? = error("broken")
            })
        }
        assertEquals(ProtocolErrorCode.INTERCEPTOR_FAILED,
            PacketExecutor(transport, broken).request(packet()).errorOrNull()?.code)
        val executor = PacketExecutor(transport)
        assertEquals(ProtocolErrorCode.INVALID_ARGUMENT, executor.request(packet(), 0).errorOrNull()?.code)
        assertEquals(ProtocolErrorCode.INVALID_ARGUMENT,
            executor.request(PacketRequest(PacketEndpoint(1, -1), byteArrayOf())).errorOrNull()?.code)
        assertEquals(ProtocolErrorCode.INVALID_ARGUMENT,
            executor.request(packet(ByteArray(ProtoMessage.MAX_BODY_BYTES + 1))).errorOrNull()?.code)
        assertEquals(0, sends)
    }

    @Test fun pipelineUsesFirstMatchingPreprocessorAndFirstReplacement() = runBlocking {
        val pipeline = PacketPipeline()
        val calls = mutableListOf<String>()
        repeat(2) { i -> pipeline.add(object : PacketPreprocessor {
            override fun matches(request: PacketRequest) = true
            override fun prepare(request: PacketRequest): PacketRequest {
                calls += "p$i"
                return packet(byteArrayOf(8, 7))
            }
        }) }
        repeat(2) { i -> pipeline.add(object : PacketInterceptor {
            override fun onRequest(request: PacketRequest): PacketRequest {
                calls += "i$i"
                return request
            }
        }) }
        val result = PacketExecutor(PacketTransport { request, callback ->
            assertArrayEquals(byteArrayOf(8, 7), request.requestBody)
            callback(ProtocolResult.Ok(response()))
            PacketCall {}
        }, pipeline).request(packet())
        assertTrue(result is ProtocolResult.Ok)
        assertEquals(listOf("p0", "i0"), calls)
    }

    @Test fun hostErrorIsPreservedAndResponseInterceptorFailureClosesCall() = runBlocking {
        val error = ProtocolError(ProtocolErrorCode.HOST_CALLBACK_FAILED, "native", hostCode = -1015)
        val failure = PacketExecutor(PacketTransport { _, callback ->
            callback(ProtocolResult.Err(error)); PacketCall {}
        }).request(packet())
        assertSame(error, (failure as ProtocolResult.Err).error)
        var closed = false
        val pipeline = PacketPipeline().apply {
            add(object : PacketInterceptor {
                override fun onResponse(response: PacketResponse): PacketResponse? = error("decode failed")
            })
        }
        val intercepted = PacketExecutor(PacketTransport { _, callback ->
            callback(ProtocolResult.Ok(response())); PacketCall { closed = true }
        }, pipeline).request(packet())
        assertEquals(ProtocolErrorCode.INTERCEPTOR_FAILED, intercepted.errorOrNull()?.code)
        assertTrue(closed)
    }
}
