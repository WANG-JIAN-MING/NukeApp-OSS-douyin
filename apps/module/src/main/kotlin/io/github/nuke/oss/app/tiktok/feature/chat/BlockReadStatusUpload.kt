package io.github.nuke.oss.app.tiktok.feature.chat

import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methodLists
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.ConversationsOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatMessageFinder
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Which chats keep their read status to themselves. ALL holds back every conversation's read status; BLACKLIST holds
// back only the listed chats and reports the rest as usual; WHITELIST reports only the listed chats and holds back the
// rest. A conversation that cannot be told apart reads as unlisted.
internal enum class ReadReceiptMode(val id: String) {
    ALL("all"), BLACKLIST("blacklist"), WHITELIST("whitelist");

    fun blocks(listed: Boolean): Boolean = when (this) {
        ALL -> true
        BLACKLIST -> listed
        WHITELIST -> !listed
    }

    companion object {
        fun of(id: String) = entries.first { it.id == id }
    }
}

// Does not tell the server how far a conversation has been read, for all chats or for a chosen set. The host marks a
// conversation read in two steps (the one that works from the read index and the one that builds the request), and
// both take the conversation or an item that holds it; a step the filter holds back is skipped whole, as it always was.
@HookEntry
internal class BlockReadStatusUpload(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val conversationName: String = CONVERSATION,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(ChatQueries.MARK_READ_BY_INDEX, ChatQueries.MARK_READ_REQUEST)
    private val mode = ReadReceiptMode.of(options.choice(MODE))
    private val chats = options.conversations(CHATS).toSet()
    private val conversationId = NamedCall("getConversationId")

    override fun install(scope: FeatureScope) {
        val methods = resolver.methodLists(hostClassLoader, *dexQueries.toTypedArray()).flatten()
        // A different return type means the host changed shape; skipping the step anyway would corrupt its callers.
        for (method in methods) {
            check(method.returnType == Void.TYPE) { "${method.declaringClass.name}.${method.name} returns ${method.returnType.name}, expected void" }
        }
        val finder = if (mode == ReadReceiptMode.ALL) null else ChatMessageFinder(conversationName.toClass(hostClassLoader))
        methods.forEachIndexed { index, method ->
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(
                id = "Answer$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { call ->
                    if (blocks(finder, (0 until method.parameterCount).map { call.argument(it) })) call.returnEarly(null)
                },
            ))
        }
    }

    private fun blocks(finder: ChatMessageFinder?, arguments: List<Any?>): Boolean {
        if (finder == null) return true
        val conversation = arguments.firstNotNullOfOrNull { finder.find(it) }
        val id = conversation?.let { conversationId.of(it)?.invoke(it) as? String }
        return mode.blocks(id != null && id in chats)
    }

    companion object : TikTokFeature {
        const val ID = "BlockReadStatusUpload"
        const val MODE = "mode"
        const val CHATS = "chats"
        const val CONVERSATION = "com.bytedance.im.core.model.Conversation"
        val OPTIONS = listOf(
            ChoiceOption(MODE, ReadReceiptMode.entries.map { it.id }),
            ConversationsOption(CHATS),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(BlockReadStatusUpload(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
