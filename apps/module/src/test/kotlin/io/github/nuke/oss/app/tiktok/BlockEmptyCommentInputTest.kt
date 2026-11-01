package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.dex.mapSearch
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.dex.DexCache
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.tiktok.feature.comment.BlockEmptyCommentInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luckypray.dexkit.wrap.DexMethod

// Contract tests for blocking the automatic comment keyboard on both comment panels, with stand-ins for the steps.
class BlockEmptyCommentInputTest : HookHarness() {
    class OldPanel { var opened = 0; fun show(count: Long, tag: String?) { opened++ } }
    class Request(private val text: String) { override fun toString() = text }
    class NewPanel { var opened = 0; fun show(request: Request?, flag: Boolean) { opened++ } }
    class Returning { fun show(count: Long): Int = 1 }
    class Plain { fun show(flag: Boolean) {} }

    private val prompts = RecordingPrompts()
    private val old = method(OldPanel::class.java, "show", Long::class.javaPrimitiveType!!, String::class.java)
    private val new = method(NewPanel::class.java, "show", Request::class.java, Boolean::class.javaPrimitiveType!!)

    private fun feature(vararg tagged: Pair<String, java.lang.reflect.Method>): BlockEmptyCommentInput {
        val lines = tagged.joinToString("\n") { (panel, method) -> "$panel:" + DexMethod(method).serialize() }
        val resolver = DexResolver(object : DexCache {
            override fun get(key: String): String? = null
            override fun put(key: String, descriptor: String) {}
        }, "scope", mapSearch { queries -> queries.mapValues { lines } }, generatedDexQueries)
        return BlockEmptyCommentInput(resolver, loader, prompts)
    }

    private val automatic = "Request(scene=DEFAULT, enterMethod=zero_enter_comment_board, clickType=zero_enter_comment_board)"

    @Test fun `the older panel's keyboard is skipped when the comment count is zero`() {
        enable(feature("old" to old))
        val panel = OldPanel()
        backend.call(old, panel, 0L, "x")
        backend.call(old, panel, 5L, "x")
        assertEquals(1, panel.opened)
        assertEquals(listOf(R.string.host_notice_comment_input_blocked to emptyList<Any>()), prompts.toasts)
    }

    @Test fun `the newer panel's keyboard is skipped only for the automatic request`() {
        enable(feature("new" to new))
        val panel = NewPanel()
        backend.call(new, panel, Request(automatic), false)
        backend.call(new, panel, Request("Request(scene=DEFAULT, enterMethod=click_text_bar, clickType=click_text_bar)"), false)
        backend.call(new, panel, null, false)
        assertEquals(2, panel.opened)
    }

    @Test fun `both panels can be present at once`() {
        enable(feature("old" to old, "new" to new))
        backend.call(old, OldPanel(), 0L, null)
        backend.call(new, NewPanel(), Request(automatic), true)
        assertEquals(2, prompts.toasts.size)
    }

    @Test fun `a step that returns a value fails the install`() {
        failsToInstall(feature("old" to method(Returning::class.java, "show", Long::class.javaPrimitiveType!!)), "returns int")
    }

    @Test fun `a step without anything to tell the cases apart fails the install`() {
        failsToInstall(feature("old" to method(Plain::class.java, "show", Boolean::class.javaPrimitiveType!!)), "No keyboard step")
    }

    @Test fun `only the zero-comment board request counts as automatic`() {
        assertTrue(BlockEmptyCommentInput.isAutomaticRequest(automatic))
        assertFalse(BlockEmptyCommentInput.isAutomaticRequest("Request(scene=DEFAULT, enterMethod=zero_enter_comment_board, clickType=other)"))
        assertFalse(BlockEmptyCommentInput.isAutomaticRequest("Request(scene=OTHER, enterMethod=zero_enter_comment_board, clickType=zero_enter_comment_board)"))
        assertFalse(BlockEmptyCommentInput.isAutomaticRequest(""))
    }
}
