package io.github.nuke.oss.app.tiktok.feature.feed

import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.activity.Timers
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.declaredMethodsNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.MultiOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanPlan
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanScreen
import io.github.nuke.oss.app.tiktok.sdk.screen.VideoControls
import io.github.nuke.oss.app.tiktok.sdk.screen.planVideoControls
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Modifier

// Takes chosen controls off the feed: some by hiding their Views after each video change, the quick share button by
// not letting its presenter show it, the follow button by marking the author as already followed on screen. Nothing
// is sent to the server, and turning the feature off brings the hidden Views back.
@HookEntry
internal class HideVideoControls(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val events: FeedEvents,
    private val screen: CleanScreen,
    private val scheduler: TaskScheduler,
    options: OptionValues,
    private val presenterName: String = PRESENTER,
    private val awemeName: String = AWEME,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    private val hidden = options.multi(HIDDEN)
    private val hideQuickShare = options.toggle(QUICK_SHARE)
    private val hideFollow = options.toggle(FOLLOW_BUTTON)
    private val views = hidden.intersect(VideoControls.ALL.toSet())
    // Only what the chosen options resolve.
    override val dexQueries: List<DexQuery> = (if (views.isNotEmpty()) FeedEvents.VIDEO_CHANGED else emptyList()) +
        listOfNotNull(FeedQueries.QUICK_SHARE.takeIf { hideQuickShare }, OptionQueries.LIVE_RING.takeIf { hideFollow })
    private val author = NamedField("author")
    private val followStatus = NamedField("follow_status")

    override fun install(scope: FeatureScope) {
        if (views.isNotEmpty()) {
            screen.install(scope, ID)
            val timers = scope.own(Timers(scheduler))
            events.onVideoChanged(scope, ID) {
                for ((index, delay) in REAPPLY_MS.withIndex()) timers.replace("hide-$index", delay) { sweep(views) }
            }
        }
        if (hideQuickShare) installQuickShare(scope)
        if (hideFollow) installFollowButton(scope)
    }

    private fun sweep(chosen: Set<String>) {
        val root = screen.snapshot() ?: return
        val handles = planVideoControls(root, chosen)
        if (handles.isNotEmpty()) screen.hide(CleanPlan(emptyList(), handles), keepClickable = false)
    }

    private fun installQuickShare(scope: FeatureScope) {
        val anchor = resolver.methods(hostClassLoader, FeedQueries.QUICK_SHARE).single()
        val overloads = anchor.declaringClass.declaredMethodsNamed(anchor.name)
        overloads.forEachIndexed { index, method ->
            check(method.returnType == Void.TYPE) { "${method.declaringClass.name}.${method.name} returns ${method.returnType.name}, not void" }
            scope.hooks.install(method, HookSpec(
                id = "SkipQuickShare$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { it.returnEarly(null) },
            ))
        }
    }

    // The presenter holds the video in its only public field of the video type; its author is marked as followed.
    private fun installFollowButton(scope: FeatureScope) {
        val setup = resolver.methods(hostClassLoader, OptionQueries.LIVE_RING).single()
        val presenter = presenterName.toClass(hostClassLoader)
        val aweme = awemeName.toClass(hostClassLoader)
        check(presenter.isAssignableFrom(setup.declaringClass)) { "${setup.declaringClass.name} is not a ${presenter.simpleName}" }
        val videoField = generateSequence(presenter) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.type == aweme && Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .toList()
            .also { check(it.size == 1) { "Expected one public ${aweme.simpleName} field in ${presenter.name}, found ${it.size}" } }
            .single()
            .apply { isAccessible = true }
        setup.isAccessible = true
        scope.hooks.install(setup, HookSpec(id = "MarkAuthorFollowed", before = { call ->
            val video = call.receiver?.let { videoField.get(it) }
            val owner = video?.let { author.of(it)?.get(it) }
            val status = owner?.let { followStatus.of(it) }
            if (owner != null && status != null && status.type == Int::class.javaPrimitiveType && status.getInt(owner) != FOLLOWED) status.setInt(owner, FOLLOWED)
        }))
    }

    companion object : TikTokFeature {
        const val ID = "HideVideoControls"
        const val HIDDEN = "hidden"
        const val QUICK_SHARE = "quick-share"
        const val FOLLOW_BUTTON = "follow-button"
        const val PRESENTER = HideAvatarRings.PRESENTER
        const val AWEME = HideAvatarRings.AWEME
        private const val FOLLOWED = 1
        private val REAPPLY_MS = listOf(500L, 900L, 1500L)
        val OPTIONS = listOf(MultiOption(HIDDEN, VideoControls.ALL), ToggleOption(QUICK_SHARE), ToggleOption(FOLLOW_BUTTON))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(HideVideoControls(resolver, hostClassLoader, events, cleanScreen(), scheduler, options(ID, OPTIONS)))
        }
    }
}
