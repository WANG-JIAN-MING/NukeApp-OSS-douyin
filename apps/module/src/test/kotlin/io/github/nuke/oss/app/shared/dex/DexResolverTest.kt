package io.github.nuke.oss.app.shared.dex

import org.junit.Assert.*
import org.junit.Test

class DexResolverTest {
    private class MemoryCache : DexCache {
        val entries = LinkedHashMap<String, String>()
        override fun get(key: String) = entries[key]
        override fun put(key: String, descriptor: String) { entries[key] = descriptor }
    }

    private val cache = MemoryCache()
    private val searches = ArrayList<List<String>>()
    private val found = mutableMapOf("A" to "La;->a()V", "B" to "Lb;->b()V", "C" to "Lc;->c()V")

    private fun query(version: Int = 1) = DexQuery(version) { error("Locator runs only inside DexKit") }
    private val a = query()
    private val b = query()
    private val c = query()
    private val catalog = mapOf("A" to a, "B" to b, "C" to c)

    private fun resolver(scope: String = "build1", catalog: Map<String, DexQuery> = this.catalog) =
        DexResolver(cache, scope, mapSearch { queries ->
            searches += queries.keys.toList()
            found.filterKeys { it in queries }
        }, catalog)

    @Test fun `misses are searched once in one batch and then served from cache`() {
        val first = resolver().resolve(listOf(a, b))
        assertEquals(listOf("La;->a()V", "Lb;->b()V"), first)
        assertEquals(listOf(listOf("A", "B")), searches)
        assertEquals(first, resolver().resolve(listOf(a, b)))
        assertEquals(1, searches.size)
    }

    @Test fun `only uncached queries are searched`() {
        resolver().resolve(listOf(a))
        resolver().resolve(listOf(a, b))
        assertEquals(listOf(listOf("A"), listOf("B")), searches)
    }

    @Test fun `descriptors come back in argument order and a repeated query is searched once`() {
        assertEquals(listOf("Lb;->b()V", "La;->a()V", "Lb;->b()V"), resolver().resolve(listOf(b, a, b)))
        assertEquals(listOf(listOf("B", "A")), searches)
    }

    @Test fun `a new descriptor version or scope invalidates the cached entry`() {
        resolver().resolve(listOf(a))
        val newer = query(version = 2)
        resolver(catalog = mapOf("A" to newer)).resolve(listOf(newer))
        resolver("build2").resolve(listOf(a))
        assertEquals(3, searches.size)
    }

    @Test fun `a query the search did not locate fails and is not cached`() {
        found.remove("B")
        val error = assertThrows(DexQueryUnavailable::class.java) { resolver().resolve(listOf(a, b)) }
        assertTrue(error.message!!.contains("Dex query B"))
        assertEquals(1, cache.entries.size)
        assertFalse(cache.entries.keys.any { it.contains("|B|") })
    }

    @Test fun `the name a query fails under is its catalog name`() {
        // A locate failure as DexKitSearch reports it: the bare message of uniqueMethod, under the name it was given.
        val failing = query()
        val resolver = DexResolver(cache, "build1", { queries, _, onEach ->
            for (name in queries.keys) onEach(name, Result.failure(IllegalStateException("expected one method, found 2")))
        }, mapOf("FeedQueries.RESUME_GATE" to failing))
        val error = assertThrows(DexQueryUnavailable::class.java) { resolver.resolve(listOf(failing)) }
        assertEquals("Dex query FeedQueries.RESUME_GATE lookup failed: java.lang.IllegalStateException: expected one method, found 2", error.message)
    }

    @Test fun `a catalog names each query once and resolves only its own queries`() {
        assertThrows(IllegalArgumentException::class.java) { resolver(catalog = mapOf("A" to a, "B" to a)) }
        assertThrows(IllegalArgumentException::class.java) { resolver(catalog = mapOf(" " to a)) }
        assertThrows(IllegalArgumentException::class.java) { resolver().resolve(listOf(query())) }
        assertThrows(IllegalArgumentException::class.java) { query(version = 0) }
        assertEquals("B", resolver().nameOf(b))
        assertTrue(searches.isEmpty())
    }

    @Test fun `analysis searches every pending query once and reports progress for each`() {
        found.remove("B")
        val progress = ArrayList<Triple<AnalysisStep, Int, Int>>()
        val analysis = resolver().analyze { step, done, total -> progress += Triple(step, done, total) }
        assertEquals(listOf(listOf("A", "B", "C")), searches)
        assertEquals(listOf(
            Triple(AnalysisStep.LOADING_NATIVE, 0, 3), Triple(AnalysisStep.OPENING_DEX, 0, 3), Triple(AnalysisStep.SEARCHING, 0, 3),
            Triple(AnalysisStep.SEARCHING, 1, 3), Triple(AnalysisStep.SEARCHING, 2, 3), Triple(AnalysisStep.SEARCHING, 3, 3),
        ), progress)
        assertEquals(3, analysis.searched)
        assertEquals(setOf("B"), analysis.failures.keys)
        assertTrue(analysis.failures.getValue("B").contains("not located"))
    }

    @Test fun `a query the analysis could not locate fails later without another search`() {
        found.remove("B")
        resolver().analyze { _, _, _ -> }
        assertEquals(listOf("La;->a()V"), resolver().resolve(listOf(a)))
        val error = assertThrows(DexQueryUnavailable::class.java) { resolver().resolve(listOf(b)) }
        assertTrue(error.message!!.contains("during analysis"))
        assertEquals(1, searches.size)
        assertTrue(resolver().pending().isEmpty())
    }

    @Test fun `a recorded failure belongs to its scope and version`() {
        found.remove("B")
        resolver(catalog = mapOf("B" to b)).analyze { _, _, _ -> }
        found["B"] = "Lb;->b()V"
        assertEquals(listOf("B"), resolver(catalog = mapOf("B" to query(version = 2))).pending())
        assertEquals(listOf("Lb;->b()V"), resolver("build2").resolve(listOf(b)))
    }

    @Test fun `nothing pending means no search and an empty analysis`() {
        resolver().resolve(listOf(a, b, c))
        val progress = ArrayList<Triple<AnalysisStep, Int, Int>>()
        val analysis = resolver().analyze { step, done, total -> progress += Triple(step, done, total) }
        assertEquals(DexAnalysis(0, emptyMap()), analysis)
        assertTrue(progress.isEmpty())
        assertEquals(1, searches.size)
    }

    @Test fun `cached entries are keyed by scope, name and version`() {
        resolver().resolve(listOf(a))
        assertEquals(mapOf("build1|A|v1" to "La;->a()V"), resolver().cachedEntries())
    }

    @Test fun `a search that cannot open the Dex files fails the whole analysis and records nothing`() {
        val broken = DexResolver(cache, "build1", { _, _, _ -> throw UnsatisfiedLinkError("dexkit") }, mapOf("A" to a))
        assertThrows(UnsatisfiedLinkError::class.java) { broken.analyze { _, _, _ -> } }
        assertTrue(cache.entries.isEmpty())
        assertEquals(listOf("A"), broken.pending())
    }
}
