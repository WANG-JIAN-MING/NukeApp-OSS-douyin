package io.github.nuke.oss.app.tiktok.protocol

import org.junit.Assert.*
import org.junit.Test

class ProtoMessageTest {
    @Test fun matchesIndependentWireFixtureAndUsesLastScalar() {
        val fixture = byteArrayOf(8, -106, 1, 18, 1, 120, 8, 1)
        val message = ProtoMessage.decode(fixture)
        assertEquals(1L, message.varint(1))
        assertEquals("x", message.string(2))
        assertEquals(2, message.all(1).size)
        assertArrayEquals(byteArrayOf(8, -106, 1, 18, 1, 120), ProtoMessage.encode(mapOf(1 to 150, 2 to "x")))
    }

    @Test fun roundTripsUnknownRepeatedFixedAndNegativeFields() {
        val source = ProtoMessage.encode(mapOf(1 to -1, 2 to listOf("a", "b"),
            3 to mapOf(50 to "安全会话"), 4 to 1.5f, 5 to -2.0, 110 to byteArrayOf(0, -1), 6 to 0))
        val message = ProtoMessage.decode(source)
        assertEquals(-1L, message.varint(1))
        assertEquals(2, message.all(2).size)
        assertEquals("安全会话", message.message(3)?.string(50))
        assertEquals(0L, message.varint(6))
        assertArrayEquals(source, message.encode())
    }

    @Test fun byteArraysAreDefensivelyCopied() {
        val original = byteArrayOf(1, 2)
        val field = ProtoField(1, 2, bytes = original)
        original[0] = 9
        field.bytes!![0] = 9
        assertArrayEquals(byteArrayOf(1, 2), field.bytes)
    }

    @Test fun rejectsMalformedWireData() {
        val malformed = listOf(byteArrayOf(0), byteArrayOf(10, 5, 1),
            byteArrayOf(8, -128), byteArrayOf(9, 1), byteArrayOf(11),
            byteArrayOf(8) + ByteArray(9) { -1 } + byteArrayOf(2))
        malformed.forEach { bytes -> assertTrue(runCatching { ProtoMessage.decode(bytes) }.isFailure) }
    }

    @Test fun rejectsInvalidFieldNumbersAndNonNumericNestedKeys() {
        assertTrue(runCatching { ProtoMessage.encode(mapOf(0 to 1)) }.isFailure)
        assertTrue(runCatching { ProtoMessage.encode(mapOf(1 to mapOf("token" to "x"))) }.isFailure)
        assertTrue(runCatching { ProtoMessage.encode(mapOf(1 to ByteArray(ProtoMessage.MAX_BODY_BYTES + 1))) }.isFailure)
    }
}
