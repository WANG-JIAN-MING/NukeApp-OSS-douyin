package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.uniqueMethod
import org.luckypray.dexkit.query.FindMethod

// Dex queries for the media pickers of the chat, taken from the legacy module. The original-image state is found through
// the native setSendRaw path, so no obfuscated member name is assumed.
@DexQueries
internal object ChatMediaQueries {
    const val MEDIA_VIEW_MODEL = "com.ss.android.ugc.aweme.im.business.mediaselectpage.choose.viewmodel.MediaChooseViewModel2"
    private const val QUICK_ALBUM_LOGIC = "com.ss.android.ugc.aweme.im.business.chat.feature.quickalbum.AlbumQuickSendLogic"
    private const val DUX_CHECKBOX = "com.bytedance.dux.widget.DuxCheckBox"

    // The view model's "send the original" switch, which the quick album calls as setSendRaw.
    val ORIGINAL_SETTER = method {
        matcher {
            declaredClass = MEDIA_VIEW_MODEL
            returnType = "void"
            paramTypes("boolean")
            callerMethods { add { declaredClass = QUICK_ALBUM_LOGIC; name = "setSendRaw" } }
        }
    }

    private fun FindMethod.albumInitialization() {
        matcher {
            returnType = "void"
            paramCount = 0
            usingEqStrings("extra_choose_parameters")
            invokeMethods { add { declaredClass = MEDIA_VIEW_MODEL; returnType = "void"; paramTypes("boolean") } }
        }
    }

    // The album's initialization, which resets the original state to off.
    val ALBUM_INITIALIZATION = method { albumInitialization() }

    // The album's getter for its view model, in the class that has the initialization.
    val ALBUM_VIEW_MODEL = DexQuery { bridge ->
        val owner = bridge.findMethod { albumInitialization() }
            .also { check(it.size == 1) { "expected one initialization, found ${it.size}" } }
            .single().declaredClassName
        bridge.uniqueMethod { matcher { declaredClass = owner; returnType = MEDIA_VIEW_MODEL; paramCount = 0 } }
    }

    // The e-commerce picker's builder of the original-image check box row.
    val ECOMMERCE_ORIGINAL_BIND = method {
        matcher {
            returnType = "android.view.ViewGroup"
            paramTypes("android.view.ViewGroup")
            invokeMethods {
                add { declaredClass = DUX_CHECKBOX; name = "setChecked" }
                add { declaredClass = DUX_CHECKBOX; name = "setOnCheckedChangeListener" }
            }
        }
    }
}
