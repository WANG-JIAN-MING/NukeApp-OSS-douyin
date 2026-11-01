package io.github.nuke.oss.app.tiktok.feature.chat

import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.app.shared.activity.Foreground
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatAdapterEvents
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

// A swipe to the left as a gesture over touch positions: the finger must travel a fifth of the list's width, mostly
// sideways.
internal class SwipeDetector {
    private var startX = 0f
    private var startY = 0f
    private var tracking = false

    fun down(x: Float, y: Float) {
        startX = x
        startY = y
        tracking = true
    }

    fun cancel() {
        tracking = false
    }

    fun up(x: Float, y: Float, width: Int): Boolean {
        val travelled = startX - x
        val hit = tracking && width > 0 && travelled > width * MIN_TRAVEL && abs(y - startY) < travelled / 2
        tracking = false
        return hit
    }

    private companion object {
        const val MIN_TRAVEL = 0.2f
    }
}

// Reports the row that was swiped left in a chat list. attach is called with the list each time a row is created for it
// and must watch a list only once.
internal interface ChatSwipe {
    fun attach(list: Any, onSwipe: (row: Any) -> Unit)
}

// Swipe a chat message to the left to start a reply to it, the way the long-press menu's reply does: the menu's data
// provider is built from the chat's logic object and its reply step is called with the message.
@HookEntry
internal class ReplyBySwipe(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val events: ChatAdapterEvents,
    private val swipe: () -> ChatSwipe,
    private val foreground: Foreground,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = "ReplyBySwipe"
    override val dexQueries = listOf(ChatQueries.CHAT_LOGIC, ChatQueries.REPLY_ACTION)

    override fun install(scope: FeatureScope) {
        foreground.install(scope, id)
        val (logicMethod, replyStep) = resolver.methods(hostClassLoader, ChatQueries.CHAT_LOGIC, ChatQueries.REPLY_ACTION)
        val logicType = logicMethod.declaringClass
        val provider = replyStep.declaringClass.resolve().optional(silent = true).firstConstructorOrNull {
            parameters { it.size == 1 && it[0].isAssignableFrom(logicType) }
        }?.self ?: error("${replyStep.declaringClass.name} has no constructor that takes the chat logic")
        val withSource = replyStep.parameterCount == 3
        check(replyStep.parameterCount == 2 || withSource) { "${replyStep.name} is not a reply step" }
        provider.isAccessible = true
        replyStep.isAccessible = true
        val constructors = logicType.resolve().optional(silent = true).constructor { isSynthetic = false }.map { it.self }
        check(constructors.isNotEmpty()) { "${logicType.name} declares no constructor" }
        val logic = AtomicReference<WeakReference<Any>?>()
        constructors.forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            scope.hooks.install(constructor, HookSpec(
                id = "ChatLogic$index",
                requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
                after = { call -> if (call.outcome is CallOutcome.Returned) call.receiver?.let { logic.set(WeakReference(it)) } },
            ))
        }
        val messages = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())
        val ui = swipe()
        events.install(scope, id,
            onCreated = { list, _ -> if (list != null) ui.attach(list) { row -> sendReply(provider, replyStep, withSource, logic.get()?.get(), messages[row]?.get()) } },
            onBound = { row, message -> messages[row] = WeakReference(message) },
        )
    }

    private fun sendReply(provider: java.lang.reflect.Constructor<*>, step: java.lang.reflect.Method, withSource: Boolean, logic: Any?, message: Any?) {
        if (foreground.activityName()?.endsWith(MERGED_FORWARD) == true) return
        if (logic == null || message == null) return logger.log("feature=$id reply_skipped chat logic or message not seen")
        try {
            val target = provider.newInstance(logic)
            if (withSource) step.invoke(target, message, SOURCE, false) else step.invoke(target, message, false)
        } catch (failure: ReflectiveOperationException) {
            logger.log("feature=$id reply_failed ${failure.javaClass.simpleName}")
        }
    }

    companion object : TikTokFeature {
        private const val SOURCE = "click_menu_reply"
        // The merged-forward viewer has no input to reply into.
        private const val MERGED_FORWARD = "ShareMergeShowActivity"

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(ReplyBySwipe(resolver, hostClassLoader, chatRows, chatSwipe, foreground(), logger))
        }
    }
}
