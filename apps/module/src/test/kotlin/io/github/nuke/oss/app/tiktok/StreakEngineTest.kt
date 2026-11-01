package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.settings.ActivityRecord
import io.github.nuke.oss.app.shared.settings.ActivityStatus
import io.github.nuke.oss.app.tiktok.feature.chat.streak.AutoStreak
import io.github.nuke.oss.app.tiktok.feature.chat.streak.PreparedMessage
import io.github.nuke.oss.app.tiktok.feature.chat.streak.StreakAttempt
import io.github.nuke.oss.app.tiktok.feature.chat.streak.StreakCodec
import io.github.nuke.oss.app.tiktok.feature.chat.streak.StreakEngine
import io.github.nuke.oss.app.tiktok.feature.chat.streak.StreakFileStore
import io.github.nuke.oss.app.tiktok.feature.chat.streak.StreakMode
import io.github.nuke.oss.app.tiktok.feature.chat.streak.StreakOutcome
import io.github.nuke.oss.app.tiktok.feature.chat.streak.StreakPlan
import io.github.nuke.oss.app.tiktok.feature.chat.streak.StreakState
import io.github.nuke.oss.app.tiktok.feature.chat.streak.StreakStatus
import io.github.nuke.oss.app.tiktok.feature.chat.streak.StreakStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class StreakEngineTest {
    private val zone = "UTC"
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun plan(friends: Set<String> = setOf("11", "22"), mode: StreakMode = StreakMode.TEXT) =
        StreakPlan(friends, mode, "hi", "https://example.com/", 19, 0, zone)

    private class MemoryStore(var state: StreakState, var writes: Int = 0) : StreakStore {
        override fun read() = state
        override fun write(state: StreakState) { this.state = state; writes++ }
    }

    private class Sends {
        val sent = ArrayList<Pair<String, String>>()
        var outcome: (String) -> StreakOutcome = { StreakOutcome(StreakStatus.ACKNOWLEDGED) }
    }

    private fun StreakEngine.run(plan: StreakPlan, now: Long, sends: Sends, allowed: () -> Boolean = { true }, clock: () -> Long = { now }) =
        tick(plan, now, allowed, clock, { PreparedMessage(it.text) }) { uid, text, _ ->
            sends.sent += uid to text
            sends.outcome(uid)
        }

    @Test fun `sends each friend once per local day from the send time`() {
        val store = MemoryStore(StreakState("1"))
        val engine = StreakEngine("1", store)
        val sends = Sends()
        engine.adopt(plan(), at(1, 8))
        engine.run(plan(), at(1, 18, 59), sends)
        assertTrue(sends.sent.isEmpty())
        engine.run(plan(), at(1, 19, 1), sends)
        assertEquals(listOf("11" to "hi", "22" to "hi"), sends.sent)
        engine.run(plan(), at(1, 21), sends)
        assertEquals(2, sends.sent.size)
        engine.run(plan(), at(2, 19, 5), sends)
        assertEquals(4, sends.sent.size)
    }

    @Test fun `a plan first seen after the send time starts the next day`() {
        val engine = StreakEngine("1", MemoryStore(StreakState("1")))
        val sends = Sends()
        engine.adopt(plan(), at(1, 20))
        engine.run(plan(), at(1, 20, 1), sends)
        assertTrue(sends.sent.isEmpty())
        assertEquals(at(2, 19), engine.nextWakeAt(plan(), at(1, 20, 1)))
        engine.run(plan(), at(2, 19, 1), sends)
        assertEquals(2, sends.sent.size)
    }

    @Test fun `a claim is on disk before the send starts`() {
        val store = MemoryStore(StreakState("1"))
        val engine = StreakEngine("1", store)
        engine.adopt(plan(setOf("11")), at(1, 8))
        var claimed: StreakStatus? = null
        engine.tick(plan(setOf("11")), at(1, 19, 1), { true }, { at(1, 19, 1) }, { PreparedMessage(it.text) }) { _, _, _ ->
            claimed = store.state.history.single().status
            StreakOutcome(StreakStatus.ACKNOWLEDGED)
        }
        assertEquals(StreakStatus.CLAIMED, claimed)
        assertEquals(StreakStatus.ACKNOWLEDGED, store.state.history.single().status)
    }

    @Test fun `an unknown or failed result is not retried the same day`() {
        val engine = StreakEngine("1", MemoryStore(StreakState("1")))
        val sends = Sends().apply { outcome = { StreakOutcome(if (it == "11") StreakStatus.UNKNOWN else StreakStatus.FAILED, "x") } }
        engine.adopt(plan(), at(1, 8))
        engine.run(plan(), at(1, 19, 1), sends)
        engine.run(plan(), at(1, 19, 2), sends)
        assertEquals(2, sends.sent.size)
    }

    @Test fun `a send that throws is recorded unknown and not retried`() {
        val store = MemoryStore(StreakState("1"))
        val engine = StreakEngine("1", store)
        engine.adopt(plan(setOf("11")), at(1, 8))
        engine.tick(plan(setOf("11")), at(1, 19, 1), { true }, { at(1, 19, 1) }, { PreparedMessage("hi") }) { _, _, _ -> error("boom") }
        assertEquals(StreakStatus.UNKNOWN, store.state.history.single().status)
        val sends = Sends()
        engine.run(plan(setOf("11")), at(1, 19, 2), sends)
        assertTrue(sends.sent.isEmpty())
    }

    @Test fun `a claim left by an ended process reads back as unknown`() {
        val attempt = StreakAttempt("a", "11", 0, 0, StreakStatus.CLAIMED, StreakMode.TEXT)
        val store = MemoryStore(StreakState("1", history = listOf(attempt)))
        val engine = StreakEngine("1", store)
        assertEquals(StreakStatus.UNKNOWN, engine.snapshot().history.single().status)
        assertEquals(StreakStatus.UNKNOWN, store.state.history.single().status)
    }

    @Test fun `turning the feature off stops the remaining friends`() {
        val engine = StreakEngine("1", MemoryStore(StreakState("1")))
        val sends = Sends()
        var on = true
        sends.outcome = { on = false; StreakOutcome(StreakStatus.ACKNOWLEDGED) }
        engine.adopt(plan(), at(1, 8))
        engine.run(plan(), at(1, 19, 1), sends, allowed = { on })
        assertEquals(1, sends.sent.size)
    }

    @Test fun `midnight during a round stops it`() {
        val engine = StreakEngine("1", MemoryStore(StreakState("1")))
        val sends = Sends()
        engine.adopt(plan(), at(1, 8))
        engine.run(plan(), at(1, 23, 59), sends, clock = { at(2, 0, 1) })
        assertTrue(sends.sent.isEmpty())
    }

    @Test fun `a changed plan is not run until it is adopted`() {
        val engine = StreakEngine("1", MemoryStore(StreakState("1")))
        val sends = Sends()
        engine.adopt(plan(), at(1, 8))
        engine.run(plan(setOf("33")), at(1, 19, 1), sends)
        assertTrue(sends.sent.isEmpty())
    }

    @Test fun `a journal of another account is refused`() {
        assertThrows(IllegalArgumentException::class.java) { StreakEngine("2", MemoryStore(StreakState("1"))) }
    }

    @Test fun `plan validation rejects bad input`() {
        assertThrows(IllegalArgumentException::class.java) { plan(emptySet()).validate() }
        assertThrows(IllegalArgumentException::class.java) { plan(setOf("0")).validate() }
        assertThrows(IllegalArgumentException::class.java) { plan().copy(text = " ").validate() }
        assertThrows(IllegalArgumentException::class.java) { plan(mode = StreakMode.HITOKOTO).copy(hitokotoUrl = "ftp://x").validate() }
        assertThrows(IllegalArgumentException::class.java) { plan().copy(hour = 24).validate() }
        plan(mode = StreakMode.INTERACTIVE).copy(text = "").validate()
    }

    @Test fun `journal round trips and a damaged one is an error`() {
        val state = StreakState(
            "1", "abc", 5, mapOf("11" to 7L),
            listOf(StreakAttempt("a", "11", 7, 9, StreakStatus.ACKNOWLEDGED, StreakMode.TEXT, true, "line\tone\nand \\ two", "c", 42)),
        )
        assertEquals(state, StreakCodec.decode(StreakCodec.encode(state)))
        assertThrows(IllegalArgumentException::class.java) { StreakCodec.decode("garbage") }
        assertThrows(RuntimeException::class.java) { StreakCodec.decode(StreakCodec.encode(state).replace("ACKNOWLEDGED", "NOPE")) }
    }

    @Test fun `the settings history reads the account journal newest first`() {
        val directory = java.nio.file.Files.createTempDirectory("streak").toFile()
        try {
            assertEquals(emptyList<ActivityRecord>(), AutoStreak.history(directory, null))
            assertEquals(emptyList<ActivityRecord>(), AutoStreak.history(directory, "42"))
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest("42".toByteArray()).joinToString("") { "%02x".format(it) }
            val file = java.io.File(directory, "$hash/state.dat")
            StreakFileStore(file, "42").write(StreakState("42", history = listOf(
                StreakAttempt("b", "22", 1, at(2, 19), StreakStatus.FAILED, StreakMode.HITOKOTO, usedFallback = true, detail = "refused"),
                StreakAttempt("a", "11", 1, at(1, 19), StreakStatus.ACKNOWLEDGED, StreakMode.TEXT),
            )))
            assertEquals(
                listOf(
                    ActivityRecord(at(2, 19), "22", ActivityStatus.FAILED, "hitokoto", true, "refused"),
                    ActivityRecord(at(1, 19), "11", ActivityStatus.DONE, "text", false, ""),
                ),
                AutoStreak.history(directory, "42"),
            )
            file.writeText("garbage")
            assertThrows(IllegalStateException::class.java) { AutoStreak.history(directory, "42") }
        } finally {
            directory.deleteRecursively()
        }
    }
}
