package io.github.nuke.oss.app.shared.settings

// One thing a feature did, for its options dialog: when it started, who it was for (a user ID for a chat), the option
// choice it ran with, whether it fell back to the backup content, and the host's answer.
internal data class ActivityRecord(
    val at: Long,
    val target: String,
    val status: ActivityStatus,
    val choice: String,
    val usedFallback: Boolean,
    val detail: String,
)

// PENDING: started and not finished. DONE: the host confirmed it. FAILED: the host refused it. UNKNOWN: it may or may
// not have happened, and is never retried.
internal enum class ActivityStatus { PENDING, DONE, FAILED, UNKNOWN }

// What a feature has done lately, newest first, read for the settings screen. Blocking: callers run it off the main
// thread. A feature that keeps no record reads as empty; a record that exists but does not read throws, so the
// dialog can say why.
internal fun interface FeatureActivity {
    fun read(featureId: String): List<ActivityRecord>
}
