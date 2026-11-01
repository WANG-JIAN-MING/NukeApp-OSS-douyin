package io.github.nuke.oss.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivationTest {
    private val abis = setOf("arm64-v8a")
    private val douyin = "com.ss.android.ugc.aweme"
    private val fixture = "io.github.nuke.oss.fixture"
    private val hosts = listOf(InstalledHost(douyin, true, "arm64-v8a"), InstalledHost(fixture, false, null))

    private fun service(scope: Set<String>, targets: List<RunningTarget>? = emptyList()) =
        FrameworkService("LSPosed", "1.10.2", 7300, 102, scope, targets)

    @Test fun `nothing loaded the module so it is inactive and the scope stays unknown`() {
        val activation = activationOf(null, null, hosts, abis)
        assertEquals(ActivationLevel.INACTIVE, activation.level)
        assertNull(activation.hosts.first().inScope)
        assertNull(activation.hosts.first().running)
    }

    @Test fun `an XP82 framework loading the module activates it without a scope`() {
        val activation = activationOf("LSPosed", null, hosts, abis)
        assertEquals(ActivationLevel.ACTIVE, activation.level)
        assertNull(activation.hosts.first().inScope)
    }

    @Test fun `a service whose scope names no host leaves the module inactive`() {
        val activation = activationOf(null, service(setOf("com.example.other")), hosts, abis)
        assertEquals(ActivationLevel.INACTIVE, activation.level)
        assertEquals(false, activation.hosts.first().inScope)
    }

    @Test fun `a scoped host activates the module and its own processes are counted`() {
        val targets = listOf(
            RunningTarget(douyin, TargetState.CURRENT),
            RunningTarget("$douyin:miniapp0", TargetState.STALE),
            RunningTarget("$douyin.other", TargetState.CURRENT),
        )
        val activation = activationOf(null, service(setOf(douyin), targets), hosts, abis)
        assertEquals(ActivationLevel.ACTIVE, activation.level)
        val host = activation.hosts.first()
        assertEquals(true, host.inScope)
        assertEquals(2, host.running)
        assertEquals(1, host.restartNeeded)
        assertEquals(0, activation.hosts.last().running)
    }

    @Test fun `an older service reports no running targets rather than none`() {
        val activation = activationOf(null, service(setOf(douyin), targets = null), hosts, abis)
        assertNull(activation.hosts.first().running)
        assertNull(activation.hosts.first().restartNeeded)
    }

    @Test fun `an installed host on an unsupported ABI leaves an active module partial`() {
        val armHosts = listOf(InstalledHost(douyin, true, "armeabi-v7a"), InstalledHost(fixture, false, "x86"))
        assertEquals(ActivationLevel.PARTIAL, activationOf("LSPosed", null, armHosts, abis).level)
        assertEquals(ActivationLevel.INACTIVE, activationOf(null, null, armHosts, abis).level)
        // A host that is not installed does not count against the module.
        assertEquals(ActivationLevel.ACTIVE, activationOf("LSPosed", null, listOf(InstalledHost(fixture, false, "x86")), abis).level)
    }
}
