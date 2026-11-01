package io.github.nuke.oss.app.tiktok.feature.chat.streak

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

// Builds the interactive streak emoji the host's own streak shortcut sends, as NukeApp's DyStreakEmojiHost does: the
// "highlight_streak" light interaction names its configuration, the configuration names an interactive emoji
// resource, and the host turns that resource into emoji content. Read-only; nothing is sent here. The host only loads
// its interactive resources once a chat or the emoji panel has been opened, so a failure says so.
internal class StreakEmoji(
    private val entry: Method,
    private val configuration: Method,
    private val convert: Method,
    private val resources: Method,
    private val loaderOwner: Method,
    private val emojiContent: Method,
) {
    init {
        check(resources.declaringClass == convert.declaringClass) { "The interactive resource loader changed" }
        listOf(entry, configuration, convert, resources, loaderOwner, emojiContent).forEach { it.isAccessible = true }
    }

    fun create(): Any {
        val service = singleton(entry.declaringClass, entry.declaringClass)
        val interaction = call(entry, service, "highlight_streak", null, null, false)
            ?: error("The host has no streak interaction yet; open a friend's chat first")
        val name = (NAME.of(interaction)?.invoke(interaction) as? String)?.takeIf(String::isNotBlank)
            ?: error("The streak interaction has no configuration name")
        val conf = call(configuration, null, singleton(configuration.declaringClass, configuration.declaringClass), name, false, null, 6)
            ?: error("The streak interaction configuration is unavailable")
        // BaseInteractionMessageConf: b is the primary message kind, e the primary resource name (40.7.0 and 40.8.0).
        check(KIND.of(conf)?.get(conf) == INTERACTIVE_EMOJI_KIND) { "The streak interaction is not an interactive emoji" }
        val resourceName = (RESOURCE_NAME.of(conf)?.get(conf) as? String)?.takeIf(String::isNotBlank)
            ?: error("The streak interaction names no emoji resource")
        val loader = singleton(loaderOwner.declaringClass, convert.declaringClass)
        val loaded = call(resources, loader) as? List<*>
            ?: error("Interactive emoji resources are not loaded; open the interactive emoji panel in a chat first")
        val resourceType = convert.parameterTypes[0]
        val resource = loaded.filterNotNull().filter {
            check(resourceType.isInstance(it)) { "Unexpected interactive emoji resource type" }
            DISPLAY_NAME.of(it)?.invoke(it) == resourceName
        }.singleOrNull() ?: error("The streak emoji resource is not loaded: $resourceName")
        check(HIDE.of(resource)?.invoke(resource) != true) { "The streak emoji is hidden by the host" }
        val emoji = call(convert, null, resource, false) ?: error("The host could not prepare the streak emoji")
        val content = call(emojiContent, null, emoji) ?: error("The host could not build the emoji content")
        prepare(content)
        return content
    }

    private fun singleton(owner: Class<*>, type: Class<*>): Any = owner.resolve().optional(silent = true).firstFieldOrNull {
        modifiers(Modifiers.STATIC)
        this.type = type
    }?.get() ?: error("The host interaction service is unavailable")

    private fun call(method: Method, receiver: Any?, vararg args: Any?): Any? = try {
        method.invoke(receiver, *args)
    } catch (error: InvocationTargetException) {
        throw (error.targetException as? RuntimeException) ?: IllegalStateException(error.targetException)
    }

    companion object {
        private const val INTERACTIVE_EMOJI_KIND = 5
        private const val INTERACTIVE_MESSAGE_TYPE = 507
        private val NAME = NamedCall("getName")
        private val DISPLAY_NAME = NamedCall("getDisplayName")
        private val HIDE = NamedCall("getHide")
        private val KIND = NamedField("b")
        private val RESOURCE_NAME = NamedField("e")
        private val TYPE = NamedField("type")

        // The shared emoji factory stores message type 507 for an interactive emoji; the native light-interaction
        // sender then fills these three fields, so they are set the same way here.
        internal fun prepare(content: Any) {
            check(TYPE.of(content)?.get(content) == INTERACTIVE_MESSAGE_TYPE) { "The host built an unexpected emoji message type" }
            set(content, "stickerType", 12)
            set(content, "emojiType", "lite_emoji")
            set(content, "emojiFrom", "lite")
        }

        private fun set(owner: Any, name: String, value: Any) {
            val field = owner.asResolver().optional(silent = true).firstFieldOrNull { this.name = name; superclass() }
            (field ?: error("${owner.javaClass.name} has no $name")).set(value)
        }
    }
}
