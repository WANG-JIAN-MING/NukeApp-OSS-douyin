package io.github.nuke.oss.app.tiktok.sdk

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methodLists
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Answers host methods with one fixed value. SKIP never runs the host method; REPLACE lets it run and swaps a normal
// return for the value, leaving an exception the host threw as it is.
internal class FixedResult(
    override val id: String,
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val queries: List<DexQuery>,
    private val returnType: Class<*>,
    private val value: Any?,
    private val mode: Mode = Mode.SKIP,
) : RuntimeFeature, UsesDexQueries {
    override val dexQueries: List<DexQuery> get() = queries

    enum class Mode { SKIP, REPLACE }

    constructor(
        id: String, resolver: DexResolver, hostClassLoader: ClassLoader, query: DexQuery,
        returnType: Class<*>, value: Any?, mode: Mode = Mode.SKIP,
    ) : this(id, resolver, hostClassLoader, listOf(query), returnType, value, mode)

    override fun install(scope: FeatureScope) {
        val methods = resolver.methodLists(hostClassLoader, *queries.toTypedArray()).flatten()
        // A different return type means the host changed shape; answering it anyway would corrupt its callers.
        for (method in methods) {
            check(method.returnType == returnType) {
                "${method.declaringClass.name}.${method.name} returns ${method.returnType.name}, expected ${returnType.name}"
            }
        }
        methods.forEachIndexed { index, method ->
            method.isAccessible = true
            scope.hooks.install(method, when (mode) {
                Mode.SKIP -> HookSpec(
                    id = "Answer$index",
                    requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                    before = { it.returnEarly(value) },
                )
                Mode.REPLACE -> HookSpec(
                    id = "Replace$index",
                    requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE),
                    after = { call -> if (call.outcome is CallOutcome.Returned) call.returnValue(value) },
                )
            })
        }
    }
}
