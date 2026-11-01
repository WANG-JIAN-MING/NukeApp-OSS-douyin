package io.github.nuke.oss.app.tiktok.feature.feed

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.ref.WeakReference
import java.lang.reflect.Method

// Where the button goes in the right-hand column. The option value is the choice ID.
internal enum class DislikePlacement(val option: String) {
    END("share"), AFTER_FAVORITE("favorite"), AFTER_COMMENT("comment"), AFTER_LIKE("like"), BELOW_AVATAR("avatar");

    companion object {
        fun of(option: String) = entries.firstOrNull { it.option == option } ?: END
    }
}

// The index at which to insert the button among the column's direct children, or -1 to append. Each child is given as
// the content descriptions found in it; the host marks its buttons with a description that ends in "按钮" and names
// the button, so a child counts as the favorite, comment or like button when one description has both.
internal fun dislikeSlot(placement: DislikePlacement, children: List<List<String>>): Int {
    val keyword = when (placement) {
        DislikePlacement.END -> return -1
        DislikePlacement.AFTER_FAVORITE -> "收藏"
        DislikePlacement.AFTER_COMMENT -> "评论"
        DislikePlacement.AFTER_LIKE, DislikePlacement.BELOW_AVATAR -> "点赞"
    }
    val index = children.indexOfFirst { descriptions -> descriptions.any { "按钮" in it && keyword in it } }
    if (index < 0) return -1
    return if (placement == DislikePlacement.BELOW_AVATAR) index else index + 1
}

// Puts the button in a right-hand column the host has just built. The button's click calls onClick with the column's
// Context once the button exists; the column's children appear after its constructor, so the work is retried.
internal interface DislikeButton {
    fun attach(bar: Any, placement: DislikePlacement, onClick: (context: Any) -> Unit)
}

// Runs the feed's own "not interested" action the way its share panel does: a share package of the item type
// "disLike", then the action object built from the video and that package, then the action's method with the
// Activity and the package. Anything missing fails with a message instead of doing something else.
internal class DislikeAction(
    private val dislike: Method,
    sharePackage: Method,
    hostClassLoader: ClassLoader,
) {
    private val packageType = sharePackage.declaringClass
    private val actionType = dislike.declaringClass
    private val builderType = BUILDER.toClass(hostClassLoader)
    private val newBuilder = builderType.resolve().firstConstructor { emptyParameters() }
    private val itemType = builderType.resolve().firstMethod { name = "itemType"; parameters(String::class); superclass() }.self

    init {
        check(dislike.parameterCount == 2) { "${dislike.name} does not take an Activity and a share package" }
        dislike.isAccessible = true
    }

    fun run(aweme: Any, context: Any) {
        val builder = newBuilder.create()
        itemType.invoke(builder, ITEM_TYPE)
        val share = construct(packageType, listOf(aweme, builder)) ?: error("${packageType.name} has no constructor for a video and a builder")
        // Hosts differ in whether the leading argument is the video or a flag.
        val action = construct(actionType, listOf(aweme, share, PAGE, ELEMENT, "", 0))
            ?: construct(actionType, listOf(0, aweme, share, PAGE, ELEMENT, ""))
            ?: error("${actionType.name} has no constructor for a dislike")
        dislike.invoke(action, context, share)
    }

    private fun construct(type: Class<*>, arguments: List<Any>): Any? {
        val constructor = type.resolve().optional(silent = true).firstConstructorOrNull {
            isSynthetic = false
            parameters { types ->
                types.size == arguments.size && types.zip(arguments).all { (parameter, value) -> boxed(parameter).isInstance(value) }
            }
        } ?: return null
        return constructor.create(*arguments.toTypedArray())
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        else -> type
    }

    companion object {
        const val BUILDER = "com.ss.android.ugc.aweme.sharer.ui.SharePackage\$Builder"
        private const val ITEM_TYPE = "disLike"
        private const val PAGE = "homepage_hot"
        private const val ELEMENT = "click_share_button"
    }
}

// Adds a "not interested" button to the video's right-hand column. Its click runs the host's own dislike action for the
// video on screen, optionally after asking.
@HookEntry
internal class AddDislikeButton(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val events: FeedEvents,
    private val button: () -> DislikeButton,
    private val prompts: HostPrompts,
    private val logger: Logger,
    options: OptionValues,
    private val rightBarName: String = HideFeedComponents.RIGHT_BAR,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(FeedQueries.DISLIKE_ACTION, FeedQueries.SHARE_PACKAGE) + FeedEvents.VIDEO_CHANGED
    private val placement = DislikePlacement.of(options.choice(POSITION))
    private val confirm = options.toggle(CONFIRM)

    override fun install(scope: FeatureScope) {
        prompts.install(scope)
        val (dislike, share) = resolver.methods(hostClassLoader, FeedQueries.DISLIKE_ACTION, FeedQueries.SHARE_PACKAGE)
        val action = DislikeAction(dislike, share, hostClassLoader)
        val bar = rightBarName.toClass(hostClassLoader)
        val constructors = bar.resolve().optional(silent = true).constructor { isSynthetic = false }.map { it.self }
        check(constructors.isNotEmpty()) { "${bar.name} declares no constructor" }
        val current = java.util.concurrent.atomic.AtomicReference<WeakReference<Any>?>()
        val ui = button()
        events.onVideoChanged(scope, ID) { aweme -> current.set(WeakReference(aweme)) }
        constructors.forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            scope.hooks.install(constructor, HookSpec(
                id = "RightBar$index",
                requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
                after = { call ->
                    val view = call.receiver
                    if (call.outcome is CallOutcome.Returned && view != null) {
                        ui.attach(view, placement) { context -> clicked(action, current.get()?.get(), context) }
                    }
                },
            ))
        }
    }

    // Runs on the main thread when the user taps the button.
    private fun clicked(action: DislikeAction, video: Any?, context: Any) {
        if (video == null) return prompts.toast(R.string.host_dislike_failed)
        val run = {
            try {
                action.run(video, context)
            } catch (failure: ReflectiveOperationException) {
                fail(failure)
            } catch (failure: IllegalStateException) {
                fail(failure)
            }
        }
        if (confirm) prompts.confirm(R.string.host_confirm_title, R.string.host_confirm_dislike, emptyArray(), R.string.host_confirm_action_dislike, onUnavailable = run, onConfirm = run)
        else run()
    }

    private fun fail(failure: Exception) {
        logger.log("feature=$ID dislike_failed ${failure.javaClass.simpleName}")
        prompts.toast(R.string.host_dislike_failed)
    }

    companion object : TikTokFeature {
        const val ID = "AddDislikeButton"
        const val POSITION = "position"
        const val CONFIRM = "confirm"
        val OPTIONS = listOf(
            ChoiceOption(POSITION, DislikePlacement.entries.map { it.option }, default = DislikePlacement.END.option),
            ToggleOption(CONFIRM),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(AddDislikeButton(resolver, hostClassLoader, events, dislikeButton, prompts(ID), logger, options(ID, OPTIONS)))
        }
    }
}
