package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.settings.MemoryFeatureMemory
import io.github.nuke.oss.app.tiktok.feature.chat.ChatRecallPolicy
import io.github.nuke.oss.app.tiktok.feature.chat.PreventMessageRecall2
import io.github.nuke.oss.app.tiktok.feature.chat.RecallNoticeIndex
import io.github.nuke.oss.app.tiktok.feature.chat.RecallNoticeKey
import io.github.nuke.oss.app.tiktok.query.RecallQueries
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for anti-recall 2, with stand-ins for the processor, the message database and the command body.
class PreventMessageRecall2Test : HookHarness() {
    class Message(
        private val uuid: String = "u1", private val id: Long = 100, private val conversation: String = "c1",
        private val self: Boolean = false, private val deleted: Boolean = false, private val recalled: Boolean = false, private val sender: Long = 7,
    ) {
        fun getUuid() = uuid
        fun getMsgId() = id
        fun getConversationId() = conversation
        fun isSelf() = self
        fun isDeleted() = deleted
        fun isRecalled() = recalled
        fun getSender() = sender
    }
    class Body(@JvmField var ext: Map<String, Any?>, @JvmField var message_type: Int, @JvmField var conversation_id: String = "c1",
               @JvmField var security_conversation_id: String? = null, @JvmField var server_message_id: Long = 999)
    class Dao(val message: Message?) {
        fun byUuid(uuid: String): Message? = message?.takeIf { it.getUuid() == uuid }
        fun byId(id: Long): Message? = message?.takeIf { it.getMsgId() == id }
    }
    class Outcome(@Suppress("unused") val handled: Boolean)
    class Completion { var outcome: Outcome? = null; fun complete(value: Outcome) { outcome = value } }
    class Processor(var dao: Dao) {
        var originals = 0
        val stored = ArrayList<String?>()
        val preStored = ArrayList<String?>()
        var preCompleted = 0
        fun getUid(): Long = 42
        fun getIMMsgDaoDelegate(): Dao = dao
        fun recall(a: Int, command: Any?, body: Body): Completion { originals++; return Completion() }
        fun updateExt(a: Int, command: Any?, body: Body): Completion { originals++; return Completion() }
        fun preRecall(save: Any, body: Body, command: Any?): Any { originals++; return "host" }
        fun preUpdateExt(save: Any, body: Body, command: Any?): Any { originals++; return "host" }
        fun cachedByUuid(conversation: String, uuid: String): Message? = dao.message?.takeIf { it.getUuid() == uuid && it.getConversationId() == conversation }
        fun cachedById(id: Long, conversation: String): Message? = dao.message?.takeIf { it.getMsgId() == id }
        fun storeCommand(body: Body, clientId: String?) { stored += clientId }
        fun preStoreCommand(save: Any, clientId: String?, body: Body) { preStored += clientId }
        fun preComplete(flag: Boolean) { preCompleted++ }
    }

    private val memory = MemoryFeatureMemory()
    private val scheduler = ManualScheduler()
    private val logged = ArrayList<String>()
    private val processor = Processor(Dao(Message()))
    private val ui = RecordingRecallNoticeUi()

    private fun m(name: String, vararg p: Class<*>) = method(Processor::class.java, name, *p)
    private val recall = m("recall", Int::class.javaPrimitiveType!!, Any::class.java, Body::class.java)
    private val updateExt = m("updateExt", Int::class.javaPrimitiveType!!, Any::class.java, Body::class.java)
    private val preRecall = m("preRecall", Any::class.java, Body::class.java, Any::class.java)
    private val preUpdateExt = m("preUpdateExt", Any::class.java, Body::class.java, Any::class.java)

    private fun feature() = PreventMessageRecall2(
        resolver(
            RecallQueries.RECALL to listOf(recall), RecallQueries.UPDATE_EXT to listOf(updateExt),
            RecallQueries.PRE_RECALL to listOf(preRecall), RecallQueries.PRE_UPDATE_EXT to listOf(preUpdateExt),
            RecallQueries.MESSAGE_BY_UUID to listOf(method(Dao::class.java, "byUuid", String::class.java)),
            RecallQueries.MESSAGE_BY_ID to listOf(method(Dao::class.java, "byId", Long::class.javaPrimitiveType!!)),
            RecallQueries.CACHED_BY_UUID to listOf(m("cachedByUuid", String::class.java, String::class.java)),
            RecallQueries.CACHED_BY_ID to listOf(m("cachedById", Long::class.javaPrimitiveType!!, String::class.java)),
            RecallQueries.STORE_COMMAND to listOf(m("storeCommand", Body::class.java, String::class.java)),
            RecallQueries.PRE_STORE_COMMAND to listOf(m("preStoreCommand", Any::class.java, String::class.java, Body::class.java)),
            RecallQueries.COMPLETE to listOf(method(Completion::class.java, "complete", Outcome::class.java)),
            RecallQueries.PRE_COMPLETE to listOf(m("preComplete", Boolean::class.javaPrimitiveType!!)),
        ),
        loader, scheduler, memory, { ui }, Logger { logged += it },
    )

    private fun command(uuid: String? = "u1", clientId: String = "cmd-1") =
        Body(mapOf("s:target_client_message_id" to uuid, "s:client_message_id" to clientId), 40001)

    @Test fun `a recall command for an incoming message is answered as handled and the message is kept`() {
        enable(feature())
        val completion = backend.call(recall, processor, 0, null, command()) as Completion
        assertEquals(0, processor.originals)
        assertTrue(completion.outcome != null)
        assertEquals(listOf<String?>("cmd-1"), processor.stored)
    }

    @Test fun `messages that are yours, deleted, recalled or of another conversation are left to the host`() {
        enable(feature())
        val cases = listOf(
            Message(self = true), Message(deleted = true), Message(recalled = true), Message(sender = 42), Message(conversation = "other"),
        )
        for (message in cases) {
            processor.dao = Dao(message)
            backend.call(recall, processor, 0, null, command())
        }
        assertEquals(cases.size, processor.originals)
        processor.dao = Dao(null)
        backend.call(recall, processor, 0, null, command())
        assertEquals(cases.size + 1, processor.originals)
    }

    @Test fun `the target can be found by its server id, and other command types are ignored`() {
        enable(feature())
        backend.call(recall, processor, 0, null, Body(mapOf("s:target_server_message_id" to "100", "s:client_message_id" to "c"), 40001))
        assertEquals(0, processor.originals)
        backend.call(recall, processor, 0, null, Body(mapOf("s:target_client_message_id" to "u1"), 12345))
        assertEquals(1, processor.originals)
        backend.call(recall, processor, 0, null, command(uuid = null))
        assertEquals(2, processor.originals)
    }

    @Test fun `an ext update counts only when it marks the message recalled, and is never stored as a message`() {
        enable(feature())
        val marked = Body(mapOf("s:is_recalled" to "true", "s:client_message_id" to "u1"), 50002)
        backend.call(updateExt, processor, 0, null, marked)
        assertEquals(0, processor.originals)
        assertTrue(processor.stored.isEmpty())
        backend.call(updateExt, processor, 0, null, Body(mapOf("s:client_message_id" to "u1"), 50002))
        assertEquals(1, processor.originals)
    }

    @Test fun `the batch pipeline variants return the save data and keep the command chain consistent`() {
        enable(feature())
        val body = command()
        assertEquals("save", backend.call(preRecall, processor, "save", body, null))
        assertEquals(0, processor.originals)
        assertEquals(1, processor.preCompleted)
        assertEquals(listOf<String?>("cmd-1"), processor.preStored)
        processor.dao = Dao(Message(self = true))
        assertEquals("host", backend.call(preUpdateExt, processor, "save", Body(mapOf("s:is_recalled" to "true", "s:client_message_id" to "u1"), 50002), null))
        assertEquals(1, processor.originals)
    }

    @Test fun `a held-back recall is remembered and persisted off the hook thread`() {
        enable(feature())
        backend.call(recall, processor, 0, null, command())
        assertNull(memory.read(PreventMessageRecall2.ID, "notices"))
        scheduler.advance(0)
        val stored = RecallNoticeIndex.decode(memory.read(PreventMessageRecall2.ID, "notices"))
        assertEquals(listOf(RecallNoticeKey("42", "c1", "u1", 100)), stored)
    }

    @Test fun `without the row classes the interception still works and says the notice is unavailable`() {
        enable(feature())
        assertTrue(logged.any { it.contains("notice_unavailable") })
        backend.call(recall, processor, 0, null, command())
        assertEquals(0, processor.originals)
    }

    @Test fun `closing the feature stops the interception`() {
        val feature = feature()
        enable(feature)
        disable(feature)
        backend.call(recall, processor, 0, null, command())
        assertEquals(1, processor.originals)
    }

    @Test fun `the policy picks targets and decides what may be stored`() {
        assertNull(ChatRecallPolicy.target(40001, emptyMap<String, Any>()))
        assertEquals("u", ChatRecallPolicy.target(40001, mapOf("s:target_client_message_id" to "u"))?.uuid)
        assertNull(ChatRecallPolicy.target(50002, mapOf("s:client_message_id" to "u")))
        assertEquals(5L, ChatRecallPolicy.target(50002, mapOf("s:recalled" to "true", "s:server_message_id" to "5"))?.serverId)
        assertTrue(ChatRecallPolicy.canStoreCommand(40001, "cmd", "u", 9, 5))
        assertFalse(ChatRecallPolicy.canStoreCommand(50002, "cmd", "u", 9, 5))
        assertFalse(ChatRecallPolicy.canStoreCommand(40001, "u", "u", 9, 5))
        assertFalse(ChatRecallPolicy.canStoreCommand(40001, "cmd", "u", 5, 5))
    }

    @Test fun `the index matches by server id, falls back to uuid, keeps a limit and survives bad data`() {
        val index = RecallNoticeIndex(limit = 2)
        val first = RecallNoticeKey("1", "c", "a", 10)
        index.mark(first)
        assertTrue(index.contains(RecallNoticeKey("1", "c", "other", 10)))
        assertFalse(index.contains(RecallNoticeKey("1", "c", "a", 11)))
        assertTrue(index.contains(RecallNoticeKey("1", "c", "a", 0)))
        index.mark(RecallNoticeKey("1", "c", "b"))
        index.mark(RecallNoticeKey("1", "c", "c"))
        assertFalse(index.contains(first))
        assertEquals(listOf(RecallNoticeKey("1", "c", "b"), RecallNoticeKey("1", "c", "c")), RecallNoticeIndex.decode(RecallNoticeIndex.encode(index.mark(RecallNoticeKey("1", "c", "c")))))
        assertTrue(RecallNoticeIndex.decode("{broken").isEmpty())
        assertTrue(RecallNoticeIndex.decode(null).isEmpty())
        assertFalse(RecallNoticeKey("0", "c", "a").isValid())
    }
}
