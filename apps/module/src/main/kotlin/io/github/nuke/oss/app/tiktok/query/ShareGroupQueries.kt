package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.method

// Dex queries of the share panel's friend picker and the IM share service, taken from the legacy module's
// ShareFriendTargets (checked there against Douyin 40.7). Not checked against a real host here.
@DexQueries
internal object ShareGroupQueries {
    // The constructor of the picker component that loads every contact; its one argument carries the share package.
    val FRIEND_PICKER = method {
        matcher { name = "<init>"; paramCount = 1; usingEqStrings("internalLoadAllContacts from ") }
    }

    val SHARE_WITH_CALLBACK = method {
        matcher {
            declaredClass = "com.ss.android.ugc.aweme.im.service.service.IImShareService"
            name = "shareWithCallBack"
            returnType = "void"
            paramTypes(
                "com.ss.android.ugc.aweme.sharer.ui.SharePackage", "java.util.List",
                "com.ss.android.ugc.aweme.im.service.share.callback.IImShareCallback", "com.ss.android.ugc.aweme.sharer.ui.ITipParams",
            )
        }
    }
}
