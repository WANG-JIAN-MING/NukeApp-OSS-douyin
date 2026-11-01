package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.chat.BlockReadStatusUpload
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import org.junit.Assert.assertEquals
import org.junit.Test

// Contract tests for holding back read status per chat, with stand-ins for the host's mark-read handler, the item that
// carries the conversation and the conversation.
class BlockReadStatusUploadTest : HookHarness() {
    class Conversation(private val id: String?) { fun getConversationId() = id }
    // The mark step takes an item that holds the conversation; the request step takes the conversation itself.
    class MarkItem(@JvmField val conversation: Conversation?)
    class ReadHandler {
        var sent = 0
        fun doMark(item: MarkItem?) { sent++ }
        fun doRequest(index: Long, conversation: Conversation?) { sent++ }
    }
    class BadHandler { fun doMark(item: MarkItem?): Boolean = true }

    private val mark = method(ReadHandler::class.java, "doMark", MarkItem::class.java)
    private val request = method(ReadHandler::class.java, "doRequest", Long::class.javaPrimitiveType!!, Conversation::class.java)

    private fun feature(mode: String, chats: List<String> = emptyList(), steps: List<java.lang.reflect.Method> = listOf(mark, request)) = BlockReadStatusUpload(
        resolver(ChatQueries.MARK_READ_BY_INDEX to listOf(steps.first()), ChatQueries.MARK_READ_REQUEST to listOf(steps.last())), loader,
        values(BlockReadStatusUpload.ID, BlockReadStatusUpload.OPTIONS, "mode" to mode, "chats" to chats), Conversation::class.java.name,
    )

    // Runs both steps for a conversation and returns how many of them reached the host.
    private fun reach(id: String?): Int {
        val handler = ReadHandler()
        backend.call(mark, handler, MarkItem(Conversation(id)))
        backend.call(request, handler, 5L, Conversation(id))
        return handler.sent
    }

    @Test fun `all chats hold back every conversation`() {
        enable(feature("all", listOf("a")))
        assertEquals(0, reach("a"))
        assertEquals(0, reach("b"))
    }

    @Test fun `the blacklist holds back only the listed chats`() {
        enable(feature("blacklist", listOf("a")))
        assertEquals(0, reach("a"))
        assertEquals(2, reach("b"))
    }

    @Test fun `the whitelist reports only the listed chats`() {
        enable(feature("whitelist", listOf("a")))
        assertEquals(2, reach("a"))
        assertEquals(0, reach("b"))
    }

    @Test fun `a conversation that cannot be told apart counts as unlisted`() {
        val blacklist = feature("blacklist", listOf("a"))
        enable(blacklist)
        assertEquals(2, reach(null))
        val handler = ReadHandler()
        backend.call(mark, handler, MarkItem(null))
        backend.call(mark, handler, null)
        assertEquals(2, handler.sent)
        disable(blacklist)
    }

    @Test fun `an unlisted unknown conversation is held back by the whitelist`() {
        enable(feature("whitelist", listOf("a")))
        assertEquals(0, reach(null))
    }

    @Test fun `turning the feature off sends read status again`() {
        val feature = feature("all")
        enable(feature)
        assertEquals(0, reach("a"))
        disable(feature)
        assertEquals(2, reach("a"))
    }

    @Test fun `the default holds back every chat`() {
        assertEquals("all", BlockReadStatusUpload.OPTIONS.filterIsInstance<io.github.nuke.oss.app.shared.settings.ChoiceOption>().single().default)
    }

    @Test fun `a step that no longer returns void fails the install`() {
        val bad = method(BadHandler::class.java, "doMark", MarkItem::class.java)
        failsToInstall(feature("all", steps = listOf(bad)), "expected void")
    }
}
