package io.github.nuke.oss.app.tiktok

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// A feature that resolves a Dex query without naming it in dexQueries is never held when the analysis misses that
// query. The check reads the sources: every DexQuery a feature file mentions must also appear in one of the file's own
// declarations (dexQueries, or a *_QUERIES list it builds that from).
class UsesDexQueriesTest {
    private val tiktok = File("src/main/kotlin/io/github/nuke/oss/app/tiktok")
    private val features = File(tiktok, "feature")
    private val reference = Regex("""\b\w+Queries\.[A-Z][A-Z0-9_]*\b""")

    // Obj.NAME for every DexQuery property of the query objects; the const class names beside them are not queries.
    private val queries: Set<String> = File(tiktok, "query").walkTopDown().plus(File(tiktok, "protocol/ProtocolQueries.kt"))
        .filter { it.extension == "kt" }.flatMap { file ->
            val holder = Regex("""object (\w+Queries)""").find(file.readText())!!.groupValues[1]
            Regex("""^ {4}val ([A-Z][A-Z0-9_]*) =""", RegexOption.MULTILINE).findAll(file.readText()).map { "$holder.${it.groupValues[1]}" }
        }.toSet()

    // Features that build dexQueries from a table instead of listing the queries again.
    private val derived = setOf("ConfirmBeforeActions.kt")

    private fun code(file: File) = file.readLines().filterNot { it.trimStart().startsWith("//") || it.startsWith("import ") }

    // Each declaration runs from its first line to the next line indented no deeper than it that is not a continuation.
    private fun declarations(lines: List<String>): String {
        val text = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("override val dexQueries") || Regex("""val [A-Z_]*QUERIES\b""").containsMatchIn(line)) {
                val indent = line.takeWhile { it == ' ' }.length
                text.append(line).append('\n')
                i++
                while (i < lines.size) {
                    val next = lines[i]
                    val depth = next.takeWhile { it == ' ' }.length
                    val continues = next.isBlank() || depth > indent || (depth == indent && next.trim().let { it.startsWith(")") || it.startsWith("+") })
                    if (!continues) break
                    text.append(next).append('\n')
                    i++
                }
            } else {
                i++
            }
        }
        return text.toString()
    }

    @Test fun `every query a feature file uses is named in its dexQueries`() {
        assertTrue("run from the module directory", features.isDirectory && queries.isNotEmpty())
        val problems = ArrayList<String>()
        for (file in features.walkTopDown().filter { it.extension == "kt" }.sortedBy { it.path }) {
            if (file.name in derived) continue
            val lines = code(file)
            // Only files that declare a feature; helpers beside it are covered by the feature that builds them.
            if (lines.none { it.contains(") : RuntimeFeature") }) continue
            val declared = declarations(lines)
            val used = lines.filterNot { it.contains("override val dexQueries") }
                .flatMap { reference.findAll(it).map { m -> m.value }.toList() }.filter { it in queries }.toSet()
            val missing = used.filterNot { it in declared }
            if (missing.isNotEmpty()) problems += "${file.name}: ${missing.sorted().joinToString()}"
        }
        assertEquals("Queries used but not declared:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test fun `the derived list is only for the confirm table`() {
        val source = code(File(features, "general/ConfirmBeforeActions.kt")).joinToString("\n")
        assertTrue(source.contains("override val dexQueries: List<DexQuery> get() = events.filter"))
    }
}
