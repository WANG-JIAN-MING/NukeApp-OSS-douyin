package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.dex.mapSearch
import io.github.nuke.oss.app.tiktok.feature.comment.HideCommentButtons
import io.github.nuke.oss.app.tiktok.feature.comment.commentButtonToHide
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for hiding the comment like and dislike buttons on both panels, with stand-ins for the state class,
// the adapter and its rows.
class HideCommentButtonsTest : HookHarness() {
    // Nine values: four flags, a number, four flags. Index 5 is the like flag and index 7 the dislike flag.
    class State(
        @JvmField val a: Boolean, @JvmField val b: Boolean, @JvmField val c: Boolean, @JvmField val d: Boolean, @JvmField val n: Int,
        @JvmField val like: Boolean, @JvmField val x: Boolean, @JvmField val dislike: Boolean, @JvmField val y: Boolean,
    ) { fun marker() {}; companion object { var last: State? = null }; init { last = this } }
    class Mismatched(@JvmField val a: Int) { fun marker() {} }
    class Holder(@JvmField val itemView: Any)
    class Adapter { fun attached(holder: Holder?) {}; fun two(holder: Holder?, flag: Boolean) {} }
    class AdapterMarker { fun marker() {} }

    private val logged = ArrayList<String>()
    private val ui = RecordingCommentButtons()
    private val bool = Boolean::class.javaPrimitiveType!!
    private val stateCtor = State::class.java.getDeclaredConstructor(bool, bool, bool, bool, Int::class.javaPrimitiveType, bool, bool, bool, bool)

    private fun feature(vararg saved: Pair<String, Any>, state: Boolean = true, adapter: Boolean = true, stateType: java.lang.reflect.Method = method(State::class.java, "marker")): HideCommentButtons {
        val located = ArrayList<Pair<io.github.nuke.oss.app.shared.dex.DexQuery, List<java.lang.reflect.Method>>>()
        if (state) located += CommentQueries.BUTTONS_STATE to listOf(stateType)
        if (adapter) located += CommentQueries.COMMENT_ADAPTER to listOf(method(Adapter::class.java, "attached", Holder::class.java))
        val resolver = io.github.nuke.oss.app.shared.dex.DexResolver(object : io.github.nuke.oss.app.shared.dex.DexCache {
            override fun get(key: String): String? = null
            override fun put(key: String, descriptor: String) {}
        }, "scope", mapSearch { queries ->
            queries.mapNotNull { (name, query) ->
                located.firstOrNull { it.first === query }?.let { name to org.luckypray.dexkit.wrap.DexMethod(it.second.single()).serialize() }
            }.toMap()
        }, generatedDexQueries)
        return HideCommentButtons(resolver, loader, { ui }, Logger { logged += it }, values(HideCommentButtons.ID, HideCommentButtons.OPTIONS, *saved), Holder::class.java.name)
    }

    private fun build(like: Boolean = true, dislike: Boolean = true): State {
        backend.call(stateCtor, null, true, true, true, true, 1, like, true, dislike, true)
        return checkNotNull(State.last)
    }

    @Test fun `the newer panel's like and dislike flags are cleared after the state is built`() {
        enable(feature("like" to true, "dislike" to true))
        val state = build()
        assertFalse(state.like)
        assertFalse(state.dislike)
        assertTrue(state.a && state.x && state.y)
    }

    @Test fun `only the chosen button is cleared`() {
        enable(feature("dislike" to true))
        val state = build()
        assertTrue(state.like)
        assertFalse(state.dislike)
    }

    @Test fun `the older panel's rows are handed to the hider`() {
        enable(feature("like" to true, state = false))
        val row = Any()
        backend.call(method(Adapter::class.java, "attached", Holder::class.java), Adapter(), Holder(row))
        assertEquals(listOf(Triple(row, true, false)), ui.hidden)
    }

    @Test fun `a host with only the older panel installs and logs the missing one`() {
        enable(feature("like" to true, state = false))
        assertTrue(logged.any { it.contains("panel_skipped CommentQueries.BUTTONS_STATE") })
    }

    @Test fun `with no choice nothing is hooked`() {
        enable(feature())
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `no usable panel fails the install`() {
        failsToInstall(feature("like" to true, state = false, adapter = false), "Neither comment panel")
    }

    @Test fun `a state class with other fields is not touched`() {
        failsToInstall(feature("like" to true, adapter = false, stateType = method(Mismatched::class.java, "marker")), "Neither comment panel")
    }

    @Test fun `a button group is picked by the host's description and shape`() {
        assertTrue(commentButtonToHide("踩", frame = true, linear = false, parentName = "Row", like = false, dislike = true))
        assertTrue(commentButtonToHide("点赞 赞", frame = false, linear = true, parentName = "Row", like = true, dislike = false))
        assertFalse(commentButtonToHide("踩", frame = true, linear = false, parentName = "CommentRecyclerView", like = true, dislike = true))
        assertFalse(commentButtonToHide("踩", frame = false, linear = true, parentName = "Row", like = true, dislike = true))
        assertFalse(commentButtonToHide(null, frame = true, linear = true, parentName = "Row", like = true, dislike = true))
    }
}
