package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.uniqueMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher

// Dex queries for intercepting a recall command before the host applies it, taken from the legacy module, which found
// each of them unique in Douyin 40.7.0. The pre- variants run in the batch pipeline, the others on the message database.
@DexQueries
internal object RecallQueries {
    private const val MESSAGE = "com.bytedance.im.core.model.Message"
    private const val MESSAGE_BODY = "com.bytedance.im.core.proto.MessageBody"
    private const val COMMAND = "com.bytedance.im.core.model.CommandMessage"
    private const val PRE_SAVE = "com.bytedance.im.core.internal.data.save.bean.CommonPreSaveData"
    private const val MESSAGE_DAO = "com.bytedance.im.core.db.delegate.IIMMsgDaoDelegate"

    private fun MethodMatcher.recall() {
        paramTypes("int", COMMAND, MESSAGE_BODY)
        usingEqStrings("handleRecallMessage local msg is null", "recall msg execute fail")
    }

    private fun MethodMatcher.preRecall() {
        returnType = PRE_SAVE
        paramTypes(PRE_SAVE, MESSAGE_BODY, COMMAND)
        usingEqStrings("handleRecallMessage local msg is null")
    }

    val RECALL = method { matcher { recall() } }
    val PRE_RECALL = method { matcher { preRecall() } }

    val UPDATE_EXT = method {
        matcher { paramTypes("int", COMMAND, MESSAGE_BODY); usingEqStrings("update message ext fail", "s:is_recalled") }
    }

    val PRE_UPDATE_EXT = method {
        matcher {
            returnType = PRE_SAVE
            paramTypes(PRE_SAVE, MESSAGE_BODY, COMMAND)
            usingEqStrings("s:server_message_id", "s:is_recalled", "a:s_rip_media")
        }
    }

    val MESSAGE_BY_UUID = method {
        matcher { declaredClass = MESSAGE_DAO; returnType = MESSAGE; paramTypes("java.lang.String"); callerMethods { add { recall() } } }
    }

    val MESSAGE_BY_ID = method {
        matcher { declaredClass = MESSAGE_DAO; returnType = MESSAGE; paramTypes("long"); callerMethods { add { recall() } } }
    }

    val CACHED_BY_UUID = method {
        matcher { returnType = MESSAGE; paramTypes("java.lang.String", "java.lang.String"); callerMethods { add { preRecall() } } }
    }

    val CACHED_BY_ID = method {
        matcher { returnType = MESSAGE; paramTypes("long", "java.lang.String"); callerMethods { add { preRecall() } } }
    }

    val STORE_COMMAND = method {
        matcher { returnType = "void"; paramTypes(MESSAGE_BODY, "java.lang.String"); callerMethods { add { recall() } } }
    }

    val PRE_STORE_COMMAND = method {
        matcher { returnType = "void"; paramTypes(PRE_SAVE, "java.lang.String", MESSAGE_BODY); callerMethods { add { preRecall() } } }
    }

    // The completion that acknowledges the command: a one-argument void method of the recall's result type.
    val COMPLETE = DexQuery { bridge ->
        val resultType = bridge.findMethod { matcher { recall() } }
            .also { check(it.size == 1) { "expected one recall method, found ${it.size}" } }.single().returnTypeName
        bridge.uniqueMethod {
            matcher { declaredClass = resultType; returnType = "void"; paramCount = 1; callerMethods { add { recall() } } }
        }
    }

    val PRE_COMPLETE = method {
        matcher { returnType = "void"; paramTypes("boolean"); callerMethods { add { preRecall() } } }
    }
}
