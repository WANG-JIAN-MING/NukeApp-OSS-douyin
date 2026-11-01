package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.annotatedWith
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.dex.uniqueMethod

// Dex queries for the chat, message list and restriction checks, located the same way as FeedQueries.
@DexQueries
internal object ChatQueries {
    // The component that plays full-screen effects for trigger words; it initializes its views in onViewCreated.
    val CHAT_EFFECTS = method {
        matcher { usingStrings("onAnimateRejectEvent notify onAnimateRejectEvent", "onAnimateRejectEvent") }
    }

    // The quick-send emoji row's adapter feed. Newer hosts mark it with the "big" size string next to "normal"
    // and "role"; the fallback is its initAdapter in the class that logs the hide animation.
    val QUICK_EMOJI = DexQuery { bridge ->
        val sized = bridge.findClass { matcher { usingStrings("normal", "role") } }
        val primary = if (sized.isEmpty()) emptyList() else bridge.findMethod {
            searchInClass(sized)
            matcher { usingEqStrings("big") }
        }
        if (primary.isNotEmpty()) {
            check(primary.size == 1) { "expected one method, found ${primary.size}" }
            primary.single().descriptor
        } else {
            val animated = bridge.findClass { matcher { usingEqStrings("hideWithOwnTransition withAnimation:") } }
            check(animated.isNotEmpty()) { "found no emoji row" }
            bridge.uniqueMethod {
                searchInClass(animated)
                matcher { name = "initAdapter" }
            }
        }
    }

    // The message page's header adapter, which logs the payloads it adds above the conversation list.
    val MESSAGE_HEADER = method {
        matcher {
            usingStrings(
                "add_payload StoryCoverPayload pos=", "add_payload LowerRightPayload pos=", "add_payload ColorRingPayload pos=",
            )
        }
    }

    // The view-once message state: whether the text may still be shown, and the step that marks it consumed.
    val VIEW_ONCE_TEXT = DexQuery { bridge ->
        val owners = bridge.findClass { matcher { usingStrings("a:once_group_msg_can_show") } }
        check(owners.isNotEmpty()) { "found no message state" }
        bridge.uniqueMethod {
            searchInClass(owners)
            matcher { usingStrings("s:once_view_done") }
        }
    }

    val VIEW_ONCE_MARK = DexQuery { bridge ->
        val owners = bridge.findClass { matcher { usingEqStrings("setLocalUserRead false when no read") } }
        check(owners.isNotEmpty()) { "found no read marker" }
        bridge.uniqueMethod {
            searchInClass(owners)
            matcher { usingEqStrings("msgId = ") }
        }
    }

    // The forward picker's maximum, an int read from a settings holder keyed im_forward_message_optimize.
    val FORWARD_LIMIT = DexQuery { bridge ->
        val owners = bridge.findClass { matcher { annotatedWith("im_forward_message_optimize", element = "value") } }
        check(owners.isNotEmpty()) { "found no settings holder" }
        bridge.uniqueMethod {
            searchInClass(owners)
            matcher {
                paramCount = 3
                returnType = "int"
            }
        }
    }

    // The two requests that tell the server how far a conversation has been read, and the typing indicator refresh.
    val MARK_READ_BY_INDEX = method {
        matcher {
            usingStrings("MarkConversationReadHandler doMark lastReadIndex = ")
            paramCount = 1
        }
    }
    val MARK_READ_REQUEST = method {
        matcher {
            usingStrings("MarkConversationReadHandler doRequest local:")
            paramCount = 2
        }
    }
    val TYPING_STATUS = method {
        matcher { usingEqStrings("checkAndRefreshTypingStatus time = ") }
    }

    // The forward action of the message menu names itself with a public final no-argument getter returning "forward";
    // its class also decides whether the message may be forwarded.
    val FORWARD_ACTION = method {
        matcher {
            usingEqStrings("forward")
            returnType = "java.lang.String"
            paramCount = 0
            modifiers(java.lang.reflect.Modifier.PUBLIC or java.lang.reflect.Modifier.FINAL)
        }
    }

    // The chat list's streak badge is rendered from a state object by a three-parameter method of the streak UI package:
    // the host, the conversation and the state it fills.
    val STREAK_RENDER = method {
        searchPackages("com.ss.android.ugc.aweme.im.streak.core.ui")
        matcher {
            usingStrings("single_session_cell")
            paramCount = 3
        }
    }

    // Every incoming message passes through one handler that logs the fix-up of its order index; it takes the raw
    // message data as its third parameter and returns the message model.
    val INCOMING_MESSAGE = method {
        matcher { usingStrings("s:basic_ext_info", "orderIndex fix before:", "ConvertUtils.convert") }
    }

    // The red packet request class logs the request it rejects; it holds the call that opens a packet.
    val RED_PACKET_API = method {
        matcher { usingStrings("RedPacketApi request params invalid, request=") }
    }

    // The IM SDK's send step, which logs why it refuses a message with neither content nor content object. It takes the
    // Message, an extras map, a request listener and two more values.
    val SEND_MESSAGE = method {
        matcher {
            usingEqStrings("sendMessage msg.contentObj == null && msg.content == null")
            paramCount = 5
        }
    }

    // The chat list's logic object: the method that logs a data-set refresh lives in its class.
    val CHAT_LOGIC = method {
        matcher { usingStrings("notifyDataSetChanged from: ", ", makeUiCacheInvalid: ", " mAdapterController.getSize(): ") }
    }

    // The long-click menu's data provider has the "reply" step: a void method that takes the Message and a flag, with a
    // click source between them on newer hosts.
    val REPLY_ACTION = DexQuery { bridge ->
        val owners = bridge.findClass { matcher { usingEqStrings("LongClickDataProvider") } }
        check(owners.isNotEmpty()) { "found no long-click data provider" }
        val message = "com.bytedance.im.core.model.Message"
        val found = listOf(arrayOf(message, "boolean"), arrayOf(message, "java.lang.String", "boolean")).flatMap { shape ->
            bridge.findMethod {
                searchInClass(owners)
                matcher {
                    returnType = "void"
                    paramTypes(*shape)
                }
            }
        }
        check(found.size == 1) { "expected one reply method, found ${found.size}" }
        found.single().descriptor
    }
}

// Dex queries for checks the host runs before it lets an action through.
@DexQueries
internal object GateQueries {
    // Rejects a QR code read from an album image and logs the paths where scanning is blocked.
    val ALBUM_SCAN = method {
        matcher { usingEqStrings("scan_to_login_block_path_list", "qr_code_scan_rejected") }
    }

    // The mini game anti-addiction dispatcher in the main process.
    val MINOR_CHECK = method {
        matcher { usingStrings("Anti-Mgr", "realDispatch not idle: ", "realDispatch: invalid") }
    }
}
