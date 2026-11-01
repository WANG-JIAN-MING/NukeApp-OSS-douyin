package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.field
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.uniqueMethod
import java.lang.reflect.Modifier
import org.luckypray.dexkit.query.matchers.MethodMatcher

// Dex queries for copying text, the avatar preview and the time and location label. Taken from the legacy module and
// checked there against Douyin 40.7.0; located the same way as FeedQueries.
@DexQueries
internal object ViewQueries {
    private const val COPY_ITEM = "com.ss.android.ugc.aweme.comment.manager.longclickaction.actions.CopyActionItem"
    private const val SIGNATURE_VIEW = "com.ss.android.ugc.aweme.profile.ui.header.ProfileSignatureView"
    private const val DESC_FRAME = "com.ss.android.ugc.aweme.feed.desc.ui.FeedDescFrameLayout"
    private const val MENSA_CONTEXT = "com.ss.android.ugc.aweme.feed.mensa.model.MensaContext"
    private const val AVATAR_DETAIL = "com.ss.android.ugc.aweme.profile.ui.HeaderDetailActivity"
    private const val Q_MODEL = "com.ss.android.ugc.aweme.kiwi.model.QModel"

    // The comment menu's copy entry factory, and the item's text and visibility.
    val COMMENT_COPY_ITEM = method {
        matcher {
            declaredClass = "com.ss.android.ugc.aweme.comment.manager.longclickaction.factory.registers.CopyItemRegister"
            returnType = "com.ss.android.ugc.aweme.comment.ui.longpress.CommentLongPressItemModel"
            paramCount = 1
            invokeMethods { add { declaredClass = COPY_ITEM; name = "<init>" } }
        }
    }

    val COMMENT_COPY_TEXT = method {
        matcher {
            declaredClass = COPY_ITEM
            returnType = "java.lang.String"
            paramCount = 0
            callerMethods { add { usingEqStrings("bpea-clipboard_for_comment_copy") } }
        }
    }

    val COMMENT_COPY_VISIBLE = method {
        matcher { declaredClass = COPY_ITEM; returnType = "boolean"; paramCount = 0 }
    }

    val SIGNATURE_BIND = method {
        matcher { declaredClass = SIGNATURE_VIEW; returnType = "void"; paramTypes("java.lang.String", "java.util.List") }
    }

    val SIGNATURE_TEXT = method {
        matcher { declaredClass = SIGNATURE_VIEW; name = "getSignature"; returnType = "java.lang.String"; paramCount = 0 }
    }

    val CAPTION_LONG_PRESS = method {
        matcher { declaredClass = DESC_FRAME; returnType = "void"; paramCount = 0; usingEqStrings("triggerLongPress by ") }
    }

    val CAPTION_CONTEXT = field {
        matcher {
            type = MENSA_CONTEXT
            readMethods { add { declaredClass = DESC_FRAME; usingEqStrings("triggerLongPress by ") } }
        }
    }

    val CAPTION_AWEME = method {
        matcher { declaredClass = MENSA_CONTEXT; returnType = "com.ss.android.ugc.aweme.feed.model.Aweme"; paramCount = 0 }
    }

    // The avatar preview: where it binds its image, where it reads touches, and where it ends.
    val AVATAR_BIND = method {
        matcher {
            declaredClass = AVATAR_DETAIL
            returnType = "void"
            paramTypes(
                "com.ss.android.ugc.aweme.base.ui.RemoteImageView", "boolean", "com.ss.android.ugc.aweme.profile.model.User",
                "java.lang.String[]",
            )
        }
    }

    val AVATAR_TOUCH = DexQuery { bridge ->
        val anchor = bridge.findMethod { matcher { returnType = "void"; paramCount = 0; usingEqStrings("pinch_zoom_avatar") } }
            .also { check(it.size == 1) { "expected one anchor, found ${it.size}" } }.single()
        bridge.uniqueMethod {
            matcher {
                declaredClass = anchor.declaredClassName
                name = "onTouch"
                returnType = "boolean"
                paramTypes("android.view.View", "android.view.MotionEvent")
            }
        }
    }

    val AVATAR_DESTROY = method {
        matcher { declaredClass = AVATAR_DETAIL; name = "onDestroy"; returnType = "void"; paramCount = 0 }
    }

    // The label under the description that shows the publish time and the IP location.
    private fun timeBinder(matcher: MethodMatcher) = matcher.apply {
        name = "onBindSafe"
        returnType = "void"
        paramTypes(Q_MODEL)
        usingEqStrings("IP属地：", "发布时间：")
    }

    val TIME_BIND = method { matcher { timeBinder(this) } }

    // The static check that hides the label; its one caller is the register component.
    val TIME_VISIBILITY = method {
        matcher {
            modifiers(Modifier.STATIC)
            returnType = "int"
            paramTypes(Q_MODEL)
            callerMethods {
                add {
                    declaredClass = "com.ss.android.ugc.aweme.flux.register.leftbottom.FeedBellowDescTimeComponentRegister"
                    returnType = "boolean"
                    paramTypes(Q_MODEL)
                }
            }
        }
    }

    // The IP display mode getter, whose one caller is the time binder.
    val TIME_IP_MODE = DexQuery { bridge ->
        val configGetter = bridge.findMethod {
            matcher {
                modifiers(Modifier.STATIC)
                paramCount = 0
                usingEqStrings("ip_mapping_location_settings")
                callerMethods { add { timeBinder(this) } }
            }
        }.also { check(it.size == 1) { "expected one config getter, found ${it.size}" } }.single()
        bridge.uniqueMethod {
            matcher {
                declaredClass = configGetter.returnTypeName
                returnType = "int"
                paramCount = 0
                callerMethods { add { timeBinder(this) } }
            }
        }
    }
}
