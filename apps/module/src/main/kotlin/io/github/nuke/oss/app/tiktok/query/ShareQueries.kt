package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.method
import java.lang.reflect.Modifier

// Dex queries for the share panel's function rows, taken from the legacy module (checked there against Douyin 40.7.0)
// and located the same way as FeedQueries. They are the panel's own rows, before its adapter creates any View.
@DexQueries
internal object ShareQueries {
    private const val PANEL = "com.ss.android.ugc.aweme.im.share.aweme.only.sharepanel"

    // The dialog method that hands the function rows to the adapter.
    val BIND_ROWS = method {
        matcher {
            declaredClass = "$PANEL.ui.SharePanelDialog"
            returnType = "void"
            paramTypes("java.util.List")
            invokeMethods { add { declaredClass = "$PANEL.ui.ShareFunctionAdapter"; returnType = "void"; paramTypes("java.util.List") } }
        }
    }

    // The function interface's one-argument initializer; its declaring class is the base of every row.
    val INITIALIZE_FUNCTION = method {
        matcher { declaredClass = "$PANEL.core.IShareFunction"; returnType = "void"; paramCount = 1 }
    }

    // The label getter of the "more" row, which names the abstract label method.
    val LABEL_OVERRIDE = method {
        matcher { declaredClass = "$PANEL.function.MoreShareFunction"; returnType = "java.lang.String"; paramCount = 0; usingEqStrings("更多分享") }
    }

    // The click handler of the copy-link row, which names the abstract click method.
    val CLICK_OVERRIDE = method {
        matcher {
            declaredClass = "$PANEL.function.CopyLinkFunction"
            returnType = "boolean"
            paramCount = 0
            invokeMethods { add { declaredClass = "com.bytedance.timon_monitor_impl.method_tracer.MethodTracer"; name = "push" } }
        }
    }

    val SHARE_UTIL = method {
        matcher {
            declaredClass = "com.ss.android.ugc.aweme.refactor.douyin.share.impl.ShareUtilImpl"
            returnType = "com.ss.android.ugc.aweme.refactor.douyin.share.impl.ShareUtilImpl"
            paramCount = 0
            modifiers(Modifier.STATIC)
        }
    }
}
