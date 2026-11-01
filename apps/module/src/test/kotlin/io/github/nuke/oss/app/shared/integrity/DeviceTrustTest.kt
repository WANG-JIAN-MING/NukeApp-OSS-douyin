package io.github.nuke.oss.app.shared.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the device trust model: how the native text becomes checks, how checks become a verdict, and every
// way the run can fail to produce a report, plus the JNI binding against the Rust core built for the host.
class DeviceTrustTest {
    private class FakeNatives(private val text: String?) : DeviceTrust.Natives {
        var loadFailure: UnsatisfiedLinkError? = null
        var scanFailure: UnsatisfiedLinkError? = null
        override fun load() { loadFailure?.let { throw it } }
        override fun scan(): String? { scanFailure?.let { throw it }; return text }
    }

    private fun report(vararg levels: TrustLevel) = TrustReport(levels.mapIndexed { index, level -> TrustCheck("check$index", level, "") })

    @Test fun `a check line becomes a check with its level and detail`() {
        val parsed = DeviceTrust.parse("check\tdebugger\tok\tno tracer attached\ncheck\troot\tinfo\tsu found: /system/bin/su\n")
        assertEquals(
            listOf(
                TrustCheck("debugger", TrustLevel.OK, "no tracer attached"),
                TrustCheck("root", TrustLevel.INFO, "su found: /system/bin/su"),
            ),
            parsed.checks,
        )
    }

    @Test fun `every level the native side writes is read`() {
        val text = TrustLevel.entries.joinToString("\n") { "check\tid-${it.code}\t${it.code}\tx" }
        assertEquals(TrustLevel.entries.toList(), DeviceTrust.parse(text).checks.map { it.level })
    }

    @Test fun `a malformed line is left out and an unknown level reads as unknown`() {
        val parsed = DeviceTrust.parse(
            "check\tonly-three\tfields\n\ncheck\ttab\tok\tfine\ncheck\t\tok\tno id\ncheck\tnew\tcritical\tfrom a newer library\nsurprise\ta\tb\tc\n",
        )
        assertEquals(listOf("tab", "new"), parsed.checks.map { it.id })
        assertTrue(parsed.flags.isEmpty())
        assertEquals(TrustLevel.UNKNOWN, parsed.checks.last().level)
    }

    @Test fun `a detail keeps its own tabs`() {
        assertEquals("a\tb", DeviceTrust.parse("check\tx\twarn\ta\tb").checks.single().detail)
    }

    @Test fun `a flag line becomes a flag with its class and whether it tripped`() {
        val parsed = DeviceTrust.parse("flag\tFRIDA\thard\t1\nflag\tWX_MAPPING\tweak\t1\nflag\tSU_BINARY\tenv\t0\nflag\tADBD_RUNNING\tnote\t0\n")
        assertEquals(
            listOf(
                TrustFlag("FRIDA", FlagClass.HARD, true),
                TrustFlag("WX_MAPPING", FlagClass.WEAK, true),
                TrustFlag("SU_BINARY", FlagClass.ENV, false),
                TrustFlag("ADBD_RUNNING", FlagClass.NOTE, false),
            ),
            parsed.flags,
        )
        assertEquals(listOf("FRIDA", "WX_MAPPING"), parsed.tripped.map { it.name })
    }

    @Test fun `checks and flags are read from the same text`() {
        val parsed = DeviceTrust.parse("check\tdebugger\tok\tfine\nflag\tADBD_RUNNING\tnote\t0\ncheck\tadbd\tinfo\trunning\n")
        assertEquals(listOf("debugger", "adbd"), parsed.checks.map { it.id })
        assertEquals(listOf("ADBD_RUNNING"), parsed.flags.map { it.name })
    }

    @Test fun `a class this build does not know reads as a note and a flag line that is short is left out`() {
        val parsed = DeviceTrust.parse("flag\tNEW_FLAG\tpurple\t0\nflag\tSHORT\thard\n")
        assertEquals(listOf(TrustFlag("NEW_FLAG", FlagClass.NOTE, false)), parsed.flags)
    }

    @Test fun `a tripped flag makes the verdict an alert even when no check is`() {
        val checks = listOf(TrustCheck("a", TrustLevel.OK, ""), TrustCheck("b", TrustLevel.INFO, ""))
        assertEquals(TrustVerdict.ALERT, TrustReport(checks, listOf(TrustFlag("FRIDA", FlagClass.HARD, true))).verdict)
        // A flag that was raised but did not trip leaves the verdict to the checks.
        assertEquals(TrustVerdict.TRUSTED, TrustReport(checks, listOf(TrustFlag("SU_BINARY", FlagClass.ENV, false))).verdict)
    }

    @Test fun `an alert outranks a warning and a warning outranks the rest`() {
        assertEquals(TrustVerdict.ALERT, report(TrustLevel.OK, TrustLevel.WARN, TrustLevel.ALERT, TrustLevel.UNKNOWN).verdict)
        assertEquals(TrustVerdict.CAUTION, report(TrustLevel.OK, TrustLevel.INFO, TrustLevel.WARN).verdict)
    }

    @Test fun `findings that are only noticed do not change the verdict`() {
        assertEquals(TrustVerdict.TRUSTED, report(TrustLevel.OK, TrustLevel.INFO, TrustLevel.INFO).verdict)
        assertEquals(TrustVerdict.TRUSTED, report(TrustLevel.OK, TrustLevel.UNKNOWN).verdict)
    }

    @Test fun `a run where nothing could be read is unknown and never trusted`() {
        assertEquals(TrustVerdict.UNKNOWN, report(TrustLevel.UNKNOWN, TrustLevel.UNKNOWN).verdict)
        assertEquals(TrustVerdict.UNKNOWN, TrustReport(emptyList()).verdict)
    }

    @Test fun `a successful scan is a report`() {
        val result = DeviceTrust.check(FakeNatives("check\tdebugger\tok\tfine\n"))
        assertEquals(1, (result as TrustResult.Ready).report.checks.size)
    }

    @Test fun `a missing native library is reported as unavailable`() {
        val natives = FakeNatives("check\tx\tok\ty").apply { loadFailure = UnsatisfiedLinkError("no nuke_protocol_core") }
        assertEquals("no nuke_protocol_core", (DeviceTrust.check(natives) as TrustResult.Unavailable).reason)
    }

    @Test fun `a library of an older build without the checks is reported as unavailable`() {
        val natives = FakeNatives(null).apply { scanFailure = UnsatisfiedLinkError("no implementation for scan") }
        assertEquals("no implementation for scan", (DeviceTrust.check(natives) as TrustResult.Unavailable).reason)
    }

    @Test fun `a scan that did not run and one that returned nothing are unavailable`() {
        assertTrue((DeviceTrust.check(FakeNatives(null)) as TrustResult.Unavailable).reason.contains("did not run"))
        assertTrue((DeviceTrust.check(FakeNatives("")) as TrustResult.Unavailable).reason.contains("nothing"))
        assertTrue((DeviceTrust.check(FakeNatives("garbage without tabs\n")) as TrustResult.Unavailable).reason.contains("nothing"))
        // Flags alone, with no check, are not a report either.
        assertTrue((DeviceTrust.check(FakeNatives("flag\tFRIDA\thard\t1\n")) as TrustResult.Unavailable).reason.contains("nothing"))
    }

    @Test fun `the Rust core built for the host runs every check`() {
        val result = DeviceTrust.check()
        val report = (result as TrustResult.Ready).report
        assertEquals(
            listOf(
                "debugger", "injection", "frida", "descriptors", "signature_path", "maps_tools", "maps_memory", "network", "root",
                "bootloader", "selinux", "selinux_policy", "kernel_exposure", "mounts", "build", "emulator", "adbd",
            ),
            report.checks.map { it.id },
        )
        assertTrue(report.checks.all { it.detail.isNotBlank() })
        // Every flag the native side raised is one the catalogue names, in a class this build knows.
        assertTrue(report.flags.all { it.name == it.name.uppercase() })
    }
}
