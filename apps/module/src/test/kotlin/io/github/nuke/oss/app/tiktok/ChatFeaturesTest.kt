package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.chat.*
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import org.junit.Assert.*
import org.junit.Test

// Contract tests for the chat features against stand-ins for the host classes. They prove the Hook semantics, not
// that any Douyin build has these targets.
class ChatFeaturesTest : HookHarness() {
    class ForwardAction {
        fun type(): String = "forward"
        fun applies(message: Any?): Boolean = false
        fun applies(message: Any?, group: Boolean): Boolean = false
        fun order(): Int = 0
    }
    class TwoChecks {
        fun type(): String = "forward"
        fun first(): Boolean = false
        fun second(): Boolean = false
    }

    @Test fun `every overload of the forward check answers yes`() {
        val feature = AllowAnyForward(resolver(ChatQueries.FORWARD_ACTION to listOf(method(ForwardAction::class.java, "type"))), loader)
        enable(feature)
        val action = ForwardAction()
        assertEquals(true, backend.call(method(ForwardAction::class.java, "applies", Any::class.java), action, null))
        assertEquals(true, backend.call(method(ForwardAction::class.java, "applies", Any::class.java, Boolean::class.javaPrimitiveType!!), action, null, false))
        disable(feature)
        assertEquals(false, backend.call(method(ForwardAction::class.java, "applies", Any::class.java), action, null))
    }

    @Test fun `two differently named checks make the forward action ambiguous`() {
        failsToInstall(AllowAnyForward(resolver(ChatQueries.FORWARD_ACTION to listOf(method(TwoChecks::class.java, "type"))), loader), "found 2")
    }

    class BubbleMessage(private val mine: Boolean, private val ext: MutableMap<String, String>) {
        fun getExt(): MutableMap<String, String> = ext
        fun isSelf(): Boolean = mine
    }
    class FrozenMessage { fun getExt(): Map<String, String> = java.util.Collections.unmodifiableMap(mapOf("a:chat_bubble" to "{}")); fun isSelf() = true }

    private fun bubble(id: String) = CustomChatBubble(
        loader, values(CustomChatBubble.ID, CustomChatBubble.OPTIONS, "bubble-id" to id), BubbleMessage::class.java.name,
    )

    @Test fun `your own message with a bubble gets the chosen bubble`() {
        val feature = bubble(" 12345 ")
        enable(feature)
        val getExt = method(BubbleMessage::class.java, "getExt")
        val mine = BubbleMessage(true, hashMapOf("a:chat_bubble" to "{}", "other" to "x"))
        backend.call(getExt, mine)
        assertEquals("""{"bubble_id":"12345","bubble_source":"0"}""", mine.getExt()["a:chat_bubble"])
        assertEquals("x", mine.getExt()["other"])
        val theirs = BubbleMessage(false, hashMapOf("a:chat_bubble" to "{}"))
        backend.call(getExt, theirs)
        assertEquals("{}", theirs.getExt()["a:chat_bubble"])
        val plain = BubbleMessage(true, hashMapOf())
        backend.call(getExt, plain)
        assertTrue(plain.getExt().isEmpty())
        disable(feature)
        val after = BubbleMessage(true, hashMapOf("a:chat_bubble" to "{}"))
        backend.call(getExt, after)
        assertEquals("{}", after.getExt()["a:chat_bubble"])
    }

    @Test fun `an empty bubble id hooks nothing`() {
        enable(bubble(""))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `a bubble id that is not digits fails the install`() {
        failsToInstall(bubble("12a"), "digits only")
    }

    @Test fun `a read-only extension map is left alone`() {
        enable(CustomChatBubble(loader, values(CustomChatBubble.ID, CustomChatBubble.OPTIONS, "bubble-id" to "7"), FrozenMessage::class.java.name))
        assertEquals(mapOf("a:chat_bubble" to "{}"), backend.call(method(FrozenMessage::class.java, "getExt"), FrozenMessage()))
        assertTrue(failures.isEmpty())
    }

    class StreakChat(private val id: String) { fun getId() = id }
    class StreakState {
        @JvmField var LIZLLL = 3
        @JvmField var LJ: String? = "3"
        @JvmField var LJFF: String? = "3"
    }
    class StreakUi {
        fun render(host: Any?, chat: StreakChat?, state: StreakState?) {}
    }
    class TwoParamStreak { fun render(chat: StreakChat?, state: StreakState?) {} }

    private val render = method(StreakUi::class.java, "render", Any::class.java, StreakChat::class.java, StreakState::class.java)

    private fun streak(vararg lines: String, target: java.lang.reflect.Method = render) = CustomStreakDays(
        resolver(ChatQueries.STREAK_RENDER to listOf(target)), loader,
        values(CustomStreakDays.ID, CustomStreakDays.OPTIONS, "days" to lines.toList()),
    )

    @Test fun `a chosen chat shows its days and the star entry covers the rest`() {
        enable(streak("chat-1=100", "*=7"))
        val special = StreakState()
        backend.call(render, StreakUi(), null, StreakChat("chat-1"), special)
        assertEquals(100, special.LIZLLL)
        assertEquals("100", special.LJ)
        assertEquals("100", special.LJFF)
        val other = StreakState()
        backend.call(render, StreakUi(), null, StreakChat("chat-2"), other)
        assertEquals(7, other.LIZLLL)
        backend.call(render, StreakUi(), null, null, StreakState())
    }

    @Test fun `without a star entry other chats keep the host's days`() {
        enable(streak("chat-1=100"))
        val state = StreakState()
        backend.call(render, StreakUi(), null, StreakChat("other"), state)
        assertEquals(3, state.LIZLLL)
        assertEquals("3", state.LJ)
    }

    @Test fun `no lines hook nothing`() {
        enable(streak())
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `a bad line fails the install with the line`() {
        failsToInstall(streak("chat-1"), "Not a conversation=days line: chat-1")
    }

    @Test fun `days out of range fail the install`() {
        failsToInstall(streak("chat-1=-4"), "Days must be 0 to 99999")
    }

    @Test fun `a render method with another shape fails the install`() {
        failsToInstall(
            streak("*=9", target = method(TwoParamStreak::class.java, "render", StreakChat::class.java, StreakState::class.java)),
            "does not take three parameters",
        )
    }

    class RawMessage(@JvmField val message_type: Int, @JvmField val content: String?)
    class ChatMessage(private val chat: String, private val id: Long) {
        fun getConversationId() = chat
        fun getMsgId() = id
    }
    class Observable {
        var subscribed = 0
        fun subscribe() { subscribed++ }
    }
    class PacketRequests {
        val opened = ArrayList<List<Any?>>()
        val last = Observable()
        fun openRedPacket(orderNo: String?, flag: String?, messageId: Long, a: Int, b: Int, version: String?): Observable {
            opened += listOf(orderNo, flag, messageId, a, b, version)
            return last
        }
    }
    class PacketApi {
        companion object {
            val requests = PacketRequests()
            @JvmStatic fun LIZ(): PacketRequests = requests
        }
        fun anchor() {}
    }
    class NoEntryApi { fun anchor() {} }
    class Dispatcher(private val message: ChatMessage?) { fun handle(a: Any?, b: Any?, raw: RawMessage?): ChatMessage? = message }
    class ShortDispatcher { fun handle(a: Any?, raw: RawMessage?): ChatMessage? = null }

    private class QueueRunner : BackgroundRunner {
        val delays = ArrayList<Long>()
        val queued = ArrayList<() -> Unit>()
        override fun run(delayMillis: Long, task: () -> Unit) { delays += delayMillis; queued += task }
    }

    private val handle = method(Dispatcher::class.java, "handle", Any::class.java, Any::class.java, RawMessage::class.java)
    private val packetAnchor = method(PacketApi::class.java, "anchor")
    private val runner = QueueRunner()
    private val logged = ArrayList<String>()

    private fun packets(
        chats: List<String> = emptyList(), words: List<String> = emptyList(),
        anchor: java.lang.reflect.Method = packetAnchor, target: java.lang.reflect.Method = handle,
        mode: String = "blacklist", allowed: List<String> = emptyList(),
    ) = OpenRedPackets(
        resolver(ChatQueries.INCOMING_MESSAGE to listOf(target), ChatQueries.RED_PACKET_API to listOf(anchor)), loader,
        values(
            OpenRedPackets.ID, OpenRedPackets.OPTIONS, "delay-ms" to 800L, "mode" to mode, "skip-chats" to chats,
            "allowed-chats" to allowed, "skip-words" to words,
        ),
        io.github.nuke.oss.core.Logger { logged += it }, runner,
    )

    private fun arrive(raw: RawMessage?, message: ChatMessage? = ChatMessage("chat-1", 77)) =
        backend.call(handle, Dispatcher(message), null, null, raw)

    @Test fun `a red packet is opened after the delay with the order and message id`() {
        PacketApi.requests.opened.clear()
        PacketApi.requests.last.subscribed = 0
        enable(packets())
        arrive(RawMessage(74, """{"order_no":"O-9","open_command":""}"""))
        assertEquals(listOf(800L), runner.delays)
        assertTrue(PacketApi.requests.opened.isEmpty())
        val first: () -> Unit = runner.queued[0]
        first()
        assertEquals(listOf(listOf<Any?>("O-9", "0", 77L, 0, 0, "8.0.6")), PacketApi.requests.opened)
        assertEquals(1, PacketApi.requests.last.subscribed)
    }

    @Test fun `other messages, skipped chats and skipped pass phrases are not opened`() {
        enable(packets(chats = listOf("chat-skip"), words = listOf("广告")))
        arrive(RawMessage(1, """{"order_no":"O-1"}"""))
        arrive(RawMessage(74, """{"order_no":"O-2"}"""), ChatMessage("chat-skip", 1))
        arrive(RawMessage(74, """{"order_no":"O-3","open_command":"来领广告红包"}"""))
        arrive(RawMessage(74, """{"order_no":""}"""))
        arrive(RawMessage(74, "not json"))
        arrive(RawMessage(74, null))
        arrive(RawMessage(74, """{"order_no":"O-4"}"""), null)
        assertTrue(runner.queued.isEmpty())
        assertTrue(logged.isEmpty())
    }

    @Test fun `blacklist mode opens every chat but the listed ones, which is the default`() {
        enable(packets(chats = listOf("chat-skip"), allowed = listOf("chat-1")))
        arrive(RawMessage(74, """{"order_no":"O-1"}"""), ChatMessage("chat-skip", 1))
        assertTrue(runner.queued.isEmpty())
        arrive(RawMessage(74, """{"order_no":"O-2"}"""), ChatMessage("chat-other", 2))
        assertEquals(1, runner.queued.size)
    }

    @Test fun `whitelist mode opens only the listed chats and ignores the blacklist`() {
        enable(packets(chats = listOf("chat-1"), allowed = listOf("chat-1", "chat-2"), mode = "whitelist"))
        arrive(RawMessage(74, """{"order_no":"O-1"}"""), ChatMessage("chat-1", 1))
        arrive(RawMessage(74, """{"order_no":"O-2"}"""), ChatMessage("chat-2", 2))
        assertEquals(2, runner.queued.size)
        arrive(RawMessage(74, """{"order_no":"O-3"}"""), ChatMessage("chat-3", 3))
        assertEquals(2, runner.queued.size)
    }

    @Test fun `pass phrase words still skip a packet in a whitelisted chat`() {
        enable(packets(words = listOf("广告"), allowed = listOf("chat-1"), mode = "whitelist"))
        arrive(RawMessage(74, """{"order_no":"O-1","open_command":"广告"}"""), ChatMessage("chat-1", 1))
        assertTrue(runner.queued.isEmpty())
    }

    @Test fun `an empty whitelist opens nothing and says so`() {
        enable(packets(mode = "whitelist"))
        arrive(RawMessage(74, """{"order_no":"O-1"}"""))
        assertTrue(runner.queued.isEmpty())
        assertEquals(1, logged.count { it.contains("whitelist_empty") })
    }

    @Test fun `a packet that arrives after the feature is turned off is not opened`() {
        PacketApi.requests.opened.clear()
        val feature = packets()
        enable(feature)
        arrive(RawMessage(74, """{"order_no":"O-5"}"""))
        disable(feature)
        val task: () -> Unit = runner.queued[runner.queued.size - 1]
        task()
        assertTrue(PacketApi.requests.opened.isEmpty())
    }

    @Test fun `a handler that does not take the raw message third fails the install`() {
        failsToInstall(packets(target = method(ShortDispatcher::class.java, "handle", Any::class.java, RawMessage::class.java)), "does not take the raw message third")
    }

    @Test fun `an api class without its static entry fails the install`() {
        failsToInstall(packets(anchor = method(NoEntryApi::class.java, "anchor")), "has no single static LIZ()")
    }
}
