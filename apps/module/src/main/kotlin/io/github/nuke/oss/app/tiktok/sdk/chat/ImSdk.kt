package io.github.nuke.oss.app.tiktok.sdk.chat

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.reflect.NamedCall
import java.lang.reflect.InvocationTargetException

// The account the IM SDK is signed in as, read from its environment service. Unlike the account service behind the
// settings profile card it can be read from any thread, which the claim batches need. A signed-out SDK, or any
// answer that is not a positive ID, throws IllegalStateException with the reason.
internal class ImAccount(private val hostClassLoader: ClassLoader) {
    fun id(): Long {
        try {
            val manager = MANAGER.toClass(hostClassLoader).resolve().firstField { name = "INSTANCE" }.get()
                ?: error("The IM SDK manager is unavailable")
            val client = GET_CLIENT.of(manager)?.invoke(manager) ?: error("The IM SDK client is unavailable")
            val env = GET_ENV.of(client)?.invoke(client) ?: error("The IM SDK environment is unavailable")
            check(IS_LOGIN.of(env)?.invoke(env) == true) { "The IM SDK is not logged in" }
            return (GET_UID.of(env)?.invoke(env) as? Long)?.takeIf { it > 0 } ?: error("The IM SDK account is unavailable")
        } catch (error: InvocationTargetException) {
            throw IllegalStateException("The IM SDK account could not be read: ${error.targetException.javaClass.simpleName}", error)
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("The IM SDK account could not be read: ${error.javaClass.simpleName}", error)
        }
    }

    private companion object {
        const val MANAGER = "com.bytedance.ies.im.core.sdk.SDKManager"
        val GET_CLIENT = NamedCall("getImSdkClient")
        val GET_ENV = NamedCall("getIIMSdkEnvService")
        val IS_LOGIN = NamedCall("isLogin")
        val GET_UID = NamedCall("getUid")
    }
}

// The host's per-account conversation list model, which must belong to the account the batch was started for.
internal class ConversationModels(private val hostClassLoader: ClassLoader) {
    private val getUid = NamedCall("getUid")

    fun current(account: Long): Any {
        val model = HELPER.toClass(hostClassLoader).resolve().firstMethod { name = "getConversationListModel"; emptyParameters() }.invoke()
            ?: error("The native conversation model is unavailable")
        check((getUid.of(model)?.invoke(model) as? Number)?.toLong() == account) { "The native conversation model belongs to another account" }
        return model
    }

    private companion object {
        const val HELPER = "com.bytedance.ies.im.core.sdk.MultiInstanceHelperKt"
    }
}
