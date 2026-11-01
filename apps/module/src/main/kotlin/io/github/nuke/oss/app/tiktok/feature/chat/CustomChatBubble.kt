package io.github.nuke.oss.app.tiktok.feature.chat

import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.reflect.declaredMethodNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.TextOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Dresses your own chat messages in the bubble with the given ID. The message model's extension map carries the
// bubble under "a:chat_bubble" when the host has one to send; the entry is rewritten in place for messages you sent.
// Nothing is added to messages that carry no bubble, so a chat without bubbles stays as it is.
@HookEntry
internal class CustomChatBubble(
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val messageName: String = MESSAGE,
) : RuntimeFeature {
    override val id = ID
    private val bubbleId = options.text(BUBBLE_ID).trim()

    override fun install(scope: FeatureScope) {
        if (bubbleId.isEmpty()) return
        check(BUBBLE_PATTERN.matches(bubbleId)) { "The bubble ID must be digits only: $bubbleId" }
        val message = messageName.toClass(hostClassLoader)
        val getExt = message.declaredMethodNamed("getExt")
        val isSelf = message.declaredMethodNamed("isSelf")
        check(Map::class.java.isAssignableFrom(getExt.returnType)) { "$messageName.getExt returns ${getExt.returnType.name}" }
        check(isSelf.returnType == Boolean::class.javaPrimitiveType && isSelf.parameterCount == 0) { "$messageName.isSelf is not a boolean check" }
        val entry = """{"bubble_id":"$bubbleId","bubble_source":"0"}"""
        scope.hooks.install(getExt, HookSpec(
            id = "WriteBubble",
            after = { call ->
                val outcome = call.outcome
                val map = (outcome as? CallOutcome.Returned)?.value as? MutableMap<*, *>
                val sender = call.receiver
                if (map != null && sender != null && map.containsKey(BUBBLE_KEY) && isSelf.invoke(sender) == true) {
                    @Suppress("UNCHECKED_CAST")
                    try { (map as MutableMap<String, String>)[BUBBLE_KEY] = entry } catch (_: UnsupportedOperationException) { }
                }
            },
        ))
    }

    companion object : TikTokFeature {
        const val ID = "CustomChatBubble"
        const val BUBBLE_ID = "bubble-id"
        const val MESSAGE = "com.bytedance.im.core.model.Message"
        private const val BUBBLE_KEY = "a:chat_bubble"
        private val BUBBLE_PATTERN = Regex("[0-9]{1,24}")
        val OPTIONS = listOf(TextOption(BUBBLE_ID, default = "", maxLength = 24))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CustomChatBubble(hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
