package io.github.nuke.oss.app.tiktok.protocol

// cmd and inbox select the host SDK route; there is no local business-command whitelist.
data class PacketEndpoint(val cmd: Int, val inboxType: Int = 1)

// Complete RequestBody PB, not the authenticated outer Request envelope.
class PacketRequest(val endpoint: PacketEndpoint, requestBody: ByteArray) {
    private val payload = requestBody.copyOf()
    val requestBody: ByteArray get() = payload.copyOf()

    companion object {
        // Wraps a business PB under an explicit RequestBody tag; the tag is never inferred from cmd.
        fun fromPayload(endpoint: PacketEndpoint, bodyTag: Int, payload: ByteArray): PacketRequest =
            PacketRequest(endpoint, native { wrapPayload(bodyTag, payload) })
    }
}

// Full Response PB encoded by the host, including protocol errors. Not a network-frame capture.
class PacketResponse(val endpoint: PacketEndpoint, response: ByteArray) {
    private val payload = response.copyOf()
    val response: ByteArray get() = payload.copyOf()
    fun decode(): ProtoMessage = ProtoMessage.decode(payload)
}

fun interface PacketCall {
    // Detaches this caller. Does not cancel or undo a request already queued in the host.
    fun close()
}

fun interface PacketTransport {
    fun dispatch(request: PacketRequest, callback: (ProtocolResult<PacketResponse>) -> Unit): PacketCall
}

interface PacketPreprocessor {
    fun matches(request: PacketRequest): Boolean
    fun prepare(request: PacketRequest): PacketRequest
}

interface PacketInterceptor {
    fun onRequest(request: PacketRequest): PacketRequest? = null
    fun onResponse(response: PacketResponse): PacketResponse? = null
}
