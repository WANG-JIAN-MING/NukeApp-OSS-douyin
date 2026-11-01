package io.github.nuke.oss.app.shared.dex

// A feature that resolves these Dex queries. When the analysis did not locate some of them and unusable says the rest
// cannot carry the feature, the host adapter records it as a Dex failure and holds it off before anything installs,
// whether it is switched on or not. By default one missing query is enough; a feature that has alternatives overrides
// unusable. dexQueries lists everything the feature may resolve, including the alternatives, so a forgotten
// declaration can be told from a deliberate one.
interface UsesDexQueries {
    val dexQueries: List<DexQuery>
    fun unusable(missing: List<DexQuery>): Boolean = missing.isNotEmpty()
}
