package io.github.nuke.oss.app.tiktok.feature.chat.claims

// A friend's private chat as the pet API addresses it: the signed-in account and the server's short conversation ID.
internal class ClosenessBinding(val account: Long, val shortId: String)

// A friend whose pet may hold claimable closeness; conversationId is the one-to-one chat with them.
internal class ClosenessTarget(val conversationId: String)

internal class ClosenessReward(val behaviorId: Long, val rewardTs: Long)

internal class ClosenessReport(
    val scanned: Int,
    val claimed: Int,
    val already: Int,
    val failed: Int,
    val unknown: Int,
    val unavailable: Int,
    val details: List<String>,
)

// The host's authenticated pet network chain. post is dispatched through the host's own signing and common
// parameters; beforeDispatch runs once, right before the request is handed over. A dispatched request that failed
// or timed out has an unknown outcome.
internal interface ClosenessTransport {
    fun accountId(): Long
    fun resolve(conversationId: String, account: Long): ClosenessBinding
    fun post(binding: ClosenessBinding, path: String, body: Map<String, Any?>, beforeDispatch: () -> Unit): Map<String, Any?>
}

// Decodes the pet home page's claimable closeness rewards; feeding, interaction and other pet APIs are not used.
internal object ClosenessCodec {
    const val READ_PATH = "/ttgame/social_pet/home/info"
    const val CLAIM_PATH = "/ttgame/social_pet/closeness/claim"
    private const val NO_PET = 994008
    private const val CLOSENESS_OFF = 996506

    fun readBody(): Map<String, Any?> = linkedMapOf("source" to "Home")

    fun claimBody(reward: ClosenessReward): Map<String, Any?> = linkedMapOf("behavior_id" to reward.behaviorId)

    fun code(root: Map<String, Any?>): Int = (root["code"] as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        ?: error("Closeness response has no valid business code")

    // No pet, or closeness not enabled, means nothing to claim; neither is turned on here.
    fun rewards(root: Map<String, Any?>): List<ClosenessReward> {
        val code = code(root)
        if (code == NO_PET || code == CLOSENESS_OFF) return emptyList()
        check(code == 0) { "Closeness inspection rejected ($code)" }
        val data = root["data"] as? Map<*, *> ?: error("Missing pet home data")
        val closeness = data["closeness"] ?: return emptyList()
        check(closeness is Map<*, *>) { "Invalid closeness state" }
        val behaviors = closeness["behavior_closeness"] ?: return emptyList()
        check(behaviors is List<*>) { "Invalid closeness rewards" }
        val rewards = behaviors.mapNotNull { value ->
            check(value is Map<*, *>) { "Invalid closeness behavior" }
            // Only a strict true counts as claimable.
            if (value["is_claimable"] != true) return@mapNotNull null
            val id = (value["behavior_id"] as? Long)?.takeIf { it > 0 } ?: error("Claimable reward has no valid behavior ID")
            val time = when (val stamp = value["reward_ts"]) {
                null -> 0L
                is Long -> stamp.takeIf { it >= 0 } ?: error("Invalid reward timestamp")
                else -> error("Invalid reward timestamp")
            }
            ClosenessReward(id, time)
        }
        check(rewards.groupBy { it.behaviorId }.values.all { group -> group.map { it.rewardTs }.distinct().size == 1 }) {
            "Conflicting closeness rewards"
        }
        return rewards.distinctBy { it.behaviorId }.sortedByDescending { it.rewardTs }
    }
}

// One explicit user action over the friends whose streak is positive. Each friend's rewards are read, then claimed one
// by one after reading the state again, keeping only a reward that is still claimable with an unchanged timestamp.
// A stop (feature off, account changed) ends the batch; a write that was dispatched and not confirmed ends it too,
// because no other pet request may overlap an unfinished write. Nothing is retried.
internal class ClosenessClaim(private val transport: ClosenessTransport) {
    private class Stopped(message: String) : IllegalStateException(message)

    fun claimAll(account: Long, targets: List<ClosenessTarget>, canSubmit: () -> Boolean): ClosenessReport {
        require(account > 0)
        var scanned = 0
        var claimed = 0
        var already = 0
        var failed = 0
        var unknown = 0
        var unavailable = 0
        val details = ArrayList<String>()
        fun detail(error: Exception) {
            if (details.size < MAX_DETAILS) details += (error.message ?: error.javaClass.simpleName).take(MAX_DETAIL_LENGTH)
        }
        fun guard() {
            if (!canSubmit()) throw Stopped("Closeness collection stopped")
            val current = try { transport.accountId() } catch (_: IllegalStateException) {
                throw Stopped("Current account unavailable; closeness collection stopped")
            }
            if (current != account) throw Stopped("Account changed; closeness collection stopped")
        }
        fun report() = ClosenessReport(scanned, claimed, already, failed, unknown, unavailable, details.take(MAX_DETAILS))
        fun read(binding: ClosenessBinding): List<ClosenessReward> {
            guard()
            val root = transport.post(binding, ClosenessCodec.READ_PATH, ClosenessCodec.readBody(), ::guard)
            guard()
            return ClosenessCodec.rewards(root)
        }
        val seen = HashSet<String>()
        try {
            guard()
            for (target in targets.distinctBy { it.conversationId }) {
                guard()
                val binding: ClosenessBinding
                val initial: List<ClosenessReward>
                try {
                    binding = transport.resolve(target.conversationId, account)
                    check(binding.account == account && (binding.shortId.toLongOrNull() ?: 0) > 0) { "Invalid private conversation" }
                    if (!seen.add(binding.shortId)) continue
                    initial = read(binding)
                } catch (stopped: Stopped) { throw stopped }
                catch (error: Exception) {
                    guard()
                    unavailable++
                    detail(error)
                    continue
                }
                scanned++
                for ((index, candidate) in initial.withIndex()) {
                    guard()
                    val current = try {
                        if (index == 0) candidate else read(binding).singleOrNull {
                            it.behaviorId == candidate.behaviorId && it.rewardTs == candidate.rewardTs
                        }
                    } catch (stopped: Stopped) { throw stopped }
                    catch (error: Exception) {
                        guard()
                        unavailable++
                        detail(error)
                        break
                    }
                    if (current == null) continue
                    var dispatched = false
                    try {
                        val response = transport.post(binding, ClosenessCodec.CLAIM_PATH, ClosenessCodec.claimBody(current)) {
                            guard()
                            dispatched = true
                        }
                        when (val code = ClosenessCodec.code(response)) {
                            0 -> claimed++
                            ALREADY_CLAIMED -> already++
                            else -> {
                                failed++
                                detail(IllegalStateException("Closeness claim rejected ($code)"))
                            }
                        }
                        guard()
                    } catch (stopped: Stopped) {
                        throw stopped
                    } catch (error: Exception) {
                        if (dispatched) unknown++ else failed++
                        detail(error)
                        guard()
                        if (dispatched) return report()
                    }
                }
            }
        } catch (stopped: Stopped) {
            details.add(0, stopped.message.orEmpty())
        }
        return report()
    }

    private companion object {
        const val ALREADY_CLAIMED = 996502
        const val MAX_DETAILS = 3
        const val MAX_DETAIL_LENGTH = 240
    }
}

// What the message tab menu's claim rows run: the IM account the batches are started for, the two claims, and the
// friends the closeness claim may query.
internal class TabClaims(
    val account: () -> Long,
    val sparks: SparkClaim,
    val closeness: ClosenessClaim,
    val closenessTargets: (account: Long) -> List<ClosenessTarget>,
)
