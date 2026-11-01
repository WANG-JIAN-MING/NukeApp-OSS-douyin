package io.github.nuke.oss.app.shared.environment

import io.github.nuke.oss.app.shared.dex.DexStatus
import io.github.nuke.oss.runtime.FrameworkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentInfoTest {
    private val device = DeviceFacts("Xiaomi", "Xiaomi", "23127PN0CC", "houji", "qcom", "Qualcomm SM8650", listOf("arm64-v8a", "armeabi-v7a"), true)
    private val system = SystemFacts("16", 36, "REL", "2026-09-05", "OS3.0.1.0", "Xiaomi/houji/houji:16/BP2A/1:user/release-keys", "user/release-keys", "6.1.99", "zh-CN")
    private val host = PackageFacts("com.ss.android.ugc.aweme", "40.8.0", 400800, 35)
    private val loaded = LoadedModule("io.github.nuke.oss", "0.1.0-dev", 4, "0.1.0-dev", "gen-1")

    private fun build(
        loaderId: String = "libxposed102",
        framework: FrameworkInfo? = FrameworkInfo("LSPosed", "1.10.2", 7365, 102),
        installed: PackageFacts? = PackageFacts("io.github.nuke.oss", "0.1.0-dev", 4, 36),
        dex: DexStatus? = DexStatus(224, 220, 3, 1),
    ) = EnvironmentBuilder.build(device, system, loaderId, framework, host, ProcessFacts("com.ss.android.ugc.aweme", 1234), loaded, installed, dex)

    private fun text(info: EnvironmentInfo = build()) = info.render()

    @Test fun `the report names the device, Android, hook, host and module`() {
        val text = text()
        assertEquals(listOf("Device", "Android", "Hook", "Host", "Module", "Dex"), build().sections.map { it.title })
        assertTrue(text.contains("Brand: Xiaomi"))
        assertTrue(text.contains("Model: 23127PN0CC"))
        assertTrue(text.contains("ABIs: arm64-v8a, armeabi-v7a"))
        assertTrue(text.contains("Process bitness: 64-bit"))
        assertTrue(text.contains("Version: 16 (API 36)"))
        assertTrue(text.contains("Security patch: 2026-09-05"))
        assertTrue(text.contains("Kernel: 6.1.99"))
        assertTrue(text.contains("Package: com.ss.android.ugc.aweme"))
        assertTrue(text.contains("Version: 40.8.0 (400800)"))
        assertTrue(text.contains("Process: com.ss.android.ugc.aweme (pid 1234)"))
        assertTrue(text.contains("Running version: 0.1.0-dev (4)"))
        assertTrue(text.contains("Installed version: 0.1.0-dev (4)"))
        assertTrue(text.contains("Generation: gen-1"))
    }

    @Test fun `each entry is named as its mode and the framework with its own version`() {
        assertTrue(text(build("libxposed102")).contains("Mode: Xposed (libxposed API 102)"))
        assertTrue(text(build("libxposed102")).contains("Framework: LSPosed 1.10.2 (7365)"))
        assertTrue(text(build("libxposed102")).contains("Framework API: 102"))
        assertTrue(text(build("xposed82", FrameworkInfo("LSPosed", apiVersion = 93))).contains("Mode: Xposed (legacy API 82)"))
        assertTrue(text(build("xposed82", FrameworkInfo("LSPosed", apiVersion = 93))).contains("Framework: LSPosed\n"))
        assertTrue(text(build("zygisk", FrameworkInfo("Zygisk"))).contains("Mode: Zygisk"))
        assertTrue(text(build("zygisk", FrameworkInfo("Zygisk"))).contains("Entry: zygisk"))
    }

    @Test fun `what is not known says so instead of being left out where it matters`() {
        val text = text(build(framework = null, installed = null, dex = null))
        assertTrue(text.contains("Framework: N/A"))
        assertTrue(text.contains("Installed version: N/A"))
        assertFalse(text.contains("[Dex]"))
        assertFalse(text.contains("Framework API"))
    }

    @Test fun `a running module that is not the installed one shows both versions`() {
        val text = text(build(installed = PackageFacts("io.github.nuke.oss", "0.2.0", 5, 36)))
        assertTrue(text.contains("Running version: 0.1.0-dev (4)"))
        assertTrue(text.contains("Installed version: 0.2.0 (5)"))
    }

    @Test fun `a preview Android shows its codename and blank facts are left out`() {
        val info = EnvironmentBuilder.build(
            device.copy(soc = null), system.copy(codename = "Baklava", securityPatch = " ", fingerprint = null),
            "zygisk", null, null, ProcessFacts("p", 1), loaded, null, null,
        )
        val text = info.render()
        assertTrue(text.contains("Version: 16 (API 36, Baklava)"))
        assertFalse(text.contains("SoC"))
        assertFalse(text.contains("Security patch"))
        assertFalse(text.contains("Fingerprint"))
    }

    @Test fun `the Dex section counts the queries`() {
        val text = text()
        assertTrue(text.contains("Queries: 224"))
        assertTrue(text.contains("Located: 220"))
        assertTrue(text.contains("Failed: 3"))
        assertTrue(text.contains("Pending: 1"))
    }

    @Test fun `sections render with a title line and are separated by a blank line`() {
        val rendered = EnvironmentInfo(listOf(EnvironmentSection("A", listOf("x" to "1")), EnvironmentSection("B", listOf("y" to "2")))).render()
        assertEquals("[A]\nx: 1\n\n[B]\ny: 2\n", rendered)
    }
}
