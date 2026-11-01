package io.github.nuke.oss.app.tiktok.protocol

import java.util.concurrent.CopyOnWriteArrayList

// Only module-originated operations pass through this registry. A failing stage aborts the operation.
class PacketPipeline {
    private val preprocessors = CopyOnWriteArrayList<PacketPreprocessor>()
    private val interceptors = CopyOnWriteArrayList<PacketInterceptor>()

    fun add(preprocessor: PacketPreprocessor): Boolean = preprocessors.addIfAbsent(preprocessor)
    fun remove(preprocessor: PacketPreprocessor): Boolean = preprocessors.remove(preprocessor)
    fun add(interceptor: PacketInterceptor): Boolean = interceptors.addIfAbsent(interceptor)
    fun remove(interceptor: PacketInterceptor): Boolean = interceptors.remove(interceptor)
    fun clear() { preprocessors.clear(); interceptors.clear() }

    internal fun prepare(request: PacketRequest): PacketRequest {
        val prepared = preprocessors.firstOrNull { it.matches(request) }?.prepare(request) ?: request
        return interceptors.firstNotNullOfOrNull { it.onRequest(prepared) } ?: prepared
    }

    internal fun receive(response: PacketResponse): PacketResponse =
        interceptors.firstNotNullOfOrNull { it.onResponse(response) } ?: response
}
