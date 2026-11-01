package io.github.nuke.oss.app.tiktok.sdk.contact

import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.HostFriend
import io.github.nuke.oss.app.shared.settings.HostGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinContactsTest {
    // Stand in for Douyin's IM classes: the same member names, read by reflection.
    class UrlModel(private val urls: List<Any?>) {
        fun getUrlList(): List<Any?> = urls
    }

    @Suppress("unused", "PropertyName")
    class IMUser(private val uid: String, private val nick: String?, private val remark: String? = null) {
        var _avatarThumb: UrlModel? = null
        var _avatarStr: String? = null
        var _avatarSmall: UrlModel? = null
        var _avatarSmallStr: String? = null
        var avatarLarger: UrlModel? = null
        fun getUid() = uid
        fun getNickname() = nick
        fun getRemarkName() = remark
        fun getDisplayName(): String? = null
    }

    open class ContactService {
        var users: List<Any?> = emptyList()
        open fun getAllFriends(scene: String): List<Any?> = users
    }

    class FallbackService

    class ImService(private val contacts: Any) {
        fun getContactService(): Any = contacts
    }

    class CoreInfo(private val name: String?, private val icon: String?) {
        fun getName() = name
        fun getIcon() = icon
    }

    @Suppress("unused")
    class Conversation(
        private val id: String,
        private val group: Boolean = true,
        private val member: Boolean = true,
        private val dissolved: Boolean = false,
        private val deleted: Int = 0,
        private val core: CoreInfo? = null,
    ) {
        fun getConversationId() = id
        fun isGroupChat() = group
        fun isMember() = member
        fun isDissolved() = dissolved
        fun getDeleted() = deleted
        fun getCoreInfo() = core
    }

    class ListModel(private val database: List<Any?>, private val loaded: List<Any?>) {
        @Suppress("UNUSED_PARAMETER")
        fun getGroupConversationList(a: Long, b: Long, c: Long, d: Int, e: Int, f: Boolean): List<Any?> = database
        fun getAllConversationSync(): List<Any?> = loaded
    }

    private val friendsMethod = ContactService::class.java.getMethod("getAllFriends", String::class.java)
    private val service = ContactService()

    private fun contacts(
        im: Any? = ImService(service),
        model: Any? = ListModel(emptyList(), emptyList()),
        days: ((String) -> Int?)? = null,
    ) = DouyinContacts({ im }, { model }, friendsMethod, { uid -> "0:1:$uid" }, days)

    @Test fun `friends carry their name, avatar, streak and one-to-one conversation`() {
        service.users = listOf(
            IMUser("100", "nick", remark = "remark").apply { _avatarThumb = UrlModel(listOf("", "//cdn/thumb")) },
            IMUser("200", "  ").apply { _avatarSmallStr = """{"uri":"x","url_list":["https:\/\/cdn\/small"]}""" },
            IMUser("0", "invalid"),
            null,
            IMUser("100", "duplicate"),
        )
        val friends = contacts(days = { if (it == "0:1:100") 12 else -1 }).load().friends
        assertEquals(listOf(
            HostFriend("100", "remark", "https://cdn/thumb", 12, "0:1:100"),
            HostFriend("200", "200", "https://cdn/small", null, "0:1:200"),
        ), friends)
    }

    @Test fun `a loaded conversation wins over its stored row, and only live groups are listed`() {
        val model = ListModel(
            database = listOf(
                Conversation("g1", core = CoreInfo("Old name", null)),
                Conversation("g2", core = CoreInfo("Left", null)),
                Conversation("0:1:100", group = false),
            ),
            loaded = listOf(
                Conversation("g1", core = CoreInfo("New name", "//cdn/icon")),
                Conversation("g2", member = false),
                Conversation("g3", dissolved = true),
                Conversation("g4", deleted = 1),
                Conversation("g5", core = CoreInfo(null, "resource-key")),
            ),
        )
        assertEquals(
            listOf(HostGroup("g1", "New name", "https://cdn/icon"), HostGroup("g5", "g5", null)),
            contacts(model = model).load().groups,
        )
    }

    @Test fun `a fallback contact service is reported instead of read as no friends`() {
        val error = assertThrows(HostContactsUnavailable::class.java) { contacts(im = ImService(FallbackService())).load() }
        assertTrue(error.message!!.contains("fallback"))
    }

    @Test fun `a missing host member is reported with its name`() {
        val error = assertThrows(HostContactsUnavailable::class.java) { contacts(model = Any()).load() }
        assertTrue(error.message!!.contains("getGroupConversationList"))
        assertThrows(HostContactsUnavailable::class.java) { contacts(im = null).load() }
    }

    @Test fun `avatar URLs are read from the url_list of the serialized model`() {
        assertEquals(listOf("https://a/1", "//b/2"), DouyinContacts.jsonUrls("""{"url_list":["https:\/\/a\/1","//b/2"],"uri":"u"}"""))
        assertEquals(emptyList<String>(), DouyinContacts.jsonUrls("""{"uri":"u"}"""))
    }
}
