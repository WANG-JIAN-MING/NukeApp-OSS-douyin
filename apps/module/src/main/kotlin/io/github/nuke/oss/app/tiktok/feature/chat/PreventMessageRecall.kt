package io.github.nuke.oss.app.tiktok.feature.chat

import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.reflect.declaredMethodNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatAdapterEvents
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// How a chat row whose message was kept from a recall is marked. mark and clear run on the main thread each time a
// row is bound, since rows are recycled.
internal interface RecallMarks {
    fun mark(row: Any, style: String)
    fun clear(row: Any)
}

// The IDs of the messages this process has kept from a recall, oldest dropped first.
internal class KeptMessages(private val capacity: Int = 512) {
    private val ids = object : LinkedHashMap<Long, Unit>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>) = size > capacity
    }

    @Synchronized fun add(id: Long) { ids[id] = Unit }
    @Synchronized operator fun contains(id: Long) = id in ids
}

// A recalled message stays readable: the message model reports "not recalled" whenever the host says it was. Your own
// messages are kept too unless "own" is off. With a style chosen, the bubble area of a row whose message was kept gets
// an outline or a tint; the list is only what this process kept, so the mark is
// gone after the host restarts.
@HookEntry
internal class PreventMessageRecall(
    private val hostClassLoader: ClassLoader,
    private val events: ChatAdapterEvents,
    private val marks: () -> RecallMarks,
    options: OptionValues,
) : RuntimeFeature {
    override val id = ID
    private val keepOwn = options.toggle(OWN)
    private val style = options.choice(STYLE)

    override fun install(scope: FeatureScope) {
        val message = MESSAGE.toClass(hostClassLoader)
        val isRecalled = message.declaredMethodNamed("isRecalled")
        check(isRecalled.returnType == Boolean::class.javaPrimitiveType) { "$MESSAGE.isRecalled returns ${isRecalled.returnType.name}" }
        val isSelf = if (keepOwn) null else message.declaredMethodNamed("isSelf").also {
            check(it.returnType == Boolean::class.javaPrimitiveType) { "$MESSAGE.isSelf returns ${it.returnType.name}" }
        }
        val messageId = if (style == NONE) null else message.declaredMethodNamed("getMsgId").also {
            check(it.returnType == Long::class.javaPrimitiveType) { "$MESSAGE.getMsgId returns ${it.returnType.name}" }
        }
        val kept = KeptMessages()
        scope.hooks.install(isRecalled, HookSpec(
            id = "ReportNotRecalled",
            requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE),
            after = { call ->
                val outcome = call.outcome
                val receiver = call.receiver
                if (outcome is CallOutcome.Returned && outcome.value == true && receiver != null) {
                    if (isSelf != null && isSelf.invoke(receiver) == true) return@HookSpec
                    call.returnValue(false)
                    messageId?.let { kept.add(it.invoke(receiver) as Long) }
                }
            },
        ))
        if (messageId == null) return
        val ui = marks()
        events.install(scope, ID, onCreated = { _, _ -> }, onBound = { row, bound ->
            if (messageId.invoke(bound) as Long in kept) ui.mark(row, style) else ui.clear(row)
        })
    }

    companion object : TikTokFeature {
        const val ID = "PreventMessageRecall"
        const val OWN = "own"
        const val STYLE = "style"
        const val NONE = "none"
        const val OUTLINE = "outline"
        const val TINT = "tint"
        private const val MESSAGE = "com.bytedance.im.core.model.Message"
        val OPTIONS = listOf(ToggleOption(OWN, default = true), ChoiceOption(STYLE, listOf(NONE, OUTLINE, TINT)))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(PreventMessageRecall(hostClassLoader, chatRows, recallMarks, options(ID, OPTIONS)))
        }
    }
}
