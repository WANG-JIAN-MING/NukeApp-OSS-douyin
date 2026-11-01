package io.github.nuke.oss.app.shared.dex

// The map-shaped search most tests need: the steps are announced as DexKitSearch does, each query is answered by
// name from the map, and one the map lacks is reported as not located. An exception from answer stands for a search
// that could not open the Dex files at all.
fun mapSearch(answer: (Map<String, DexQuery>) -> Map<String, String>) = DexSearch { queries, onStep, onEach ->
    onStep(AnalysisStep.LOADING_NATIVE)
    onStep(AnalysisStep.OPENING_DEX)
    val found = answer(queries)
    onStep(AnalysisStep.SEARCHING)
    for (name in queries.keys) {
        val descriptor = found[name]
        onEach(name, if (descriptor != null) Result.success(descriptor)
            else Result.failure(IllegalStateException("Dex query $name was not located")))
    }
}
