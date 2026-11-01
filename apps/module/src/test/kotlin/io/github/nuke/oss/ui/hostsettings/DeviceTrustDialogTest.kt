package io.github.nuke.oss.ui.hostsettings

import io.github.nuke.oss.app.shared.integrity.FlagClass
import io.github.nuke.oss.app.shared.integrity.TrustCheck
import io.github.nuke.oss.app.shared.integrity.TrustFlag
import io.github.nuke.oss.app.shared.integrity.TrustLevel
import io.github.nuke.oss.app.shared.integrity.TrustReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// The parts of the device trust dialog that are not drawing: the order of its rows and the label of each check.
class DeviceTrustDialogTest {
    private fun check(id: String, level: TrustLevel) = TrustCheck(id, level, "")

    @Test fun `the most serious findings come first and checks of one level keep their order`() {
        val report = TrustReport(
            listOf(
                check("a", TrustLevel.OK), check("b", TrustLevel.INFO), check("c", TrustLevel.ALERT),
                check("d", TrustLevel.UNKNOWN), check("e", TrustLevel.WARN), check("f", TrustLevel.INFO),
                check("g", TrustLevel.ALERT),
            ),
        )
        assertEquals(listOf("c", "g", "e", "b", "f", "d", "a"), trustRows(report).map { it.id })
    }

    @Test fun `flags are grouped by class with the hard ones first and empty classes left out`() {
        val report = TrustReport(
            emptyList(),
            listOf(
                TrustFlag("SU_BINARY", FlagClass.ENV, false), TrustFlag("FRIDA", FlagClass.HARD, true),
                TrustFlag("WX_MAPPING", FlagClass.WEAK, true), TrustFlag("DEBUGGER", FlagClass.HARD, true),
            ),
        )
        val groups = trustFlagGroups(report)
        assertEquals(listOf(FlagClass.HARD, FlagClass.WEAK, FlagClass.ENV), groups.map { it.first })
        assertEquals(listOf("FRIDA", "DEBUGGER"), groups.first().second.map { it.name })
        assertEquals(emptyList<Pair<FlagClass, List<TrustFlag>>>(), trustFlagGroups(TrustReport(emptyList())))
    }

    @Test fun `every check the native side writes has a label and an unknown one does not`() {
        val ids = listOf(
            "debugger", "injection", "frida", "descriptors", "signature_path", "maps_tools", "maps_memory", "network", "root",
            "bootloader", "selinux", "selinux_policy", "kernel_exposure", "mounts", "build", "emulator", "adbd",
        )
        for (id in ids) assertNotNull(id, trustCheckLabel(id))
        assertEquals(ids.size, ids.map { trustCheckLabel(it) }.toSet().size)
        assertNull(trustCheckLabel("from-a-newer-library"))
    }
}
