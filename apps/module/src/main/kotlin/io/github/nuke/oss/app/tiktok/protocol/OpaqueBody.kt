package io.github.nuke.oss.app.tiktok.protocol

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import java.lang.reflect.Method

// Keeps every supplied byte in Wire's unknown-fields buffer, even tags the host schema knows.
internal object OpaqueBody {
    fun create(builderType: Class<*>, byteStringType: Class<*>, build: Method, bytes: ByteArray): Any {
        val builder = builderType.resolve().firstConstructor { emptyParameters() }.create()
        val byteString = byteStringType.resolve().firstConstructor { parameters(ByteArray::class) }.create(bytes.copyOf())
        builder.asResolver().firstMethod {
            name = "addUnknownFields"
            parameters(byteStringType)
            superclass()
        }.invoke(byteString)
        val body = build.apply { isAccessible = true }.invoke(builder) ?: error("RequestBody is unavailable")
        check(encode(body).contentEquals(bytes)) { "Host codec changed the supplied RequestBody bytes" }
        return body
    }

    fun encode(message: Any): ByteArray =
        message.asResolver().firstMethod { name = "encode"; emptyParameters(); superclass() }.invoke() as ByteArray
}
