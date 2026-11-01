package io.github.nuke.oss.script

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Starts script sessions and watches them for tasks that never yield. Its owner closes it, which stops every session
// it started.
class ScriptEngine(private val limits: ScriptLimits = ScriptLimits()) : AutoCloseable {
    private val sessions = CopyOnWriteArraySet<ScriptSession>()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nuke-script-watchdog").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    init {
        watchdog.scheduleWithFixedDelay({
            val now = System.nanoTime()
            sessions.forEach { it.checkBusy(now) }
        }, WATCH_MILLIS, WATCH_MILLIS, TimeUnit.MILLISECONDS)
    }

    // Starts evaluating the entry module in the background; use ScriptSession.awaitStart to wait for it. Module names
    // must be unique.
    fun start(definition: ScriptDefinition, modules: List<ScriptModule>, listener: ScriptSessionListener): ScriptSession {
        check(!closed) { "The script engine is closed" }
        val tracked = AtomicReference<ScriptSession?>()
        val tracking = object : ScriptSessionListener by listener {
            override fun stopped(failure: ScriptException?) {
                tracked.get()?.let(sessions::remove)
                listener.stopped(failure)
            }
        }
        val session = ScriptSession(definition, modules, limits, tracking)
        sessions += session
        tracked.set(session)
        // A session that stopped before it was tracked could not remove itself.
        if (session.isFinished) sessions.remove(session)
        return session
    }

    override fun close() {
        closed = true
        sessions.forEach { it.close() }
        watchdog.shutdownNow()
    }

    private companion object {
        const val WATCH_MILLIS = 250L
    }
}
