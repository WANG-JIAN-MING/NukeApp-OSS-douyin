package io.github.nuke.oss.app.tiktok.protocol

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.uniqueField
import io.github.nuke.oss.app.shared.dex.uniqueMethod
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge

internal const val SDK_CONTEXT_CLASS = "com.bytedance.im.core.mi.IMSdkContext"
internal const val REQUEST_BODY_CLASS = "com.bytedance.im.core.proto.RequestBody"
internal const val RESPONSE_CLASS = "com.bytedance.im.core.proto.Response"
internal const val REQUEST_CLASS = "com.bytedance.im.core.proto.Request"
internal const val LISTENER_CLASS = "com.bytedance.im.core.client.callback.IRequestListener"
private const val DISPATCH_ANCHOR = "IMBaseHandler-sendRequest"

// Dex queries for the IM SDK's raw request path. The handler class and the per-request context class are found
// from the dispatch method, so no obfuscated name is stored.
@DexQueries
internal object ProtocolQueries {
    // A DexKit descriptor reads "Lpkg/Name;->method(...)"; matchers take dotted class names.
    private fun DexKitBridge.handlerName(): String = uniqueMethod {
        matcher {
            returnType = "long"
            paramTypes("int", REQUEST_BODY_CLASS, "java.lang.Object[]")
            usingEqStrings(DISPATCH_ANCHOR)
        }
    }.substringBefore("->").removePrefix("L").removeSuffix(";").replace('/', '.')

    private fun DexKitBridge.requestContextName(): String {
        val handler = handlerName()
        val abstractChecks = findMethod {
            matcher {
                declaredClass = handler
                modifiers(Modifier.ABSTRACT)
                returnType = "boolean"
                paramCount = 1
            }
        }
        check(abstractChecks.size == 1) {
            "expected one method, found ${abstractChecks.size}"
        }
        return abstractChecks.single().paramTypeNames.single()
    }

    val RAW_DISPATCH = method {
        matcher {
            modifiers(Modifier.PUBLIC or Modifier.FINAL)
            returnType = "long"
            paramTypes("int", REQUEST_BODY_CLASS, "java.lang.Object[]")
            usingEqStrings(DISPATCH_ANCHOR)
        }
    }

    val HANDLE_RESPONSE = DexQuery { bridge ->
        val handler = bridge.handlerName()
        bridge.uniqueMethod {
            matcher {
                declaredClass = handler
                modifiers(Modifier.ABSTRACT)
                returnType = "void"
                paramCount = 2
            }
        }
    }

    val CHECK_RESPONSE = DexQuery { bridge ->
        val handler = bridge.handlerName()
        bridge.uniqueMethod {
            matcher {
                declaredClass = handler
                modifiers(Modifier.ABSTRACT)
                returnType = "boolean"
                paramCount = 1
            }
        }
    }

    val SDK_CONTEXT = method {
        matcher {
            declaredClass = "com.bytedance.im.core.mi.IMSdkClient"
            returnType = SDK_CONTEXT_CLASS
            paramCount = 0
        }
    }

    val BUILD_BODY = method {
        matcher {
            declaredClass = "$REQUEST_BODY_CLASS\$Builder"
            returnType = REQUEST_BODY_CLASS
            paramCount = 0
        }
    }

    val COMMAND_ROUTE = method {
        matcher {
            modifiers(Modifier.STATIC)
            returnType = "java.lang.String"
            paramTypes(SDK_CONTEXT_CLASS, "int")
            usingEqStrings("cmd not find!")
        }
    }

    val RESPONSE_FIELD = DexQuery { bridge ->
        val context = bridge.requestContextName()
        bridge.uniqueField { matcher { declaredClass = context; type = RESPONSE_CLASS } }
    }

    val REQUEST_FIELD = DexQuery { bridge ->
        val context = bridge.requestContextName()
        bridge.uniqueField { matcher { declaredClass = context; type = REQUEST_CLASS } }
    }
}
