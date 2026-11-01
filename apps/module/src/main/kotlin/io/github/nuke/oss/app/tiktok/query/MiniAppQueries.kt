package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.field
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.uniqueField
import io.github.nuke.oss.app.shared.dex.uniqueMethod
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

// Dex queries for the mini-app and mini-game processes, and for the code patch loader, taken from the legacy module
// (which checked them against Douyin 40.7.0) and located the same way as FeedQueries. The reward video queries follow
// the generated video API handlers into their service contract, so no obfuscated member name is assumed.
@DexQueries
internal object MiniAppQueries {
    private const val INPUT_DIALOG = "com.minigame.merge.miniapp.game.health.ui.dialog.verifyinput.VerifyInputDialog"

    // The real-name input dialog's onStart, and the int field it fills with the verification state in onCreate.
    val REAL_NAME_STARTED = method {
        matcher { declaredClass = INPUT_DIALOG; name = "onStart"; paramCount = 0; returnType = "void" }
    }

    val REAL_NAME_STATE = field {
        matcher {
            declaredClass = INPUT_DIALOG
            type = "int"
            writeMethods {
                add {
                    declaredClass = INPUT_DIALOG
                    name = "onCreate"
                    paramTypes("android.os.Bundle")
                    usingEqStrings("VERIFY_STATUS")
                }
            }
        }
    }

    // The code patch loader (Reparo): its initialization and its check for a remote patch.
    val REPARO_INIT = method {
        matcher {
            paramTypes("com.bytedance.reparo.IReparoConfig")
            returnType = "void"
            usingEqStrings("Reparo", "init failed. ")
        }
    }

    val REPARO_FETCH = method {
        matcher {
            paramCount = 0
            returnType = "void"
            usingEqStrings("https://security.snssdk.com/api/plugin/config/v3/", "queryRemotePatchInfo error.", "hotfix_type", "reparo")
        }
    }

    val APP_CONTEXT = method {
        matcher {
            declaredClass = "com.bytedance.bdp.appbase.context.service.ContextService"
            returnType = "com.bytedance.bdp.appbase.context.BdpAppContext"
            paramCount = 0
        }
    }

    // The video ad API of one of the two SDKs (mini apps and mini games).
    private class RewardSdk(private val game: Boolean) {
        private val apiPackage = if (game) "com.bytedance.minigame.merge.cpapi.impl.handler.ad" else "com.bytedance.bdp.cpapi.impl.handler.ad"
        private val service = if (game) "com.bytedance.minigame.merge.appbase.service.protocol.ad.AdApiService"
            else "com.bytedance.bdp.appbase.service.protocol.ad.AdApiService"
        private val implementation = if (game) "com.minigame.merge.miniapp.ad.AdApiServiceImpl" else "com.tt.miniapphost.ad.api.AdApiServiceImpl"

        // The service call the generated handler makes: void, with the request and the callback.
        private fun dispatch(bridge: DexKitBridge, create: Boolean): MethodData {
            val child = "$apiPackage.${if (create) "Create" else "Operate"}VideoAdApiHandler"
            val handler = bridge.findClass { matcher { className = child } }
                .also { check(it.size == 1) { "expected one handler, found ${it.size}" } }.single()
            val parent = checkNotNull(handler.superClass) { "$child has no base class" }
            val calls = parent.methods.flatMap { it.invokes }.distinctBy { it.descriptor }
                .filter { it.declaredClassName == service && it.returnTypeName == "void" && it.paramCount == 2 }
            check(calls.size == 1) { "expected one service call, found ${calls.size}" }
            return calls.single()
        }

        val create = DexQuery { bridge ->
            val entry = dispatch(bridge, true)
            bridge.uniqueMethod {
                matcher { declaredClass = implementation; name = entry.name; paramTypes(*entry.paramTypeNames.toTypedArray()); returnType = "void" }
            }
        }

        val operate = DexQuery { bridge ->
            val entry = dispatch(bridge, false)
            bridge.uniqueMethod {
                matcher { declaredClass = implementation; name = entry.name; paramTypes(*entry.paramTypeNames.toTypedArray()); returnType = "void" }
            }
        }

        // The request model's event sender.
        val event = DexQuery { bridge ->
            val model = dispatch(bridge, false).paramTypeNames[0]
            bridge.uniqueMethod {
                matcher { declaredClass = model; returnType = "void"; paramTypes("java.lang.String", "org.json.JSONObject") }
            }
        }

        val adId = DexQuery { bridge ->
            val model = dispatch(bridge, false).paramTypeNames[0]
            bridge.uniqueField {
                matcher {
                    declaredClass = model
                    type = "java.lang.String"
                    readMethods { add { declaredClass = model; returnType = "void"; paramTypes("java.lang.String", "org.json.JSONObject") } }
                }
            }
        }

        // The action string field: the one non-final String the operate path reads.
        val action = DexQuery { bridge ->
            val model = dispatch(bridge, false).paramTypeNames[0]
            val fields = bridge.findField {
                matcher {
                    declaredClass = model
                    type = "java.lang.String"
                    readMethods {
                        add {
                            usingEqStrings(if (game) "VideoAdServiceImplPangolin" else "G_Ad.Video.Impl", "operateVideoAd")
                            paramCount = 2
                            returnType = "void"
                        }
                    }
                }
            }.filter { !Modifier.isFinal(it.modifiers) }
            check(fields.size == 1) { "expected one field, found ${fields.size}" }
            fields.single().descriptor
        }

        val success = DexQuery { bridge ->
            val callback = dispatch(bridge, false).paramTypeNames[1]
            bridge.uniqueMethod {
                matcher {
                    declaredClass = callback
                    returnType = "void"
                    if (game) paramTypes("java.lang.Object") else {
                        paramCount = 0
                        callerMethods { add { usingEqStrings("G_Ad.Video.Impl", "createVideoAd") } }
                    }
                }
            }
        }

        val failure = DexQuery(version = 2) { bridge ->
            val callback = dispatch(bridge, false).paramTypeNames[1]
            bridge.uniqueMethod {
                matcher {
                    declaredClass = callback
                    returnType = "void"
                    paramTypes("int", "int", "java.lang.String", "java.lang.String")
                    modifiers(Modifier.ABSTRACT)
                }
            }
        }
    }

    private val miniApp = RewardSdk(false)
    private val miniGame = RewardSdk(true)

    // Both SDKs' queries, in the order the feature reads them: create, operate, event, ad ID, action, success, failure.
    val APP_CREATE = miniApp.create
    val APP_OPERATE = miniApp.operate
    val APP_EVENT = miniApp.event
    val APP_AD_ID = miniApp.adId
    val APP_ACTION = miniApp.action
    val APP_SUCCESS = miniApp.success
    val APP_FAILURE = miniApp.failure
    val GAME_CREATE = miniGame.create
    val GAME_OPERATE = miniGame.operate
    val GAME_EVENT = miniGame.event
    val GAME_AD_ID = miniGame.adId
    val GAME_ACTION = miniGame.action
    val GAME_SUCCESS = miniGame.success
    val GAME_FAILURE = miniGame.failure
}
