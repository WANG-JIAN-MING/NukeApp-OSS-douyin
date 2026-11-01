package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.chat.MessageSendTime
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatAdapterEvents
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatMessageFinder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the message send time: finding the message behind a row, the hooks on the chat adapter and the text
// that reaches the row. Stand-ins replace the adapter, its data store and the message.
class MessageSendTimeTest : HookHarness() {
    class Msg(private val at: Long, private val mine: Boolean) {
        fun getCreatedAt() = at
        fun isSelf() = mine
    }
    class Cell(@JvmField val payload: Any?)
    class Entry(@JvmField val cell: Cell?, @JvmField val tag: String = "x")
    class Store(private val entries: List<Entry>) { fun entries(): List<Entry> = entries }
    class Holder(@JvmField val itemView: Any)
    class Adapter(@Suppress("UNUSED_PARAMETER") context: Any?, @Suppress("UNUSED_PARAMETER") store: Store?) {
        init { last = this }
        fun onCreateViewHolder(parent: Any?, type: Int): Holder = Holder(Any())
        fun onBindViewHolder(holder: Holder?, position: Int, payloads: List<*>?) {}
        // The fake backend builds the object itself, so the test reads it back from here.
        companion object { @JvmStatic var last: Adapter? = null }
    }
    class NoCreate { fun onBindViewHolder(holder: Holder?, position: Int, payloads: List<*>?) {} }

    private val labels = RecordingMessageLabels()
    private val create = method(Adapter::class.java, "onCreateViewHolder", Any::class.java, Int::class.javaPrimitiveType!!)
    private val bind = method(Adapter::class.java, "onBindViewHolder", Holder::class.java, Int::class.javaPrimitiveType!!, List::class.java)
    private val constructor = Adapter::class.java.getDeclaredConstructor(Any::class.java, Store::class.java)

    private fun feature(vararg saved: Pair<String, Any>, adapter: String = Adapter::class.java.name) =
        MessageSendTime(ChatAdapterEvents(loader, adapter, Msg::class.java.name), { labels }, values(MessageSendTime.ID, MessageSendTime.OPTIONS, *saved))

    private fun format(pattern: String, millis: Long) =
        DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun built(store: Store): Adapter {
        backend.call(constructor, null, null, store)
        return checkNotNull(Adapter.last)
    }

    @Test fun `a bound row shows the time of its message formatted with the pattern`() {
        enable(feature("pattern" to "HH:mm:ss"))
        val older = Msg(1_700_000_000_000, true)
        val newer = Msg(1_700_000_123_000, false)
        val receiver = built(Store(listOf(Entry(Cell(older)), Entry(Cell(newer)))))
        val row = Any()
        backend.call(bind, receiver, Holder(row), 0, emptyList<Any>())
        backend.call(bind, receiver, Holder(row), 1, emptyList<Any>())
        assertEquals(
            listOf(Triple(row, format("HH:mm:ss", 1_700_000_123_000), false), Triple(row, format("HH:mm:ss", 1_700_000_000_000), true)),
            labels.shown,
        )
    }

    @Test fun `a created row is prepared once with the chosen color`() {
        enable(feature("color" to "#FF8800"))
        val holder = backend.call(create, Adapter(null, null), Any(), 3) as Holder
        assertEquals(listOf(holder.itemView to 0xFFFF8800.toInt()), labels.prepared)
    }

    @Test fun `an adapter whose store was never seen shows nothing`() {
        enable(feature())
        backend.call(bind, Adapter(null, null), Holder(Any()), 0, emptyList<Any>())
        assertTrue(labels.shown.isEmpty())
    }

    @Test fun `a row beyond the store shows nothing`() {
        enable(feature())
        val receiver = built(Store(listOf(Entry(Cell(Msg(1, true))))))
        backend.call(bind, receiver, Holder(Any()), 5, emptyList<Any>())
        assertTrue(labels.shown.isEmpty())
    }

    @Test fun `a bad color or pattern fails the install`() {
        failsToInstall(feature("color" to "orange"), "is not a #RRGGBB color")
    }

    @Test fun `an unusable pattern fails the install`() {
        failsToInstall(feature("pattern" to "bad {pattern"), "is not a time pattern")
    }

    @Test fun `an adapter without the view holder methods fails the install`() {
        failsToInstall(feature(adapter = NoCreate::class.java.name), "declares no single onCreateViewHolder")
    }

    @Test fun `the finder searches the entry for the message and remembers the path`() {
        val finder = ChatMessageFinder(Msg::class.java)
        val message = Msg(1, true)
        assertSame(message, finder.find(Entry(Cell(message))))
        assertSame(message, finder.find(message))
        assertNull(finder.find(Entry(null)))
        assertNull(finder.find(null))
        val other = Msg(2, false)
        assertSame(other, finder.find(Entry(Cell(other))))
    }

    @Test fun `colors are read as six or eight hex digits`() {
        assertEquals(0xFF112233.toInt(), MessageSendTime.parseColor("#112233"))
        assertEquals(0x80112233.toInt(), MessageSendTime.parseColor("#80112233"))
        assertNull(MessageSendTime.parseColor("112233"))
        assertNull(MessageSendTime.parseColor("#12"))
    }
}
