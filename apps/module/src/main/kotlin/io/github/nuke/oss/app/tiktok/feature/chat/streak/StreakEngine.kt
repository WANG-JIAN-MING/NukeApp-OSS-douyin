package io.github.nuke.oss.app.tiktok.feature.chat.streak

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal interface StreakStore {
    fun read(): StreakState
    // Must be durable before it returns: no message is sent before its claim is on disk.
    fun write(state: StreakState)
}

// One journal file per account in the host's private storage, replaced atomically and read back after each write.
internal class StreakFileStore(private val file: File, private val account: String) : StreakStore {
    override fun read(): StreakState {
        if (!file.exists()) return StreakState(account)
        val state = StreakCodec.decode(file.readText())
        check(state.account == account) { "The streak journal belongs to another account" }
        return state
    }

    override fun write(state: StreakState) {
        require(state.account == account)
        val directory = checkNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the streak journal directory" }
        val encoded = StreakCodec.encode(state)
        val temporary = File(directory, file.name + ".new")
        FileOutputStream(temporary).use { output ->
            output.write(encoded.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        check(file.readText() == encoded) { "The streak journal did not persist" }
    }
}

internal class PreparedMessage(val text: String, val usedFallback: Boolean = false, val detail: String = "")

// Runs one account's plan, the way NukeApp's AutoStreakEngine does: every friend at most once per local calendar day,
// starting at the send time. A plan first seen after that day's time starts the next day, and a late start only
// catches up the current day. Each friend is claimed in the journal before the send starts, and a send whose result
// is not known is never retried, because the host may already have delivered it.
// Calls come from one worker thread; snapshot may be read from any thread.
internal class StreakEngine(private val account: String, private val store: StreakStore) {
    @Volatile private var state: StreakState = store.read()

    init {
        require((account.toLongOrNull() ?: 0) > 0 && state.account == account) { "Streak journal account mismatch" }
        if (state.history.any { it.status == StreakStatus.CLAIMED }) persist(state.copy(history = state.history.map {
            if (it.status == StreakStatus.CLAIMED) it.copy(status = StreakStatus.UNKNOWN, detail = "The process ended before the result was recorded")
            else it
        }))
    }

    fun snapshot(): StreakState = state

    // Records a plan the journal has not run yet; its day starts counting from now.
    @Synchronized fun adopt(plan: StreakPlan, now: Long) {
        plan.validate()
        if (state.revision != plan.revision) persist(state.copy(revision = plan.revision, savedAt = now, daily = null))
    }

    @Synchronized fun nextWakeAt(plan: StreakPlan, now: Long): Long {
        val today = plan.todayAt(now)
        val day = plan.dayOf(now)
        val pending = plan.friends.any { (state.lastAttemptDays[it] ?: Long.MIN_VALUE) < day }
        if (today >= state.savedAt && pending) return maxOf(now + 1_000, today)
        return plan.tomorrowAt(now)
    }

    // allowed is checked before each friend and again by the send itself through canContinue, so turning the feature
    // off, an account change, midnight or a clock moved back stops the remaining friends.
    fun tick(
        plan: StreakPlan,
        now: Long,
        allowed: () -> Boolean,
        clock: () -> Long,
        prepare: (StreakPlan) -> PreparedMessage,
        send: (uid: String, text: String, canContinue: () -> Boolean) -> StreakOutcome,
    ) {
        if (state.revision != plan.revision) return
        val at = plan.todayAt(now)
        val day = plan.dayOf(now)
        if (now < at || at < state.savedAt || !allowed() || plan.friends.none { (state.lastAttemptDays[it] ?: Long.MIN_VALUE) < day }) return
        val message = state.daily?.takeIf { it.day == day && it.revision == plan.revision && plan.mode != StreakMode.INTERACTIVE }
            ?: run {
                val prepared = prepare(plan)
                require(prepared.text.isNotBlank() && prepared.text.length <= StreakPlan.MAX_TEXT) { "The prepared message has no valid text" }
                val observed = clock()
                if (!allowed() || plan.dayOf(observed) != day || observed < at) return
                StreakDailyMessage(day, plan.revision, prepared.text, prepared.usedFallback, prepared.detail.take(240)).also {
                    synchronized(this) {
                        if (state.revision != plan.revision) return
                        persist(state.copy(daily = it))
                    }
                }
            }
        for (uid in plan.friends.sorted()) {
            if (!allowed()) return
            val observed = clock()
            if (plan.dayOf(observed) != day || observed < at) return
            val attempt = synchronized(this) {
                if (state.revision != plan.revision || (state.lastAttemptDays[uid] ?: Long.MIN_VALUE) >= day) null
                else StreakAttempt(UUID.randomUUID().toString(), uid, day, observed, mode = plan.mode,
                    usedFallback = message.usedFallback, detail = message.detail).also {
                    persist(state.copy(lastAttemptDays = state.lastAttemptDays + (uid to day), history = (listOf(it) + state.history).take(MAX_HISTORY)))
                }
            } ?: continue
            fun canContinue(): Boolean {
                val current = clock()
                return allowed() && state.revision == plan.revision && plan.dayOf(current) == day && current >= at
            }
            val outcome = try {
                if (canContinue()) send(uid, message.text, ::canContinue) else StreakOutcome(StreakStatus.UNKNOWN, "Stopped before the send")
            } catch (error: InterruptedException) {
                finish(attempt, StreakOutcome(StreakStatus.UNKNOWN, "Stopped while waiting; the host result is unknown"))
                Thread.currentThread().interrupt()
                return
            } catch (error: RuntimeException) {
                // The host may have started the send before it threw, so this day is not retried.
                StreakOutcome(StreakStatus.UNKNOWN, (error.message ?: error.javaClass.simpleName).take(500))
            }
            require(outcome.status != StreakStatus.CLAIMED)
            finish(attempt, outcome)
        }
    }

    @Synchronized private fun finish(attempt: StreakAttempt, outcome: StreakOutcome) {
        persist(state.copy(history = state.history.map {
            if (it.id != attempt.id) it else it.copy(
                status = outcome.status,
                detail = listOf(attempt.detail, outcome.detail).filter(String::isNotBlank).joinToString("; ").take(500),
                clientMessageId = outcome.clientMessageId,
                serverMessageId = outcome.serverMessageId,
            )
        }))
    }

    private fun persist(next: StreakState) {
        store.write(next)
        state = next
    }

    private companion object {
        const val MAX_HISTORY = 200
    }
}
