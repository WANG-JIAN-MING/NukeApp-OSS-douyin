package io.github.nuke.oss.app.tiktok.feature.chat.streak

import java.time.Instant
import java.time.ZoneId

// What each daily message is: fixed text, a sentence from a Hitokoto-style API with the text as fallback, or the
// host's own interactive streak emoji, the one its streak shortcut sends.
internal enum class StreakMode { TEXT, HITOKOTO, INTERACTIVE }

// The schedule as the options describe it. The revision identifies one set of option values, so the journal can tell
// a new plan from the one it already ran; friend IDs are kept in canonical decimal form.
internal data class StreakPlan(
    val friends: Set<String>,
    val mode: StreakMode,
    val text: String,
    val hitokotoUrl: String,
    val hour: Int,
    val minute: Int,
    val zoneId: String,
) {
    val revision: String = listOf(friends.sorted().joinToString(","), mode.name, text, hitokotoUrl, hour, minute, zoneId)
        .joinToString("\u0000").hashCode().toUInt().toString(16)

    fun validate() {
        require(friends.size in 1..MAX_FRIENDS && friends.all { (it.toLongOrNull() ?: 0) > 0 }) { "Pick 1 to $MAX_FRIENDS friends" }
        if (mode != StreakMode.INTERACTIVE) require(text.isNotBlank() && text.length <= MAX_TEXT) { "The message must have 1 to $MAX_TEXT characters" }
        if (mode == StreakMode.HITOKOTO) require(HitokotoClient.isValidUrl(hitokotoUrl)) { "The sentence API address is not a valid HTTP or HTTPS URL" }
        require(hour in 0..23 && minute in 0..59) { "Invalid daily time" }
        ZoneId.of(zoneId)
    }

    // The send time on the local calendar day of now, and that day's number.
    fun todayAt(now: Long): Long {
        val zone = ZoneId.of(zoneId)
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }

    fun tomorrowAt(now: Long): Long {
        val zone = ZoneId.of(zoneId)
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }

    fun dayOf(now: Long): Long = Instant.ofEpochMilli(now).atZone(ZoneId.of(zoneId)).toLocalDate().toEpochDay()

    companion object {
        const val MAX_FRIENDS = 100
        const val MAX_TEXT = 500

        fun canonical(uid: String): String? = uid.trim().toLongOrNull()?.takeIf { it > 0 }?.toString()
    }
}

// CLAIMED is written before a send starts; a claim left by a process that ended is read back as UNKNOWN and never
// retried, because the host may already have sent it.
internal enum class StreakStatus { CLAIMED, ACKNOWLEDGED, FAILED, UNKNOWN }

internal data class StreakAttempt(
    val id: String,
    val uid: String,
    val day: Long,
    val startedAt: Long,
    val status: StreakStatus = StreakStatus.CLAIMED,
    val mode: StreakMode,
    val usedFallback: Boolean = false,
    val detail: String = "",
    val clientMessageId: String? = null,
    val serverMessageId: Long? = null,
)

// The message chosen for one day, saved before the first friend is claimed so every friend gets the same one.
internal data class StreakDailyMessage(val day: Long, val revision: String, val text: String, val usedFallback: Boolean, val detail: String)

// The journal of one account. revision and savedAt record when the current plan was first seen: a plan first seen
// after that day's send time starts the next day. lastAttemptDays is the guard against a second send on one day.
internal data class StreakState(
    val account: String,
    val revision: String? = null,
    val savedAt: Long = 0,
    val lastAttemptDays: Map<String, Long> = emptyMap(),
    val history: List<StreakAttempt> = emptyList(),
    val daily: StreakDailyMessage? = null,
)

internal data class StreakOutcome(
    val status: StreakStatus,
    val detail: String = "",
    val clientMessageId: String? = null,
    val serverMessageId: Long? = null,
)

// The journal's stored form: one record per line, fields separated by tabs, with tab, line break and backslash escaped
// inside text. A journal that does not read back is an error, never an empty state, so a damaged file cannot lead to a
// second send on a day that already had one.
internal object StreakCodec {
    private const val VERSION = "nuke-streak 1"

    fun encode(state: StreakState): String = buildString {
        appendLine(VERSION)
        line("account", state.account)
        state.revision?.let { line("revision", it, state.savedAt.toString()) }
        for ((uid, day) in state.lastAttemptDays.toSortedMap()) line("day", uid, day.toString())
        state.daily?.let { line("daily", it.day.toString(), it.revision, it.usedFallback.toString(), it.text, it.detail) }
        for (attempt in state.history) line(
            "attempt", attempt.id, attempt.uid, attempt.day.toString(), attempt.startedAt.toString(), attempt.status.name,
            attempt.mode.name, attempt.usedFallback.toString(), attempt.clientMessageId ?: "", attempt.serverMessageId?.toString() ?: "",
            attempt.detail,
        )
    }

    fun decode(text: String): StreakState {
        val lines = text.split('\n').filter { it.isNotEmpty() }
        require(lines.firstOrNull() == VERSION) { "Not a streak journal" }
        var state: StreakState? = null
        for (line in lines.drop(1)) {
            val fields = line.split('\t').map(::unescape)
            val current = state
            state = when (fields[0]) {
                "account" -> { require(current == null && fields.size == 2); StreakState(fields[1]) }
                "revision" -> { require(fields.size == 3); current!!.copy(revision = fields[1], savedAt = fields[2].toLong()) }
                "day" -> { require(fields.size == 3); current!!.copy(lastAttemptDays = current.lastAttemptDays + (fields[1] to fields[2].toLong())) }
                "daily" -> {
                    require(fields.size == 6)
                    current!!.copy(daily = StreakDailyMessage(fields[1].toLong(), fields[2], fields[4], fields[3].toBooleanStrict(), fields[5]))
                }
                "attempt" -> {
                    require(fields.size == 11)
                    current!!.copy(history = current.history + StreakAttempt(
                        id = fields[1], uid = fields[2], day = fields[3].toLong(), startedAt = fields[4].toLong(),
                        status = StreakStatus.valueOf(fields[5]), mode = StreakMode.valueOf(fields[6]), usedFallback = fields[7].toBooleanStrict(),
                        clientMessageId = fields[8].ifEmpty { null }, serverMessageId = fields[9].ifEmpty { null }?.toLong(), detail = fields[10],
                    ))
                }
                else -> error("Unknown journal record ${fields[0]}")
            }
        }
        return requireNotNull(state) { "The journal names no account" }
    }

    private fun StringBuilder.line(vararg fields: String) = appendLine(fields.joinToString("\t", transform = ::escape))

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c != '\\') { out.append(c); continue }
            require(i < value.length) { "Dangling escape" }
            out.append(when (val next = value[i++]) {
                '\\' -> '\\'; 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'
                else -> error("Unknown escape \\$next")
            })
        }
        return out.toString()
    }
}
