package io.github.nuke.oss.app.tiktok.feature.general

import android.app.Activity
import android.app.Instrumentation
import android.view.MotionEvent
import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostExit
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.activity.Timers
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Method

// The framework methods the module scheme listens to. Tests pass stand-ins, since the real ones cannot run on a JVM.
internal class ActivitySignals(
    val touch: Method = Activity::class.resolve().firstMethod { name = "dispatchTouchEvent"; parameters(MotionEvent::class) }.self,
    val resume: Method = Instrumentation::class.resolve().firstMethod { name = "callActivityOnResume"; parameters(Activity::class) }.self,
    val pause: Method = Instrumentation::class.resolve().firstMethod { name = "callActivityOnPause"; parameters(Activity::class) }.self,
    val action: (Any?) -> Int? = { (it as? MotionEvent)?.actionMasked },
)

// Closes Douyin after a stretch without use. The module scheme restarts its timer on every touch and whenever an
// Activity comes to the front, stops it while none is in front, and when it runs out shows a ten-second countdown
// that a touch or Cancel stops. The host scheme starts Douyin's own sleep timer with the chosen time instead.
@HookEntry
internal class TimedClose(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val prompts: HostPrompts,
    private val exit: HostExit,
    private val scheduler: TaskScheduler,
    private val logger: Logger,
    private val signals: () -> ActivitySignals = ::ActivitySignals,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(OptionQueries.COUNTDOWN_TASK)
    private val idleMillis = options.number(MINUTES) * 60_000
    private val scheme = options.choice(SCHEME)
    @Volatile private var countdown: AutoCloseable? = null

    override fun install(scope: FeatureScope) {
        if (scheme == HOST) installHostTimer(scope) else installIdleTimer(scope)
    }

    private fun installIdleTimer(scope: FeatureScope) {
        prompts.install(scope)
        val timers = scope.own(Timers(scheduler))
        scope.own(AutoCloseable { countdown?.close(); countdown = null })
        val restart = {
            countdown?.close()
            countdown = null
            timers.replace(IDLE, idleMillis) { startCountdown(timers) }
        }
        val signal = signals()
        for (method in listOf(signal.touch, signal.resume, signal.pause)) method.isAccessible = true
        scope.hooks.install(signal.touch, HookSpec(id = "IdleTouch", before = { call ->
            val action = signal.action(call.argument(0))
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) restart()
        }))
        scope.hooks.install(signal.resume, HookSpec(id = "IdleResume", after = { restart() }))
        scope.hooks.install(signal.pause, HookSpec(id = "IdlePause", before = { timers.cancel(IDLE) }))
        restart()
    }

    private fun startCountdown(timers: Timers) {
        countdown = prompts.countdown(
            R.string.host_timed_close_title, R.string.host_timed_close_message, COUNTDOWN_SECONDS, R.string.host_timed_close_cancel,
            onCancel = { countdown = null; timers.replace(IDLE, idleMillis) { startCountdown(timers) } },
            onFinish = { exit.exit() },
        )
    }

    // Douyin's count-down task takes the time in seconds, a mode and the source that started it. Its owner may not be
    // ready right after construction, so the start is retried for a few seconds.
    private fun installHostTimer(scope: FeatureScope) {
        val start = resolver.methods(hostClassLoader, OptionQueries.COUNTDOWN_TASK).single()
        val types = start.parameterTypes
        check(types.size == 3 && types[0] == Long::class.javaPrimitiveType && types[1].isEnum && types[2] == String::class.java) {
            "${start.declaringClass.name}.${start.name} is not (long, enum, String)"
        }
        @Suppress("UNCHECKED_CAST")
        val stable = java.lang.Enum.valueOf(types[1] as Class<out Enum<*>>, STABLE_TIME)
        start.isAccessible = true
        val timers = scope.own(Timers(scheduler))
        val constructors = start.declaringClass.resolve().optional(silent = true).constructor { isSynthetic = false }.map { it.self }
        check(constructors.isNotEmpty()) { "${start.declaringClass.name} declares no constructor" }
        constructors.forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            scope.hooks.install(constructor, HookSpec(
                id = "HostTimer$index",
                requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
                after = { call ->
                    val task = call.receiver
                    if (call.outcome is CallOutcome.Returned && task != null) startHostTimer(timers, start, task, stable, 1)
                },
            ))
        }
    }

    private fun startHostTimer(timers: Timers, start: Method, task: Any, stable: Any, attempt: Int) {
        timers.after(RETRY_MILLIS) {
            try {
                start.invoke(task, idleMillis / 1000, stable, SOURCE)
            } catch (error: ReflectiveOperationException) {
                if (attempt < MAX_ATTEMPTS) startHostTimer(timers, start, task, stable, attempt + 1)
                else logger.log("feature=$ID host_timer_failed attempts=$attempt ${error.cause ?: error}")
            }
        }
    }

    companion object : TikTokFeature {
        const val ID = "TimedClose"
        const val MINUTES = "minutes"
        const val SCHEME = "scheme"
        const val MODULE = "module"
        const val HOST = "host"
        private const val IDLE = "idle"
        private const val COUNTDOWN_SECONDS = 10
        private const val STABLE_TIME = "STABLE_TIME"
        private const val SOURCE = "long_press"
        private const val RETRY_MILLIS = 1000L
        private const val MAX_ATTEMPTS = 10
        val OPTIONS = listOf(NumberOption(MINUTES, default = 30, min = 1, max = 1440), ChoiceOption(SCHEME, listOf(MODULE, HOST)))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(TimedClose(resolver, hostClassLoader, options(ID, OPTIONS), prompts(ID), exit, scheduler, logger))
        }
    }
}
