package io.github.nuke.oss.app.tiktok.feature.chat

import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.TextOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatAdapterEvents
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The label under a chat message. prepare adds it to a message row when the row is created; show fills it in each time
// the row is bound to a message. Rows are recycled, so show runs for every bind.
internal interface MessageTimeLabels {
    fun prepare(row: Any, color: Int?)
    fun show(row: Any, text: String, mine: Boolean)
}

// Writes the exact send time under every chat message. The time comes from the message the row shows, formatted with
// the chosen pattern. The click-for-more-options, the repeat icon and the recalled-message styling that share
// these hooks are separate features and are not part of this one.
@HookEntry
internal class MessageSendTime(
    private val events: ChatAdapterEvents,
    private val labels: () -> MessageTimeLabels,
    options: OptionValues,
) : RuntimeFeature {
    override val id = ID
    private val pattern = options.text(PATTERN).ifBlank { DEFAULT_PATTERN }
    private val colorText = options.text(COLOR)
    private val created = NamedCall("getCreatedAt")
    private val self = NamedCall("isSelf")

    override fun install(scope: FeatureScope) {
        // A bad pattern or color fails the install before anything is hooked.
        val formatter = try { DateTimeFormatter.ofPattern(pattern) } catch (_: IllegalArgumentException) { error("$pattern is not a time pattern") }
        val color = colorText.takeIf { it.isNotBlank() }?.let { parseColor(it) ?: error("$it is not a #RRGGBB color") }
        val ui = labels()
        events.install(scope, ID,
            onCreated = { _, row -> ui.prepare(row, color) },
            onBound = { row, message ->
                val millis = created.of(message)?.invoke(message) as? Long
                if (millis != null) {
                    val mine = self.of(message)?.invoke(message) as? Boolean ?: false
                    ui.show(row, formatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())), mine)
                }
            },
        )
    }

    companion object : TikTokFeature {
        const val ID = "MessageSendTime"
        const val PATTERN = "pattern"
        const val COLOR = "color"
        const val DEFAULT_PATTERN = "yyyy-MM-dd HH:mm:ss"
        val OPTIONS = listOf(TextOption(PATTERN, default = DEFAULT_PATTERN), TextOption(COLOR, default = "", maxLength = 9))

        // #RRGGBB or #AARRGGBB, or null when the text is neither.
        internal fun parseColor(text: String): Int? {
            if (!Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})").matches(text)) return null
            val value = text.substring(1).toLong(16)
            return (if (text.length == 7) value or 0xFF000000L else value).toInt()
        }

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(MessageSendTime(chatRows, messageLabels, options(ID, OPTIONS)))
        }
    }
}
