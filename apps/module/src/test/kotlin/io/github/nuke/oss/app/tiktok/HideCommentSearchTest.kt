package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.comment.HideCommentSearch
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import org.junit.Assert.assertEquals
import org.junit.Test

// Contract tests for hiding the comment panel's related search, with a stand-in for the strip.
class HideCommentSearchTest : HookHarness() {
    class Strip { var built = 0; fun build(container: Any?, video: Any?, extra: Any?) { built++ } }
    class ValueStrip { fun build(container: Any?, video: Any?, extra: Any?): Int = 1 }

    private val build = method(Strip::class.java, "build", Any::class.java, Any::class.java, Any::class.java)

    @Test fun `the related search strip is not built`() {
        enable(HideCommentSearch(resolver(CommentQueries.RELATED_SEARCH to listOf(build)), loader))
        val strip = Strip()
        backend.call(build, strip, null, null, null)
        assertEquals(0, strip.built)
    }

    @Test fun `a strip method that returns a value fails the install`() {
        failsToInstall(
            HideCommentSearch(resolver(CommentQueries.RELATED_SEARCH to listOf(method(ValueStrip::class.java, "build", Any::class.java, Any::class.java, Any::class.java))), loader),
            "returns int",
        )
    }
}
