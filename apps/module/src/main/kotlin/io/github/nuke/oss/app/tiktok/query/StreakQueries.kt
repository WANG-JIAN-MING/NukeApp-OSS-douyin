package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.uniqueMethod
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

// Dex queries for sending a chat message the way the host's own chat does, and for the host's interactive streak
// emoji, taken from NukeApp's DyMsgDexTargets and DyStreakEmojiHost and checked against Douyin 40.8.0. Each query
// looks up what it depends on with the same bridge, never another query's cached result.
@DexQueries
internal object StreakQueries {
    const val CONTENT = "com.bytedance.ies.im.core.api.model.PlatformBaseContent"
    const val MESSAGE = "com.bytedance.im.core.model.Message"
    const val CONVERSATION = "com.bytedance.im.core.model.Conversation"
    const val ERROR = "com.bytedance.im.core.model.IMError"
    const val EMOJI = "com.ss.android.ugc.aweme.emoji.model.Emoji"
    private const val TEXT_CONTENT_CLASS = "com.ss.android.ugc.aweme.im.sdk.chat.model.TextContent"
    private const val EMOJI_CONTENT_CLASS = "com.ss.android.ugc.aweme.im.sdk.chat.model.EmojiContent"
    private const val RESOURCE = "com.ss.android.ugc.aweme.emoji.model.InteractiveEmojiResource"
    private const val HELPER = "com.ss.android.ugc.aweme.im.sdk.lightinteraction.LightInteractionDataHelper"
    private const val CONF = "com.ss.android.ugc.aweme.im.sdk.resource.BaseInteractionMessageConf"
    private const val SEND_LOG = "MessageSender_sendMessage"

    // The content sender: built from a Map, takes a PlatformBaseContent, and has a void step that logs the send.
    private fun DexKitBridge.builder(): String = findClass {
        matcher {
            methods {
                add { name = "<init>"; paramTypes("java.util.Map") }
                add { paramTypes(CONTENT) }
                add { returnType = "void"; invokeMethods { add { usingEqStrings(SEND_LOG) } } }
            }
        }
    }.single().name

    // The send step's listener: an interface with the (Conversation, Message, IMError) failure callback. The other
    // one-argument send takes a listener without it and only inserts a local message.
    private fun DexKitBridge.sendListener(): String {
        val owner = builder()
        return findMethod {
            matcher {
                declaredClass = owner
                returnType = "void"
                paramCount = 1
                invokeMethods { add { usingEqStrings(SEND_LOG) } }
                params { add { type { modifiers(Modifier.INTERFACE); methods { add { paramTypes(CONVERSATION, MESSAGE, ERROR) } } } } }
            }
        }.single().paramTypeNames.single()
    }

    val CREATE_SENDER = DexQuery { bridge ->
        val owner = bridge.builder()
        bridge.uniqueMethod { matcher { modifiers(Modifier.STATIC); returnType = owner; paramTypes("java.util.Map") } }
    }

    val SET_CONTENT = DexQuery { bridge ->
        val owner = bridge.builder()
        bridge.uniqueMethod { matcher { declaredClass = owner; returnType = owner; paramTypes(CONTENT) } }
    }

    val SET_USER = DexQuery { bridge ->
        val owner = bridge.builder()
        bridge.uniqueMethod { matcher { declaredClass = owner; returnType = owner; paramTypes("java.lang.String") } }
    }

    val SEND = DexQuery { bridge ->
        val owner = bridge.builder()
        val listener = bridge.sendListener()
        bridge.uniqueMethod { matcher { declaredClass = owner; returnType = "void"; paramTypes(listener) } }
    }

    val SENT = DexQuery { bridge ->
        val listener = bridge.sendListener()
        bridge.uniqueMethod { matcher { declaredClass = listener; returnType = "void"; paramTypes(CONVERSATION, MESSAGE) } }
    }

    val SEND_FAILED = DexQuery { bridge ->
        val listener = bridge.sendListener()
        bridge.uniqueMethod {
            matcher { declaredClass = listener; returnType = "void"; paramTypes(CONVERSATION, MESSAGE, ERROR) }
        }
    }

    val TEXT_CONTENT = method {
        matcher { declaredClass = TEXT_CONTENT_CLASS; modifiers(Modifier.STATIC); returnType = TEXT_CONTENT_CLASS; paramTypes("java.lang.String") }
    }

    val EMOJI_CONTENT = method {
        matcher { declaredClass = EMOJI_CONTENT_CLASS; modifiers(Modifier.STATIC); returnType = EMOJI_CONTENT_CLASS; paramTypes(EMOJI) }
    }

    // The light-interaction entry the native streak shortcut reads its "highlight_streak" configuration from.
    val INTERACTION_ENTRY = method {
        matcher {
            returnType = "com.ss.android.ugc.aweme.im.service.service.EntryLightInteractionConf"
            paramTypes("java.lang.String", "java.lang.String", "java.lang.String", "boolean")
            usingEqStrings("light_interaction_entry")
        }
    }

    val INTERACTION_CONFIGURATION = method {
        matcher { declaredClass = HELPER; modifiers(Modifier.STATIC); returnType = CONF; paramTypes(HELPER, "java.lang.String", "boolean", "java.lang.String", "int") }
    }

    val RESOURCE_TO_EMOJI = method {
        matcher { modifiers(Modifier.STATIC); returnType = EMOJI; paramTypes(RESOURCE, "boolean") }
    }

    val INTERACTIVE_RESOURCES = method {
        matcher { returnType = "java.util.List"; paramCount = 0; usingEqStrings("getValidResources") }
    }

    // A static getter in the class that holds the interactive resource loader instance.
    val RESOURCE_LOADER_OWNER = method {
        matcher {
            modifiers(Modifier.STATIC)
            returnType = "kotlin.Pair"
            paramCount = 0
            invokeMethods { add { returnType = EMOJI; paramTypes(RESOURCE, "boolean") } }
        }
    }
}
