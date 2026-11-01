package io.github.nuke.oss.app.tiktok.sdk.contact

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostContacts
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.HostFriend
import io.github.nuke.oss.app.shared.settings.HostGroup
import io.github.nuke.oss.app.tiktok.query.ContactQueries
import io.github.nuke.oss.core.Logger
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

// Douyin's local friend and group lists, read the way NukeApp's DyContactHost reads them and checked against
// Douyin 40.8.0. Friends come from the IM contact service's mutual-follow list and are mapped to their one-to-one
// conversation ID with ConversationModel.Companion; groups come from the conversation list model, where the loaded
// copy of a conversation wins over its database row so a group left or deleted since is not shown. Streak days are
// optional: a host without the streak service still lists every friend, with an unknown count.
internal class DouyinContacts(
    private val imService: () -> Any?,
    private val conversationListModel: () -> Any?,
    private val friends: Method,
    private val conversationId: (uid: String) -> String?,
    private val streakDays: ((conversationId: String) -> Int?)?,
) : HostContacts {
    override fun load(): HostContactList = try {
        HostContactList(readFriends(), readGroups())
    } catch (error: ReflectiveOperationException) {
        throw HostContactsUnavailable((error as? InvocationTargetException)?.targetException?.toString() ?: error.toString(), error)
    }

    private fun readFriends(): List<HostFriend> = friendUsers(imService, friends).mapNotNull { (uid, user) ->
        val chat = conversationId(uid)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val name = listOf(REMARK_NAME, DISPLAY_NAME, NICKNAME)
            .firstNotNullOfOrNull { (it.call(user) as? String)?.takeIf(String::isNotBlank) } ?: uid
        HostFriend(uid, name, avatarUrlOf(user), streakDays?.invoke(chat)?.takeIf { it >= 0 }, chat)
    }.distinctBy { it.uid }

    private fun readGroups(): List<HostGroup> {
        val model = conversationListModel() ?: throw HostContactsUnavailable("Conversation list model is unavailable")
        // Bounds 0 and limit 0 ask the native store for every group conversation without paging.
        val members = model.asResolver()
        val database = members.firstMethod {
            name = "getGroupConversationList"
            parameters(Long::class, Long::class, Long::class, Int::class, Int::class, Boolean::class)
            superclass()
        }.invoke(0L, 0L, 0L, 0, 0, false) as? List<*> ?: throw HostContactsUnavailable("Host returned no group list")
        val loaded = members.firstMethod { name = "getAllConversationSync"; emptyParameters(); superclass() }.invoke() as? List<*>
            ?: throw HostContactsUnavailable("Host returned no loaded conversations")
        val merged = LinkedHashMap<String, Any>()
        for (conversation in database + loaded) {
            conversation ?: continue
            val id = (CONVERSATION_ID.call(conversation) as? String)?.takeIf(String::isNotBlank) ?: continue
            merged[id] = conversation
        }
        return merged.mapNotNull { (id, conversation) ->
            if (IS_GROUP.call(conversation) != true || IS_MEMBER.call(conversation) != true ||
                IS_DISSOLVED.call(conversation) != false || DELETED.call(conversation) != 0) return@mapNotNull null
            val core = CORE_INFO.call(conversation)
            val name = core?.let { (NAME.call(it) as? String)?.takeIf(String::isNotBlank) } ?: id
            HostGroup(id, name, core?.let { imageUrl(ICON.call(it) as? String) })
        }
    }

    private class Required(private val name: String) {
        private val method = NamedCall(name)

        fun call(owner: Any): Any? =
            (method.of(owner) ?: throw NoSuchMethodException("${owner.javaClass.name}.$name")).invoke(owner)
    }

    companion object {
        // The scene name the friend list call is tagged with on the host side.
        private const val SCENE = "nuke_sdk"
        private val UID = Required("getUid")
        private val REMARK_NAME = Required("getRemarkName")
        private val DISPLAY_NAME = Required("getDisplayName")
        private val NICKNAME = Required("getNickname")
        private val CONVERSATION_ID = Required("getConversationId")
        private val IS_GROUP = Required("isGroupChat")
        private val IS_MEMBER = Required("isMember")
        private val IS_DISSOLVED = Required("isDissolved")
        private val DELETED = Required("getDeleted")
        private val CORE_INFO = Required("getCoreInfo")
        private val NAME = Required("getName")
        private val ICON = Required("getIcon")
        private val URL_LIST = NamedCall("getUrlList")
        private val AVATARS = listOf(
            NamedField("_avatarThumb") to NamedField("_avatarStr"),
            NamedField("_avatarSmall") to NamedField("_avatarSmallStr"),
            NamedField("avatarLarger") to null,
        )
        private val URL_LIST_JSON = Regex("\"url_list\"\\s*:\\s*\\[([^\\]]*)]")
        private val JSON_STRING = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

        // IMUser keeps decoded UrlModels next to their JSON form and decodes lazily, so either may be the one present.
        internal fun avatarUrlOf(user: Any): String? {
            for ((model, json) in AVATARS) {
                val decoded = model.of(user)?.get(user)
                if (decoded != null) {
                    (URL_LIST.of(decoded)?.invoke(decoded) as? List<*>)?.firstNotNullOfOrNull { imageUrl(it as? String) }?.let { return it }
                }
                val serialized = json?.of(user)?.get(user) as? String
                if (!serialized.isNullOrBlank()) jsonUrls(serialized).firstNotNullOfOrNull(::imageUrl)?.let { return it }
            }
            return null
        }

        internal fun jsonUrls(json: String): List<String> {
            val list = URL_LIST_JSON.find(json)?.groupValues?.get(1) ?: return emptyList()
            return JSON_STRING.findAll(list).map { it.groupValues[1].replace("\\/", "/") }.toList()
        }

        internal fun imageUrl(value: String?): String? {
            val url = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true) -> url
                url.startsWith("//") -> "https:$url"
                else -> null
            }
        }

        // Builds the reader from the located Dex queries. A feature that only needs the contacts sometimes must not fail
        // for them, so targets that were not located give a reader that reports why it is empty. Streak days are optional.
        fun open(resolver: DexResolver, hostClassLoader: ClassLoader, logger: Logger, owner: String): HostContacts {
            fun unavailable(error: Exception): HostContacts {
                logger.log("feature=$owner contacts_unavailable $error")
                val reason = error.toString()
                return HostContacts { throw HostContactsUnavailable(reason) }
            }
            val (friends, conversationId) = try {
                resolver.methods(hostClassLoader, ContactQueries.FRIENDS, ContactQueries.SINGLE_CONVERSATION_ID)
            } catch (error: IllegalStateException) {
                return unavailable(error)
            } catch (error: ReflectiveOperationException) {
                return unavailable(error)
            }
            val days = try {
                streakDays(resolver.methods(hostClassLoader, ContactQueries.STREAK_DAYS).single())
            } catch (error: IllegalStateException) {
                logger.log("feature=$owner streak_days_unavailable $error")
                null
            } catch (error: ReflectiveOperationException) {
                logger.log("feature=$owner streak_days_unavailable $error")
                null
            }
            friends.isAccessible = true
            conversationId.isAccessible = true
            return DouyinContacts(
                imService(hostClassLoader),
                conversationListModel(hostClassLoader),
                friends,
                { uid -> conversationId.invoke(null, uid) as? String },
                days,
            )
        }

        // The host's own friend objects of the mutual-follow list, each with its positive UID, in the host's order.
        // A caller that hands friends back to the host needs these rather than the summaries load gives.
        private fun friendUsers(imService: () -> Any?, friends: Method): List<Pair<String, Any>> {
            val im = imService() ?: throw HostContactsUnavailable("IM service is unavailable")
            // IIMService declares getContactService twice with different return types; the friend list belongs to the
            // implementation located by Dex, so the service must be an instance of it rather than a fallback.
            val service = im.javaClass.methods
                .filter { it.name == "getContactService" && it.parameterCount == 0 }
                .firstNotNullOfOrNull { it.invoke(im)?.takeIf(friends.declaringClass::isInstance) }
                ?: throw HostContactsUnavailable("Contact service is unavailable or is a fallback")
            val users = friends.invoke(service, SCENE) as? List<*> ?: throw HostContactsUnavailable("Host returned no friend list")
            return users.mapNotNull { user ->
                user ?: return@mapNotNull null
                val uid = (UID.call(user) as? String)?.takeIf { (it.toLongOrNull() ?: 0) > 0 } ?: return@mapNotNull null
                uid to user
            }
        }

        // Reads the friend objects on each call, resolving the friend list target on first use. Every failure,
        // including a target that was not located, is a HostContactsUnavailable with the reason.
        fun friendObjects(resolver: DexResolver, hostClassLoader: ClassLoader): () -> List<Pair<String, Any>> {
            val im = imService(hostClassLoader)
            return {
                try {
                    val friends = resolver.methods(hostClassLoader, ContactQueries.FRIENDS).single().apply { isAccessible = true }
                    friendUsers(im, friends)
                } catch (error: IllegalStateException) {
                    throw HostContactsUnavailable(error.toString(), error)
                } catch (error: ReflectiveOperationException) {
                    throw HostContactsUnavailable((error as? InvocationTargetException)?.targetException?.toString() ?: error.toString(), error)
                }
            }
        }

        fun imService(hostClassLoader: ClassLoader): () -> Any? = {
            "com.ss.android.ugc.aweme.im.IMProxy".toClass(hostClassLoader).resolve().firstMethod { name = "get"; emptyParameters() }.invoke()
        }

        fun conversationListModel(hostClassLoader: ClassLoader): () -> Any? = {
            "com.bytedance.ies.im.core.sdk.MultiInstanceHelperKt".toClass(hostClassLoader).resolve()
                .firstMethod { name = "getConversationListModel"; emptyParameters() }.invoke()
        }

        // The streak service instance sits in a static field of its own type.
        fun streakDays(days: Method): (String) -> Int? {
            val holder = days.declaringClass.resolve().field {
                modifiers(Modifiers.STATIC)
                type = days.declaringClass
            }.single().self.apply { isAccessible = true }
            days.isAccessible = true
            return { conversation -> holder.get(null)?.let { days.invoke(it, conversation) as? Int } }
        }
    }
}
