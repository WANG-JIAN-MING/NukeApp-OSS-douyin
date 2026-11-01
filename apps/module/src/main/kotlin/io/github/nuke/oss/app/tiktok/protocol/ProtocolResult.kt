package io.github.nuke.oss.app.tiktok.protocol

enum class ProtocolErrorCode {
    INVALID_ARGUMENT,
    UNSUPPORTED_COMMAND,
    NOT_LOGGED_IN,
    DEX_TARGET_UNAVAILABLE,
    HOST_RESOLUTION_FAILED,
    HOST_CALLBACK_FAILED,
    SERIALIZE_FAILED,
    RESPONSE_DECODE_FAILED,
    INTERCEPTOR_FAILED,
    NATIVE_UNAVAILABLE,
    INTERNAL,
    TIMEOUT,
}

data class ProtocolError(
    val code: ProtocolErrorCode,
    val message: String,
    val cause: Throwable? = null,
    val hostCode: Int? = null,
)

sealed interface ProtocolResult<out T> {
    data class Ok<T>(val value: T) : ProtocolResult<T>
    data class Err(val error: ProtocolError) : ProtocolResult<Nothing>

    fun errorOrNull(): ProtocolError? = (this as? Err)?.error
}

internal class ProtocolException(val error: ProtocolError) : RuntimeException(error.message, error.cause)

internal fun Throwable.asProtocolError(code: ProtocolErrorCode): ProtocolError =
    if (this is ProtocolException) error else ProtocolError(code, message ?: javaClass.name, this)
