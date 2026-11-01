package io.github.nuke.oss.app.shared.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UiConfigTest {
    @get:Rule val folder = TemporaryFolder()

    private class MapValues : UiConfigValues {
        val map = HashMap<String, Any?>()
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
        override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun getString(key: String) = map[key] as? String
        override fun put(key: String, value: Any?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @Test fun `hex colours are normalized and invalid ones rejected`() {
        assertEquals("#EC4899", normalizeHex(" ec4899 "))
        assertEquals("#00AAFF", normalizeHex("#00aAfF"))
        assertNull(normalizeHex("#12345"))
        assertNull(normalizeHex("#12345G"))
        assertNull(normalizeHex(""))
    }

    @Test fun `the history keeps new custom colours first and skips presets and repeats`() {
        val presets = setOf("#EC4899", "#F43F5E")
        var history = emptyList<String>()
        history = history.withAccentHistory("#112233", presets)
        history = history.withAccentHistory("#EC4899", presets)
        history = history.withAccentHistory("#445566", presets)
        history = history.withAccentHistory("112233", presets)
        assertEquals(listOf("#112233", "#445566"), history)
    }

    @Test fun `the history holds at most twelve colours`() {
        val history = (0 until 20).fold(emptyList<String>()) { acc, i -> acc.withAccentHistory("#0000%02X".format(i), emptySet()) }
        assertEquals(12, history.size)
        assertEquals("#000013", history.first())
    }

    @Test fun `a stored configuration reads back unchanged`() {
        val file = folder.newFile("background")
        val store = ValuesUiConfigStore(MapValues(), file)
        val config = UiConfig(
            ThemeChoice.DARK, LanguageChoice.CHINESE, "#14B8A6", listOf("#112233"), true,
            backgroundRevision = 42, backgroundBlur = 12f, backgroundScrim = 0.4f, cardOpacity = 0.6f, cardBlur = true,
        )
        store.write(config)
        assertEquals(config, store.read())
    }

    @Test fun `a background revision without its image reads as no background`() {
        val values = MapValues()
        val store = ValuesUiConfigStore(values, java.io.File(folder.root, "missing"))
        store.write(UiConfig(backgroundRevision = 42))
        assertEquals(0L, store.read().backgroundRevision)
    }

    @Test fun `out of range or malformed stored values are clamped or dropped`() {
        val values = MapValues()
        values.map["ui.theme"] = 99
        values.map["ui.accent"] = "not-a-colour"
        values.map["ui.recent_accents"] = "#112233,bad,#445566"
        values.map["ui.background_blur"] = 500f
        values.map["ui.card_opacity"] = 0f
        val read = ValuesUiConfigStore(values, java.io.File(folder.root, "none")).read()
        assertEquals(ThemeChoice.SYSTEM, read.theme)
        assertNull(read.accent)
        assertEquals(listOf("#112233", "#445566"), read.recentAccents)
        assertEquals(40f, read.backgroundBlur)
        assertEquals(0.2f, read.cardOpacity)
    }
}
