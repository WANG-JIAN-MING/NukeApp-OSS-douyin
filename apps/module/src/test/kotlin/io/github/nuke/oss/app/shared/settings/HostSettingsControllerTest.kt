package io.github.nuke.oss.app.shared.settings

import io.github.nuke.oss.core.FeatureState
import io.github.nuke.oss.core.FeatureStatus
import org.junit.Assert.*
import org.junit.Test

class HostSettingsControllerTest {
    private val stored = LinkedHashMap<String, Boolean>()
    private val switches = object : FeatureSwitches {
        override fun read(featureId: String, default: Boolean) = stored[featureId] ?: default
        override fun write(featureId: String, enabled: Boolean) { stored[featureId] = enabled }
    }
    private var running = listOf(
        FeatureStatus("a", FeatureState.ACTIVE),
        FeatureStatus("b", FeatureState.DISABLED),
        FeatureStatus("c", FeatureState.FAILED, "found 0"),
    )
    private val controller = HostSettingsController(
        listOf(FeatureDefault("a", true), FeatureDefault("b", false), FeatureDefault("c", true)), switches,
    ) { running }

    @Test fun `entries join stored switches, defaults and running states`() {
        assertEquals(listOf(
            HostFeature("a", true, FeatureState.ACTIVE),
            HostFeature("b", false, FeatureState.DISABLED),
            HostFeature("c", true, FeatureState.FAILED, "found 0"),
        ), controller.entries())
    }

    @Test fun `a write is stored and reported as pending, not active`() {
        val after = controller.set("b", true).single { it.id == "b" }
        assertEquals(mapOf("b" to true), stored)
        assertEquals(FeatureDisplay.ON_AFTER_RESTART, after.display())
        val off = controller.set("a", false).single { it.id == "a" }
        assertEquals(FeatureDisplay.OFF_AFTER_RESTART, off.display())
    }

    @Test fun `unknown features are rejected without a write`() {
        assertThrows(IllegalArgumentException::class.java) { controller.set("SettingsEntry", false) }
        assertTrue(stored.isEmpty())
    }

    @Test fun `a feature missing from the snapshot reads as not running`() {
        running = emptyList()
        assertEquals(FeatureDisplay.ON_AFTER_RESTART, controller.entries().first().display())
    }

    @Test fun `display separates configured intent from the running state`() {
        fun show(configured: Boolean, state: FeatureState?) = HostFeature("x", configured, state).display()
        assertEquals(FeatureDisplay.ACTIVE, show(true, FeatureState.ACTIVE))
        assertEquals(FeatureDisplay.FAILED, show(true, FeatureState.FAILED))
        assertEquals(FeatureDisplay.ON_AFTER_RESTART, show(true, FeatureState.DISABLED))
        assertEquals(FeatureDisplay.ON_AFTER_RESTART, show(true, FeatureState.INSTALLING))
        assertEquals(FeatureDisplay.ON_AFTER_RESTART, show(true, null))
        assertEquals(FeatureDisplay.OFF_AFTER_RESTART, show(false, FeatureState.ACTIVE))
        assertEquals(FeatureDisplay.OFF, show(false, FeatureState.FAILED))
        assertEquals(FeatureDisplay.OFF, show(false, FeatureState.CLOSED))
    }
}
