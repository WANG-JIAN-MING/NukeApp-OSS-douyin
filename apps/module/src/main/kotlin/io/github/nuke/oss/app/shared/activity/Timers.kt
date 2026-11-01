package io.github.nuke.oss.app.shared.activity

import android.os.Handler
import android.os.Looper

// Runs a task later. The returned handle cancels it if it has not run yet.
internal fun interface TaskScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): AutoCloseable
}

// Schedules on the host's main thread, where Views and dialogs may be touched.
internal val mainThreadScheduler = TaskScheduler { delayMillis, task ->
    val handler = Handler(Looper.getMainLooper())
    val runnable = Runnable(task)
    handler.postDelayed(runnable, delayMillis)
    AutoCloseable { handler.removeCallbacks(runnable) }
}

// The pending tasks of one installed feature. Handed to the feature's scope, so turning the feature off cancels every
// task it still has queued; tasks scheduled after that are dropped. A task only runs while it is still pending, so a
// cancel that races with the scheduler never lets it through.
internal class Timers(private val scheduler: TaskScheduler) : AutoCloseable {
    private class Entry(val key: String?) : AutoCloseable {
        @Volatile var handle: AutoCloseable? = null
        override fun close() { handle?.close() }
    }

    private val lock = Any()
    private val pending = HashSet<Entry>()
    private val keyed = HashMap<String, Entry>()
    private var closed = false

    fun after(delayMillis: Long, task: () -> Unit) = schedule(delayMillis, null, task)

    // A keyed task replaces the one already queued under the same key, as a restarted countdown does.
    fun replace(key: String, delayMillis: Long, task: () -> Unit) = schedule(delayMillis, key, task)

    fun cancel(key: String) {
        synchronized(lock) { keyed.remove(key)?.also(pending::remove) }?.close()
    }

    fun isPending(key: String): Boolean = synchronized(lock) { key in keyed }

    private fun schedule(delayMillis: Long, key: String?, task: () -> Unit) {
        val entry = Entry(key)
        val replaced = synchronized(lock) {
            if (closed) return
            pending += entry
            if (key == null) null else keyed.put(key, entry)?.also(pending::remove)
        }
        replaced?.close()
        entry.handle = scheduler.schedule(delayMillis) {
            val live = synchronized(lock) {
                if (key != null && keyed[key] === entry) keyed.remove(key)
                pending.remove(entry) && !closed
            }
            if (live) task()
        }
    }

    override fun close() {
        val all = synchronized(lock) {
            if (closed) return
            closed = true
            pending.toList().also { pending.clear(); keyed.clear() }
        }
        all.forEach(AutoCloseable::close)
    }
}
