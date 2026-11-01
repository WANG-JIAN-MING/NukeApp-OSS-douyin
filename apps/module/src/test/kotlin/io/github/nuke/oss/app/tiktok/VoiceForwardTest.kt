package io.github.nuke.oss.app.tiktok

import com.bytedance.im.core.model.Message
import com.ss.android.ugc.aweme.im.sdk.chat.model.AudioContent
import com.ss.android.ugc.aweme.im.sdk.chat.model.BaseContent
import com.ss.android.ugc.aweme.im.sdk.chat.model.TextContent
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostContacts
import io.github.nuke.oss.app.shared.settings.HostFriend
import io.github.nuke.oss.app.shared.settings.HostGroup
import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.chat.ContentCodec
import io.github.nuke.oss.app.tiktok.feature.chat.VoiceForward
import io.github.nuke.oss.app.tiktok.feature.chat.VoiceSnapshot
import io.github.nuke.oss.app.tiktok.query.VoiceQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ContentSender
import io.github.nuke.oss.app.tiktok.sdk.chat.SendResult
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for voice forwarding, with stand-ins for the menu, the click handlers, the sender and the contacts.
class VoiceForwardTest : HookHarness() {
    interface Capability { fun canForward(): Boolean }
    class Menu { fun make(context: Any?, session: Any?, message: Message, content: BaseContent, capability: Capability): Any = capability }
    open class Handler(@JvmField var content: BaseContent, @JvmField var message: Message) {
        var ran = 0
        open fun click(view: Any?) { ran++ }
    }
    class ThreadHandler(content: BaseContent, message: Message) : Handler(content, message) { fun clickInThread(view: Any?) { ran++ } }

    // The content survives encoding as the same object, plus a mention the host's own JSON would also carry.
    class FakeCodec : ContentCodec {
        private val stored = ArrayList<AudioContent>()
        override fun encode(content: Any): String { stored += content as AudioContent; return "c${stored.size - 1}" }
        override fun decode(json: String, type: Class<*>): Any = AudioContent().also { copy ->
            val original = stored[json.drop(1).toInt()]
            copy.type = original.type; copy.duration = original.duration; copy.md5 = original.md5; copy.url = original.url
            copy.waveList = original.waveList; copy.audioContentType = original.audioContentType; copy.aiAudioText = original.aiAudioText
            copy.mention = "@someone"
        }
    }

    internal class FakeSender : ContentSender {
        val sent = ArrayList<Pair<String, AudioContent>>()
        val results = ArrayDeque<SendResult>()
        private fun next(): SendResult = results.removeFirstOrNull() ?: SendResult.Sent("u", 1, "")
        override fun send(uid: String, content: Any, timeoutMillis: Long): SendResult { sent += "user:$uid" to content as AudioContent; return next() }
        override fun sendToConversation(conversationId: String, content: Any, timeoutMillis: Long): SendResult {
            sent += "chat:$conversationId" to content as AudioContent
            return next()
        }
    }

    private val prompts = RecordingPrompts()
    private val sender = FakeSender()
    private var account = 42L
    private val inline = BackgroundRunner { _, task -> task() }
    private val never = BackgroundRunner { _, _ -> }
    private val friends = listOf(HostFriend("1", "Ann", null, 3, "c1"), HostFriend("2", "Bob", null, 3, "c2"))
    private val groups = listOf(HostGroup("g1", "Team", null))
    private var list = HostContactList(friends, groups)

    private val menu = method(Menu::class.java, "make", Any::class.java, Any::class.java, Message::class.java, BaseContent::class.java, Capability::class.java)
    private val click = method(Handler::class.java, "click", Any::class.java)
    private val threadClick = method(ThreadHandler::class.java, "clickInThread", Any::class.java)

    private fun feature() = VoiceForward(
        resolver(
            VoiceQueries.MENU_FACTORY to listOf(menu), VoiceQueries.FORWARD_CAPABILITY to listOf(method(Capability::class.java, "canForward")),
            VoiceQueries.FORWARD_ACTION to listOf(click), VoiceQueries.THREAD_FORWARD_ACTION to listOf(threadClick),
        ),
        loader, HostContacts { list }, { account }, sender, FakeCodec(), prompts, Logger {}, inline, never,
    )

    private fun voice() = AudioContent().apply { duration = 4000; url = "https://voice"; md5 = "abc"; mention = "@original"; aiAudioText = "hi" }

    @Test fun `the menu's forward switch answers true for a voice that can be forwarded`() {
        enable(feature())
        val closed = object : Capability { override fun canForward() = false }
        val made = backend.call(menu, Menu(), null, null, Message(false), voice(), closed) as Capability
        assertTrue(made.canForward())
        val recalled = backend.call(menu, Menu(), null, null, Message(true), voice(), closed) as Capability
        assertFalse(recalled.canForward())
        val text = backend.call(menu, Menu(), null, null, Message(false), TextContent(), closed) as Capability
        assertFalse(text.canForward())
    }

    @Test fun `choosing a voice's forward lists friends and chats, and sends a clean copy to each pick`() {
        enable(feature())
        val handler = Handler(voice(), Message(false))
        backend.call(click, handler, "view")
        assertEquals(0, handler.ran)
        val choice = prompts.manyChoices.single()
        assertEquals(listOf<Any>("Ann", "Bob", "👥 Team"), choice.items)
        assertEquals(20, choice.limit)
        choice.onChosen(setOf(0, 2))
        assertEquals(listOf("user:1", "chat:g1"), sender.sent.map { it.first })
        val copy = sender.sent.first().second
        assertEquals(4000L, copy.duration)
        assertEquals("abc", copy.md5)
        assertEquals(R.string.host_notice_voice_done, prompts.toasts.last().first)
        assertEquals(listOf<Any>(2), prompts.toasts.last().second)
    }

    @Test fun `the thread handler is taken over too`() {
        enable(feature())
        val handler = ThreadHandler(voice(), Message(false))
        backend.call(threadClick, handler, "view")
        assertEquals(0, handler.ran)
        assertEquals(1, prompts.manyChoices.size)
    }

    @Test fun `a failed send stops the run and later recipients are not tried`() {
        enable(feature())
        backend.call(click, Handler(voice(), Message(false)), "view")
        sender.results += SendResult.Sent("u", 1, "")
        sender.results += SendResult.Failed("403: no permission")
        prompts.manyChoices.single().onChosen(setOf(0, 1, 2))
        assertEquals(2, sender.sent.size)
        assertEquals(R.string.host_notice_voice_partial, prompts.toasts.last().first)
        assertEquals(listOf<Any>(1, 3, "403: no permission"), prompts.toasts.last().second)
    }

    @Test fun `an unknown outcome also stops the run`() {
        enable(feature())
        backend.call(click, Handler(voice(), Message(false)), "view")
        sender.results += SendResult.Unknown("No answer from the host in 30 s")
        prompts.manyChoices.single().onChosen(setOf(0, 1))
        assertEquals(1, sender.sent.size)
        assertEquals(R.string.host_notice_voice_partial, prompts.toasts.last().first)
    }

    @Test fun `a changed account stops before sending`() {
        enable(feature())
        backend.call(click, Handler(voice(), Message(false)), "view")
        account = 43
        prompts.manyChoices.single().onChosen(setOf(0))
        assertTrue(sender.sent.isEmpty())
        assertEquals(R.string.host_notice_voice_partial, prompts.toasts.last().first)
    }

    @Test fun `a message that cannot be forwarded is reported and the host's own forward is not run`() {
        enable(feature())
        val handler = Handler(voice(), Message(true))
        backend.call(click, handler, "view")
        assertEquals(0, handler.ran)
        assertTrue(prompts.manyChoices.isEmpty())
        assertEquals(R.string.host_notice_voice_failed, prompts.toasts.last().first)
    }

    @Test fun `other content is left to the host`() {
        enable(feature())
        val handler = Handler(TextContent(), Message(false))
        backend.call(click, handler, "view")
        assertEquals(1, handler.ran)
        assertTrue(prompts.manyChoices.isEmpty())
    }

    @Test fun `without anyone to choose, or while one run is open, nothing new starts`() {
        enable(feature())
        list = HostContactList(emptyList(), emptyList())
        backend.call(click, Handler(voice(), Message(false)), "view")
        assertEquals(R.string.host_notice_voice_none, prompts.toasts.last().first)
        list = HostContactList(friends, groups)
        backend.call(click, Handler(voice(), Message(false)), "view")
        backend.call(click, Handler(voice(), Message(false)), "view")
        assertEquals(1, prompts.manyChoices.size)
        assertEquals(R.string.host_notice_voice_busy, prompts.toasts.last().first)
    }

    @Test fun `a snapshot needs a duration and a resource`() {
        val codec = FakeCodec()
        assertTrue(runCatching { VoiceSnapshot.capture(AudioContent(), codec) }.isFailure)
        assertTrue(runCatching { VoiceSnapshot.capture(AudioContent().apply { duration = 1 }, codec) }.isFailure)
        assertTrue(runCatching { VoiceSnapshot.capture(TextContent(), codec) }.isFailure)
        assertTrue(VoiceSnapshot.supports(AudioContent()))
    }

    @Test fun `the copy a recipient gets carries the media and not the original's mention`() {
        val codec = FakeCodec()
        val snapshot = VoiceSnapshot.capture(voice(), codec)
        val copy = snapshot.freshContent() as AudioContent
        assertEquals(4000L, copy.duration)
        assertEquals("hi", copy.aiAudioText)
        assertNull(copy.mention)
    }
}
