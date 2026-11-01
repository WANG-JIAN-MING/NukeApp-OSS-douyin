package io.github.nuke.oss.app.shared.settings

import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.app.tiktok.preferenceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleConfigTest {
    private val host = "com.ss.android.ugc.aweme"
    private val values = MemoryConfigValues()
    private var filesRemoved = 0
    private var dexForgotten = 0
    private val config = ModuleConfig(host, values, ::preferenceKind, forgetDex = { dexForgotten++ }) { filesRemoved++ }

    private fun preferences() = mapOf(
        "enabled.A" to true,
        "option.A.words" to "[\"x\"]",
        "ui.theme" to 2,
        "ui.card_opacity" to 0.2f,
        "setup.bug_reports" to false,
    )

    private fun rejected(text: String) = assertThrows(ConfigImportRejected::class.java) { config.import(text) }

    @Test fun `only preferences are exported and never the dex cache, records or memory`() {
        values.stored.putAll(preferences())
        values.stored += mapOf(
            "failure.A" to "record", "memory.A.last" to "1", "setup.safe_mode" to true,
            "ui.background" to 7L, "dex.tiktok.target" to "Lcom/example;->m()V",
        )
        val document = MiniJson.obj(MiniJson.parse(config.export()))!!
        assertEquals("io.github.nuke.oss.config", document["format"])
        assertEquals(1L, document["version"])
        assertEquals(host, document["host"])
        val exported = MiniJson.obj(document["values"])!!
        assertEquals(preferences().keys, exported.keys)
        // A float travels as its decimal text.
        assertEquals(0.2, exported["ui.card_opacity"])
    }

    @Test fun `an exported file imports back to the same values and replaces the old preferences`() {
        values.stored.putAll(preferences())
        val text = config.export()
        values.stored.clear()
        values.stored += mapOf("enabled.Stale" to true, "failure.A" to "record", "dex.tiktok.target" to "kept")
        assertEquals(5, config.import(text))
        assertEquals(preferences() + mapOf("failure.A" to "record", "dex.tiktok.target" to "kept"), values.stored)
    }

    @Test fun `a file that is not a configuration changes nothing`() {
        values.stored += "enabled.A" to true
        for (text in listOf("not json", "[]", """{"format":"other","version":1,"host":"$host","values":{}}""",
            """{"format":"io.github.nuke.oss.config","host":"$host","values":{}}""")) {
            assertEquals(ConfigImportRejected.Reason.NOT_CONFIG, rejected(text).reason)
        }
        assertEquals(mapOf<String, Any?>("enabled.A" to true), values.stored)
    }

    @Test fun `another host, a newer version or any bad setting rejects the whole file`() {
        values.stored += "enabled.A" to true
        fun file(values: String, host: String = this.host, version: Int = 1) =
            """{"format":"io.github.nuke.oss.config","version":$version,"host":"$host","values":{$values}}"""
        rejected(file("", host = "io.github.nuke.oss.fixture")).let {
            assertEquals(ConfigImportRejected.Reason.OTHER_HOST, it.reason)
            assertEquals("io.github.nuke.oss.fixture", it.detail)
        }
        assertEquals(ConfigImportRejected.Reason.NEWER_VERSION, rejected(file("", version = 2)).reason)
        for ((entries, key) in listOf(
            """"enabled.B":false,"dex.tiktok.target":"x"""" to "dex.tiktok.target",
            """"enabled.B":"yes"""" to "enabled.B",
            """"ui.theme":3000000000""" to "ui.theme",
            """"failure.A":"x"""" to "failure.A",
        )) {
            rejected(file(entries)).let {
                assertEquals(ConfigImportRejected.Reason.INVALID_SETTING, it.reason)
                assertEquals(key, it.detail)
            }
        }
        assertEquals(mapOf<String, Any?>("enabled.A" to true), values.stored)
    }

    @Test fun `cleaning removes every value and the module's files`() {
        values.stored.putAll(preferences())
        values.stored += mapOf("failure.A" to "record", "setup.safe_mode" to true)
        config.clean()
        assertTrue(values.stored.isEmpty())
        assertEquals(1, filesRemoved)
    }

    @Test fun `cleaning also forgets the DexKit cache so the analysis runs again`() {
        values.stored.putAll(preferences())
        config.clean()
        assertEquals(1, dexForgotten)
    }

    @Test fun `exporting and importing never touch the DexKit cache`() {
        values.stored.putAll(preferences())
        config.import(config.export())
        assertEquals(0, dexForgotten)
    }

    @Test fun `the TikTok store names its preferences and nothing else`() {
        assertEquals(ConfigKind.BOOLEAN, preferenceKind("enabled.tiktok.x"))
        assertEquals(ConfigKind.STRING, preferenceKind("option.tiktok.x.y"))
        assertEquals(ConfigKind.FLOAT, preferenceKind("ui.background_blur"))
        for (key in listOf("ui.background", "setup.safe_mode", "failure.tiktok.x", "memory.tiktok.x.y", "dex.anything")) {
            assertNull(key, preferenceKind(key))
        }
        assertFalse(preferenceKind("ui.unknown") != null)
    }
}
