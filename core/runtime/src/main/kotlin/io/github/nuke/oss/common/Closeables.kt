package io.github.nuke.oss.common

// Release every resource, preserving later failures as suppressed exceptions.
fun Iterable<AutoCloseable>.closeAll() {
    var failure: Throwable? = null
    for (resource in this) {
        try {
            resource.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}
