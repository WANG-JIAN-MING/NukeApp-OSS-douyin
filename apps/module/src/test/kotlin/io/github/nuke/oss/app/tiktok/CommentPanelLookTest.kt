package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.comment.CommentPanelLook
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the comment panel look: the page View reaches the style with the chosen options.
class CommentPanelLookTest : HookHarness() {
    class PageView
    class Page {
        fun reset(param: Any?) {}
        fun onCreateView(inflater: android.view.LayoutInflater?, parent: android.view.ViewGroup?, state: android.os.Bundle?): Any = PageView()
    }
    class NoView { fun reset(param: Any?) {} }

    private val style = RecordingCommentPanelStyle()
    private val reset = method(Page::class.java, "reset", Any::class.java)
    private val created = method(Page::class.java, "onCreateView", android.view.LayoutInflater::class.java, android.view.ViewGroup::class.java, android.os.Bundle::class.java)

    private val directory = java.nio.file.Files.createTempDirectory("comment-look").toFile()
    private val backgroundFile = java.io.File(directory, "background")
    private val entries = RecordingSharePanelEntries()
    private val picker = RecordingFilePicker()
    private val prompts = RecordingPrompts()

    private fun feature(anchor: java.lang.reflect.Method = reset, vararg saved: Pair<String, Any>) = CommentPanelLook(
        resolver(CommentQueries.LIST_PAGE to listOf(anchor)), loader, { style }, values(CommentPanelLook.ID, CommentPanelLook.OPTIONS, *saved),
        backgroundFile, entries, { picker }, prompts, io.github.nuke.oss.core.Logger {},
    )

    @Test fun `the created page view is styled with the chosen options`() {
        val feature = feature(reset, CommentPanelLook.TEXT_COLOR to "#112233")
        enable(feature)
        val view = backend.call(created, Page(), null, null, null)
        assertEquals(listOf(Triple(view!!, true, 0xFF112233.toInt())), style.applied)
        disable(feature)
        backend.call(created, Page(), null, null, null)
        assertEquals(1, style.applied.size)
    }

    @Test fun `a bad color fails the install`() {
        failsToInstall(feature(reset, CommentPanelLook.TEXT_COLOR to "red"), "is not a #RRGGBB color")
    }

    @Test fun `nothing to change fails the install`() {
        failsToInstall(feature(reset, CommentPanelLook.TRANSPARENT to false), "Nothing to change")
    }

    @Test fun `a page without onCreateView fails the install`() {
        failsToInstall(feature(method(NoView::class.java, "reset", Any::class.java)), "Expected one onCreateView")
        assertTrue(style.applied.isEmpty())
    }

    @Test fun `a chosen background is kept and shown on the next page`() {
        enable(feature(reset, CommentPanelLook.BACKGROUND to true))
        backend.call(created, Page(), null, null, null)
        assertEquals(listOf<java.io.File?>(null), style.backgrounds)
        val chosen = java.io.File(directory, "picked.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        picker.answers += io.github.nuke.oss.app.shared.activity.PickResult.Picked(chosen, "cat.png")
        entries.added.single().onClick(Any(), Any()) {}
        prompts.choices.single().onChosen(0)
        assertEquals(listOf(listOf("image/*")), picker.picks)
        assertTrue(backgroundFile.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        backend.call(created, Page(), null, null, null)
        assertEquals(backgroundFile, style.backgrounds.last())
        // Removing it takes it off the next page again.
        entries.added.single().onClick(Any(), Any()) {}
        prompts.choices.last().onChosen(1)
        backend.call(created, Page(), null, null, null)
        assertEquals(null, style.backgrounds.last())
    }

    @Test fun `without the background option there is no entry`() {
        enable(feature(reset))
        assertTrue(entries.added.isEmpty())
        assertEquals(0, picker.installs)
    }
}
