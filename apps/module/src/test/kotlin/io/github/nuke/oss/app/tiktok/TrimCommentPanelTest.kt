package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.comment.TrimCommentPanel
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Test

// Contract tests for trimming the comment long-press menu, with stand-ins for the provider, its groups and entries.
class TrimCommentPanelTest : HookHarness() {
    class SparseArray(private val lists: List<MutableList<Any?>>) {
        fun size() = lists.size
        fun valueAt(index: Int) = lists[index]
    }
    class Entry(private val name: String) { fun getTag() = name }
    class Group(@JvmField val rows: SparseArray, @JvmField val other: String? = null)
    class Provider {
        var groups: List<Group> = emptyList()
        fun build(flag: Boolean): List<Group> = groups
        fun build(): List<Group> = groups
        fun count(): Int = 0
    }
    class Plain { fun build(): Int = 0 }

    private val logged = ArrayList<String>()
    private val build = method(Provider::class.java, "build", Boolean::class.javaPrimitiveType!!)
    private val buildAll = method(Provider::class.java, "build")

    private fun feature(vararg hidden: String, anchor: java.lang.reflect.Method = build) = TrimCommentPanel(
        resolver(CommentQueries.LONG_PRESS_PANEL to listOf(anchor)), loader, Logger { logged += it },
        values(TrimCommentPanel.ID, TrimCommentPanel.OPTIONS, "hidden" to hidden.toList()),
    )

    private fun names(list: MutableList<Any?>) = list.map { (it as Entry).getTag() }

    @Test fun `entries with a hidden tag are removed from every group and overload`() {
        enable(feature("copy", "report"))
        val first = mutableListOf<Any?>(Entry("copy"), Entry("reply"))
        val second = mutableListOf<Any?>(Entry("report"), Entry("save"))
        val provider = Provider().apply { groups = listOf(Group(SparseArray(listOf(first))), Group(SparseArray(listOf(second)))) }
        backend.call(build, provider, true)
        assertEquals(listOf("reply"), names(first))
        assertEquals(listOf("save"), names(second))
        val third = mutableListOf<Any?>(Entry("copy"))
        provider.groups = listOf(Group(SparseArray(listOf(third))))
        backend.call(buildAll, provider)
        assertEquals(emptyList<String>(), names(third))
    }

    @Test fun `every tag is logged once with whether it is hidden`() {
        enable(feature("copy"))
        val entries = mutableListOf<Any?>(Entry("copy"), Entry("reply"))
        val provider = Provider().apply { groups = listOf(Group(SparseArray(listOf(entries)))) }
        backend.call(build, provider, true)
        backend.call(build, provider, true)
        assertEquals(listOf("feature=${TrimCommentPanel.ID} tag_seen name=copy hidden=true", "feature=${TrimCommentPanel.ID} tag_seen name=reply hidden=false"), logged)
    }

    @Test fun `a method that builds no list fails the install`() {
        failsToInstall(feature("copy", anchor = method(Plain::class.java, "build")), "builds no list")
    }
}
