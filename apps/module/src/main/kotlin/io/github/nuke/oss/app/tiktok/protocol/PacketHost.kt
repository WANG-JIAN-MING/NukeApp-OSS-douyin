package io.github.nuke.oss.app.tiktok.protocol

import com.android.dx.stock.ProxyBuilder
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.fields
import io.github.nuke.oss.app.shared.dex.methods
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

// Sends one raw request through the Douyin IM SDK by subclassing its native request handler per request.
// Nothing hooks host traffic and nothing is reused between requests. Host shapes are resolved lazily at the
// first dispatch from unique Dex queries; no Douyin version has been verified against them on a device.
internal class PacketHost(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    // Directory for dexmaker's generated proxy classes, inside the host's private storage.
    private val proxyCacheDir: File,
) : PacketTransport {
    private class Members(
        val sdkContext: Method,
        val commandRoute: Method,
        val dispatch: Method,
        val handle: Method,
        val checkResponse: Method,
        val buildBody: Method,
        val responseField: Field,
        val requestField: Field,
    )

    // A failure is not cached: a later dispatch retries the lookup.
    private val members by lazy(::resolveMembers)

    // Module classes must be visible to the generated subclass next to the host classes it extends.
    private val proxyClassLoader = object : ClassLoader(hostClassLoader) {
        override fun findClass(name: String): Class<*> = PacketHost::class.java.classLoader!!.loadClass(name)
    }

    override fun dispatch(request: PacketRequest, callback: (ProtocolResult<PacketResponse>) -> Unit): PacketCall {
        val client = requireLoggedInClient()
        val found = try {
            members
        } catch (error: Exception) {
            throw ProtocolException(ProtocolError(ProtocolErrorCode.DEX_TARGET_UNAVAILABLE,
                "Protocol Dex queries are not ready: ${error.message}", error))
        }
        val sdkContext = found.sdkContext.invoke(client) ?: error("IM SDK context is unavailable")
        val endpoint = request.endpoint
        try {
            val route = found.commandRoute.invoke(null, sdkContext, endpoint.cmd)
            check(route is String && route.isNotBlank()) { "No native command route" }
        } catch (error: InvocationTargetException) {
            throw ProtocolException(ProtocolError(ProtocolErrorCode.UNSUPPORTED_COMMAND,
                "Host SDK has no route for cmd ${endpoint.cmd}", error.targetException))
        }
        val body = try {
            OpaqueBody.create(found.buildBody.declaringClass, "okio.ByteString".toClass(hostClassLoader),
                found.buildBody, request.requestBody)
        } catch (error: Exception) {
            throw ProtocolException(error.asProtocolError(ProtocolErrorCode.SERIALIZE_FAILED))
        }
        val delivery = AtomicReference<((ProtocolResult<PacketResponse>) -> Unit)?>(callback)
        val deliver: (ProtocolResult<PacketResponse>) -> Unit = { delivery.getAndSet(null)?.invoke(it) }
        val listenerType = LISTENER_CLASS.toClass(hostClassLoader)
        val handler = ProxyBuilder.forClass(found.dispatch.declaringClass)
            .parentClassLoader(proxyClassLoader)
            .dexCache(proxyCacheDir.apply { check(isDirectory || mkdirs()) { "Cannot create proxy Dex cache" } })
            .onlyMethods(arrayOf(found.handle, found.checkResponse))
            .constructorArgTypes(Int::class.javaPrimitiveType!!, SDK_CONTEXT_CLASS.toClass(hostClassLoader), listenerType)
            .constructorArgValues(endpoint.cmd, sdkContext, failureListener(listenerType, deliver))
            .handler(InvocationHandler { proxy, method, args -> handle(found, endpoint, deliver, delivery, proxy, method, args) })
            .build()
        try {
            found.dispatch.invoke(handler, endpoint.inboxType, body, emptyArray<Any>())
        } catch (error: Exception) {
            delivery.set(null)
            throw error
        }
        return PacketCall { delivery.set(null) }
    }

    private fun handle(
        found: Members,
        endpoint: PacketEndpoint,
        deliver: (ProtocolResult<PacketResponse>) -> Unit,
        delivery: AtomicReference<((ProtocolResult<PacketResponse>) -> Unit)?>,
        proxy: Any,
        method: Method,
        args: Array<Any?>?,
    ): Any? = when (method) {
        found.checkResponse -> runCatching {
            val response = args?.firstOrNull()?.let(found.responseField::get)
            response != null && response.readField("status_code") == 0
        }.getOrDefault(false)
        found.handle -> {
            try {
                if (delivery.get() != null) deliverResponse(found, endpoint, deliver, requireNotNull(args?.firstOrNull()) { "No request context" })
            } catch (error: Exception) {
                deliver(ProtocolResult.Err(error.asProtocolError(ProtocolErrorCode.RESPONSE_DECODE_FAILED)))
            } finally {
                (args?.getOrNull(1) as? Runnable)?.run()
            }
            null
        }
        else -> ProxyBuilder.callSuper(proxy, method, *(args ?: emptyArray()))
    }

    private fun deliverResponse(
        found: Members,
        endpoint: PacketEndpoint,
        deliver: (ProtocolResult<PacketResponse>) -> Unit,
        context: Any,
    ) {
        val response = found.responseField.get(context)
        if (response == null) {
            deliver(ProtocolResult.Err(ProtocolError(ProtocolErrorCode.HOST_CALLBACK_FAILED,
                "Host completed the request without a Response envelope")))
            return
        }
        val origin = requireNotNull(found.requestField.get(context)) { "No originating Request" }
        val requestSequence = origin.readField("sequence_id") as? Long
        val responseCmd = response.readField("cmd") as? Int
        val responseSequence = response.readField("sequence_id") as? Long
        native {
            correlate(endpoint.cmd, presence(requestSequence, responseCmd, responseSequence),
                requestSequence ?: 0, responseCmd ?: 0, responseSequence ?: 0)
        }
        deliver(ProtocolResult.Ok(PacketResponse(endpoint, OpaqueBody.encode(response))))
    }

    private fun presence(requestSequence: Long?, responseCmd: Int?, responseSequence: Long?): Int =
        (if (requestSequence != null) NativeProtocol.HAS_REQUEST_SEQUENCE else 0) or
            (if (responseCmd != null) NativeProtocol.HAS_RESPONSE_CMD else 0) or
            (if (responseSequence != null) NativeProtocol.HAS_RESPONSE_SEQUENCE else 0)

    private fun resolveMembers(): Members {
        val (sdkContext, commandRoute, dispatch, handle, checkResponse, buildBody) = resolver.methods(hostClassLoader,
            ProtocolQueries.SDK_CONTEXT, ProtocolQueries.COMMAND_ROUTE, ProtocolQueries.RAW_DISPATCH,
            ProtocolQueries.HANDLE_RESPONSE, ProtocolQueries.CHECK_RESPONSE, ProtocolQueries.BUILD_BODY)
        val (responseField, requestField) = resolver.fields(hostClassLoader,
            ProtocolQueries.RESPONSE_FIELD, ProtocolQueries.REQUEST_FIELD)
        listOf(sdkContext, commandRoute, dispatch, handle, checkResponse, buildBody).forEach { it.isAccessible = true }
        listOf(responseField, requestField).forEach { it.isAccessible = true }
        // The handler's three members and the per-request context must belong together.
        check(dispatch.declaringClass == handle.declaringClass && handle.declaringClass == checkResponse.declaringClass) {
            "Dispatch and callback methods belong to different classes"
        }
        check(handle.parameterTypes[0] == checkResponse.parameterTypes[0] &&
            responseField.declaringClass == handle.parameterTypes[0] && requestField.declaringClass == handle.parameterTypes[0]) {
            "Request context fields do not belong to the callback parameter"
        }
        check(Runnable::class.java.isAssignableFrom(handle.parameterTypes[1])) { "Callback completion is not a Runnable" }
        return Members(sdkContext, commandRoute, dispatch, handle, checkResponse, buildBody, responseField, requestField)
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    private fun requireLoggedInClient(): Any {
        val manager = "com.bytedance.ies.im.core.sdk.SDKManager".toClass(hostClassLoader).resolve()
            .firstField { name = "INSTANCE"; superclass() }.get() ?: error("SDK manager is unavailable")
        val client = manager.invokeNoArg("getImSdkClient") ?: error("SDK client is unavailable")
        val environment = client.invokeNoArg("getIIMSdkEnvService") ?: error("SDK environment is unavailable")
        if (environment.invokeNoArg("isLogin") != true) fail(ProtocolErrorCode.NOT_LOGGED_IN, "IM SDK is not logged in")
        return client
    }

    private fun Any.invokeNoArg(name: String): Any? =
        asResolver().firstMethod { this.name = name; emptyParameters(); superclass() }.invoke()

    private fun Any.readField(name: String): Any? = asResolver().firstField { this.name = name; superclass() }.get()

    // Only onFailure is expected when the request fails before a Response; any other callback is an error result.
    private fun failureListener(type: Class<*>, deliver: (ProtocolResult<PacketResponse>) -> Unit): Any =
        Proxy.newProxyInstance(hostClassLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "NukeTikTokProtocolCallback"
                else -> {
                    try {
                        if (method.name == "onFailure") {
                            val error = args?.firstOrNull()
                            deliver(ProtocolResult.Err(ProtocolError(ProtocolErrorCode.HOST_CALLBACK_FAILED,
                                "Host SDK request failed", error?.invokeNoArg("getThrowable") as? Throwable,
                                error?.invokeNoArg("getCode") as? Int)))
                        } else {
                            deliver(ProtocolResult.Err(ProtocolError(ProtocolErrorCode.HOST_CALLBACK_FAILED,
                                "Unexpected typed SDK callback: ${method.name}")))
                        }
                    } catch (error: Exception) {
                        deliver(ProtocolResult.Err(error.asProtocolError(ProtocolErrorCode.RESPONSE_DECODE_FAILED)))
                    }
                    null
                }
            }
        }

    private fun fail(code: ProtocolErrorCode, message: String): Nothing = throw ProtocolException(ProtocolError(code, message))
}
