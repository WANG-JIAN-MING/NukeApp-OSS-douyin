package io.github.nuke.oss.ui.hostsettings

import io.github.nuke.oss.app.shared.diagnostics.FailurePhases
import io.github.nuke.oss.app.shared.diagnostics.FailureRecord
import io.github.nuke.oss.app.shared.environment.EnvironmentInfo
import io.github.nuke.oss.app.shared.environment.EnvironmentSection
import io.github.nuke.oss.app.shared.settings.HostFeature
import io.github.nuke.oss.core.FeatureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureReportTest {
    private val late = FailureRecord("Late", FailurePhases.AFTER_CALLBACK, "java.lang.IllegalStateException", "boom", "at a.b(C.java:1)", 2_000_000_000_000)
    private val early = FailureRecord("Early", FailurePhases.DEX_FIND, "io.github.nuke.oss.app.shared.dex.DexQueryUnavailable", null, "trace", 1_000_000_000_000)

    private val environment = EnvironmentInfo(listOf(EnvironmentSection("Host", listOf("Package" to "com.ss.android.ugc.aweme")), EnvironmentSection("Module", listOf("Running version" to "1.2.3 (4)"))))

    private fun report(features: List<HostFeature>, failures: List<FailureRecord>) =
        FailureReport.build(environment, features, failures.associateBy { it.featureId }, safeMode = false, generatedAt = 0)

    @Test fun `every record is listed oldest first with the feature's own state`() {
        val text = report(listOf(HostFeature("Late", true, FeatureState.DISABLED), HostFeature("Early", false, FeatureState.DISABLED)), listOf(late, early))
        assertTrue(text.lineSequence().first() == "NukeApp-OSS failure report")
        // The environment comes before the first failure, so a reader sees where it ran first.
        assertTrue(text.indexOf("[Host]") in 0 until text.indexOf("=== 1/2 Early ==="))
        assertTrue(text.contains("Package: com.ss.android.ugc.aweme"))
        assertTrue(text.contains("Running version: 1.2.3 (4)"))
        assertTrue(text.contains("Failures: 2"))
        assertTrue(text.indexOf("=== 1/2 Early ===") in 0 until text.indexOf("=== 2/2 Late ==="))
        assertTrue(text.contains("Phase: dex_find"))
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("boom"))
        assertTrue(text.contains("at a.b(C.java:1)"))
        // A record without a message says so instead of leaving the field empty.
        assertTrue(text.substringAfter("=== 1/2 Early ===").substringBefore("=== 2/2").contains("Message:\nN/A"))
    }

    @Test fun `a feature that failed to run without a record is listed too`() {
        val features = listOf(HostFeature("Broken", true, FeatureState.FAILED, "no such method"), HostFeature("Fine", true, FeatureState.ACTIVE))
        val text = report(features, emptyList())
        assertTrue(text.contains("Failures: 1"))
        assertTrue(text.contains("=== 1/1 Broken ==="))
        assertTrue(text.contains("Runtime reason: no such method"))
        assertTrue(text.contains("Phase: N/A (no failure record)"))
        assertFalse(text.contains("Fine"))
    }

    @Test fun `a feature with a record is listed once, not again for its failed state`() {
        val features = listOf(HostFeature("Late", true, FeatureState.FAILED, "boom"))
        assertEquals(1, FailureReport.count(features, mapOf("Late" to late)))
        assertEquals(1, Regex("=== \\d+/1 Late ===").findAll(report(features, listOf(late))).count())
    }

    @Test fun `nothing to report counts zero`() {
        assertEquals(0, FailureReport.count(listOf(HostFeature("A", true, FeatureState.ACTIVE)), emptyMap()))
    }
}
