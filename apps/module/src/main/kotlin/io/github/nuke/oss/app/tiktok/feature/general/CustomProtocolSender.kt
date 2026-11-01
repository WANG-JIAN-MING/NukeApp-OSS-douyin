package io.github.nuke.oss.app.tiktok.feature.general

import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ConsoleReply
import io.github.nuke.oss.app.shared.settings.ProtocolConsole
import io.github.nuke.oss.app.shared.settings.ProtocolInputException
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.protocol.PacketEndpoint
import io.github.nuke.oss.app.tiktok.protocol.PacketExecutor
import io.github.nuke.oss.app.tiktok.protocol.PacketRequest
import io.github.nuke.oss.app.tiktok.protocol.PacketTransport
import io.github.nuke.oss.app.tiktok.protocol.ProtoMessage
import io.github.nuke.oss.app.tiktok.protocol.ProtocolErrorCode
import io.github.nuke.oss.app.tiktok.protocol.ProtocolException
import io.github.nuke.oss.app.tiktok.protocol.ProtocolQueries
import io.github.nuke.oss.app.tiktok.protocol.ProtocolResult
import io.github.nuke.oss.app.tiktok.sdk.chat.ImAccount
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// NukeApp's custom protocol sender: a manual debugging tool that sends one raw IM request, as the signed-in account,
// through the host's own IM SDK (PacketHost), and shows the full Response PB. The switch only makes the tool available
// on the settings screen; nothing is hooked and nothing is sent until the user presses send there. Requests are never
// retried, and a timed-out request may still have been sent. Logs name the command and the outcome, never the bytes.
@HookEntry
internal class CustomProtocolSender(
    private val transport: () -> PacketTransport,
    private val account: () -> Long,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries, ProtocolConsole {
    override val id = ID
    override val dexQueries = listOf(
        ProtocolQueries.SDK_CONTEXT, ProtocolQueries.COMMAND_ROUTE, ProtocolQueries.RAW_DISPATCH, ProtocolQueries.HANDLE_RESPONSE,
        ProtocolQueries.CHECK_RESPONSE, ProtocolQueries.BUILD_BODY, ProtocolQueries.RESPONSE_FIELD, ProtocolQueries.REQUEST_FIELD,
    )
    @Volatile private var active = false
    private val executor by lazy { PacketExecutor(transport()) }

    override fun install(scope: FeatureScope) {
        active = true
        scope.own(AutoCloseable { active = false })
    }

    override fun running() = active

    override fun account(): Long? = try {
        account.invoke()
    } catch (_: IllegalStateException) {
        null
    }

    override fun requestBody(cmd: Int, inboxType: Int, bodyTag: Int?, payload: ByteArray): ByteArray {
        if (cmd <= 0) throw ProtocolInputException("cmd must be positive")
        if (inboxType < 0) throw ProtocolInputException("inboxType must not be negative")
        if (bodyTag != null && bodyTag !in 1..MAX_TAG) throw ProtocolInputException("The body tag must be 1 to $MAX_TAG")
        if (payload.size > MAX_BODY_BYTES) throw ProtocolInputException("The body is larger than $MAX_BODY_BYTES bytes")
        val endpoint = PacketEndpoint(cmd, inboxType)
        val request = try {
            if (bodyTag == null) PacketRequest(endpoint, payload) else PacketRequest.fromPayload(endpoint, bodyTag, payload)
        } catch (error: ProtocolException) {
            // Wrapping runs in the native core; a core that does not load is not the input's fault.
            if (error.error.code == ProtocolErrorCode.NATIVE_UNAVAILABLE) throw IllegalStateException(error.message, error)
            throw ProtocolInputException(error.message ?: error.error.code.name)
        }
        return request.requestBody.also { if (it.size > MAX_BODY_BYTES) throw ProtocolInputException("The body is larger than $MAX_BODY_BYTES bytes") }
    }

    override suspend fun send(account: Long, cmd: Int, inboxType: Int, requestBody: ByteArray): ConsoleReply {
        check(active) { "The custom protocol sender is not running; turn it on and restart Douyin" }
        check(account() == account) { "The account changed; reopen the sender" }
        return when (val result = executor.request(PacketRequest(PacketEndpoint(cmd, inboxType), requestBody))) {
            is ProtocolResult.Ok -> {
                val bytes = result.value.response
                logger.log("feature=$ID response cmd=$cmd bytes=${bytes.size}")
                val envelope = try {
                    ProtoMessage.decode(bytes)
                } catch (_: RuntimeException) {
                    null
                }
                ConsoleReply.Response(bytes, envelope?.varint(STATUS_FIELD), envelope?.string(ERROR_FIELD)?.take(MAX_ERROR_CHARS))
            }
            is ProtocolResult.Err -> {
                logger.log("feature=$ID failed cmd=$cmd ${result.error.code}")
                ConsoleReply.Failed(result.error.code.name, result.error.message, result.error.hostCode)
            }
        }
    }

    companion object : TikTokFeature {
        const val ID = "CustomProtocolSender"
        // The native core's limit for a RequestBody.
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private const val MAX_TAG = 0x1fffffff
        private const val STATUS_FIELD = 3
        private const val ERROR_FIELD = 4
        private const val MAX_ERROR_CHARS = 1024

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CustomProtocolSender(packetTransport, ImAccount(hostClassLoader)::id, logger))
        }
    }
}
