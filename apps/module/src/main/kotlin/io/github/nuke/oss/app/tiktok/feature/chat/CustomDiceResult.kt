package io.github.nuke.oss.app.tiktok.feature.chat

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.declaredMethodsNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatAdapterEvents
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

// The edits that turn the content of a rock-paper-scissors or dice message into one that shows the chosen result. The
// content lists, under potential_static_url_list, the picture for every possible result; the chosen picture becomes the
// message's url, size and image type.
internal object DiceContent {
    // The uri of the rock-paper-scissors pictures carries the hex of the hand's name: scissors, rock, paper.
    private val HANDS = listOf("e589aae58880", "e79fb3e5a4b4", "e5b883")

    // target is 1 scissors, 2 rock or 3 paper.
    fun withHand(content: String, target: Int): String? {
        if (target !in 1..3) return null
        return pick(content) { pictures -> pictures.firstOrNull { picture -> uriOf(picture)?.contains(HANDS[target - 1]) == true } }
    }

    // point is 1 to 6; the pictures are listed in order of the points.
    fun withDice(content: String, point: Int): String? {
        if (point !in 1..6) return null
        return pick(content) { pictures -> if (pictures.size >= 6) pictures[point - 1] else null }
    }

    private fun uriOf(picture: Any?): String? = MiniJson.obj(MiniJson.obj(picture)?.get("static_url"))?.get("uri") as? String

    private fun pick(content: String, choose: (List<Any?>) -> Any?): String? {
        val message = MiniJson.obj(MiniJson.parse(content)) ?: return null
        val pictures = MiniJson.array(message["potential_static_url_list"])?.takeIf { it.isNotEmpty() } ?: return null
        val chosen = MiniJson.obj(choose(pictures)) ?: return null
        val image = MiniJson.obj(chosen["static_url"]) ?: return null
        message["url"] = LinkedHashMap(image)
        message["width"] = image["width"] ?: 100L
        message["height"] = image["height"] ?: 100L
        message["image_type"] = chosen["static_type"] ?: "png"
        return MiniJson.write(message)
    }

    // The game a message content stands for: 1 rock-paper-scissors, 2 dice, null for anything else.
    fun kindOf(content: String): Int? {
        val message = MiniJson.obj(MiniJson.parse(content)) ?: return null
        if ((message["aweType"] as? Long) != 507L) return null
        return when (message["display_name"]) {
            "猜拳" -> 1
            "摇骰子" -> 2
            else -> null
        }
    }
}

// Lets the user pick the result of a rock-paper-scissors or dice message before it is sent. The other person gets a
// message that looks like a normal random result, so this is a way of cheating at the game: it is a setting the user
// turns on knowingly, and off by default. The send is held back and a dialog like NukeApp's emoji game dialog asks for
// the result: Send goes out with the picked one, Random goes out unchanged with the host's own result, and Cancel sends
// nothing. When no dialog can be shown the message goes out unchanged.
@HookEntry
internal class CustomDiceResult(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val prompts: HostPrompts,
    private val logger: Logger,
    private val messageName: String = ChatAdapterEvents.MESSAGE,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(ChatQueries.SEND_MESSAGE)
    private val msgType = NamedCall("getMsgType")
    private val content = NamedCall("getContent")
    private val replaying = ThreadLocal<Method?>()

    override fun install(scope: FeatureScope) {
        prompts.install(scope)
        val anchor = resolver.methods(hostClassLoader, ChatQueries.SEND_MESSAGE).single()
        val messageType = messageName.toClass(hostClassLoader)
        val setContent = messageType.resolve().optional(silent = true).method {
            name = "setContent"
            parameterCount = 1
            modifiers(Modifiers.PUBLIC)
            superclass(interfaces = true)
        }.singleOrNull()?.self ?: error("$messageName has no single setContent")
        val steps = anchor.declaringClass.declaredMethodsNamed(anchor.name).filter { it.parameterCount >= 1 && it.parameterTypes[0] == messageType }
        check(steps.isNotEmpty()) { "${anchor.declaringClass.name}.${anchor.name} takes no $messageName" }
        for (step in steps) check(step.returnType == Void.TYPE) { "${step.declaringClass.name}.${step.name} returns ${step.returnType.name}" }
        steps.forEachIndexed { index, step ->
            step.isAccessible = true
            scope.hooks.install(step, HookSpec(
                id = "DiceSend$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { call ->
                    val message = call.argument(0)
                    if (replaying.get() !== step && message != null) hold(call.receiver, Array(call.argumentCount) { call.argument(it) }, message, step, setContent) {
                        call.returnEarly(null)
                    }
                },
            ))
        }
    }

    private fun hold(receiver: Any?, arguments: Array<Any?>, message: Any, step: Method, setContent: Method, skip: () -> Unit) {
        if ((msgType.of(message)?.invoke(message) as? Int) != TEXT_MESSAGE) return
        val original = content.of(message)?.invoke(message) as? String ?: return
        val kind = DiceContent.kindOf(original) ?: return
        skip()
        val items: List<Any> = if (kind == 1) listOf(R.string.host_game_scissors, R.string.host_game_rock, R.string.host_game_paper) else (1..6).toList().map { it.toString() }
        val send = { replay(step, receiver, arguments) }
        val title = if (kind == 1) R.string.host_game_title_hands else R.string.host_game_title_dice
        prompts.pick(
            title, R.string.host_game_result, items, R.string.host_game_send, R.string.host_game_random, R.string.home_cancel,
            onUnavailable = send, onCancel = {}, onOther = send,
        ) { position ->
            val edited = if (kind == 1) DiceContent.withHand(original, position + 1) else DiceContent.withDice(original, position + 1)
            if (edited == null) {
                logger.log("feature=$ID result_failed")
                prompts.toast(R.string.host_game_failed)
            } else {
                setContent.invoke(message, edited)
            }
            send()
        }
    }

    // Runs on the main thread from the dialog. Only this exact call is let through the hook.
    private fun replay(step: Method, receiver: Any?, arguments: Array<Any?>) {
        replaying.set(step)
        try {
            step.invoke(receiver, *arguments)
        } catch (error: InvocationTargetException) {
            logger.log("feature=$ID replay_failed ${error.targetException.javaClass.simpleName}")
        } catch (error: ReflectiveOperationException) {
            logger.log("feature=$ID replay_failed ${error.javaClass.simpleName}")
        } finally {
            replaying.remove()
        }
    }

    companion object : TikTokFeature {
        const val ID = "CustomDiceResult"
        // Rock-paper-scissors and dice go out as message type 5.
        private const val TEXT_MESSAGE = 5

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CustomDiceResult(resolver, hostClassLoader, prompts(ID), logger))
        }
    }
}
