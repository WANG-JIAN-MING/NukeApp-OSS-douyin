package io.github.nuke.oss.app.shared.dex

import org.luckypray.dexkit.DexKitBridge

// DexKit does not load its own library. Natives bind to the loader that defined DexKitBridge, which is this class's loader.
// There is no extraction fallback yet: a failed load surfaces so the owning feature reports FAILED instead of faking success.
class DexKitSearch(
    private val apkPath: String,
    private val loadLibrary: () -> Unit = { System.loadLibrary("dexkit") },
) : DexSearch {
    override fun search(queries: Map<String, DexQuery>, onStep: (AnalysisStep) -> Unit, onEach: (name: String, Result<String>) -> Unit) {
        onStep(AnalysisStep.LOADING_NATIVE)
        loadLibrary()
        onStep(AnalysisStep.OPENING_DEX)
        DexKitBridge.create(apkPath).use { bridge ->
            onStep(AnalysisStep.SEARCHING)
            for ((name, query) in queries) {
                // A stale matcher throws; that is this query's outcome, and the others still get looked up.
                val outcome = try {
                    Result.success(query.locate(bridge))
                } catch (error: Exception) {
                    Result.failure(error)
                }
                onEach(name, outcome)
            }
        }
    }
}
