package io.github.nuke.oss.app.tiktok.protocol

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

// The wait/dispatch boundary: no Android static state and no automatic retries.
class PacketExecutor(
    private val transport: PacketTransport,
    private val pipeline: PacketPipeline = PacketPipeline(),
) {
    suspend fun request(request: PacketRequest, timeoutMillis: Long = DEFAULT_TIMEOUT_MS): ProtocolResult<PacketResponse> {
        if (timeoutMillis <= 0) return invalidArgument("timeout must be positive")
        validate(request)?.let { return it }
        val prepared = try {
            pipeline.prepare(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return ProtocolResult.Err(error.asProtocolError(ProtocolErrorCode.INTERCEPTOR_FAILED))
        }
        validate(prepared)?.let { return it }
        val response = CompletableDeferred<ProtocolResult<PacketResponse>>()
        val accepting = AtomicBoolean(true)
        var call: PacketCall? = null
        return try {
            withTimeoutOrNull(timeoutMillis) {
                currentCoroutineContext().ensureActive()
                call = transport.dispatch(prepared) { result ->
                    if (accepting.compareAndSet(true, false)) response.complete(result)
                }
                when (val result = response.await()) {
                    is ProtocolResult.Ok -> try {
                        ProtocolResult.Ok(pipeline.receive(result.value))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        ProtocolResult.Err(error.asProtocolError(ProtocolErrorCode.INTERCEPTOR_FAILED))
                    }
                    is ProtocolResult.Err -> result
                }
            } ?: ProtocolResult.Err(ProtocolError(ProtocolErrorCode.TIMEOUT,
                "Host callback timed out; the queued request may still be sent"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ProtocolResult.Err(error.asProtocolError(ProtocolErrorCode.HOST_RESOLUTION_FAILED))
        } finally {
            accepting.set(false)
            response.cancel()
            call?.close()
        }
    }

    // The native core owns the request rules; a library that cannot load is reported, not treated as invalid input.
    private fun validate(request: PacketRequest): ProtocolResult.Err? = try {
        native { validateRequest(request.endpoint.cmd, request.endpoint.inboxType, request.requestBody.size) }
        null
    } catch (error: ProtocolException) {
        ProtocolResult.Err(error.error)
    }

    private fun invalidArgument(message: String) =
        ProtocolResult.Err(ProtocolError(ProtocolErrorCode.INVALID_ARGUMENT, message))

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
