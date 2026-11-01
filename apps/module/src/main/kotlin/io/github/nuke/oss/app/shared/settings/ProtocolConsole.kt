package io.github.nuke.oss.app.shared.settings

// NukeApp's custom protocol sender, for the settings screen: one raw IM request at a time, as the signed-in account,
// through the host's own IM SDK. Opening the tool or editing a draft never sends anything; only send does.
internal interface ProtocolConsole {
    // Whether the tool's switch is on and it is running in this process; send refuses otherwise.
    fun running(): Boolean

    // The signed-in IM account, or null without one. Blocking: callers run it off the main thread.
    fun account(): Long?

    // The RequestBody a send would use: payload as it is, or wrapped under bodyTag. Throws ProtocolInputException
    // when cmd, inboxType, bodyTag or the size is not acceptable.
    fun requestBody(cmd: Int, inboxType: Int, bodyTag: Int?, payload: ByteArray): ByteArray

    // Sends one RequestBody as account and waits for the host. Throws IllegalStateException with the reason when the
    // tool is not running or the account changed.
    suspend fun send(account: Long, cmd: Int, inboxType: Int, requestBody: ByteArray): ConsoleReply
}

internal sealed interface ConsoleReply {
    // The host's full Response PB, with the envelope's status (field 3) and error text (field 4) when they read.
    class Response(val bytes: ByteArray, val status: Long?, val error: String?) : ConsoleReply

    // The request did not get a response: why, with the host's own code when it gave one. A timeout does not mean
    // nothing was sent.
    class Failed(val code: String, val message: String, val hostCode: Int?) : ConsoleReply
}

internal class ProtocolInputException(message: String) : IllegalArgumentException(message)
