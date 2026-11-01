package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.method

// Dex queries for the notification-permission prompts, taken from the legacy module and located the same way as
// FeedQueries.
@DexQueries
internal object PermissionQueries {
    // The loader of the push-permission guide: it logs its scene and the "notification has enable" check, and reports
    // back through the listener it is given.
    val LOAD_GUIDE = method {
        matcher {
            returnType = "void"
            paramTypes(
                "com.ss.android.ugc.awemepushapi.PermissionScene", "com.ss.android.ugc.awemepushapi.PermissionType",
                "com.ss.android.ugc.awemepushapi.IPushPermissionUIGuideLoadListener", "org.json.JSONObject",
                "org.json.JSONObject", "java.lang.String",
            )
            usingEqStrings("tryLoadPushPermissionGuideData， permissionScene=", "notification has enable")
        }
    }

    // The custom alert shown before the system dialog.
    val GOOGLE_ALERT = method {
        matcher {
            declaredClass = "com.bytedance.android.push.permission.boot.PermissionBootServiceProvider"
            name = "requestNotificationPermissionByGoogleAlert"
            returnType = "void"
            paramCount = 8
            usingEqStrings("[requestNotificationPermissionByGoogleAlert]customDialogView:")
        }
    }
}
