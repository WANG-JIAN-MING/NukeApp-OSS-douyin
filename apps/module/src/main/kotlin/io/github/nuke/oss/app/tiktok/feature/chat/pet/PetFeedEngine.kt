package io.github.nuke.oss.app.tiktok.feature.chat.pet

import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClaimNotDispatchedException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal enum class PetFeedStatus { FEEDING, CONFIRMED, SKIPPED, FAILED, UNKNOWN }

// The last feeding sent for one friend, kept apart from any display so nothing can forget it. actionEndAtMillis is the
// end of the account's food that period showed, and only ever grows; confirmedFeedEndAtMillis is the end of the food
// the service confirmed this feeding gave.
internal data class PetFeedGuard(
    val cycleKey: String,
    val status: PetFeedStatus,
    val actionEndAtMillis: Long,
    val attemptId: String,
    val updatedAt: Long,
    val confirmedFeedEndAtMillis: Long? = null,
)

internal data class PetFeedState(val account: String, val guards: Map<String, PetFeedGuard> = emptyMap())

// Friends to feed and which food. Read from the options when the host starts.
internal data class PetFeedPlan(val friends: List<String>, val policy: PetFeedPolicy) {
    fun validate() {
        policy.validate()
        require(friends.size <= MAX_FRIENDS && friends.all { (it.toLongOrNull() ?: 0) > 0 }) { "Select up to $MAX_FRIENDS valid friends" }
    }

    companion object {
        const val MAX_FRIENDS = 100
    }
}

internal interface PetFeedStore {
    fun read(): PetFeedState
    // Must be durable before it returns; a failed write must keep the feeding from being sent.
    fun write(state: PetFeedState)
}

// What one friend's check ended with, for the log; it never names the friend.
internal class PetFeedOutcome(val status: PetFeedStatus, val detail: String)

// The feeding journal of one account, ported from the legacy module's AutoPetFeedEngine. Before a feeding is sent its
// intent is written to disk as a FEEDING guard for that friend's food period, so a process killed after sending can
// never feed the same period again: on the next start every FEEDING guard becomes UNKNOWN, and an UNKNOWN or
// CONFIRMED guard allows another feeding only once the service shows a newer period of ours. A request refused before
// it was sent turns the guard FAILED, which allows a fresh try. Business codes the pet page names as rejections
// (no relation, already fed, food unavailable, price changed, no pet, balance too low) are FAILED too; every other
// error after the intent was saved is UNKNOWN. Calls come from one worker thread.
internal class PetFeedEngine(private val account: String, private val store: PetFeedStore, private val clock: () -> Long) {
    @Volatile private var state = store.read()

    init {
        require((account.toLongOrNull() ?: 0) > 0 && state.account == account) { "Pet feeding journal account mismatch" }
        require(state.guards.size <= MAX_GUARDS) { "Too many pet feeding guards" }
        require(state.guards.all { (uid, guard) ->
            (uid.toLongOrNull() ?: 0) > 0 && guard.cycleKey.isNotBlank() && guard.actionEndAtMillis >= 0 && guard.status != PetFeedStatus.SKIPPED &&
                (guard.confirmedFeedEndAtMillis == null || guard.confirmedFeedEndAtMillis > 0)
        }) { "Invalid pet feeding guard" }
        if (state.guards.values.any { it.status == PetFeedStatus.FEEDING }) persist(state.copy(guards = state.guards.mapValues { (_, guard) ->
            if (guard.status == PetFeedStatus.FEEDING) guard.copy(status = PetFeedStatus.UNKNOWN) else guard
        }))
    }

    fun snapshot(): PetFeedState = state

    // Checks every friend of the plan once. canContinue is asked before each step and right before a feeding is sent;
    // when it says no, the round ends without sending.
    fun tick(
        plan: PetFeedPlan,
        canContinue: () -> Boolean,
        inspect: (uid: String) -> PetFeedSnapshot,
        feed: (uid: String, cycleKey: String, beforeSubmit: (PetFood) -> Unit) -> PetFeedReceipt,
    ): List<PetFeedOutcome> {
        plan.validate()
        val outcomes = ArrayList<PetFeedOutcome>()
        for (uid in plan.friends) {
            if (!canContinue()) break
            val snapshot = try {
                inspect(uid)
            } catch (error: Exception) {
                // Reads change nothing; whatever failed, the next round reads again.
                outcomes += PetFeedOutcome(PetFeedStatus.FAILED, "State inspection failed: ${error.javaClass.simpleName}")
                continue
            }
            if (!canContinue()) break
            outcomes += check(uid, snapshot, plan.policy, canContinue, feed) ?: break
        }
        return outcomes
    }

    // Null when the round must stop.
    private fun check(
        uid: String,
        snapshot: PetFeedSnapshot,
        policy: PetFeedPolicy,
        canContinue: () -> Boolean,
        feed: (String, String, (PetFood) -> Unit) -> PetFeedReceipt,
    ): PetFeedOutcome? {
        if (snapshot.availability == PetFeedAvailability.STILL_FED) {
            return if (reconcile(uid, snapshot)) PetFeedOutcome(PetFeedStatus.CONFIRMED, "A newer own feeding reconciles the uncertain one")
            else PetFeedOutcome(PetFeedStatus.SKIPPED, snapshot.detail.ifBlank { "Own food is still active" })
        }
        if (snapshot.availability != PetFeedAvailability.READY) return PetFeedOutcome(PetFeedStatus.SKIPPED, snapshot.detail.ifBlank { snapshot.availability.name })
        val food = snapshot.food
        if (snapshot.cycleKey.isBlank() || snapshot.shortId.isBlank() || snapshot.selfSecUid.isBlank() || (snapshot.nextFeedAtMillis ?: 0) < 0 ||
            food == null || food.id.isBlank()) return PetFeedOutcome(PetFeedStatus.SKIPPED, "No verified feeding cycle or policy-selected food")
        if (!maySubmit(uid, snapshot)) return PetFeedOutcome(PetFeedStatus.SKIPPED, "Waiting for a newer period after ${state.guards[uid]?.status}")
        var attempt: PetFeedGuard? = null
        return try {
            val receipt = feed(uid, snapshot.cycleKey) { fresh ->
                check(attempt == null) { "The feeding callback ran twice" }
                if (!canContinue() || !maySubmit(uid, snapshot)) throw StoppedBeforeSubmit()
                requireFoodAllowed(policy, fresh)
                check(uid in state.guards || state.guards.size < MAX_GUARDS) { "The pet feeding journal is full" }
                val previous = state.guards[uid]
                // Clearing an expired food must not forget the last known end, or a late answer with that old food
                // would look like a new period.
                val boundary = maxOf(snapshot.nextFeedAtMillis ?: 0, previous?.actionEndAtMillis ?: 0, previous?.confirmedFeedEndAtMillis ?: 0)
                val now = clock()
                val pending = PetFeedGuard(snapshot.cycleKey, PetFeedStatus.FEEDING, boundary, UUID.randomUUID().toString(), now)
                persist(state.copy(guards = state.guards + (uid to pending)))
                attempt = pending
                // Writing to disk takes time; a stop in that time still keeps the request from being sent.
                if (!canContinue()) {
                    finish(uid, pending, PetFeedStatus.FAILED)
                    throw StoppedBeforeSubmit()
                }
            }
            val pending = attempt ?: return PetFeedOutcome(PetFeedStatus.FAILED, "The transport returned without sending a saved feeding")
            val matches = receipt.shortId == snapshot.shortId && receipt.cycleKey == snapshot.cycleKey
            val confirmed = matches && receipt.confirmed
            finish(uid, pending, if (confirmed) PetFeedStatus.CONFIRMED else PetFeedStatus.UNKNOWN, if (confirmed) receipt.nextFeedAtMillis else null)
            PetFeedOutcome(if (confirmed) PetFeedStatus.CONFIRMED else PetFeedStatus.UNKNOWN,
                if (!matches) "The receipt does not match the submitted conversation and period" else receipt.detail.ifBlank { "Feeding confirmed" })
        } catch (_: StoppedBeforeSubmit) {
            attempt?.let { finish(uid, it, PetFeedStatus.FAILED) }
            null
        } catch (error: ClaimNotDispatchedException) {
            attempt?.let { finish(uid, it, PetFeedStatus.FAILED) }
            PetFeedOutcome(PetFeedStatus.FAILED, "The request was not sent: ${error.message}")
        } catch (error: Exception) {
            // Any other failure once the intent is saved may have reached the service, so it stays uncertain.
            val pending = attempt ?: return PetFeedOutcome(PetFeedStatus.FAILED, "Stopped before sending: ${error.javaClass.simpleName}")
            val rejected = error is PetApiException && error.businessCode in REJECTIONS
            finish(uid, pending, if (rejected) PetFeedStatus.FAILED else PetFeedStatus.UNKNOWN)
            PetFeedOutcome(if (rejected) PetFeedStatus.FAILED else PetFeedStatus.UNKNOWN,
                if (rejected) "Rejected (${error.businessCode})" else "Feeding result is unknown: ${error.javaClass.simpleName}")
        }
    }

    private fun requireFoodAllowed(policy: PetFeedPolicy, food: PetFood) {
        policy.validate()
        require(food.id.isNotBlank() && food.price >= 0 && (!food.isFree || food.price == 0L)) { "Fresh food quote is invalid" }
        val selected = policy.foodId
        if (selected == null) require(food.isFree) { "Automatic selection permits only free food" }
        else require(food.id == selected) { "Fresh food does not match the selected food ID" }
        if (!food.isFree) {
            require(selected != null && policy.allowPaidFood) { "Paid food is not authorized" }
            require(food.price <= policy.maxPrice) { "Fresh food price exceeds the per-feeding limit" }
        }
    }

    // A timeout never allows another feeding, even when the service leaves out its own action; a confirmed food may
    // disappear after its known end.
    private fun maySubmit(uid: String, snapshot: PetFeedSnapshot): Boolean {
        val guard = state.guards[uid] ?: return true
        if (guard.status == PetFeedStatus.FAILED) return true
        val confirmedEnd = guard.confirmedFeedEndAtMillis
        if (guard.status == PetFeedStatus.CONFIRMED && confirmedEnd != null) {
            if (snapshot.nextFeedAtMillis == null) return (snapshot.serverTimeMillis ?: 0) > confirmedEnd
            if (snapshot.nextFeedAtMillis < confirmedEnd) return false
        }
        return snapshot.cycleKey != guard.cycleKey && (snapshot.nextFeedAtMillis ?: 0) > guard.actionEndAtMillis
    }

    // A newer food of ours on the service settles an uncertain feeding as done. Its own end becomes the next period.
    private fun reconcile(uid: String, snapshot: PetFeedSnapshot): Boolean {
        val guard = state.guards[uid] ?: return false
        if (guard.status != PetFeedStatus.UNKNOWN && guard.status != PetFeedStatus.FEEDING) return false
        if (snapshot.cycleKey.isBlank() || !maySubmit(uid, snapshot)) return false
        persist(state.copy(guards = state.guards + (uid to guard.copy(
            status = PetFeedStatus.CONFIRMED, confirmedFeedEndAtMillis = snapshot.nextFeedAtMillis, updatedAt = maxOf(clock(), guard.updatedAt),
        ))))
        return true
    }

    private fun finish(uid: String, attempt: PetFeedGuard, status: PetFeedStatus, confirmedEnd: Long? = null) {
        val guard = checkNotNull(state.guards[uid])
        check(guard.attemptId == attempt.attemptId) { "The feeding guard changed during the request" }
        // A refusal already recorded must not turn into uncertainty.
        if (guard.status == PetFeedStatus.FAILED && status == PetFeedStatus.UNKNOWN) return
        if (guard.status == status) return
        persist(state.copy(guards = state.guards + (uid to guard.copy(
            status = status, updatedAt = maxOf(clock(), attempt.updatedAt),
            confirmedFeedEndAtMillis = confirmedEnd?.takeIf { status == PetFeedStatus.CONFIRMED && it > guard.actionEndAtMillis },
        ))))
    }

    private fun persist(next: PetFeedState) {
        store.write(next)
        state = next
    }

    private class StoppedBeforeSubmit : IllegalStateException("Pet feeding stopped before dispatch")

    private companion object {
        const val MAX_GUARDS = 10_000
        // The pet page's own rejection codes, taken from the legacy module.
        val REJECTIONS = setOf(102104, 300001, 300002, 300003, 994008, 996001)
    }
}

// The journal as JSON. Anything unreadable throws, because an empty journal could feed a period twice.
internal object PetFeedJournal {
    fun encode(state: PetFeedState): String = MiniJson.write(linkedMapOf(
        "version" to 1L,
        "account" to state.account,
        "guards" to state.guards.mapValues { (_, guard) ->
            linkedMapOf(
                "cycle" to guard.cycleKey, "status" to guard.status.name, "actionEnd" to guard.actionEndAtMillis,
                "attempt" to guard.attemptId, "updated" to guard.updatedAt, "confirmedEnd" to guard.confirmedFeedEndAtMillis,
            )
        },
    ))

    fun decode(text: String): PetFeedState {
        val root = requireNotNull(MiniJson.obj(MiniJson.parse(text))) { "The pet feeding journal is not an object" }
        require(root["version"] == 1L) { "Unknown pet feeding journal version" }
        val guards = requireNotNull(MiniJson.obj(root["guards"])) { "The pet feeding journal has no guards" }.mapValues { (_, value) ->
            val guard = requireNotNull(MiniJson.obj(value)) { "A pet feeding guard is not an object" }
            PetFeedGuard(
                guard["cycle"] as String, PetFeedStatus.valueOf(guard["status"] as String), guard["actionEnd"] as Long,
                guard["attempt"] as String, guard["updated"] as Long, guard["confirmedEnd"] as Long?,
            )
        }
        return PetFeedState(root["account"] as String, guards)
    }
}

// One journal file per account in the host's private storage, replaced atomically and read back after each write.
internal class PetFeedFileStore(private val file: File, private val account: String) : PetFeedStore {
    override fun read(): PetFeedState {
        if (!file.exists()) return PetFeedState(account)
        val state = PetFeedJournal.decode(file.readText())
        check(state.account == account) { "The pet feeding journal belongs to another account" }
        return state
    }

    override fun write(state: PetFeedState) {
        require(state.account == account)
        val directory = checkNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the pet feeding journal directory" }
        val encoded = PetFeedJournal.encode(state)
        val temporary = File(directory, file.name + ".new")
        FileOutputStream(temporary).use { output ->
            output.write(encoded.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        check(file.readText() == encoded) { "The pet feeding journal did not persist" }
    }
}
