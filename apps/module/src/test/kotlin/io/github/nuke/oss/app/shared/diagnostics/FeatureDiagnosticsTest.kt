package io.github.nuke.oss.app.shared.diagnostics

import io.github.nuke.oss.app.shared.dex.DexQueryUnavailable
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FailurePhase
import io.github.nuke.oss.script.ScriptException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class FeatureDiagnosticsTest {
    private val store = MemoryDiagnosticsStore()
    private val disabled = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private val queued = ArrayList<Runnable>()
    private val recorder = FailureRecorder(store, setOf("settings"), { disabled += it }, Logger { logs += it }, Executor { queued += it }) { 42L }

    private fun drain() = queued.toList().also { queued.clear() }.forEach(Runnable::run)

    @Test fun `a record survives storage, with or without a message`() {
        val full = FailureRecord("f", FailurePhases.INSTALL, "java.lang.IllegalStateException", "line one\nline two", "at a\nat b", 7)
        assertEquals(full, FailureRecord.decode("f", full.encode()))
        val bare = full.copy(message = null)
        assertEquals(bare, FailureRecord.decode("f", bare.encode()))
    }

    @Test fun `a separator inside the text cannot split a record`() {
        val record = FailureRecord("f", FailurePhases.INSTALL, "E", "a\u0000b", "trace", 7)
        assertEquals(record.copy(message = "ab"), FailureRecord.decode("f", record.encode()))
        assertNull(FailureRecord.decode("f", "garbage"))
        assertNull(FailureRecord.decode("f", "a\u0000b\u0000c\u0000d\u0000not-a-time"))
    }

    @Test fun `a missing Dex query is named apart from other install failures`() {
        assertEquals(FailurePhases.DEX_FIND, FailurePhases.of(FailurePhase.INSTALL, DexQueryUnavailable("gone")))
        assertEquals(FailurePhases.DEX_FIND, FailurePhases.of(FailurePhase.INSTALL, RuntimeException("wrapped", DexQueryUnavailable("gone"))))
        assertEquals(FailurePhases.SCRIPT, FailurePhases.of(FailurePhase.INSTALL, ScriptException(ScriptException.TIMEOUT, "spun")))
        assertEquals(FailurePhases.INSTALL, FailurePhases.of(FailurePhase.INSTALL, IllegalStateException("bug")))
        assertEquals(FailurePhases.BEFORE_CALLBACK, FailurePhases.of(FailurePhase.BEFORE_CALLBACK, DexQueryUnavailable("gone")))
    }

    @Test fun `the failing thread only queues, and the first failure of a feature is kept`() {
        recorder.report("f", FailurePhase.AFTER_CALLBACK, IllegalStateException("first"))
        recorder.report("f", FailurePhase.AFTER_CALLBACK, IllegalStateException("second"))
        assertTrue(store.read().isEmpty())
        assertTrue(disabled.isEmpty())
        drain()
        val record = store.read().single()
        assertEquals(FailurePhases.AFTER_CALLBACK, record.phase)
        assertEquals("first", record.message)
        assertEquals(42L, record.timestamp)
        assertEquals(listOf("f"), disabled)
    }

    @Test fun `an install failure is recorded without switching anything off`() {
        recorder.report("f", FailurePhase.INSTALL, IllegalStateException("install"))
        drain()
        assertEquals(FailurePhases.INSTALL, store.read().single().phase)
        assertTrue(disabled.isEmpty())
    }

    @Test fun `an exempt feature is recorded but never locked`() {
        recorder.report("settings", FailurePhase.BEFORE_CALLBACK, IllegalStateException("click"))
        recorder.report("f", FailurePhase.INSTALL, IllegalStateException("install"))
        drain()
        assertTrue(disabled.isEmpty())
        assertEquals(setOf("settings", "f"), store.read().map { it.featureId }.toSet())
        assertEquals(setOf("f"), recorder.locked())
    }

    @Test fun `a store that fails is logged and the feature is still locked`() {
        val broken = object : DiagnosticsStore by MemoryDiagnosticsStore() {
            override fun write(record: FailureRecord) = throw IllegalStateException("disk full")
        }
        val failing = FailureRecorder(broken, emptySet(), { disabled += it }, Logger { logs += it }, Executor { it.run() })
        failing.report("f", FailurePhase.AFTER_CALLBACK, IllegalStateException("boom"))
        assertEquals(listOf("f"), disabled)
        assertTrue(logs.any { "diagnostics_write_failed" in it })
    }

    @Test fun `very long traces and messages are bounded`() {
        val error = IllegalStateException("m".repeat(10_000))
        recorder.report("f", FailurePhase.INSTALL, error)
        drain()
        val record = store.read().single()
        assertTrue(record.message!!.length <= 2_000)
        assertTrue(record.stackTrace.length <= 16_000)
    }
}
