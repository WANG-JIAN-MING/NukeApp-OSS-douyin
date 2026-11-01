package io.github.nuke.oss.app.tiktok.feature.chat.claims

// A private chat with a friend, as the spark reward API names it: the conversation ID, the server's short ID (kept
// as text so the integer is never rounded) and the host's conversation type.
internal class SparkConversation(val id: String, val shortId: String, val type: Int)

// One earned spark reward. taskId is kept exactly as the server sent it, a Long or a String, and key tells tasks apart.
internal class SparkTask(val key: String, val taskId: Any, val token: String)

internal class SparkReport(
    val scanned: Int,
    val claimed: Int,
    val already: Int,
    val failed: Int,
    val unknown: Int,
    val unavailable: Int,
    val details: List<String>,
)

// The host's authenticated network chain. A null body is a GET. beforeDispatch runs once, right before the request
// is handed to the host; a request that was dispatched and then failed or timed out has an unknown outcome.
internal interface SparkTransport {
    fun accountId(): Long
    fun request(url: String, body: Map<String, Any?>?, beforeDispatch: () -> Unit): Map<String, Any?>
}

// Loaded private chats with a friend whose cached streak is positive; nothing else may be queried.
internal fun interface SparkConversations {
    fun load(account: Long): List<SparkConversation>
}

// Decodes earned spark rewards only; completing tasks and other reward currencies are left out. Numbers arrive as
// Long when they are integers, which is all this accepts.
internal object SparkCodec {
    private const val MAX_TASK_DEPTH = 32
    private const val MAX_TASK_NODES = 10_000
    const val TASKS_URL = "https://api.amemv.com/aweme/v1/im_communication/flame_achieve/get/"
    const val REWARD_URL = "https://api.amemv.com/aweme/im/growth/pet/task/reward"

    fun tasksUrl(conversation: SparkConversation) = "$TASKS_URL?conversation_short_id=${conversation.shortId}&need_pet_elf=true"

    fun status(root: Map<String, Any?>): Int = integer(root["status_code"]) ?: error("Spark response has no integer status_code")

    fun tasks(root: Map<String, Any?>): List<SparkTask> {
        val status = status(root)
        check(status == 0) { "Spark task list was rejected ($status)" }
        val list = root["new_task_list"] as? List<*> ?: error("Spark response has no valid task list")
        val found = ArrayList<SparkTask>()
        val seen = HashSet<String>()
        var visited = 0

        fun visit(nodes: List<*>, depth: Int) {
            check(depth <= MAX_TASK_DEPTH) { "Spark task tree is too deep" }
            for (element in nodes) {
                check(++visited <= MAX_TASK_NODES) { "Spark task list is too large" }
                @Suppress("UNCHECKED_CAST")
                val node = element as? Map<String, Any?> ?: error("Spark task is not an object")
                val task = candidate(node)
                if (task != null) {
                    if (seen.add(task.key)) found += task
                    // The official reward handler completes the chosen node and all its descendants.
                    continue
                }
                val children = node["sub_tasks"]
                if (children != null) visit(children as? List<*> ?: error("Spark subtasks are not a list"), depth + 1)
            }
        }

        visit(list, 1)
        return found
    }

    fun claimBody(conversation: SparkConversation, task: SparkTask): Map<String, Any?> {
        check(conversation.type > 0 && conversation.id.isNotBlank() && conversation.shortId.isNotBlank()) { "Spark reward has no valid conversation" }
        check(task.token.isNotBlank()) { "Spark reward task is invalid" }
        return linkedMapOf(
            "token" to task.token,
            "task_id" to task.taskId,
            "enter_from" to "pet",
            "conversation_info" to linkedMapOf(
                "conversation_type" to conversation.type.toLong(),
                "conversation_id" to conversation.id,
                "conversation_short_id" to conversation.shortId,
            ),
        )
    }

    private fun candidate(node: Map<String, Any?>): SparkTask? {
        if (integer(node["task_status"]) != 2) return null
        val reward = node["reward"] as? Map<*, *> ?: return null
        if (reward["reward_type"] != "spark") return null
        val interaction = (node["button_config"] as? Map<*, *>)?.get("interaction") as? Map<*, *> ?: return null
        if (integer(interaction["type"]) != 4) return null
        val taskId = identifier(node["task_id"]) ?: return null
        val token = (node["token"] as? String)?.takeIf(String::isNotBlank) ?: return null
        val unique = node["unique_task_id"]
        val key = when {
            unique == null || unique == "" || unique == 0L -> taskId.toString()
            else -> identifier(unique)?.toString() ?: return null
        }
        return SparkTask(key, taskId, token)
    }

    private fun identifier(value: Any?): Any? = when (value) {
        is String -> value.takeIf(String::isNotBlank)
        is Long -> value
        else -> null
    }

    private fun integer(value: Any?): Int? = (value as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}

// One explicit user action. It reads each friend's reward list, claims what is earned, and never completes tasks or
// retries a reward submission. Everything is serial; a stop (feature off, account changed) ends the batch with the
// counts so far. A failed friend or conversation lookup is thrown to the caller, since nothing was queried then.
internal class SparkClaim(private val transport: SparkTransport, private val conversations: SparkConversations) {
    private class Stopped(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    fun claimAll(account: Long, canSubmit: () -> Boolean): SparkReport {
        require(account > 0)
        fun allowed() {
            if (!canSubmit()) throw Stopped("Spark collection was stopped")
            val current = try { transport.accountId() } catch (error: IllegalStateException) {
                throw Stopped("Current account is unavailable; collection stopped", error)
            }
            if (current != account) throw Stopped("Account changed; collection stopped")
        }
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
        fun read(conversation: SparkConversation): List<SparkTask> {
            allowed()
            val root = transport.request(SparkCodec.tasksUrl(conversation), null) { allowed() }
            allowed()
            return SparkCodec.tasks(root)
        }
        try {
            allowed()
            val targets = conversations.load(account).distinctBy { it.shortId }
            allowed()
            for (conversation in targets) {
                allowed()
                val tasks = try {
                    read(conversation)
                } catch (stopped: Stopped) { throw stopped }
                catch (error: Exception) {
                    allowed()
                    unavailable++
                    detail(error)
                    emptyList()
                }
                scanned++
                val attempted = HashSet<String>()
                for ((index, original) in tasks.withIndex()) {
                    allowed()
                    if (!attempted.add(original.key)) continue
                    // A claim can finish a whole subtree or replace its tokens, so every later candidate is read
                    // again and the stale snapshot is never submitted.
                    val task = if (index == 0) original else try {
                        read(conversation).singleOrNull { it.key == original.key }
                    } catch (stopped: Stopped) { throw stopped }
                    catch (error: Exception) {
                        allowed()
                        unavailable++
                        detail(error)
                        break
                    }
                    if (task == null) continue
                    var dispatched = false
                    var acknowledged = false
                    try {
                        val response = transport.request(SparkCodec.REWARD_URL, SparkCodec.claimBody(conversation, task)) {
                            allowed()
                            dispatched = true
                        }
                        when (val status = SparkCodec.status(response)) {
                            0 -> claimed++
                            ALREADY_CLAIMED -> already++
                            else -> {
                                failed++
                                detail(IllegalStateException("Spark reward was rejected ($status)"))
                            }
                        }
                        acknowledged = true
                        allowed()
                    } catch (stopped: Stopped) {
                        if (dispatched && !acknowledged) unknown++
                        throw stopped
                    } catch (error: Exception) {
                        // A missing callback or an invalid answer after dispatch cannot prove the reward failed.
                        if (dispatched) unknown++ else failed++
                        detail(error)
                        allowed()
                    }
                }
            }
        } catch (stopped: Stopped) {
            details.add(0, (stopped.message ?: "Spark collection stopped").take(MAX_DETAIL_LENGTH))
            while (details.size > MAX_DETAILS) details.removeAt(details.lastIndex)
        }
        return SparkReport(scanned, claimed, already, failed, unknown, unavailable, details)
    }

    private companion object {
        const val ALREADY_CLAIMED = 996004
        const val MAX_DETAILS = 3
        const val MAX_DETAIL_LENGTH = 240
    }
}
