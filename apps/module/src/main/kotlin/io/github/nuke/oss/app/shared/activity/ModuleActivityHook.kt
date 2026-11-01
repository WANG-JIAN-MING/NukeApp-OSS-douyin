package io.github.nuke.oss.app.shared.activity

import android.app.Instrumentation
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import java.lang.reflect.Method

// Runs a module Activity inside a host process without declaring it in the host manifest. The caller starts a
// host Activity (the stub) that the system accepts; when ActivityThread then asks Instrumentation for that stub
// instance, this hook answers with the module Activity instead. The module class keeps the stub's task, window and
// lifecycle, so no ActivityThread, Handler or ActivityManager object has to be replaced.
// If the module Activity cannot be built, the host's own stub Activity opens unchanged and the reason is logged.
internal class ModuleActivityHook(
    private val newActivity: Method,
    private val create: (launchIntent: Any?) -> Any?,
    private val logger: Logger,
    private val featureId: String,
) {
    private val lock = Any()
    private val covered = HashSet<Method>()
    private var scope: FeatureScope? = null

    fun install(scope: FeatureScope) {
        synchronized(lock) { this.scope = scope }
        hook(newActivity)
    }

    // A host may replace the process's Instrumentation with subclasses that build Activities themselves and never
    // reach the base method (Douyin's Mira plugin framework does), so every override on the live instance's class
    // chain gets the same hook. The outermost one runs first and answers before any host wrapper.
    fun cover(instrumentation: Any) {
        var type: Class<*>? = instrumentation.javaClass
        while (type != null && type != newActivity.declaringClass) {
            type.resolve().optional(silent = true).firstMethodOrNull {
                name = newActivity.name
                parameters(*newActivity.parameterTypes)
            }?.let { hook(it.self) }
            type = type.superclass
        }
    }

    private fun hook(target: Method) {
        synchronized(lock) {
            if (target in covered) return
            val scope = checkNotNull(scope) { "ModuleActivityHook is not installed" }
            scope.hooks.install(target, HookSpec(
                id = "ModuleActivity:${target.declaringClass.name}.${target.name}",
                before = { call ->
                    val activity = try {
                        create(call.argument(INTENT_INDEX))
                    } catch (error: ReflectiveOperationException) {
                        logger.log("feature=$featureId module_activity_failed $error")
                        null
                    } catch (error: LinkageError) {
                        logger.log("feature=$featureId module_activity_failed $error")
                        null
                    }
                    if (activity != null) call.returnEarly(activity)
                },
            ))
            covered += target
            if (target != newActivity) logger.log("feature=$featureId module_activity_covered class=${target.declaringClass.name}")
        }
    }

    companion object {
        // Instrumentation.newActivity(ClassLoader, String, Intent)
        private const val INTENT_INDEX = 2

        // The instance ActivityThread hands each launch to, after any wrapping the host has done.
        fun currentInstrumentation(): Instrumentation {
            val thread = "android.app.ActivityThread".toClass().resolve()
            val current = thread.firstMethod { name = "currentActivityThread"; emptyParameters() }.invoke()
            return thread.firstField { name = "mInstrumentation" }.of(current).get() as Instrumentation
        }
    }
}
