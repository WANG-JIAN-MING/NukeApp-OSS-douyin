package io.github.nuke.oss.app.shared.activity

import android.app.Activity
import android.app.Instrumentation
import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

// The host Activity the user currently sees, followed through Instrumentation's resume and pause calls, which every
// Activity goes through whatever it overrides. Only a weak reference is kept, and only while the owning feature's
// hooks are installed; a finishing or destroyed Activity reads as none. A feature may hold several of these (its prompts
// and its clipboard each follow the Activity), so every instance names its subscriptions apart.
internal class ResumedActivity {
    @Volatile private var resumed: WeakReference<Activity>? = null
    private val serial = SERIAL.incrementAndGet()

    fun install(scope: FeatureScope, featureId: String) {
        val type = Instrumentation::class.resolve()
        val onResume = type.firstMethod { name = "callActivityOnResume"; parameters(Activity::class) }.self
        val onPause = type.firstMethod { name = "callActivityOnPause"; parameters(Activity::class) }.self
        scope.hooks.install(onResume, HookSpec(id = "$featureId:ActivityResumed#$serial", after = { call ->
            (call.argument(0) as? Activity)?.let { resumed = WeakReference(it) }
        }))
        scope.hooks.install(onPause, HookSpec(id = "$featureId:ActivityPaused#$serial", before = { call ->
            if (resumed?.get() === call.argument(0)) resumed = null
        }))
    }

    fun get(): Activity? = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    private companion object {
        val SERIAL = AtomicInteger()
    }
}
