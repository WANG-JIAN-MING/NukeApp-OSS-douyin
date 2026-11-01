package io.github.nuke.oss.app.tiktok.feature.chat.claims

import io.github.nuke.oss.app.tiktok.sdk.chat.ConversationModels
import io.github.nuke.oss.app.tiktok.sdk.chat.ImAccount
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.core.Logger
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URI
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

// A network failure of a claim request; a request that was already dispatched has an unknown outcome.
internal open class ClaimTransportException(message: String, cause: Throwable? = null) : IOException(message, cause)

// The host refused to start the request, so it was never sent.
internal class ClaimNotDispatchedException(message: String, cause: Throwable? = null) : ClaimTransportException(message, cause)

private const val REQUEST_TIMEOUT_MILLIS = 15_000L
private const val LOOKUP_TIMEOUT_MILLIS = 5_000L

private fun unwrap(error: Throwable): Throwable = (error as? InvocationTargetException)?.targetException ?: error

private fun Class<*>.onlyStaticVoid(parameterNames: List<String>): Method {
    val found = resolve().optional(silent = true).method {
        modifiers(Modifiers.STATIC)
        returnType = Void.TYPE
        parameters { types -> types.map(Class<*>::getName) == parameterNames }
    }
    check(found.size == 1) { "Expected one request method with ${parameterNames.size} parameters in $name, found ${found.size}" }
    return found.single().self.apply { isAccessible = true }
}

// What a host callback that returns kotlin.Unit must return: the host's own Unit instance.
private fun hostUnit(returnType: Class<*>): Any? =
    if (returnType == Void.TYPE) null else returnType.resolve().firstField { name = "INSTANCE" }.get()

// The host's own network provider: it signs requests and supplies the common parameters and the login state, so no
// independent HTTP client is used. It exists only after the host's bridge runtime started, which a native page does.
private class NetworkProvider(hostClassLoader: ClassLoader) {
    private val provider: Method = RUNTIME.toClass(hostClassLoader).resolve().optional(silent = true).method {
        name = "getHostNetworkDepend"
        emptyParameters()
        returnType { it.name == DEPEND }
    }.also { check(it.size == 1) { "Expected one getHostNetworkDepend in $RUNTIME, found ${it.size}" } }.single().self.apply { isAccessible = true }

    fun get(): Any {
        val runtime = provider.declaringClass.resolve().firstField { name = "INSTANCE" }.get()
            ?: error("The host bridge runtime is unavailable")
        return try { provider.invoke(runtime) } catch (error: InvocationTargetException) { throw unwrap(error) }
            ?: error("The host network bridge is not initialized; open the native pet page first")
    }

    private companion object {
        const val RUNTIME = "com.bytedance.sdk.xbridge.cn.runtime.depend.XBaseRuntime"
        const val DEPEND = "com.bytedance.sdk.xbridge.cn.runtime.depend.IHostNetworkDepend"
    }
}

// Spark requests go through the host's XBridge request helper, which adds the common parameters and the login state.
// There are no retries, and a request in flight is never issued twice.
internal class SparkHost(
    private val hostClassLoader: ClassLoader,
    private val account: ImAccount,
    private val active: () -> Boolean,
) : SparkTransport {
    private class Members(val get: Method, val post: Method)

    // A failure is not cached: the next request looks again.
    private val members by lazy {
        val utils = UTILS.toClass(hostClassLoader)
        Members(utils.onlyStaticVoid(GET_PARAMETERS), utils.onlyStaticVoid(POST_PARAMETERS))
    }
    private val network by lazy { NetworkProvider(hostClassLoader) }

    override fun accountId(): Long {
        check(active()) { "The spark network bridge is inactive" }
        return account.id()
    }

    override fun request(url: String, body: Map<String, Any?>?, beforeDispatch: () -> Unit): Map<String, Any?> {
        val requestUrl = requestUrl(url, body != null)
        val method = if (body == null) members.get else members.post
        val host = network.get()
        val callbackIndex = if (body == null) 2 else 4
        check(method.parameterTypes[callbackIndex + 1].isInstance(host)) { "The host network provider type changed" }
        val answer = ArrayBlockingQueue<Result<Map<String, Any?>>>(1)
        val callbackType = method.parameterTypes[callbackIndex]
        val callback = Proxy.newProxyInstance(hostClassLoader, arrayOf(callbackType)) { proxy, event, args ->
            when (event.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "NukeSparkResponse"
                "onSuccess" -> {
                    answer.offer(runCatching {
                        // XBridge forwards the HTTP status code as the third argument.
                        val status = (args?.getOrNull(2) as? Number)?.toInt()
                        if (status == null || status !in 200..299) throw ClaimTransportException("The spark HTTP response failed ($status)")
                        val text = args[0]?.toString() ?: throw ClaimTransportException("The spark HTTP response body is unavailable")
                        MiniJson.obj(MiniJson.parse(text)) ?: throw ClaimTransportException("The spark HTTP response is not an object")
                    })
                    null
                }
                "onFailed" -> {
                    answer.offer(Result.failure(ClaimTransportException("The host spark request failed", args?.getOrNull(2) as? Throwable)))
                    null
                }
                "onParsingFailed" -> {
                    answer.offer(Result.failure(ClaimTransportException("The host could not decode the spark response", args?.getOrNull(3) as? Throwable)))
                    // The callback interface returns the host's kotlin.Unit, not ours.
                    hostUnit(event.returnType)
                }
                else -> null
            }
        }
        beforeDispatch()
        try {
            if (body == null) {
                method.invoke(null, requestUrl, linkedMapOf<String, String>(), callback, host, true, false, -1)
            } else {
                method.invoke(null, requestUrl, linkedMapOf("Content-Type" to "application/json"), "application/json",
                    JSONObject(MiniJson.write(body)),
                    callback, host, true, false, -1)
            }
        } catch (error: InvocationTargetException) {
            throw ClaimTransportException("The host spark request could not be started", unwrap(error))
        }
        val outcome = answer.poll(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            ?: throw ClaimTransportException("The host spark request timed out; the outcome is unknown")
        return outcome.getOrThrow()
    }

    // Only the two spark endpoints on the host's API domain may be requested, each with its own method.
    private fun requestUrl(url: String, post: Boolean): String {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.rawAuthority == "api.amemv.com" && uri.rawFragment == null) { "Unsupported spark request host" }
        require(if (post) uri.rawPath == "/aweme/im/growth/pet/task/reward" && uri.rawQuery == null
            else uri.rawPath == "/aweme/v1/im_communication/flame_achieve/get/") { "Unsupported spark request path or method" }
        val separator = if (uri.rawQuery == null) "?" else "&"
        return "$url${separator}scm_version=1.0.0.666&scm_version_v2=1.0.0.666"
    }

    private companion object {
        const val UTILS = "com.bytedance.sdk.xbridge.cn.runtime.utils.XBridgeAPIRequestUtils"
        const val CALLBACK = "com.bytedance.sdk.xbridge.cn.runtime.utils.IResponseCallback"
        const val DEPEND = "com.bytedance.sdk.xbridge.cn.runtime.depend.IHostNetworkDepend"
        val GET_PARAMETERS = listOf("java.lang.String", "java.util.Map", CALLBACK, DEPEND, "boolean", "boolean", "int")
        val POST_PARAMETERS = listOf("java.lang.String", "java.util.Map", "java.lang.String", "org.json.JSONObject",
            CALLBACK, DEPEND, "boolean", "boolean", "int")
    }
}

// Closeness and feeding requests are the pet page's own Retrofit calls, made with the host's authenticated Retrofit factory so
// signing, the device and the account come from the host. The conversation comes from the host's memory or database.
internal class PetHost(
    private val hostClassLoader: ClassLoader,
    private val account: ImAccount,
    private val models: ConversationModels,
    private val active: () -> Boolean,
    private val logger: Logger,
) : ClosenessTransport {
    private class Members(val post: Method, val retrofitV2: Method)

    private val members by lazy {
        val post = RUNTIME_API.toClass(hostClassLoader).resolve().optional(silent = true).method {
            name = "doPostForString"
            parameters { types -> types.map(Class<*>::getName) == POST_PARAMETERS }
        }.also { check(it.size == 1) { "Expected one doPostForString in $RUNTIME_API, found ${it.size}" } }.single().self
        val v2 = DEPEND_V2.toClass(hostClassLoader).resolve().optional(silent = true).method {
            parameters { types -> types.map(Class<*>::getName) == listOf("java.lang.String", "java.util.Map") }
        }.also { check(it.size == 1) { "Expected one Retrofit factory in $DEPEND_V2, found ${it.size}" } }.single().self
        Members(post.apply { isAccessible = true }, v2.apply { isAccessible = true })
    }
    private val network by lazy { NetworkProvider(hostClassLoader) }

    override fun accountId(): Long {
        check(active()) { "The pet network bridge is inactive" }
        return account.id()
    }

    override fun resolve(conversationId: String, account: Long): ClosenessBinding {
        check(accountId() == account) { "Account changed before the pet lookup" }
        val model = models.current(account)
        val conversation = cachedConversation(model, conversationId) ?: databaseConversation(model, conversationId)
        check(conversation.callByName("isGroupChat") == false && conversation.callByName("isSingleChat") == true &&
            conversation.callByName("getConversationId") == conversationId &&
            (conversation.callByName("getDeleted") as? Number)?.toInt() == 0) { "Expected the selected non-deleted private conversation" }
        val shortId = (conversation.callByName("getConversationShortId") as? Number)?.toLong()?.takeIf { it > 0 }
            ?: error("The private conversation has no valid server short ID")
        check(accountId() == account) { "Account changed during the pet lookup" }
        return ClosenessBinding(account, shortId.toString())
    }

    private fun cachedConversation(model: Any, id: String): Any? = try {
        model.asResolver().firstMethod { name = "getConversation"; parameters(String::class); superclass() }.invoke(id)
    } catch (error: InvocationTargetException) {
        throw unwrap(error)
    }

    // Asks the host database for a chat that is not in memory. It never opens UI or creates a conversation.
    private fun databaseConversation(model: Any, id: String): Any {
        val lookup = model.asResolver().optional(silent = true).firstMethodOrNull {
            name = "getConversationAsync"
            parameterCount = 2
            modifiers(Modifiers.PUBLIC)
            superclass(interfaces = true)
        } ?: throw NoSuchMethodException("${model.javaClass.name}.getConversationAsync")
        val answer = ArrayBlockingQueue<Result<Any?>>(1)
        val listener = lookup.self.parameterTypes[1]
        val callback = Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { proxy, event, args ->
            when (event.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "NukeConversationLookup"
                "onSuccess" -> { answer.offer(Result.success(args?.firstOrNull())); null }
                "onFailure" -> { answer.offer(Result.failure(IllegalStateException("The host conversation database lookup failed"))); null }
                else -> null
            }
        }
        try { lookup.invoke(id, callback) } catch (error: InvocationTargetException) { throw unwrap(error) }
        val found = answer.poll(LOOKUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            ?: throw ClaimTransportException("The host private conversation lookup timed out")
        return found.getOrThrow() ?: error("The private conversation was not found in the host database")
    }

    override fun post(binding: ClosenessBinding, path: String, body: Map<String, Any?>, beforeDispatch: () -> Unit): Map<String, Any?> {
        require(path in PATHS) { "Unsupported pet request path" }
        check(accountId() == binding.account) { "Account changed before the pet request" }
        val host = network.get()
        val common = host.callByName("getAPIParams") as? Map<*, *> ?: error("The host network common parameters are unavailable")
        val deviceId = common["device_id"]?.toString()?.toLongOrNull()?.takeIf { it > 0 } ?: error("The host device ID is unavailable")
        // Retrofit's QueryMap takes strings; the body keeps the native page's numeric types.
        val query = linkedMapOf(
            "conversation_short_id" to binding.shortId, "aid" to "1128", "device_id" to deviceId.toString(),
            "user_id" to binding.account.toString(), "enter_from" to "", "scm_version" to "0.0.0.0",
        )
        val payload = LinkedHashMap(body).apply {
            put("conversation_short_id", binding.shortId)
            put("aid", 1128L)
            put("device_id", deviceId)
            put("user_id", binding.account)
            put("enter_from", "")
            put("scm_version", "0.0.0.0")
        }
        val retrofit = if (members.retrofitV2.declaringClass.isInstance(host)) {
            members.retrofitV2.invoke(host, BASE_URL, linkedMapOf("isCustomizedCookie" to false, "needAddCommonParams" to true))
        } else host.callByName("createRetrofit", BASE_URL, false)
        val api = retrofit?.callByName("create", members.post.declaringClass) ?: error("The native pet Retrofit service is unavailable")
        val header = "com.bytedance.retrofit2.client.Header".toClass(hostClassLoader).resolve()
            .firstConstructor { parameters(String::class, String::class) }
            .create("Content-Type", "application/json")
        val content = "com.bytedance.retrofit2.mime.TypedByteArray".toClass(hostClassLoader).resolve()
            .constructor { parameterCount = 3 }.single()
            .create("application/json", MiniJson.write(payload).toByteArray(Charsets.UTF_8), emptyArray<String>())
        val context = "com.bytedance.ttnet.http.RequestContext".toClass(hostClassLoader).resolve()
            .firstConstructor { emptyParameters() }.create()
        for (name in listOf("timeout_connect", "timeout_read", "timeout_write")) setField(context, name, REQUEST_TIMEOUT_MILLIS)
        setField(context, "force_handle_response", false)
        // -1 is the native maximum-length argument, not a network timeout.
        val call = members.post.invoke(api, true, -1, path, query, listOf(header), content, context) ?: error("No native pet call")
        val answer = ArrayBlockingQueue<Result<Map<String, Any?>>>(1)
        val callbackType = "com.bytedance.retrofit2.Callback".toClass(hostClassLoader)
        val callback = Proxy.newProxyInstance(hostClassLoader, arrayOf(callbackType)) { proxy, event, args ->
            when (event.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "NukePetResponse"
                "onResponse" -> {
                    answer.offer(runCatching { decode(args?.getOrNull(1) ?: throw ClaimTransportException("The pet HTTP response is unavailable")) })
                    null
                }
                "onFailure" -> {
                    val failure = args?.getOrNull(1) as? Throwable
                    answer.offer(Result.failure(ClaimTransportException("The host pet request failed: ${failure?.javaClass?.simpleName ?: "unknown error"}", failure)))
                    null
                }
                else -> null
            }
        }
        try {
            beforeDispatch()
            check(accountId() == binding.account) { "Account changed before the pet request" }
        } catch (error: Exception) {
            cancel(call)
            throw error
        }
        try {
            call.callByName("enqueue", callback)
        } catch (error: Exception) {
            cancel(call)
            throw ClaimNotDispatchedException("The host pet request could not be started", error)
        }
        val outcome = answer.poll(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        if (outcome == null) {
            cancel(call)
            throw ClaimTransportException("The host pet request timed out; the outcome is unknown")
        }
        return outcome.getOrThrow()
    }

    private fun decode(response: Any): Map<String, Any?> {
        val status = (response.callByName("code") as? Number)?.toInt()
        if (status == null || status !in 200..299) throw ClaimTransportException("The pet HTTP response failed ($status)")
        val text = response.callByName("body") as? String ?: throw ClaimTransportException("The pet HTTP response body is unavailable")
        return MiniJson.obj(MiniJson.parse(text)) ?: throw ClaimTransportException("The pet HTTP response is not an object")
    }

    // A failure of the host's own cancellation must not hide the real error.
    private fun cancel(call: Any) {
        try { call.callByName("cancel") } catch (error: Exception) { logger.log("component=claim-network cancel_failed ${error.javaClass.simpleName}") }
    }

    private fun setField(target: Any, name: String, value: Any) {
        target.asResolver().firstField { this.name = name; superclass() }.set(value)
    }

    private companion object {
        const val BASE_URL = "https://stark-gate.zijieapi.com"
        const val READ_PATH = "/ttgame/social_pet/home/info"
        const val CLAIM_PATH = "/ttgame/social_pet/closeness/claim"
        // The closeness claim and the feeding read and write; the parameters are the same for all of them.
        val PATHS = setOf(READ_PATH, CLAIM_PATH, "/ttgame/social_pet/feed/get", "/ttgame/social_pet/feed")
        const val RUNTIME_API = "com.bytedance.sdk.xbridge.cn.runtime.api.IHostNetworkRuntimeApi"
        const val DEPEND_V2 = "com.bytedance.sdk.xbridge.cn.runtime.depend.IHostNetworkDependV2"
        val POST_PARAMETERS = listOf("boolean", "int", "java.lang.String", "java.util.Map", "java.util.List",
            "com.bytedance.retrofit2.mime.TypedOutput", "java.lang.Object")
    }
}
