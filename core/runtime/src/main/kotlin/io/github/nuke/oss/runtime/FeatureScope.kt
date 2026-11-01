package io.github.nuke.oss.runtime

import io.github.nuke.oss.common.closeAll
import io.github.nuke.oss.hook.api.*
import java.lang.reflect.Executable

interface ScopedHooks {
    fun install(target: Executable, spec: HookSpec): HookHandle
}

// Owns a feature's Hook subscriptions and the resources it hands over with own, such as timers and pending main-thread
// tasks. Closing releases them all once, newest first.
class FeatureScope(owner: HookOwner, service: HookService) : AutoCloseable {
    private val lock = Any()
    private val resources = ArrayList<AutoCloseable>()
    private var closed = false

    val hooks: ScopedHooks = object : ScopedHooks {
        override fun install(target: Executable, spec: HookSpec): HookHandle = synchronized(lock) {
            check(!closed) { "Feature scope is closed" }
            val handle = service.install(owner, target, spec)
            if (closed) {
                handle.close()
                error("Feature scope closed during installation")
            }
            resources += handle
            handle
        }
    }

    // A resource handed to a scope that is already closed is closed at once, so it can never outlive the feature.
    fun <T : AutoCloseable> own(resource: T): T {
        val accepted = synchronized(lock) { if (closed) false else { resources += resource; true } }
        if (!accepted) resource.close()
        return resource
    }

    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            resources.asReversed().toList().also { resources.clear() }
        }
        owned.closeAll()
    }
}
