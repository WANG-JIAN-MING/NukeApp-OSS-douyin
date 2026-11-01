package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.method
import java.lang.reflect.Modifier

// Dex queries for the host's friends and one-to-one chats, which the contact reader and the pet feeding use.
@DexQueries
internal object ContactQueries {
    // The IM contact service's friend list: the getAllFriends implementation that keeps mutual follows only.
    val FRIENDS = method {
        matcher {
            name = "getAllFriends"
            returnType = "java.util.List"
            paramTypes("java.lang.String")
            invokeMethods { add { usingEqStrings("follow_each_other") } }
        }
    }

    // ConversationModel.Companion's static mapping from a user ID to the one-to-one conversation ID.
    val SINGLE_CONVERSATION_ID = method {
        matcher {
            declaredClass = "com.bytedance.ies.im.core.api.client.ConversationModel\$Companion"
            modifiers(Modifier.STATIC)
            paramTypes("java.lang.String")
            returnType = "java.lang.String"
        }
    }

    // StreakApiService's streak day count for a conversation ID.
    val STREAK_DAYS = method {
        matcher {
            declaredClass = "com.ss.android.ugc.aweme.im.streak.api.StreakApiService"
            paramTypes("java.lang.String")
            returnType = "int"
        }
    }
}
