package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.comment.CommentPreciseTime
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

// Contract tests for exact comment times: the relative time formatter answers in the chosen pattern.
class CommentPreciseTimeTest : HookHarness() {
    object TimeText {
        @JvmStatic fun relative(at: Long, short: Boolean, mask: Int): String = "3分钟前"
    }
    class NoTime { fun relative(short: Boolean, mask: Int, other: String?): String = "" }

    @Test fun `comment times are answered in the chosen pattern`() {
        val relative = method(TimeText::class.java, "relative", Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        val feature = CommentPreciseTime(
            resolver(CommentQueries.RELATIVE_TIME to listOf(relative)), loader,
            values(CommentPreciseTime.ID, CommentPreciseTime.OPTIONS, CommentPreciseTime.PATTERN to "yyyy-MM-dd HH:mm"), { ZoneOffset.UTC },
        )
        enable(feature)
        assertEquals("2023-11-14 22:13", backend.call(relative, null, 1_700_000_000_000L, false, 0))
        disable(feature)
        assertEquals("3分钟前", backend.call(relative, null, 1_700_000_000_000L, false, 0))
    }

    @Test fun `a formatter without a time or a bad pattern fails the install`() {
        val noTime = method(NoTime::class.java, "relative", Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java)
        failsToInstall(CommentPreciseTime(resolver(CommentQueries.RELATIVE_TIME to listOf(noTime)), loader, values(CommentPreciseTime.ID, CommentPreciseTime.OPTIONS)), "takes no time")
    }

    @Test fun `a bad time pattern fails the install`() {
        val relative = method(TimeText::class.java, "relative", Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        failsToInstall(
            CommentPreciseTime(resolver(CommentQueries.RELATIVE_TIME to listOf(relative)), loader, values(CommentPreciseTime.ID, CommentPreciseTime.OPTIONS, CommentPreciseTime.PATTERN to "yyyy-MM-dd bb")),
            "is not a time pattern",
        )
    }
}
