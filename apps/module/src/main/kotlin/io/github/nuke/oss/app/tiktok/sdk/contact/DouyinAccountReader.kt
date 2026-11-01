package io.github.nuke.oss.app.tiktok.sdk.contact

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.settings.HostAccount

// Reads the signed-in Douyin account through the host's account service facade:
// AccountProxyService.userService() answers isLogin, getCurUserId and getCurUser, and the profile User carries
// getUid, getNickname and the avatar UrlModel fields. Checked against Douyin 40.8.0.
// A profile that still belongs to the previous account is not used, and an account switch during the read
// discards the whole answer. Avatar members are optional; the required ones failing reads as Unavailable.
internal class DouyinAccountReader(
    private val service: () -> Any?,
    private val report: (ReflectiveOperationException) -> Unit,
) {
    fun read(): HostAccount = try {
        readOrThrow()
    } catch (error: ReflectiveOperationException) {
        report(error)
        HostAccount.Unavailable
    }

    private fun readOrThrow(): HostAccount {
        val source = service() ?: return HostAccount.Unavailable
        if (IS_LOGIN.call(source) != true) return HostAccount.SignedOut
        val userId = (CUR_USER_ID.call(source) as? String)?.takeIf { it.isNotBlank() } ?: return HostAccount.Unavailable
        val user = CUR_USER.call(source)
        val account = if (user == null || UID.call(user) != userId) {
            HostAccount.SignedIn(userId, "", emptyList())
        } else {
            HostAccount.SignedIn(userId, (NICKNAME.call(user) as? String).orEmpty(), avatarUrls(user))
        }
        val unchanged = IS_LOGIN.call(source) == true && CUR_USER_ID.call(source) == userId
        return if (unchanged) account else HostAccount.Unavailable
    }

    // The secure UID of the signed-in account, which the pet service uses to tell the two sides of a pet apart. Null
    // while the service answers for another account or has no profile of this one. The account service is read on
    // the host's main thread.
    fun secUid(expectedUid: String): String? {
        val source = service() ?: return null
        if (IS_LOGIN.call(source) != true || CUR_USER_ID.call(source) != expectedUid) return null
        val user = CUR_USER.call(source) ?: return null
        if (UID.call(user) != expectedUid) return null
        return (SEC_UID.call(user) as? String)?.takeIf { it.isNotBlank() }
    }

    private fun avatarUrls(user: Any): List<String> {
        val urls = ArrayList<String>()
        for (field in AVATAR_MODELS) {
            val model = field.of(user)?.get(user) ?: continue
            val candidates = URL_LIST.of(model)?.invoke(model) as? List<*> ?: continue
            candidates.mapNotNullTo(urls) { imageUrl(it as? String) }
        }
        imageUrl(AVATAR_URL.of(user)?.get(user) as? String)?.let(urls::add)
        return urls.distinct()
    }

    // Resource keys and avatar URIs are not downloadable, so only http(s) and scheme-relative URLs are kept.
    private fun imageUrl(value: String?): String? {
        val url = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true) -> url
            url.startsWith("//") -> "https:$url"
            else -> null
        }
    }

    private class Required(private val name: String) {
        private val method = NamedCall(name)

        fun call(owner: Any): Any? =
            (method.of(owner) ?: throw NoSuchMethodException("${owner.javaClass.name}.$name")).invoke(owner)
    }

    companion object {
        private val IS_LOGIN = Required("isLogin")
        private val CUR_USER_ID = Required("getCurUserId")
        private val CUR_USER = Required("getCurUser")
        private val UID = Required("getUid")
        private val NICKNAME = Required("getNickname")
        private val SEC_UID = Required("getSecUid")
        private val AVATAR_MODELS = listOf("avatar300x300", "avatarMedium", "avatarThumb", "avatarLarger").map(::NamedField)
        private val AVATAR_URL = NamedField("avatarUrl")
        private val URL_LIST = NamedCall("getUrlList")

        fun service(hostClassLoader: ClassLoader): () -> Any? = {
            "com.ss.android.ugc.aweme.account.AccountProxyService".toClass(hostClassLoader).resolve()
                .firstMethod { name = "userService"; emptyParameters() }.invoke()
        }
    }
}
