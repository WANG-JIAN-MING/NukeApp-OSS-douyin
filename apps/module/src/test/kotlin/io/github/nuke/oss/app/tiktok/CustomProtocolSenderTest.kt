package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.settings.ConsoleReply
import io.github.nuke.oss.app.shared.settings.ProtocolInputException
import io.github.nuke.oss.app.tiktok.feature.general.CustomProtocolSender
import io.github.nuke.oss.app.tiktok.protocol.PacketCall
import io.github.nuke.oss.app.tiktok.protocol.PacketRequest
import io.github.nuke.oss.app.tiktok.protocol.PacketResponse
import io.github.nuke.oss.app.tiktok.protocol.PacketTransport
import io.github.nuke.oss.app.tiktok.protocol.ProtoMessage
import io.github.nuke.oss.app.tiktok.protocol.ProtocolError
import io.github.nuke.oss.app.tiktok.protocol.ProtocolErrorCode
import io.github.nuke.oss.app.tiktok.protocol.ProtocolResult
import io.github.nuke.oss.core.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the protocol sender with a stand-in transport: it sends nothing until asked, only while it runs and
// for the account it was opened with, and reports the host's answer as it came.
class CustomProtocolSenderTest : HookHarness() {
    private val sent = ArrayList<PacketRequest>()
    private var account = 42L
    private var answer: (PacketRequest) -> ProtocolResult<PacketResponse> = { request ->
        ProtocolResult.Ok(PacketResponse(request.endpoint, ProtoMessage.encode(mapOf(1 to request.endpoint.cmd, 3 to 7L, 4 to "denied"))))
    }
    private val transport = PacketTransport { request, callback ->
        sent += request
        callback(answer(request))
        PacketCall {}
    }

    private fun sender() = CustomProtocolSender({ transport }, { if (account > 0) account else error("Not logged in") }, Logger {})

    @Test fun `nothing is sent before send and nothing while the tool is off`() = runBlocking {
        val tool = sender()
        assertFalse(tool.running())
        assertThrows(IllegalStateException::class.java) { runBlocking { tool.send(42, 1, 1, byteArrayOf()) } }
        enable(tool)
        assertTrue(tool.running())
        assertTrue(sent.isEmpty())
    }

    @Test fun `a response carries the full bytes and the envelope status`() = runBlocking {
        val tool = sender().also(::enable)
        val reply = tool.send(42, 601, 2, byteArrayOf(8, 1)) as ConsoleReply.Response
        assertEquals(601, sent.single().endpoint.cmd)
        assertEquals(2, sent.single().endpoint.inboxType)
        assertArrayEquals(byteArrayOf(8, 1), sent.single().requestBody)
        assertEquals(7L, reply.status)
        assertEquals("denied", reply.error)
    }

    @Test fun `a host failure is reported with its code and a changed account sends nothing`() = runBlocking {
        val tool = sender().also(::enable)
        answer = { ProtocolResult.Err(ProtocolError(ProtocolErrorCode.TIMEOUT, "late", hostCode = 9)) }
        val failed = tool.send(42, 1, 1, byteArrayOf()) as ConsoleReply.Failed
        assertEquals("TIMEOUT", failed.code)
        assertEquals(9, failed.hostCode)
        account = 43
        assertThrows(IllegalStateException::class.java) { runBlocking { tool.send(42, 1, 1, byteArrayOf()) } }
        assertEquals(1, sent.size)
        account = 0
        assertNull(tool.account())
    }

    @Test fun `the request body is the payload or the payload under its tag`() {
        val tool = sender()
        assertArrayEquals(byteArrayOf(1, 2), tool.requestBody(1, 0, null, byteArrayOf(1, 2)))
        val wrapped = ProtoMessage.decode(tool.requestBody(1, 0, 5, byteArrayOf(8, 1)))
        assertArrayEquals(byteArrayOf(8, 1), wrapped.bytes(5))
        assertThrows(ProtocolInputException::class.java) { tool.requestBody(0, 0, null, byteArrayOf()) }
        assertThrows(ProtocolInputException::class.java) { tool.requestBody(1, -1, null, byteArrayOf()) }
        assertThrows(ProtocolInputException::class.java) { tool.requestBody(1, 0, 0, byteArrayOf()) }
    }
}
