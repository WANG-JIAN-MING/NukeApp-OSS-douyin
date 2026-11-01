package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.feed.AddAuthorInfoRow
import io.github.nuke.oss.app.tiktok.feature.feed.AuthorInfo
import io.github.nuke.oss.app.tiktok.feature.feed.AuthorRow
import io.github.nuke.oss.app.tiktok.feature.feed.authorSummary
import io.github.nuke.oss.app.tiktok.feature.feed.hostGson
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the author page copy row: where the author and the root View come from, and the text copied.
class AuthorInfoRowTest : HookHarness() {
    class Root
    // The model names its fields the way Douyin 40.7.0 does: avatarThumb and urlList, not the serialized names.
    class Avatar(@JvmField val urlList: List<String>)
    class User(@JvmField val uid: String?, @JvmField val nickname: String, @JvmField val signature: String, @JvmField val avatarThumb: Avatar?)
    // The class name is the one the feature looks for; it holds a column and the container the column was added to.
    class PandaHeaderInfoArea(@JvmField val column: Root, @JvmField val container: Root)
    class Header(@JvmField val view: Root?, @JvmField val area: PandaHeaderInfoArea? = null) {
        fun bind(user: User?) {}
        fun refresh(user: User?) {}
        fun other(flag: Boolean) {}
    }
    class Plain { fun name(): String = "plain" }

    private val bind = method(Header::class.java, "bind", User::class.java)
    private val refresh = method(Header::class.java, "refresh", User::class.java)
    private val parents = java.util.IdentityHashMap<Any, Any>()
    private val ui = RecordingAuthorRow()

    private val anchor: java.lang.reflect.Method = method(Header::class.java, "other", Boolean::class.javaPrimitiveType!!)
    private val prompts = RecordingPrompts()
    private val files = RecordingMediaFiles()
    private val printed = ArrayList<Any>()

    private fun feature(vararg saved: Pair<String, Any>, printer: (ClassLoader) -> (Any) -> String = { { value -> printed += value; "{\"uid\":\"42\"}" } }) =
        AddAuthorInfoRow(
            resolver(FeedQueries.AUTHOR_HOME to listOf(anchor)), loader, { ui }, values(AddAuthorInfoRow.ID, AddAuthorInfoRow.OPTIONS, *saved),
            prompts, { files }, Logger {}, isRoot = { it is Root }, parentOf = { parents[it] }, userName = User::class.java.name,
            printer = printer, runner = { _, task -> task() },
        )

    private val user = User("42", "Ann", "hi", Avatar(listOf("https://a/1.jpg")))

    @Test fun `the bound user and the header's view reach the row`() {
        enable(feature())
        val held = Root()
        backend.call(bind, Header(held), user)
        val (attached, info) = ui.attached.single()
        assertSame(held, attached)
        assertEquals("42", info.uid)
        assertEquals("Ann", info.name)
        assertEquals("hi", info.signature)
        assertEquals("https://a/1.jpg", info.avatar)
    }

    @Test fun `the container of the info area is preferred to the header's own view`() {
        enable(feature())
        val column = Root()
        val container = Root()
        parents[column] = container
        backend.call(bind, Header(Root(), PandaHeaderInfoArea(column, container)), user)
        assertSame(container, ui.attached.single().first)
    }

    @Test fun `every void method that takes a user is hooked and others are left alone`() {
        enable(feature())
        val held = Root()
        backend.call(bind, Header(held), user)
        backend.call(refresh, Header(held), user)
        backend.call(anchor, Header(held), true)
        assertEquals(2, ui.attached.size)
    }

    @Test fun `without an author, a uid or a view nothing is added`() {
        enable(feature())
        backend.call(bind, Header(Root()), null)
        backend.call(bind, Header(Root()), User(null, "x", "y", null))
        backend.call(bind, Header(null), user)
        assertTrue(ui.attached.isEmpty())
    }

    @Test fun `a missing avatar copies as empty`() {
        enable(feature())
        backend.call(bind, Header(Root()), User("1", "n", "s", null))
        assertEquals("", ui.attached.single().second.avatar)
    }

    @Test fun `a header class with no method that takes a user fails the install`() {
        failsToInstall(
            AddAuthorInfoRow(
                resolver(FeedQueries.AUTHOR_HOME to listOf(method(Plain::class.java, "name"))), loader, { ui },
                values(AddAuthorInfoRow.ID, AddAuthorInfoRow.OPTIONS), prompts, { files }, Logger {},
                isRoot = { it is Root }, userName = User::class.java.name,
            ),
            "has no method that takes a User",
        )
    }

    @Test fun `the summary lists every field on its own line`() {
        assertEquals("UID: 1\nName: n\nSignature: s\nAvatar: a", authorSummary(AuthorInfo("1", "n", "s", "a")))
    }

    @Test fun `full information is off by default`() {
        enable(feature(printer = { error("Gson is not needed") }))
        backend.call(bind, Header(Root()), user)
        assertNull(ui.full.single())
    }

    @Test fun `full information prints the user and saves it`() {
        enable(feature(AddAuthorInfoRow.FULL to true))
        backend.call(bind, Header(Root()), user)
        val full = checkNotNull(ui.full.single())
        val json = checkNotNull(full.json())
        assertSame(user, printed.single())
        full.save("context", json)
        assertEquals(json, files.texts.single().second)
        assertTrue(files.texts.single().first.startsWith("author_42_") && files.texts.single().first.endsWith(".json"))
    }

    @Test fun `the host Gson is built on first use and a failed build is tried again`() {
        com.google.gson.GsonBuilder.built = 0
        com.google.gson.GsonBuilder.failing = true
        try {
            val print = hostGson(loader)
            assertEquals(0, com.google.gson.GsonBuilder.built)
            assertTrue(runCatching { print("a") }.exceptionOrNull() is java.lang.reflect.InvocationTargetException)
            com.google.gson.GsonBuilder.failing = false
            assertEquals("json:a", print("a"))
            assertEquals("json:b", print("b"))
            assertEquals(2, com.google.gson.GsonBuilder.built)
        } finally {
            com.google.gson.GsonBuilder.failing = false
        }
    }

    @Test fun `a host without Gson fails the full information install`() {
        failsToInstall(feature(AddAuthorInfoRow.FULL to true, printer = { throw ClassNotFoundException("com.google.gson.GsonBuilder") }), "GsonBuilder")
    }
}
