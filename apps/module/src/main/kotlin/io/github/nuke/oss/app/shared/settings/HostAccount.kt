package io.github.nuke.oss.app.shared.settings

// The host account the settings screen shows on its profile card, read from the host's own account service on the
// host main thread. It is only displayed: never stored, logged or sent anywhere.
internal sealed interface HostAccount {
    // No answer: the service is missing or failed, or the account changed while it was read.
    data object Unavailable : HostAccount

    data object SignedOut : HostAccount

    // The nickname is blank and the avatar list empty while the host has not loaded this account's profile yet.
    data class SignedIn(val userId: String, val nickname: String, val avatarUrls: List<String>) : HostAccount
}

// What the profile card keeps showing after a new read. A read with no answer keeps what is known; the same account
// keeps a known nickname or avatar the host has not loaded yet; another account never inherits the previous one's.
internal fun HostAccount.refreshedBy(next: HostAccount): HostAccount = when (next) {
    HostAccount.Unavailable -> this
    HostAccount.SignedOut -> next
    is HostAccount.SignedIn -> if (this !is HostAccount.SignedIn || userId != next.userId) next else HostAccount.SignedIn(
        userId,
        next.nickname.ifBlank { nickname },
        next.avatarUrls.ifEmpty { avatarUrls },
    )
}
