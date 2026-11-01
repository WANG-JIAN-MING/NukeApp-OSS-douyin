package io.github.nuke.oss.app.tiktok.feature.chat.claims

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.settings.HostContacts
import io.github.nuke.oss.app.tiktok.sdk.chat.ConversationModels
import io.github.nuke.oss.app.tiktok.sdk.chat.ImAccount

// Friends the claims may touch: another user's account, a positive cached streak and a known one-to-one chat. A missing
// or zero count does not allow a reward query.
internal fun streakFriends(contacts: HostContacts, account: Long): List<Pair<String, String>> = contacts.load().friends
    .filter { friend -> friend.uid.toLongOrNull()?.let { it > 0 && it != account } == true && (friend.streakDays ?: 0) > 0 }
    .map { it.uid to it.conversationId }

internal fun closenessTargets(contacts: HostContacts, account: Long): List<ClosenessTarget> =
    streakFriends(contacts, account).map { ClosenessTarget(it.second) }

// Spark rewards are only queried for chats the host has already loaded and not deleted; older history is not pulled in.
internal class LoadedSparkConversations(
    private val contacts: HostContacts,
    private val models: ConversationModels,
    private val account: ImAccount,
    private val hostClassLoader: ClassLoader,
) : SparkConversations {
    override fun load(account: Long): List<SparkConversation> {
        check(this.account.id() == account) { "Account changed before the spark lookup" }
        val friendChats = streakFriends(contacts, account).map { it.second }.toSet()
        if (friendChats.isEmpty()) return emptyList()
        val model = models.current(account)
        val loaded = model.asResolver().firstMethod { name = "getAllConversationSync"; emptyParameters(); superclass() }.invoke() as? List<*>
            ?: error("The native loaded conversation list is unavailable")
        val conversationType = CONVERSATION.toClass(hostClassLoader)
        val byShortId = LinkedHashMap<String, SparkConversation>()
        for (value in loaded) {
            check(value != null && conversationType.isInstance(value)) { "Unexpected native conversation type" }
            if (IS_SINGLE.of(value)?.invoke(value) != true || (DELETED.of(value)?.invoke(value) as? Number)?.toInt() != 0) continue
            val id = (CONVERSATION_ID.of(value)?.invoke(value) as? String)?.takeIf(String::isNotBlank) ?: continue
            if (id !in friendChats) continue
            val shortId = (SHORT_ID.of(value)?.invoke(value) as? Number)?.toLong()?.takeIf { it > 0 }?.toString() ?: continue
            val type = (TYPE.of(value)?.invoke(value) as? Number)?.toInt() ?: error("A private conversation has no native type")
            val previous = byShortId.putIfAbsent(shortId, SparkConversation(id, shortId, type))
            check(previous == null || previous.id == id) { "A native conversation short ID has conflicting owners" }
        }
        check(this.account.id() == account) { "Account changed during the spark lookup" }
        return byShortId.values.toList()
    }

    private companion object {
        const val CONVERSATION = "com.bytedance.im.core.model.Conversation"
        val IS_SINGLE = NamedCall("isSingleChat")
        val DELETED = NamedCall("getDeleted")
        val CONVERSATION_ID = NamedCall("getConversationId")
        val SHORT_ID = NamedCall("getConversationShortId")
        val TYPE = NamedCall("getConversationType")
    }
}
