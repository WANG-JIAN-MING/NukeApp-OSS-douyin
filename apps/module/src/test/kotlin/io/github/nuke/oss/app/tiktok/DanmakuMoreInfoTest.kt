package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.playback.DanmakuMoreInfo
import io.github.nuke.oss.app.tiktok.feature.playback.danmakuUserId
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the danmaku "More" entry, with stand-ins for the action handler, the danmaku and the popover.
class DanmakuMoreInfoTest : HookHarness() {
    class Danmaku(private val text: String) { override fun toString() = text; fun marker() {} }
    // The 40.7.0 shape: an item typed as a host interface, holding the danmaku one object down.
    class Item(@JvmField val inner: Inner)
    class Inner(@JvmField val danmaku: Danmaku)
    class Handler {
        fun show(item: Item?, flag: Boolean, source: String?) {}
        fun click(flag: Boolean, danmaku: Danmaku?) {}
        fun click(danmaku: Danmaku?) {}
        fun other(text: String?) {}
    }
    class Popover { fun show(anchor: Any?, offset: Int, flag: Boolean) {}; fun show(anchor: Any?) {} }
    class NoPopover { fun hide() {} }

    private val prompts = RecordingPrompts()
    private val ui = RecordingDanmakuMenu()
    private val logged = ArrayList<String>()
    private val two = method(Handler::class.java, "click", Boolean::class.javaPrimitiveType!!, Danmaku::class.java)
    private val one = method(Handler::class.java, "click", Danmaku::class.java)
    private val show = method(Popover::class.java, "show", Any::class.java, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)

    private fun feature(popover: String = Popover::class.java.name, action: java.lang.reflect.Method = two) = DanmakuMoreInfo(
        resolver(FeedQueries.DANMAKU_ACTION to listOf(action), FeedQueries.DANMAKU_STRUCT to listOf(method(Danmaku::class.java, "marker"))),
        loader, { ui }, prompts, Logger { logged += it }, popover,
    )

    @Test fun `the entry opens the details of the danmaku the menu is for`() {
        enable(feature())
        backend.call(two, Handler(), true, Danmaku("DanmakuStruct(danmakuId=1, userId=u42, text=hi)"))
        val popover = Popover()
        backend.call(show, popover, Any(), 0, true)
        val (attached, onClick) = ui.attached.single()
        assertEquals(popover, attached)
        onClick(Any())
        val confirm = prompts.confirms.single()
        assertEquals(R.string.host_danmaku_info, confirm.first)
        assertEquals(listOf<Any>("DanmakuStruct(danmakuId=1, userId=u42, text=hi)"), confirm.second)
        confirm.third()
        assertEquals(listOf("u42"), ui.copied)
    }

    @Test fun `the newest danmaku wins and the other overload is followed too`() {
        enable(feature())
        backend.call(two, Handler(), true, Danmaku("DanmakuStruct(userId=a)"))
        backend.call(one, Handler(), Danmaku("DanmakuStruct(userId=b)"))
        backend.call(show, Popover(), Any(), 0, true)
        ui.attached.single().second(Any())
        prompts.confirms.single().third()
        assertEquals(listOf("b"), ui.copied)
    }

    @Test fun `a danmaku held by the item the handler receives is found`() {
        enable(feature(action = method(Handler::class.java, "show", Item::class.java, Boolean::class.javaPrimitiveType!!, String::class.java)))
        backend.call(method(Handler::class.java, "show", Item::class.java, Boolean::class.javaPrimitiveType!!, String::class.java), Handler(), Item(Inner(Danmaku("DanmakuStruct(userId=w9)"))), true, "x")
        backend.call(show, Popover(), Any(), 0, true)
        ui.attached.single().second(Any())
        prompts.confirms.single().third()
        assertEquals(listOf("w9"), ui.copied)
    }

    @Test fun `an item without a danmaku leaves the entry without details`() {
        enable(feature(action = method(Handler::class.java, "show", Item::class.java, Boolean::class.javaPrimitiveType!!, String::class.java)))
        backend.call(method(Handler::class.java, "show", Item::class.java, Boolean::class.javaPrimitiveType!!, String::class.java), Handler(), null, true, "x")
        backend.call(show, Popover(), Any(), 0, true)
        ui.attached.single().second(Any())
        assertEquals(listOf(R.string.host_danmaku_unavailable to emptyList<Any>()), prompts.toasts)
    }

    @Test fun `the log says how far the feature got, once per outcome`() {
        enable(feature())
        backend.call(two, Handler(), true, Danmaku("DanmakuStruct(userId=a)"))
        backend.call(two, Handler(), true, Danmaku("DanmakuStruct(userId=b)"))
        backend.call(show, Popover(), Any(), 0, true)
        backend.call(show, Popover(), Any(), 0, true)
        assertEquals(
            listOf("feature=DanmakuMoreInfo handler_seen danmaku=found", "feature=DanmakuMoreInfo popover_shown danmaku=true"),
            logged,
        )
    }

    @Test fun `without a danmaku the entry says there are no details`() {
        enable(feature())
        backend.call(show, Popover(), Any(), 0, true)
        ui.attached.single().second(Any())
        assertEquals(listOf(R.string.host_danmaku_unavailable to emptyList<Any>()), prompts.toasts)
    }

    @Test fun `a handler that takes no danmaku fails the install`() {
        failsToInstall(feature(action = method(Handler::class.java, "other", String::class.java)), "takes no")
    }

    @Test fun `a popover without show fails the install`() {
        failsToInstall(feature(popover = NoPopover::class.java.name), "declares no show")
    }

    @Test fun `the sender is read from the printed danmaku`() {
        assertEquals("u42", danmakuUserId("DanmakuStruct(danmakuId=1, userId=u42)"))
        assertNull(danmakuUserId("DanmakuStruct(danmakuId=1)"))
        assertTrue(danmakuUserId("userId=7,")!! == "7")
    }
}
