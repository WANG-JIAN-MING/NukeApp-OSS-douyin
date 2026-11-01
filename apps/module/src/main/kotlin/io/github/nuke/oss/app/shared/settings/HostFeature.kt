package io.github.nuke.oss.app.shared.settings

import io.github.nuke.oss.core.FeatureState

// One host feature as seen by the settings screen: the stored switch and the state in the running host process.
data class HostFeature(
    val id: String,
    val configured: Boolean,
    val state: FeatureState?,
    val reason: String? = null,
    val options: List<OptionEntry> = emptyList(),
)

enum class FeatureDisplay { ACTIVE, OFF, ON_AFTER_RESTART, OFF_AFTER_RESTART, FAILED }

// Switches are read once when the host starts, so a changed switch is pending until the host restarts.
fun HostFeature.display(): FeatureDisplay {
    val running = state == FeatureState.ACTIVE
    return when {
        configured && state == FeatureState.FAILED -> FeatureDisplay.FAILED
        configured && running -> FeatureDisplay.ACTIVE
        configured -> FeatureDisplay.ON_AFTER_RESTART
        running -> FeatureDisplay.OFF_AFTER_RESTART
        else -> FeatureDisplay.OFF
    }
}
