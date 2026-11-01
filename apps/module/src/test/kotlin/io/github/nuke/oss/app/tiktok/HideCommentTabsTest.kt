package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.comment.HideCommentTabs
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for hiding comment panel tabs by page type, with stand-ins for the pager and its pages.
class HideCommentTabsTest : HookHarness() {
    class Page(private val type: Int) { fun getPageType() = type }
    class Pager {
        fun marker() {}
        fun shows(page: Page?): Boolean = true
        fun shows(other: String?): Boolean = true
    }
    class PagerTwo { fun marker() {}; fun a(page: Page?): Boolean = true; fun b(page: Page?): Boolean = true }

    private val logged = ArrayList<String>()
    private val shows = method(Pager::class.java, "shows", Page::class.java)

    private fun tabs(vararg hidden: String, anchor: java.lang.reflect.Method = method(Pager::class.java, "marker")) = HideCommentTabs(
        resolver(CommentQueries.PANEL_TABS to listOf(anchor)), loader, Logger { logged += it },
        values(HideCommentTabs.ID, HideCommentTabs.OPTIONS, "page-types" to hidden.toList()), Page::class.java.name,
    )

    @Test fun `pages of a chosen type are reported as not shown and every type is logged once`() {
        enable(tabs("3", " 7 ", "x"))
        val pager = Pager()
        assertEquals(false, backend.call(shows, pager, Page(3)))
        assertEquals(true, backend.call(shows, pager, Page(5)))
        assertEquals(false, backend.call(shows, pager, Page(7)))
        backend.call(shows, pager, Page(3))
        assertEquals(
            listOf("feature=${HideCommentTabs.ID} page_seen type=3 hidden=true", "feature=${HideCommentTabs.ID} page_seen type=5 hidden=false", "feature=${HideCommentTabs.ID} page_seen type=7 hidden=true"),
            logged,
        )
        assertTrue(backend.call(shows, pager, null) as Boolean)
    }

    @Test fun `a pager with two questions about a page fails the install`() {
        failsToInstall(tabs("3", anchor = method(PagerTwo::class.java, "marker")), "found 2")
        assertFalse(logged.any { it.contains("page_seen") })
    }
}
