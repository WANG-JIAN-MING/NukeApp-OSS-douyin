package io.github.nuke.oss.ui.hostsettings

import io.github.nuke.oss.app.shared.diagnostics.FailurePhases
import io.github.nuke.oss.app.shared.diagnostics.FailureRecord
import io.github.nuke.oss.app.shared.settings.HostFeature
import io.github.nuke.oss.core.FeatureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleDebugTest {
    private val record = FailureRecord("f", FailurePhases.AFTER_CALLBACK, "java.lang.IllegalStateException", "boom", "at x", 0)

    @Test fun `a failure record or a failed load locks the feature`() {
        assertEquals(DebugStatus.LOCKED, debugStatus(HostFeature("f", true, FeatureState.DISABLED), record))
        assertEquals(DebugStatus.LOCKED, debugStatus(HostFeature("f", false, FeatureState.DISABLED), record))
        assertEquals(DebugStatus.LOCKED, debugStatus(HostFeature("f", true, FeatureState.FAILED, "gone"), null))
    }

    @Test fun `a feature switched on but not running is unavailable`() {
        assertEquals(DebugStatus.UNAVAILABLE, debugStatus(HostFeature("f", true, FeatureState.DISABLED), null))
    }

    @Test fun `running as switched is normal, on or off`() {
        assertEquals(DebugStatus.NORMAL, debugStatus(HostFeature("f", true, FeatureState.ACTIVE), null))
        assertEquals(DebugStatus.NORMAL, debugStatus(HostFeature("f", false, FeatureState.DISABLED), null))
        assertEquals(DebugStatus.NORMAL, debugStatus(HostFeature("f", false, FeatureState.ACTIVE), null))
    }

    @Test fun `search covers the name, ID, reason and the recorded error`() {
        val item = DebugItem(HostFeature("CleanMode", true, FeatureState.DISABLED), "Clean mode", "Video", DebugStatus.LOCKED, "Callback failed", record)
        assertTrue(item.matches("clean"))
        assertTrue(item.matches("CleanMode"))
        assertTrue(item.matches("IllegalState"))
        assertTrue(item.matches("boom"))
        assertFalse(item.matches("nothing"))
    }

    @Test fun `the detail text carries the full report`() {
        val item = DebugItem(HostFeature("CleanMode", true, FeatureState.DISABLED), "Clean mode", "Video", DebugStatus.LOCKED, "Callback failed", record)
        val text = item.detailText(safeMode = false, host = "com.example")
        assertTrue(text.contains("ID: CleanMode"))
        assertTrue(text.contains("Status: LOCKED"))
        assertTrue(text.contains("Phase: after_callback"))
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.endsWith("at x"))
    }
}
