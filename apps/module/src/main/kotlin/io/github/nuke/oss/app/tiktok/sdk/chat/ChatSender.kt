package io.github.nuke.oss.app.tiktok.sdk.chat

import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.fields
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.tiktok.query.StreakQueries
import io.github.nuke.oss.app.tiktok.query.VoiceQueries
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

// What the host reported for one send. Sent is the server's acknowledgement; local construction and queue
// notifications are not, so a send that only got those, or no answer in time, is Unknown.
internal sealed interface SendResult {
    data class Sent(val clientMessageId: String?, val serverMessageId: Long?, val detail: String) : SendResult
    data class Failed(val detail: String) : SendResult
    data class Unknown(val detail: String) : SendResult
}

// Sends chat content to one user through the host's own content sender, the path its chat screen uses, as NukeApp's
// DyMsgHost does: create the sender, set the content and the user, then send with a listener and wait for its answer.
// What a feature needs to send content to a friend or to an existing chat; ChatSender is the host-backed one.
internal interface ContentSender {
    fun send(uid: String, content: Any, timeoutMillis: Long): SendResult
    fun sendToConversation(conversationId: String, content: Any, timeoutMillis: Long): SendResult
}

internal class ChatSender(
    private val hostClassLoader: ClassLoader,
    private val createSender: Method,
    private val setContent: Method,
    private val setUser: Method,
    private val send: Method,
    private val sent: Method,
    private val sendFailed: Method,
    private val textContent: Method,
    // Only a send to an existing chat (a group) needs these: the sender's message task and its conversation ID field.
    private val taskField: java.lang.reflect.Field? = null,
    private val conversationField: java.lang.reflect.Field? = null,
) : ContentSender {
    private val listenerType: Class<*> = send.parameterTypes.single()
    private val batchTypes = arrayOf(CONVERSATION.toClass(hostClassLoader), List::class.java, Map::class.java)

    init {
        check(sent.declaringClass == listenerType && sendFailed.declaringClass == listenerType) { "The send listener changed" }
        listOf(createSender, setContent, setUser, send, sent, sendFailed, textContent).forEach { it.isAccessible = true }
    }

    fun text(text: String): Any = call(textContent, null, text) ?: error("The text factory returned no content")

    // Blocks the calling thread until the host answers or the timeout passes; the caller is a worker thread.
    override fun send(uid: String, content: Any, timeoutMillis: Long): SendResult =
        dispatch(content, timeoutMillis) { sender -> call(setUser, sender, uid) }

    override fun sendToConversation(conversationId: String, content: Any, timeoutMillis: Long): SendResult {
        val task = checkNotNull(taskField) { "Sending to a chat needs the message task field" }.apply { isAccessible = true }
        val conversation = checkNotNull(conversationField) { "Sending to a chat needs the conversation field" }.apply { isAccessible = true }
        return dispatch(content, timeoutMillis) { sender ->
            conversation.set(checkNotNull(task.get(sender)) { "The sender has no message task" }, conversationId)
        }
    }

    private fun dispatch(content: Any, timeoutMillis: Long, address: (sender: Any) -> Unit): SendResult {
        val sender = call(createSender, null, null) ?: error("The host returned no content sender")
        call(setContent, sender, content)
        address(sender)
        val answers = ArrayBlockingQueue<SendResult>(1)
        val listener = Proxy.newProxyInstance(hostClassLoader, arrayOf(listenerType)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                "toString" -> return@newProxyInstance "NukeChatSendListener"
            }
            answer(method, args ?: emptyArray())?.let(answers::offer)
            null
        }
        call(send, sender, listener)
        return answers.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: SendResult.Unknown("No answer from the host in ${timeoutMillis / 1000} s")
    }

    private fun answer(event: Method, args: Array<out Any?>): SendResult? = when {
        event == sent -> receipt(args.getOrNull(1))
        event == sendFailed -> SendResult.Failed(error(args.getOrNull(2)))
        event.parameterTypes.contentEquals(batchTypes) -> {
            val errors = args.getOrNull(2) as? Map<*, *>
            val messages = args.getOrNull(1) as? List<*>
            if (!errors.isNullOrEmpty()) SendResult.Failed(error(errors.values.first())) else receipt(messages?.singleOrNull())
        }
        else -> null
    }

    private fun receipt(message: Any?): SendResult {
        message ?: return SendResult.Unknown("The host acknowledged without a message")
        val extra = LOCAL_EXT.of(message)?.invoke(message) as? Map<*, *>
        return SendResult.Sent(
            UUID_CALL.of(message)?.invoke(message) as? String,
            (MSG_ID.of(message)?.invoke(message) as? Long)?.takeIf { it > 0 },
            "sendCode=${extra?.get("s:send_response_extra_code")}, checkCode=${extra?.get("s:send_response_check_code")}",
        )
    }

    private fun error(error: Any?): String {
        error ?: return "The host reported a failure"
        val message = (STATUS_MSG.of(error)?.invoke(error) as? String)?.takeIf { it.isNotBlank() } ?: "The host reported a failure"
        return "${CODE.of(error)?.invoke(error) ?: "?"}: $message"
    }

    private fun call(method: Method, receiver: Any?, vararg args: Any?): Any? = try {
        method.invoke(receiver, *args)
    } catch (error: InvocationTargetException) {
        throw (error.targetException as? RuntimeException) ?: IllegalStateException(error.targetException)
    }

    private companion object {
        const val CONVERSATION = "com.bytedance.im.core.model.Conversation"
        val LOCAL_EXT = NamedCall("getLocalExt")
        val UUID_CALL = NamedCall("getUuid")
        val MSG_ID = NamedCall("getMsgId")
        val STATUS_MSG = NamedCall("getStatusMsg")
        val CODE = NamedCall("getCode")
    }
}

// The host-backed sender, built on first use from the located targets so a missing one fails that send, not the feature.
internal class HostContentSender(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : ContentSender {
    private val sender by lazy {
        val methods = resolver.methods(hostClassLoader, METHODS)
        val (task, conversation) = resolver.fields(hostClassLoader, FIELDS)
        ChatSender(hostClassLoader, methods[0], methods[1], methods[2], methods[3], methods[4], methods[5], methods[6], task, conversation)
    }

    override fun send(uid: String, content: Any, timeoutMillis: Long) = sender.send(uid, content, timeoutMillis)
    override fun sendToConversation(conversationId: String, content: Any, timeoutMillis: Long) =
        sender.sendToConversation(conversationId, content, timeoutMillis)

    // Text content of the host's own message type, built through the same located factory as the sender.
    fun text(text: String): Any = sender.text(text)

    // Every query the sender resolves on first use; a feature that sends through it declares them (see UsesDexQueries).
    companion object {
        private val METHODS = listOf(
            StreakQueries.CREATE_SENDER, StreakQueries.SET_CONTENT, StreakQueries.SET_USER, StreakQueries.SEND,
            StreakQueries.SENT, StreakQueries.SEND_FAILED, StreakQueries.TEXT_CONTENT,
        )
        private val FIELDS = listOf(VoiceQueries.MESSAGE_PAYLOAD, VoiceQueries.TASK_CONVERSATION)
        val QUERIES: List<DexQuery> = METHODS + FIELDS
    }
}
