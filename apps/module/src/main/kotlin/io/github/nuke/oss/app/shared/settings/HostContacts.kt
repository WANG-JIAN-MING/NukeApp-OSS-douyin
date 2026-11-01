package io.github.nuke.oss.app.shared.settings

// A friend of the signed-in host account. The conversation ID is the one-to-one chat with that friend, which is what
// a conversations option stores. Streak days are null when the host did not report a count.
internal data class HostFriend(
    val uid: String,
    val name: String,
    val avatarUrl: String?,
    val streakDays: Int?,
    val conversationId: String,
)

internal data class HostGroup(val conversationId: String, val name: String, val avatarUrl: String?)

internal data class HostContactList(val friends: List<HostFriend>, val groups: List<HostGroup>)

// Reads the host's local friend and group lists for a picker. Blocking: callers run it off the main thread. Throws
// HostContactsUnavailable with the reason when the host cannot answer, so the picker can say why it is empty.
internal fun interface HostContacts {
    fun load(): HostContactList
}

internal class HostContactsUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
