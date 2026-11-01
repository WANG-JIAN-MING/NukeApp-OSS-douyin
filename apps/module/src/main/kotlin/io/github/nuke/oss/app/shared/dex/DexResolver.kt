package io.github.nuke.oss.app.shared.dex

import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap

interface DexCache {
    fun get(key: String): String?
    fun put(key: String, descriptor: String)
}

// The steps of one search, in order: loading the native library, opening the host's Dex files, then looking up each
// query. They let a progress bar follow the real work, as NukeCore's analysis dialog does.
enum class AnalysisStep { LOADING_NATIVE, OPENING_DEX, SEARCHING }

// Opens the host's Dex files once per call and reports every query in order, by name, each with its descriptor or
// the error its lookup threw, announcing each step before it starts. Only a failure to load the library or open the
// Dex files at all is thrown from search itself.
fun interface DexSearch {
    fun search(queries: Map<String, DexQuery>, onStep: (AnalysisStep) -> Unit, onEach: (name: String, Result<String>) -> Unit)
}

// Searched is how many queries had no cached outcome; failures maps each query that was not located to the reason.
data class DexAnalysis(val searched: Int, val failures: Map<String, String>)

// A query this host build does not answer, as opposed to a failure of the feature itself; diagnostics show it apart.
class DexQueryUnavailable(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

// How far the analysis got in this scope: every query of the catalog is located, failed or still pending.
data class DexStatus(val total: Int, val located: Int, val failed: Int, val pending: Int)

// Resolves the queries of one catalog from the cache first and runs one batched search for the misses. The catalog
// maps each name to its query, as a host's generatedDexQueries does; a query is named by identity, so the same
// object registered twice is rejected. scope identifies the module build and host build the cached descriptors
// belong to.
class DexResolver(
    private val cache: DexCache,
    private val scope: String,
    private val search: DexSearch,
    private val catalog: Map<String, DexQuery>,
) {
    private val names = IdentityHashMap<DexQuery, String>(catalog.size)

    init {
        for ((name, query) in catalog) {
            require(name.isNotBlank()) { "Dex query name is blank" }
            val other = names.put(query, name)
            require(other == null) { "Dex query $name is also registered as $other" }
        }
    }

    fun nameOf(query: DexQuery): String = requireNotNull(names[query]) { "Dex query is not in the catalog" }

    // The descriptors of the queries, in argument order.
    fun resolve(queries: List<DexQuery>): List<String> {
        val resolved = HashMap<String, String>()
        val missing = LinkedHashMap<String, DexQuery>()
        for (query in queries) {
            val name = nameOf(query)
            val cached = cache.get(key(name, query))
            if (!cached.isNullOrEmpty()) {
                resolved[name] = cached
                continue
            }
            // A query the analysis could not locate stays failed for this scope instead of rescanning every launch.
            cache.get(failureKey(name, query))?.let { throw DexQueryUnavailable("Dex query $name was not located during analysis: $it") }
            missing[name] = query
        }
        if (missing.isNotEmpty()) {
            search.search(missing, {}) { name, outcome ->
                val descriptor = outcome.getOrElse { throw DexQueryUnavailable("Dex query $name lookup failed: $it", it) }
                if (descriptor.isEmpty()) throw DexQueryUnavailable("Dex query $name was not located")
                cache.put(key(name, missing.getValue(name)), descriptor)
                resolved[name] = descriptor
            }
            for (name in missing.keys) if (name !in resolved) throw DexQueryUnavailable("Dex query $name was not located")
        }
        return queries.map { resolved.getValue(nameOf(it)) }
    }

    // The names of the queries with neither a cached descriptor nor a recorded failure, which an analysis would search.
    fun pending(): List<String> = catalog.filter { (name, query) ->
        cache.get(key(name, query)).isNullOrEmpty() && cache.get(failureKey(name, query)) == null
    }.keys.toList()

    fun status() = DexStatus(catalog.size, cachedEntries().size, failures().size, pending().size)

    // The queries the analysis could not locate in this scope, by name, with the reason it recorded.
    fun failures(): Map<String, String> = catalog.mapNotNull { (name, query) ->
        cache.get(failureKey(name, query))?.let { name to it }
    }.toMap()

    // Looks up every pending query in one search and records both outcomes, so later launches resolve from the
    // cache and a failed query is not searched again until the scope changes. Runs off the main thread.
    fun analyze(onProgress: (step: AnalysisStep, done: Int, total: Int) -> Unit): DexAnalysis {
        val pending = pending().associateWith { catalog.getValue(it) }
        val failures = LinkedHashMap<String, String>()
        var done = 0
        if (pending.isNotEmpty()) {
            search.search(pending, { step -> onProgress(step, 0, pending.size) }) { name, outcome ->
                val query = pending.getValue(name)
                val descriptor = outcome.getOrNull()
                if (descriptor.isNullOrEmpty()) {
                    val reason = (outcome.exceptionOrNull()?.toString() ?: "no descriptor").take(MAX_REASON)
                    cache.put(failureKey(name, query), reason)
                    failures[name] = reason
                } else {
                    cache.put(key(name, query), descriptor)
                }
                onProgress(AnalysisStep.SEARCHING, ++done, pending.size)
            }
            check(done == pending.size) { "Dex search reported $done of ${pending.size} queries" }
        }
        return DexAnalysis(pending.size, failures)
    }

    // The cached descriptor of every query that has one, by cache key. A process that must not search or write takes
    // these over and reads them through a cache of its own.
    fun cachedEntries(): Map<String, String> = catalog.mapNotNull { (name, query) ->
        cache.get(key(name, query))?.takeIf { it.isNotEmpty() }?.let { key(name, query) to it }
    }.toMap()

    private fun key(name: String, query: DexQuery) = "$scope|$name|v${query.version}"
    private fun failureKey(name: String, query: DexQuery) = "${key(name, query)}|failed"

    private companion object {
        const val MAX_REASON = 500
    }
}

// Loads resolved queries from the host loader in argument order. The Method objects stay in memory only.
fun DexResolver.methods(classLoader: ClassLoader, vararg queries: DexQuery): List<Method> =
    resolve(queries.asList()).map { DexMethod(it).getMethodInstance(classLoader) }

// Same as methods, for queries that locate a constructor.
fun DexResolver.constructors(classLoader: ClassLoader, vararg queries: DexQuery): List<Constructor<*>> =
    resolve(queries.asList()).map { DexMethod(it).getConstructorInstance(classLoader) }

// Same as methods, but each query may select several methods (see the methods query builder).
fun DexResolver.methodLists(classLoader: ClassLoader, vararg queries: DexQuery): List<List<Method>> =
    resolve(queries.asList()).map { descriptors ->
        descriptors.split(DESCRIPTOR_SEPARATOR).map { DexMethod(it).getMethodInstance(classLoader) }
    }

fun DexResolver.fields(classLoader: ClassLoader, vararg queries: DexQuery): List<Field> =
    resolve(queries.asList()).map { DexField(it).getFieldInstance(classLoader) }

// The same lookups for a list a helper keeps, so the list it resolves is the one its users declare.
fun DexResolver.methods(classLoader: ClassLoader, queries: List<DexQuery>): List<Method> = methods(classLoader, *queries.toTypedArray())

fun DexResolver.fields(classLoader: ClassLoader, queries: List<DexQuery>): List<Field> = fields(classLoader, *queries.toTypedArray())
