package io.github.nuke.oss.app.tiktok.feature.general

import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.declaredMethodsNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.MultiOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.query.ConfirmQueries
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.BeforeCall
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

// Asks before a tap does its work. The chosen taps are held back and a dialog asks; confirming runs the same call again
// with the same receiver and arguments, and only that call passes the hook. Only the receiver and arguments are kept
// for the dialog, never the hook frame. If no dialog can be shown the tap goes through as if the feature were off.
@HookEntry
internal class ConfirmBeforeActions(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val prompts: HostPrompts,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    private val chosen = options.multi(EVENTS)
    // Only the taps the user chose are resolved.
    override val dexQueries: List<DexQuery> get() = events.filter { it.id in chosen }.map { it.query }
    private val replaying = ThreadLocal<Method?>()
    private val author = NamedField("author")
    private val liveStatus = NamedField("live_status")

    // Which methods an event hooks, relative to the one its Dex query found.
    private enum class Shape {
        // The found method itself, which must take the user ID.
        FOUND_WITH_STRING,
        // The click listener the found method's class declares.
        CLICK_LISTENER,
        // Every overload of the found method's name in its class.
        ALL_OVERLOADS,
        // The found method itself, which must take the two Kotlin callbacks of the favourite click.
        FOUND_WITH_CALLBACKS,
    }

    private class Event(
        val id: String,
        val query: DexQuery,
        val shape: Shape,
        val message: Int,
        val confirm: Int,
        // What a held-back call answers when it returns a boolean; null means a boolean return is unexpected.
        val handledBoolean: Boolean? = null,
        val applies: (BeforeCall) -> Boolean = { true },
    )

    private val events = listOf(
        Event(FOLLOW, ConfirmQueries.FOLLOW, Shape.FOUND_WITH_STRING, R.string.host_confirm_follow, R.string.host_confirm_action_follow, handledBoolean = false),
        Event(LIKE, ConfirmQueries.LIKE, Shape.CLICK_LISTENER, R.string.host_confirm_like, R.string.host_confirm_action_like),
        Event(DAILY, ConfirmQueries.DAILY, Shape.ALL_OVERLOADS, R.string.host_confirm_daily, R.string.host_confirm_action_open),
        Event(LIVE, ConfirmQueries.LIVE_AVATAR, Shape.ALL_OVERLOADS, R.string.host_confirm_live, R.string.host_confirm_action_enter, handledBoolean = true, applies = ::authorIsLive),
        Event(FULL_SCREEN, ConfirmQueries.FULL_SCREEN, Shape.ALL_OVERLOADS, R.string.host_confirm_full_screen, R.string.host_confirm_action_enter),
        Event(TOPIC, ConfirmQueries.TOPIC, Shape.ALL_OVERLOADS, R.string.host_confirm_topic, R.string.host_confirm_action_go),
        Event(LIVE_COVER, ConfirmQueries.LIVE_COVER, Shape.ALL_OVERLOADS, R.string.host_confirm_live, R.string.host_confirm_action_enter),
        Event(SEARCH, ConfirmQueries.SEARCH_PAGE, Shape.ALL_OVERLOADS, R.string.host_confirm_search, R.string.host_confirm_action_go),
        Event(COMMENT_LIKE, ConfirmQueries.COMMENT_LIKE, Shape.ALL_OVERLOADS, R.string.host_confirm_comment_like, R.string.host_confirm_action_like),
        Event(COLLECT, ConfirmQueries.COLLECT, Shape.FOUND_WITH_CALLBACKS, R.string.host_confirm_collect, R.string.host_confirm_action_collect),
    )

    override fun install(scope: FeatureScope) {
        val picked = events.filter { it.id in chosen }
        if (picked.isEmpty()) return
        prompts.install(scope)
        val found = resolver.methods(hostClassLoader, *picked.map { it.query }.toTypedArray())
        picked.zip(found).forEach { (event, anchor) ->
            val hooked = methodsOf(event, anchor)
            hooked.forEachIndexed { index, method ->
                val answer = answerOf(event, method)
                method.isAccessible = true
                // Event IDs are stored option choices and stay lowercase; the hook name is their UpperCamelCase form.
                scope.hooks.install(method, HookSpec(
                    id = event.id.split('-').joinToString("", postfix = "$index") { it.replaceFirstChar(Char::uppercaseChar) },
                    requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                    before = { call -> intercept(call, method, event, answer) },
                ))
            }
        }
    }

    private fun methodsOf(event: Event, anchor: Method): List<Method> = when (event.shape) {
        Shape.FOUND_WITH_STRING -> {
            check(anchor.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                "${anchor.declaringClass.name}.${anchor.name} does not take only the user ID"
            }
            listOf(anchor)
        }
        Shape.CLICK_LISTENER -> {
            val listener = anchor.declaringClass.resolve().optional(silent = true).method {
                name = "onClick"
                parameters(View::class)
                isSynthetic = false
                isBridge = false
            }.map { it.self }
            check(listener.size == 1) { "Expected one onClick(View) in ${anchor.declaringClass.name}, found ${listener.size}" }
            listener
        }
        Shape.ALL_OVERLOADS -> anchor.declaringClass.declaredMethodsNamed(anchor.name)
        Shape.FOUND_WITH_CALLBACKS -> {
            check(anchor.parameterTypes.map { it.name } == listOf(FUNCTION1, FUNCTION2)) {
                "${anchor.declaringClass.name}.${anchor.name} does not take the favourite callbacks"
            }
            listOf(anchor)
        }
    }

    // A held-back call answers as if it had done nothing; a return type that cannot be answered that way fails the install.
    private fun answerOf(event: Event, method: Method): Any? = when (method.returnType) {
        Void.TYPE -> null
        Boolean::class.javaPrimitiveType -> event.handledBoolean
            ?: error("${method.declaringClass.name}.${method.name} returns boolean, which ${event.id} does not expect")
        else -> error("${method.declaringClass.name}.${method.name} returns ${method.returnType.name}, which ${event.id} does not expect")
    }

    private fun authorIsLive(call: BeforeCall): Boolean {
        val video = call.argumentOrNull(2) ?: return false
        val owner = author.of(video)?.get(video) ?: return false
        return liveStatus.of(owner)?.get(owner) == LIVE_NOW
    }

    private fun BeforeCall.argumentOrNull(index: Int): Any? = if (index < argumentCount) argument(index) else null

    private fun intercept(call: BeforeCall, method: Method, event: Event, answer: Any?) {
        if (replaying.get() === method || !event.applies(call)) return
        val receiver = call.receiver
        val arguments = Array(call.argumentCount) { call.argument(it) }
        call.returnEarly(answer)
        val proceed = { replay(method, receiver, arguments, event.id) }
        prompts.confirm(R.string.host_confirm_title, event.message, emptyArray(), event.confirm, proceed, proceed)
    }

    // Runs on the main thread from the dialog (or at once when none can be shown). Only this exact call is let through.
    private fun replay(method: Method, receiver: Any?, arguments: Array<Any?>, eventId: String) {
        replaying.set(method)
        try {
            method.invoke(receiver, *arguments)
        } catch (error: InvocationTargetException) {
            logger.log("feature=$ID replay_failed event=$eventId ${error.targetException}")
        } catch (error: ReflectiveOperationException) {
            logger.log("feature=$ID replay_failed event=$eventId $error")
        } finally {
            replaying.remove()
        }
    }

    companion object : TikTokFeature {
        const val ID = "ConfirmBeforeActions"
        const val EVENTS = "events"
        const val FOLLOW = "follow"
        const val LIKE = "like"
        const val DAILY = "daily"
        const val LIVE = "live"
        const val FULL_SCREEN = "full-screen"
        const val TOPIC = "topic"
        const val LIVE_COVER = "live-cover"
        const val SEARCH = "search"
        const val COMMENT_LIKE = "comment-like"
        const val COLLECT = "collect"
        private const val LIVE_NOW = 1L
        private const val FUNCTION1 = "kotlin.jvm.functions.Function1"
        private const val FUNCTION2 = "kotlin.jvm.functions.Function2"
        val ALL = listOf(FOLLOW, LIKE, DAILY, LIVE, FULL_SCREEN, TOPIC, LIVE_COVER, SEARCH, COMMENT_LIKE, COLLECT)
        val OPTIONS = listOf(MultiOption(EVENTS, ALL))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(ConfirmBeforeActions(resolver, hostClassLoader, options(ID, OPTIONS), prompts(ID), logger))
        }
    }
}
