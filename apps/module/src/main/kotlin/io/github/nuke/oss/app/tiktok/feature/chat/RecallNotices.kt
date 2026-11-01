package io.github.nuke.oss.app.tiktok.feature.chat

import io.github.nuke.oss.app.shared.data.MiniJson

// Which commands may be held back. Only commands from other people about a message that is still there are, and an
// ext update counts only when it explicitly marks the message recalled.
internal object ChatRecallPolicy {
    class Target(val uuid: String?, val serverId: Long?)

    fun target(messageType: Int?, ext: Map<*, *>): Target? {
        val target = when (messageType) {
            RECALL_COMMAND -> Target(
                (ext["s:target_client_message_id"] as? String)?.takeIf(String::isNotBlank),
                (ext["s:target_server_message_id"] as? String)?.toLongOrNull()?.takeIf { it > 0 },
            )
            EXT_UPDATE -> {
                if (ext["s:is_recalled"] != "true" && ext["s:recalled"] != "true") return null
                Target(
                    (ext["s:client_message_id"] as? String)?.takeIf(String::isNotBlank),
                    (ext["s:server_message_id"] as? String)?.toLongOrNull()?.takeIf { it > 0 },
                )
            }
            else -> return null
        }
        return target.takeIf { it.uuid != null || it.serverId != null }
    }

    fun shouldPreserve(
        isSelf: Boolean?, isDeleted: Boolean?, isRecalled: Boolean?, sender: Long?, currentUid: Long?,
        localConversationId: String?, commandConversationIds: Set<String>,
    ): Boolean = isSelf == false && isDeleted == false && isRecalled == false &&
        sender != null && sender > 0 && currentUid != null && currentUid > 0 && sender != currentUid &&
        !localConversationId.isNullOrBlank() && localConversationId in commandConversationIds

    // An ext update shares the target's UUID and must never be stored as a deleted message of its own.
    fun canStoreCommand(messageType: Int?, commandUuid: String?, targetUuid: String?, commandId: Long?, targetId: Long?): Boolean =
        messageType == RECALL_COMMAND && !commandUuid.isNullOrBlank() && commandUuid != targetUuid &&
            commandId != null && commandId > 0 && commandId != targetId

    private const val RECALL_COMMAND = 40001
    private const val EXT_UPDATE = 50002
}

// A message a recall was held back for. The message has an ID once the server gave it one, and a client UUID before.
internal data class RecallNoticeKey(val account: String, val conversation: String, val uuid: String = "", val messageId: Long = 0) {
    fun isValid(): Boolean = (account.toLongOrNull() ?: 0) > 0 && conversation.isNotBlank() && (uuid.isNotBlank() || messageId > 0)

    fun matches(other: RecallNoticeKey): Boolean {
        if (!isValid() || !other.isValid() || account != other.account || conversation != other.conversation) return false
        return if (messageId > 0 && other.messageId > 0) messageId == other.messageId else uuid.isNotBlank() && uuid == other.uuid
    }
}

// The held-back messages, newest last, kept to a limit. Local metadata only: no message content or host state.
internal class RecallNoticeIndex(initial: List<RecallNoticeKey> = emptyList(), private val limit: Int = 1_000) {
    init { require(limit > 0) }

    private var entries = initial.filter { it.isValid() }.takeLast(limit)

    @Synchronized
    fun mark(key: RecallNoticeKey): List<RecallNoticeKey> {
        require(key.isValid())
        entries = (entries.filterNot { it.matches(key) } + key).takeLast(limit)
        return entries
    }

    @Synchronized
    fun contains(key: RecallNoticeKey): Boolean = entries.any { it.matches(key) }

    companion object {
        fun encode(keys: List<RecallNoticeKey>): String = MiniJson.write(keys.map {
            linkedMapOf("account" to it.account, "conversation" to it.conversation, "uuid" to it.uuid, "messageId" to it.messageId)
        })

        // Damaged data reads as empty, so it cannot stop future notices.
        fun decode(text: String?): List<RecallNoticeKey> {
            val items = MiniJson.array(text?.let(MiniJson::parse)) ?: return emptyList()
            return items.mapNotNull { item ->
                val map = MiniJson.obj(item) ?: return@mapNotNull null
                RecallNoticeKey(
                    map["account"] as? String ?: return@mapNotNull null, map["conversation"] as? String ?: return@mapNotNull null,
                    map["uuid"] as? String ?: "", map["messageId"] as? Long ?: 0,
                ).takeIf { it.isValid() }
            }
        }
    }
}
