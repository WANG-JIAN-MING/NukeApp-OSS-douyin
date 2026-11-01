package io.github.nuke.oss.app.tiktok.feature.chat

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import io.github.nuke.oss.app.shared.data.FlatJson
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.ConversationsOption
import io.github.nuke.oss.app.shared.settings.LinesOption
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Runs a task after a delay somewhere other than the Hook callback's thread.
internal fun interface BackgroundRunner {
    fun run(delayMillis: Long, task: () -> Unit)
}

internal val threadRunner = BackgroundRunner { delayMillis, task ->
    Thread {
        try {
            if (delayMillis > 0) Thread.sleep(delayMillis)
        } catch (_: InterruptedException) {
            return@Thread
        }
        task()
    }.apply { isDaemon = true; name = "nuke-red-packet" }.start()
}

// Opens the red packets that arrive in your chats. Each incoming message of the red packet type is opened with the
// host's own request after a delay, unless its chat is ruled out or its pass phrase has a skip word in it. Which chats
// are ruled out depends on the mode: in blacklist mode every chat but the listed ones is opened, in whitelist mode only
// the listed chats are. Both lists are kept, so switching the mode does not lose the other list.
@HookEntry
internal class OpenRedPackets(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val logger: Logger,
    private val runner: BackgroundRunner = threadRunner,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(ChatQueries.INCOMING_MESSAGE, ChatQueries.RED_PACKET_API)
    private val delayMillis = options.number(DELAY_MS)
    private val whitelistMode = options.choice(MODE) == WHITELIST_MODE
    private val blacklist = options.conversations(BLACKLIST).toSet()
    private val whitelist = options.conversations(WHITELIST).toSet()
    private val skipWords = options.lines(SKIP_WORDS)
    private val messageType = NamedField("message_type")
    private val content = NamedField("content")
    private val conversationId = NamedCall("getConversationId")
    private val messageId = NamedCall("getMsgId")
    @Volatile private var active = false

    override fun install(scope: FeatureScope) {
        val (handler, apiAnchor) = resolver.methods(hostClassLoader, ChatQueries.INCOMING_MESSAGE, ChatQueries.RED_PACKET_API)
        check(handler.parameterCount >= 3) { "${handler.declaringClass.name}.${handler.name} does not take the raw message third" }
        val entry = apiAnchor.declaringClass.resolve().optional(silent = true).method {
            name = "LIZ"
            emptyParameters()
            modifiers(Modifiers.STATIC)
        }.singleOrNull()?.self ?: error("${apiAnchor.declaringClass.name} has no single static LIZ()")
        val open = entry.returnType.resolve().optional(silent = true).method {
            name = "openRedPacket"
            parameterCount = OPEN_PARAMETERS
            modifiers(Modifiers.PUBLIC)
            superclass(interfaces = true)
        }.singleOrNull()?.self ?: error("${entry.returnType.name} has no single openRedPacket with $OPEN_PARAMETERS parameters")
        entry.isAccessible = true
        handler.isAccessible = true
        // An empty whitelist opens nothing; say so, since that is rarely what a switched-on feature is for.
        if (whitelistMode && whitelist.isEmpty()) logger.log("feature=$ID whitelist_empty no chat is allowed, nothing will be opened")
        active = true
        scope.own(AutoCloseable { active = false })
        scope.hooks.install(handler, HookSpec(id = "OpenRedPackets", after = { call ->
            val message = (call.outcome as? CallOutcome.Returned)?.value
            val raw = call.argument(2)
            if (message != null && raw != null) consider(raw, message) { orderNo, msgId ->
                runner.run(delayMillis) {
                    if (active) claim(entry, open, orderNo, msgId)
                }
            }
        }))
    }

    // Decides whether an incoming message is a red packet to open; start is only called for those.
    private fun consider(raw: Any, message: Any, start: (orderNo: String, messageId: Long) -> Unit) {
        val chat = conversationId.of(message)?.invoke(message) as? String ?: return
        if (!allowed(chat)) return
        if ((messageType.of(raw)?.get(raw) as? Int) != RED_PACKET_TYPE) return
        val payload = (content.of(raw)?.get(raw) as? String)?.let(FlatJson::strings) ?: return
        val orderNo = payload["order_no"]?.takeIf { it.isNotEmpty() } ?: return
        val phrase = payload["open_command"].orEmpty()
        if (phrase.isNotEmpty() && skipWords.any { phrase.contains(it) }) return
        val msgId = messageId.of(message)?.invoke(message) as? Long ?: return
        start(orderNo, msgId)
    }

    private fun allowed(chat: String): Boolean = if (whitelistMode) chat in whitelist else chat !in blacklist

    private fun claim(entry: java.lang.reflect.Method, open: java.lang.reflect.Method, orderNo: String, msgId: Long) {
        try {
            val api = entry.invoke(null)
            val request = open.invoke(api, orderNo, "0", msgId, 0, 0, CLIENT_VERSION)
            request.asResolver().firstMethod { name = "subscribe"; emptyParameters(); superclass(interfaces = true) }.invoke()
        } catch (error: ReflectiveOperationException) {
            // The log names the failure and nothing about the chat, the sender or the packet.
            logger.log("feature=$ID open_failed ${(error.cause ?: error).javaClass.simpleName}")
        }
    }

    companion object : TikTokFeature {
        const val ID = "OpenRedPackets"
        const val DELAY_MS = "delay-ms"
        const val MODE = "mode"
        const val BLACKLIST_MODE = "blacklist"
        const val WHITELIST_MODE = "whitelist"
        // The key the skip list was stored under before there was a mode; the saved list stays the blacklist.
        const val BLACKLIST = "skip-chats"
        const val WHITELIST = "allowed-chats"
        const val SKIP_WORDS = "skip-words"
        private const val RED_PACKET_TYPE = 74
        private const val OPEN_PARAMETERS = 6
        private const val CLIENT_VERSION = "8.0.6"
        val OPTIONS = listOf(
            NumberOption(DELAY_MS, default = 500, min = 0, max = 60_000),
            ChoiceOption(MODE, listOf(BLACKLIST_MODE, WHITELIST_MODE), default = BLACKLIST_MODE),
            ConversationsOption(BLACKLIST),
            ConversationsOption(WHITELIST),
            LinesOption(SKIP_WORDS),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(OpenRedPackets(resolver, hostClassLoader, options(ID, OPTIONS), logger))
        }
    }
}
