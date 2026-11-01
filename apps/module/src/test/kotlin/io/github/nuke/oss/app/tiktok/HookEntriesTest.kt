package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.feed.DisableDoubleTapDigg
import io.github.nuke.oss.app.tiktok.settings.SettingsEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HookEntriesTest : HookHarness() {
    private val features = generatedHookEntries.flatMap { entry -> entry.create(testServices(resolver(), loader)).map { it to entry } }

    @Test fun `every feature class in the host package is built by a generated entry`() {
        // A RuntimeFeature class without @HookEntry would silently never register, so the sources are the reference.
        val sources = File("src/main/kotlin/io/github/nuke/oss/app/tiktok")
        assertTrue(sources.absolutePath, sources.isDirectory)
        val declared = sources.walk().filter { it.extension == "kt" }.flatMap { file ->
            Regex("""(?s)\bclass (\w+)(?:\([^{]*?\))?\s*:\s*RuntimeFeature\b""").findAll(file.readText()).map { it.groupValues[1] }
        }.toSet()
        val built = features.map { it.first.javaClass.simpleName }.toSet()
        // The settings entry and the init are registered by the adapter itself, not through the registry.
        assertEquals(emptySet<String>(), declared - built - SettingsEntry::class.java.simpleName)
    }

    @Test fun `entries are ordered by qualified name and only the first migrated feature defaults to on`() {
        val names = generatedHookEntries.map { (it.javaClass.enclosingClass ?: it.javaClass).name }
        assertEquals(names.sorted(), names)
        assertEquals(listOf(DisableDoubleTapDigg.ID), features.filter { it.second.enabledByDefault }.map { it.first.id })
    }
}
