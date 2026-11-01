package io.github.nuke.oss.ui.hostsettings

import io.github.nuke.oss.app.shared.diagnostics.FailureRecord
import io.github.nuke.oss.app.shared.environment.EnvironmentInfo
import io.github.nuke.oss.app.shared.settings.FeatureDisplay
import io.github.nuke.oss.app.shared.settings.HostFeature
import io.github.nuke.oss.app.shared.settings.display
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Everything the debug page knows about what went wrong, as one text file for a bug report: the environment it ran in,
// every failure record, and every feature that failed to run in this process without leaving a record. English labels, like the detail dialog, so
// a report reads the same whatever language the user picked.
//
// TODO(log-sending): this report is only exported to a file the user picks. Sending detailed logs is to be built on the
// device trust report (app.shared.integrity.DeviceTrust), taken once at the moment of sending; see the note on TrustReport.
internal object FailureReport {
    fun build(
        environment: EnvironmentInfo,
        features: List<HostFeature>,
        failures: Map<String, FailureRecord>,
        safeMode: Boolean,
        generatedAt: Long,
    ): String {
        val byId = features.associateBy { it.id }
        val recorded = failures.values.sortedWith(compareBy<FailureRecord> { it.timestamp }.thenBy { it.featureId })
        val runtimeOnly = features.filter { it.id !in failures && it.display() == FeatureDisplay.FAILED }
        val total = recorded.size + runtimeOnly.size
        return buildString {
            appendLine("NukeApp-OSS failure report")
            appendLine("Generated: ${formatTime(generatedAt)}")
            appendLine("Safe mode: $safeMode")
            appendLine("Failures: $total")
            appendLine()
            append(environment.render())
            var number = 0
            for (record in recorded) {
                appendLine()
                appendLine("=== ${++number}/$total ${record.featureId} ===")
                appendFeature(byId[record.featureId])
                appendLine("Phase: ${record.phase}")
                appendLine("Time: ${formatTime(record.timestamp)}")
                appendLine()
                appendLine("Throwable:")
                appendLine(record.errorClass)
                appendLine()
                appendLine("Message:")
                appendLine(record.message ?: "N/A")
                appendLine()
                appendLine("Stack trace:")
                appendLine(record.stackTrace)
            }
            for (feature in runtimeOnly) {
                appendLine()
                appendLine("=== ${++number}/$total ${feature.id} ===")
                appendFeature(feature)
                appendLine("Phase: N/A (no failure record)")
            }
        }
    }

    private fun StringBuilder.appendFeature(feature: HostFeature?) {
        appendLine("Switch: ${feature?.let { if (it.configured) "on" else "off" } ?: "N/A"}")
        appendLine("Runtime state: ${feature?.state?.name ?: "N/A"}")
        appendLine("Runtime reason: ${feature?.reason ?: "N/A"}")
    }

    // The number of entries the report lists, for the confirmation shown after the export.
    fun count(features: List<HostFeature>, failures: Map<String, FailureRecord>): Int =
        failures.size + features.count { it.id !in failures && it.display() == FeatureDisplay.FAILED }

    private fun formatTime(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(millis))
}
