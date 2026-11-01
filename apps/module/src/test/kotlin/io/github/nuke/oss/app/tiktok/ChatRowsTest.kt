package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.sdk.chat.ChatMessageFinder
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatRows
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

// How a row finds its message when the adapter's data store is a wrapper around the object that holds the lists, as in
// Douyin 40.7.0: a wrapper with no list of its own, a controller behind it with a mirror list of a different entry type
// next to the displayed one.
class ChatRowsTest {
    class Message
    class Cell(@JvmField val message: Message)
    class MirrorEntry(@JvmField val message: Message)

    class Controller(@JvmField val mirror: List<MirrorEntry>, @JvmField val shown: List<Cell>) {
        // A getter on a live controller must not be called while a row is bound.
        @Suppress("unused") fun snapshot(): List<Cell> = error("getter called")
    }
    class Wrapper(@JvmField val controller: Controller)
    class Direct(@JvmField val entries: List<Cell>)

    private val rows = ChatRows(ChatMessageFinder(Message::class.java))
    private val a = Message()
    private val b = Message()
    private val c = Message()

    @Test fun `the list behind a wrapper store is searched and rows count from the newest`() {
        val store = Wrapper(Controller(emptyList(), listOf(Cell(a), Cell(b), Cell(c))))
        assertSame(c, rows.messageAt(store, 0, 3))
        assertSame(a, rows.messageAt(store, 2, 3))
    }

    @Test fun `the list whose size is the row count wins over the mirror`() {
        val store = Wrapper(Controller(listOf(MirrorEntry(a)), listOf(Cell(b), Cell(c))))
        assertSame(c, rows.messageAt(store, 0, 2))
        assertSame(a, rows.messageAt(store, 0, 1))
    }

    @Test fun `a row count no list matches does not stop the lookup`() {
        val store = Wrapper(Controller(emptyList(), listOf(Cell(a), Cell(b))))
        assertSame(b, rows.messageAt(store, 0, 99))
        assertSame(b, rows.messageAt(store, 0))
    }

    @Test fun `a store that holds the list itself still works`() {
        assertSame(b, ChatRows(ChatMessageFinder(Message::class.java)).messageAt(Direct(listOf(Cell(a), Cell(b))), 0, 2))
    }

    @Test fun `a position past the list finds nothing`() {
        assertNull(rows.messageAt(Wrapper(Controller(emptyList(), listOf(Cell(a)))), 5, 1))
    }
}
