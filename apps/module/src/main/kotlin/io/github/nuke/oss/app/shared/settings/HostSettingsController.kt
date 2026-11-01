package io.github.nuke.oss.app.shared.settings

import io.github.nuke.oss.core.FeatureStatus

interface FeatureSwitches {
    fun read(featureId: String, default: Boolean): Boolean
    fun write(featureId: String, enabled: Boolean)
}

internal class FeatureDefault(val id: String, val default: Boolean)

// Joins stored switches and options with the running feature states. Writes only change storage; the runtime reads
// switches and options once at startup, so a write never installs or removes a Hook in the current process.
internal class HostSettingsController(
    private val features: List<FeatureDefault>,
    private val switches: FeatureSwitches,
    private val configurable: List<FeatureOptions> = emptyList(),
    private val optionStore: OptionStore? = null,
    private val snapshot: () -> List<FeatureStatus>,
) {
    init {
        require(configurable.isEmpty() || optionStore != null) { "Features with options need an option store" }
        for (feature in configurable) require(features.any { it.id == feature.featureId }) { "Options for unknown feature ${feature.featureId}" }
    }

    fun entries(): List<HostFeature> {
        val states = snapshot().associateBy { it.id }
        return features.map { feature ->
            val status = states[feature.id]
            HostFeature(feature.id, switches.read(feature.id, feature.default), status?.state, status?.reason, optionEntries(feature.id))
        }
    }

    fun set(featureId: String, enabled: Boolean): List<HostFeature> {
        require(features.any { it.id == featureId }) { "Unknown host feature: $featureId" }
        switches.write(featureId, enabled)
        return entries()
    }

    // The value must already be of the option's type; the stored form is the normalized one.
    fun setOption(featureId: String, key: String, value: Any): List<HostFeature> {
        val feature = configurable.firstOrNull { it.featureId == featureId } ?: throw IllegalArgumentException("Unknown feature with options: $featureId")
        val option = feature.options.firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown option $key of $featureId")
        checkNotNull(optionStore).write(featureId, key, option.encode(option.normalize(value)))
        return entries()
    }

    private fun optionEntries(featureId: String): List<OptionEntry> {
        val feature = configurable.firstOrNull { it.featureId == featureId } ?: return emptyList()
        val saved = checkNotNull(optionStore).load(featureId, feature.options)
        return feature.options.map { OptionEntry(it, saved[it], feature.running[it]) }
    }
}
