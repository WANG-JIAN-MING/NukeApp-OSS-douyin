package io.github.nuke.oss.app.tiktok.feature.playback

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.makeAccessible
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.activity.Timers
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.feed.HideFeedComponents
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

// Floats a draggable autoplay switch over the feed's window. The feed's right-hand bar, which the host has just built,
// says the feed exists and which window it is in. The switch shows what active reports when it is built and when update
// is called, and its click calls onClick on the main thread.
internal interface AutoPlayButton {
    fun attach(bar: Any, active: () -> Boolean, onClick: () -> Unit)
    fun update(active: Boolean)
}

// Autoplay, in one of two schemes. Douyin's scheme turns the host's own autoplay on every time its view model is
// created; the view model exposes the setting as its only no-argument getter of the host's live data type. The module
// scheme moves the feed on itself: just before a video ends, and after a set time on live rooms and image posts. The
// optional floating button over the feed turns either scheme off and on again until the process ends.
@HookEntry
internal class KeepAutoPlayOn(
    private val hostClassLoader: ClassLoader,
    private val events: FeedEvents,
    private val scheduler: TaskScheduler,
    private val button: () -> AutoPlayButton,
    options: OptionValues,
    private val viewModelName: String = VIEW_MODEL,
    private val liveDataName: String = LIVE_DATA,
    private val rightBarName: String = HideFeedComponents.RIGHT_BAR,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = FeedEvents.VIDEO_CHANGED + FeedEvents.PAGER + FeedEvents.PLAYBACK + FeedEvents.PROGRESS
    private val scheme = options.choice(SCHEME)
    private val showButton = options.toggle(BUTTON)
    private val liveMillis = options.number(LIVE_SECONDS) * 1000
    private val imagesMillis = options.number(IMAGES_SECONDS) * 1000
    private val awemeId = NamedField("aweme_id")
    private val awemeType = NamedField("aweme_type")
    private val images = NamedField("images")
    @Volatile private var current: String? = null
    @Volatile private var playing = false
    @Volatile private var queuedFor: String? = null
    // The switch the button flips; autoplay starts on, as it does without the button.
    @Volatile private var switchedOn = true
    @Volatile private var hostSwitch: WeakReference<Any>? = null
    @Volatile private var hostPost: Method? = null

    override fun install(scope: FeatureScope) {
        switchedOn = true
        hostSwitch = null
        hostPost = null
        if (scheme == MODULE) installModuleScheme(scope) else installHostScheme(scope)
        if (showButton) installButton(scope)
    }

    // The switch only acts while the feature is installed; a button the host keeps after that does nothing.
    private fun installButton(scope: FeatureScope) {
        val ui = button()
        val open = AtomicBoolean(true)
        scope.own(AutoCloseable { open.set(false) })
        val bar = rightBarName.toClass(hostClassLoader)
        val constructors = bar.resolve().optional(silent = true).constructor { isSynthetic = false }.map { it.self }
        check(constructors.isNotEmpty()) { "${bar.name} declares no constructor" }
        constructors.forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            scope.hooks.install(constructor, HookSpec(
                id = "RightBar$index",
                requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
                after = { call ->
                    val view = call.receiver
                    if (call.outcome is CallOutcome.Returned && view != null) {
                        ui.attach(view, { switchedOn }) {
                            if (open.get()) {
                                switchedOn = !switchedOn
                                postToHost(switchedOn)
                                ui.update(switchedOn)
                            }
                        }
                    }
                },
            ))
        }
    }

    // Douyin's scheme keeps its own setting; the module scheme reads the switch itself.
    private fun postToHost(value: Boolean) {
        val target = hostSwitch?.get() ?: return
        hostPost?.invoke(target, value)
    }

    private fun installHostScheme(scope: FeatureScope) {
        val viewModel = viewModelName.toClass(hostClassLoader)
        val liveData = liveDataName.toClass(hostClassLoader)
        val members = viewModel.resolve().optional(silent = true)
        val getters = members.method {
            emptyParameters()
            returnType = liveData
            isSynthetic = false
            isBridge = false
        }
        check(getters.size == 1) { "Expected one ${liveData.simpleName} getter in ${viewModel.name}, found ${getters.size}" }
        val getter = getters.single().self.apply { isAccessible = true }
        val post = postValueOf(liveData)
        hostPost = post
        val constructors = members.constructor { isSynthetic = false }.map { it.self }
        check(constructors.isNotEmpty()) { "${viewModel.name} declares no constructor" }
        constructors.forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            scope.hooks.install(constructor, HookSpec(
                id = "AutoplayOn$index",
                requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
                after = { call ->
                    val model = call.receiver
                    if (call.outcome is CallOutcome.Returned && model != null) {
                        getter.invoke(model)?.let {
                            hostSwitch = WeakReference(it)
                            post.invoke(it, switchedOn)
                        }
                    }
                },
            ))
        }
    }

    // Only one move is ever queued, for the video it was queued for; a video change or a pause before it fires stops it.
    private fun installModuleScheme(scope: FeatureScope) {
        val timers = scope.own(Timers(scheduler))
        val pager = events.pager(scope, ID)
        current = null
        playing = false
        queuedFor = null
        val moveOn = { videoId: String?, delay: Long, needsPlaying: Boolean ->
            queuedFor = videoId
            timers.replace(NEXT, delay) {
                if (switchedOn && current == videoId && (!needsPlaying || playing)) pager.next()
            }
        }
        events.onVideoChanged(scope, ID) { aweme ->
            val videoId = awemeId.of(aweme)?.get(aweme) as? String
            current = videoId
            queuedFor = null
            timers.cancel(NEXT)
            val wait = when {
                awemeType.of(aweme)?.get(aweme) == LIVE_TYPE -> liveMillis
                (images.of(aweme)?.get(aweme) as? List<*>)?.isNotEmpty() == true -> imagesMillis
                else -> -1L
            }
            if (wait > 0 && switchedOn) moveOn(videoId, wait, false)
        }
        events.onPlayback(scope, ID, onPlay = { playing = true }, onPause = { playing = false })
        events.onProgress(scope, ID) { videoId, position, duration ->
            val left = duration - position
            val id = videoId ?: current
            if (switchedOn && duration > 0 && left in 0 until END_WINDOW_MS && queuedFor != id) moveOn(id, left, true)
        }
    }

    private fun postValueOf(liveData: Class<*>): Method =
        liveData.resolve().optional(silent = true).firstMethodOrNull { name = "postValue"; parameterCount = 1; superclass() }
            ?.self?.apply { makeAccessible() }
            ?: error("${liveData.name} has no postValue")

    companion object : TikTokFeature {
        const val ID = "KeepAutoplayOn"
        const val SCHEME = "scheme"
        const val HOST = "host"
        const val MODULE = "module"
        const val BUTTON = "button"
        const val LIVE_SECONDS = "live-seconds"
        const val IMAGES_SECONDS = "images-seconds"
        const val VIEW_MODEL = "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay.AutoPlayViewModel"
        const val LIVE_DATA = "com.ss.android.ugc.aweme.kiwi.viewmodel.QLiveData"
        private const val NEXT = "next"
        private const val LIVE_TYPE = 101
        private const val END_WINDOW_MS = 5000L
        val OPTIONS = listOf(
            ChoiceOption(SCHEME, listOf(HOST, MODULE)),
            ToggleOption(BUTTON),
            NumberOption(LIVE_SECONDS, default = 15, min = 0, max = 3600),
            NumberOption(IMAGES_SECONDS, default = 15, min = 0, max = 3600),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(KeepAutoPlayOn(hostClassLoader, events, scheduler, autoPlayButton, options(ID, OPTIONS)))
        }
    }
}
