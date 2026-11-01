package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.playback.ColorfulDanmaku
import io.github.nuke.oss.app.tiktok.feature.playback.lightDanmakuColor
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.core.Logger
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorfulDanmakuTest : HookHarness() {
    class StyleConfig(@JvmField val alpha: Int, @JvmField val textColor: Int, @JvmField val borderColor: Int) {
        init { created += this }
        override fun toString() = "DDanmakuConfig(alpha=$alpha, textColor=$textColor)"

        companion object {
            val created = ArrayList<StyleConfig>()
        }
    }

    private val printer = method(StyleConfig::class.java, "toString")
    private val constructor = StyleConfig::class.java.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

    private fun build(alpha: Int, text: Int, border: Int): StyleConfig {
        StyleConfig.created.clear()
        backend.call(constructor, null, alpha, text, border)
        return StyleConfig.created.single()
    }

    @Test fun `colors are opaque light and white one time in four`() {
        val random = Random(7)
        val colors = List(4000) { lightDanmakuColor(random) }
        val white = colors.count { it == -1 }
        assertTrue("$white", white in 850..1150)
        for (color in colors.filter { it != -1 }) {
            assertEquals(0xFF, color ushr 24)
            val luma = (color shr 16 and 0xFF) * 0.299 + (color shr 8 and 0xFF) * 0.587 + (color and 0xFF) * 0.114
            assertTrue(luma >= 150.0)
        }
    }

    // Stores the color with its alpha forced on, so no field ever equals the constructor argument.
    class AlteredConfig(@JvmField val alpha: Int, color: Int) {
        @JvmField val textColor = color or (0xFF shl 24)
        @JvmField val shadowColor = 0
        init { created += this }
        override fun toString() = "DDanmakuConfig(alpha=$alpha, textColor=$textColor)"

        companion object {
            val created = ArrayList<AlteredConfig>()
        }
    }

    // Does not show its text color, so the field cannot be told from the others.
    class SilentConfig(@JvmField val alpha: Int, @JvmField val textColor: Int) {
        init { created += this }
        override fun toString() = "DDanmakuConfig(alpha=$alpha)"

        companion object {
            val created = ArrayList<SilentConfig>()
        }
    }

    @Test fun `the field the printed text color follows is rewritten even when others hold the same value`() {
        enable(ColorfulDanmaku(resolver(FeedQueries.DANMAKU_STYLE to listOf(printer)), loader, Logger {}, Random(1)))
        // The text and border colors are equal, yet only the text color changes; the others are untouched.
        var changed = 0
        repeat(20) {
            val style = build(200, -1, -1)
            assertEquals(200, style.alpha)
            assertEquals(-1, style.borderColor)
            if (style.textColor != -1) changed++
        }
        assertTrue("$changed", changed in 5..20)
        assertTrue(failures.isEmpty())
    }

    @Test fun `a color the constructor stored altered is still rewritten`() {
        val constructor = AlteredConfig::class.java.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val printer = method(AlteredConfig::class.java, "toString")
        enable(ColorfulDanmaku(resolver(FeedQueries.DANMAKU_STYLE to listOf(printer)), loader, Logger {}, Random(2)))
        var changed = 0
        repeat(20) {
            AlteredConfig.created.clear()
            backend.call(constructor, null, 200, 0x00FFFFFF)
            val style = AlteredConfig.created.single()
            assertEquals(0, style.shadowColor)
            if (style.textColor != -1) changed++
        }
        assertTrue("$changed", changed in 5..20)
        assertTrue(failures.isEmpty())
    }

    @Test fun `nothing is written when the printed text cannot say which field it is`() {
        val constructor = SilentConfig::class.java.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val printer = method(SilentConfig::class.java, "toString")
        val messages = ArrayList<String>()
        enable(ColorfulDanmaku(resolver(FeedQueries.DANMAKU_STYLE to listOf(printer)), loader, Logger { messages += it }, Random(4)))
        repeat(5) {
            SilentConfig.created.clear()
            backend.call(constructor, null, 200, -1)
            assertEquals(-1, SilentConfig.created.single().textColor)
        }
        assertEquals(1, messages.count { it.contains("no_color_field") })
        assertTrue(failures.isEmpty())
    }

    @Test fun `switched off the constructor result is the host's`() {
        val feature = ColorfulDanmaku(resolver(FeedQueries.DANMAKU_STYLE to listOf(printer)), loader, Logger {}, Random(3))
        enable(feature)
        disable(feature)
        assertEquals(-1, build(255, -1, 0).textColor)
        assertNotEquals(0, backend.attachments.get())
    }
}
