package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.uniqueField
import io.github.nuke.oss.app.shared.dex.uniqueMethod
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.MethodMatcher

// Dex queries for forwarding voice messages, taken from the legacy module (checked there against Douyin 40.7.0) and
// located the same way as FeedQueries: the long-press menu's factory and its forward switch, the forward click handlers,
// and the sender's message task fields that a send to an existing chat sets.
@DexQueries
internal object VoiceQueries {
    private const val MESSAGE = "com.bytedance.im.core.model.Message"
    private const val CONTENT = "com.ss.android.ugc.aweme.im.sdk.chat.model.BaseContent"
    private const val CONTENT_BUILDER = "com.bytedance.ies.im.core.api.model.PlatformBaseContent"
    private const val SEND_LOG = "MessageSender_sendMessage"

    // The menu's factory: it takes the message and its content, and an object that answers whether forwarding is allowed.
    private fun MethodMatcher.factory() {
        declaredClass { usingEqStrings("message_menu_star_shop_config") }
        paramCount = 5
        paramTypes("android.content.Context", "com.ss.android.ugc.aweme.im.sdk.chat.SessionInfo", MESSAGE, CONTENT, null)
    }

    val MENU_FACTORY = method { matcher { factory() } }

    // Forward and multi-select both read disable_forward; only multi-select also reads the message type.
    val FORWARD_CAPABILITY = DexQuery { bridge ->
        val api = bridge.findMethod { matcher { factory() } }
            .also { check(it.size == 1) { "expected one factory, found ${it.size}" } }.single().returnTypeName
        val gates = bridge.findMethod {
            matcher { returnType = "boolean"; paramCount = 0; invokeMethods { add { usingEqStrings("a:disable_forward") } } }
        }.filter { it.invokes.none { call -> call.name == "getMsgType" } }
        check(gates.size == 1) { "expected one gate, found ${gates.size}" }
        bridge.uniqueMethod {
            matcher { declaredClass = api; name = gates.single().name; returnType = "boolean"; paramCount = 0 }
        }
    }

    private fun MethodMatcher.action() {
        usingEqStrings("DefaultLongClickHandler_onClickShare")
        returnType = "void"
        paramTypes("android.view.View")
    }

    val FORWARD_ACTION = method { matcher { action(); declaredClass { superClass = "java.lang.Object" } } }

    // The same click in the handler used inside a thread, which extends the plain one.
    val THREAD_FORWARD_ACTION = DexQuery { bridge ->
        val owner = bridge.findMethod { matcher { action(); declaredClass { superClass = "java.lang.Object" } } }
            .also { check(it.size == 1) { "expected one handler, found ${it.size}" } }.single().declaredClassName
        bridge.uniqueMethod { matcher { action(); declaredClass { superClass = owner } } }
    }

    private fun DexKitBridge.builder(): String = findClass {
        matcher {
            methods {
                add { name = "<init>"; paramTypes("java.util.Map") }
                add { paramTypes(CONTENT_BUILDER) }
                add { returnType = "void"; invokeMethods { add { usingEqStrings(SEND_LOG) } } }
            }
        }
    }.also { check(it.size == 1) { "Expected one content sender class, found ${it.size}" } }.single().name

    private fun DexKitBridge.task(): String = findClass { matcher { usingEqStrings("MessageTask{", "onConversationReady: ") } }
        .also { check(it.size == 1) { "Expected one message task class, found ${it.size}" } }.single().name

    // The sender's field that holds its message task, and the task's String field for the conversation ID.
    val MESSAGE_PAYLOAD = DexQuery { bridge ->
        val owner = bridge.builder()
        val task = bridge.task()
        bridge.uniqueField { matcher { declaredClass = owner; type = task } }
    }

    val TASK_CONVERSATION = DexQuery { bridge ->
        val task = bridge.task()
        bridge.uniqueField { matcher { declaredClass = task; type = "java.lang.String" } }
    }

}
