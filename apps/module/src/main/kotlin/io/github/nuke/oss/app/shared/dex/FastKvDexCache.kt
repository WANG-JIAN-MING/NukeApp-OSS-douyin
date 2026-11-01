package io.github.nuke.oss.app.shared.dex

import io.fastkv.FastKV
import java.io.File

// Stores descriptors in the host's private directory. A different scope clears the store, so stale builds never accumulate.
class FastKvDexCache(directory: File, private val scope: String) : DexCache {
    private val store: FastKV

    init {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create Dex cache directory: ${directory.path}" }
        store = FastKV.Builder(directory.path, "dex-cache").build()
        if (store.getString(SCOPE_KEY, null) != scope) {
            store.clear()
            store.putString(SCOPE_KEY, scope)
        }
    }

    override fun get(key: String): String? = store.getString(key, null)

    override fun put(key: String, descriptor: String) {
        store.putString(key, descriptor)
    }

    // Forgets every descriptor and every recorded failure, so the next start finds all queries pending and analyses
    // them again. The scope stays, which keeps this instance and the stored marker in step.
    fun clear() {
        store.clear()
        store.putString(SCOPE_KEY, scope)
    }

    private companion object {
        const val SCOPE_KEY = "scope"
    }
}
