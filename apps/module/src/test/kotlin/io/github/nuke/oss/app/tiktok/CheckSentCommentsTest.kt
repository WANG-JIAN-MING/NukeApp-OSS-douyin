package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.comment.CheckSentComments
import io.github.nuke.oss.app.tiktok.feature.comment.CommentLookup
import io.github.nuke.oss.app.tiktok.feature.comment.CommentVisibility
import io.github.nuke.oss.app.tiktok.feature.comment.CommentRecord
import io.github.nuke.oss.app.tiktok.feature.comment.CommentLog
import io.github.nuke.oss.app.tiktok.feature.comment.commentVisibility
import io.github.nuke.oss.core.Logger
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the sent comment check: recording what the host reports published, reading the web comment list
// response and the dialogs. The request itself is replaced; no network is used.
class CheckSentCommentsTest : HookHarness() {
    class Comment(@JvmField val cid: String?, @JvmField val awemeId: String?, @JvmField val text: String?, @JvmField val createTime: Long)
    class PublishResponse(@JvmField val comment: Comment?)
    class Publisher { fun onSuccess(response: PublishResponse?, extra: Any?) {} }
    object CookieStore {
        @JvmStatic var value: String? = "sessionid=abc"
        @JvmStatic fun read(): String? = value
    }
    class InstanceCookie { fun read(): String? = "x" }

    private val prompts = RecordingPrompts()
    private val entries = RecordingSharePanelEntries()
    private val pages = RecordingCommentPages()
    private val logged = ArrayList<String>()
    private val directory = Files.createTempDirectory("sent-comments").toFile()
    private val log = CommentLog(java.io.File(directory, "sent.json"), capacity = 3)
    private val publish = method(Publisher::class.java, "onSuccess", PublishResponse::class.java, Any::class.java)
    private val cookie = method(CookieStore::class.java, "read")
    private val delays = ArrayList<Long>()
    private val requests = ArrayList<Triple<String, String, String>>()
    private var response = """{"status_code":0,"comments":[{"cid":"c1","status":1}]}"""

    private val lookup = CommentLookup { awemeId, cid, session ->
        requests += Triple(awemeId, cid, session)
        response
    }

    private fun feature(vararg saved: Pair<String, Any>, cookieReader: java.lang.reflect.Method = cookie) = CheckSentComments(
        resolver(CommentQueries.PUBLISH_SUCCESS to listOf(publish), CommentQueries.WEB_COOKIE to listOf(cookieReader)), loader,
        values(CheckSentComments.ID, CheckSentComments.OPTIONS, *saved), log, entries, { pages }, prompts, Logger { logged += it },
        lookup, runner = { delay, task -> delays += delay; task() },
    )

    @Test fun `the web list status is read from comments and their replies`() {
        assertEquals(CommentVisibility.Listed(1), commentVisibility("""{"status_code":0,"comments":[{"cid":"c1","status":1}]}""", "c1"))
        assertEquals(
            CommentVisibility.Listed(7),
            commentVisibility("""{"comments":[{"cid":"top","status":1,"reply_comment":[{"cid":"c2","status":7}]}]}""", "c2"),
        )
        assertEquals(CommentVisibility.NotListed, commentVisibility("""{"status_code":0,"comments":[{"cid":"other","status":1}]}""", "c1"))
        assertEquals(CommentVisibility.NotListed, commentVisibility("""{"status_code":0,"comments":null}""", "c1"))
        assertEquals(CommentVisibility.Failed("status_code 8"), commentVisibility("""{"status_code":8}""", "c1"))
        assertEquals(CommentVisibility.Failed("not JSON"), commentVisibility("<html>", "c1"))
    }

    @Test fun `the log keeps the newest comments once each`() {
        log.add(CommentRecord("a", "1", "one", 1))
        log.add(CommentRecord("a", "2", "two", 2))
        log.add(CommentRecord("a", "1", "one again", 3))
        log.add(CommentRecord("a", "3", "three", 4))
        log.add(CommentRecord("a", "4", "four", 5))
        assertEquals(listOf("1", "3", "4"), log.read().map { it.cid })
        assertEquals("one again", log.read().first().text)
        log.remove("3")
        assertEquals(listOf("1", "4"), log.read().map { it.cid })
    }

    @Test fun `a published comment is recorded and checked once after the delay`() {
        enable(feature(CheckSentComments.DELAY_SECONDS to 30L))
        backend.call(publish, Publisher(), PublishResponse(Comment("c1", "post", "hello", 1_700_000_000)), null)
        assertEquals(listOf(CommentRecord("post", "c1", "hello", 1_700_000_000)), log.read())
        assertEquals(listOf(0L, 30_000L), delays)
        assertEquals(listOf(Triple("post", "c1", "sessionid=abc")), requests)
        assertEquals(listOf(R.string.host_sent_comment_visible to emptyList<Any>()), prompts.toasts)
        // The log names the outcome only.
        assertTrue(logged.none { it.contains("hello") || it.contains("sessionid") || it.contains("c1") })
    }

    @Test fun `without auto-check a published comment is only recorded`() {
        enable(feature(CheckSentComments.AUTO_CHECK to false))
        backend.call(publish, Publisher(), PublishResponse(Comment("c1", "post", "hello", 5)), null)
        assertEquals(1, log.read().size)
        assertTrue(requests.isEmpty())
    }

    @Test fun `a response without a comment id records nothing`() {
        enable(feature())
        backend.call(publish, Publisher(), PublishResponse(Comment(null, "post", "hello", 5)), null)
        backend.call(publish, Publisher(), PublishResponse(null), null)
        assertTrue(log.read().isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test fun `my comments lists the newest first and opens the chosen one`() {
        log.add(CommentRecord("p1", "c1", "first", 1))
        log.add(CommentRecord("p2", "c2", "second", 2))
        enable(feature(CheckSentComments.AUTO_CHECK to false))
        response = """{"status_code":0,"comments":[{"cid":"c2","status":0}]}"""
        var dismissed = false
        val activity = Any()
        entries.added.single().onClick(activity, Any()) { dismissed = true }
        assertTrue(dismissed)
        val choice = prompts.choices.single()
        assertEquals(R.string.host_sent_comments_title, choice.title)
        assertTrue((choice.items[0] as String).endsWith("second"))
        choice.onChosen(0)
        assertEquals(listOf(Triple("p2", "c2", "sessionid=abc")), requests)
        val (message, _, open) = prompts.confirms.single()
        assertEquals(R.string.host_sent_comment_mutual, message)
        open()
        assertEquals(listOf(Triple(activity, "p2", "c2")), pages.opened)
    }

    @Test fun `no cookie or a failed request reports a failed check`() {
        CookieStore.value = ""
        try {
            enable(feature())
            backend.call(publish, Publisher(), PublishResponse(Comment("c1", "post", "hi", 5)), null)
            assertTrue(requests.isEmpty())
            assertEquals(R.string.host_sent_comment_failed, prompts.toasts.single().first)
        } finally {
            CookieStore.value = "sessionid=abc"
        }
    }

    @Test fun `an unknown status is reported with its value`() {
        response = """{"status_code":0,"comments":[{"cid":"c1","status":2}]}"""
        enable(feature())
        backend.call(publish, Publisher(), PublishResponse(Comment("c1", "post", "hi", 5)), null)
        assertEquals(R.string.host_sent_comment_hidden to listOf<Any>(2), prompts.toasts.single())
    }

    @Test fun `a request that throws is a failed check, not a crash`() {
        val failing = CheckSentComments(
            resolver(CommentQueries.PUBLISH_SUCCESS to listOf(publish), CommentQueries.WEB_COOKIE to listOf(cookie)), loader,
            values(CheckSentComments.ID, CheckSentComments.OPTIONS), log, entries, { pages }, prompts, Logger {},
            CommentLookup { _, _, _ -> throw IOException("offline") }, runner = { _, task -> task() },
        )
        enable(failing)
        backend.call(publish, Publisher(), PublishResponse(Comment("c1", "post", "hi", 5)), null)
        assertEquals(R.string.host_sent_comment_failed, prompts.toasts.single().first)
    }

    @Test fun `a cookie reader that is not static fails the install`() {
        failsToInstall(feature(cookieReader = method(InstanceCookie::class.java, "read")), "is not static")
    }
}
