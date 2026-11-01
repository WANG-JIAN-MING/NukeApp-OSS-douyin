package io.github.nuke.oss.app.tiktok.feature.chat

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.toClass
import com.highcapable.kavaref.resolver.ConstructorResolver
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatAdapterEvents
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

// The repeat mark on a chat message row. prepare adds it when the row is created; onTap sets what a tap does each time
// the row is bound to a message, since rows are recycled. The mark sits on the side of the bubble, so the bound form also
// says whether the message is the user's own; an implementation that does not care keeps the plain form.
internal interface RepeatMarks {
    fun prepare(row: Any)
    fun onTap(row: Any, action: () -> Unit)
    fun onTap(row: Any, mine: Boolean, action: () -> Unit) = onTap(row, action)
}

// Sends a copy of a chat message into the same conversation when its repeat mark is tapped. The copy is a new Message
// with the original's conversation, content and types, handed to the IM SDK's send step like any message the user
// sends. The send step lives on a singleton, found as the one static field of its own class that holds an instance.
@HookEntry
internal class RepeatMessage(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val events: ChatAdapterEvents,
    private val marks: () -> RepeatMarks,
    private val logger: Logger,
    private val messageName: String = ChatAdapterEvents.MESSAGE,
    private val listenerName: String = LISTENER,
) : RuntimeFeature, UsesDexQueries {
    override val id = "RepeatMessage"
    override val dexQueries = listOf(ChatQueries.SEND_MESSAGE)
    private val conversationId = NamedCall("getConversationId")
    private val conversationType = NamedCall("getConversationType")
    private val msgType = NamedCall("getMsgType")
    private val content = NamedCall("getContent")
    private val self = NamedCall("isSelf")

    override fun install(scope: FeatureScope) {
        val send = resolver.methods(hostClassLoader, ChatQueries.SEND_MESSAGE).single()
        check(send.parameterCount == 5) { "${send.declaringClass.name}.${send.name} is not a send step" }
        val messageType = messageName.toClass(hostClassLoader)
        val listenerType = listenerName.toClass(hostClassLoader)
        check(send.parameterTypes[0] == messageType && send.parameterTypes[2] == listenerType) {
            "${send.declaringClass.name}.${send.name} does not take a message and a request listener"
        }
        val setters = listOf("setUuid", "setContent", "setConversationId", "setConversationType", "setMsgType").associateWith { name ->
            messageType.resolve().optional(silent = true).method {
                this.name = name
                parameterCount = 1
                modifiers(Modifiers.PUBLIC)
                superclass(interfaces = true)
            }.singleOrNull()?.self ?: error("$messageName has no single $name")
        }
        val newMessage = messageType.resolve().firstConstructor { emptyParameters() }
        send.isAccessible = true
        val ui = marks()
        val rows = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())
        events.install(scope, id,
            onCreated = { _, row -> ui.prepare(row) },
            onBound = { row, message ->
                rows[row] = WeakReference(message)
                val mine = self.of(message)?.invoke(message) as? Boolean ?: false
                ui.onTap(row, mine) { rows[row]?.get()?.let { repeat(send, newMessage, listenerType, setters, it) } }
            },
        )
    }

    // Runs on the main thread when the mark is tapped.
    private fun repeat(send: Method, newMessage: ConstructorResolver<*>, listenerType: Class<*>, setters: Map<String, Method>, original: Any) {
        try {
            val copy = newMessage.create()
            setters.getValue("setUuid").invoke(copy, UUID.randomUUID().toString())
            setters.getValue("setContent").invoke(copy, content.of(original)?.invoke(original) ?: return logger.log("feature=$id repeat_skipped message has no content"))
            setters.getValue("setConversationId").invoke(copy, conversationId.of(original)?.invoke(original))
            setters.getValue("setConversationType").invoke(copy, conversationType.of(original)?.invoke(original))
            setters.getValue("setMsgType").invoke(copy, msgType.of(original)?.invoke(original))
            val sender = instanceOf(send.declaringClass) ?: return logger.log("feature=$id repeat_skipped no sender instance")
            val listener = Proxy.newProxyInstance(hostClassLoader, arrayOf(listenerType), InvocationHandler { _, method, _ ->
                if (method.name == "onFailure") logger.log("feature=$id repeat_failed")
                null
            })
            send.invoke(sender, copy, LinkedHashMap<Any?, Any?>(), listener, 0, null)
        } catch (failure: ReflectiveOperationException) {
            logger.log("feature=$id repeat_failed ${failure.javaClass.simpleName}")
        }
    }

    private fun instanceOf(type: Class<*>): Any? = type.resolve().optional(silent = true).firstFieldOrNull {
        this.type = type
        modifiers(Modifiers.STATIC)
    }?.get()

    companion object : TikTokFeature {
        const val LISTENER = "com.bytedance.im.core.client.callback.IRequestListener"

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(RepeatMessage(resolver, hostClassLoader, chatRows, repeatMarks, logger))
        }
    }
}
