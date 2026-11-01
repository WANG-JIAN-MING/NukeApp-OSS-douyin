package io.github.nuke.oss.script.module

import io.github.nuke.oss.script.ScriptException
import io.github.nuke.oss.script.ScriptModule
import io.github.nuke.oss.script.ScriptModuleContext
import io.github.nuke.oss.script.bytesAt
import io.github.nuke.oss.script.longAt
import io.github.nuke.oss.script.optionalBytesAt
import io.github.nuke.oss.script.stringAt
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Byte results reach scripts as Uint8Array; inputs accept a Uint8Array, an Int8Array or an array of byte values, and
// the text-or-bytes parameters also accept a string, encoded as UTF-8.
// java.util.HexFormat would be shorter but Android only has it from API 34.
private const val LOWER_HEX = "0123456789abcdef"
private const val UPPER_HEX = "0123456789ABCDEF"

internal object EncodingModule : ScriptModule {
    override val name = "nuke:encoding"
    override val source = """
const n = globalThis;
const c = __nuke.check;
export function encodeUtf8(text) { return c(n.__nk_encoding_utf8_encode(String(text))); }
export function decodeUtf8(bytes) { return c(n.__nk_encoding_utf8_decode(bytes)); }
export function encodeBase64(data, options) { return c(n.__nk_encoding_base64_encode(data, !!(options && options.url))); }
export function decodeBase64(text) { return c(n.__nk_encoding_base64_decode(String(text))); }
export function encodeHex(data, uppercase) { return c(n.__nk_encoding_hex_encode(data, !!uppercase)); }
export function decodeHex(text) { return c(n.__nk_encoding_hex_decode(String(text))); }
"""

    override fun bind(context: ScriptModuleContext) {
        context.function("encoding_utf8_encode") { args -> args.stringAt(0, "text").toByteArray() }
        context.function("encoding_utf8_decode") { args -> args.bytesAt(0, "bytes", text = false).decodeToString() }
        context.function("encoding_base64_encode") { args ->
            val encoder = if (args.getOrNull(1) == true) Base64.getUrlEncoder().withoutPadding() else Base64.getEncoder()
            encoder.encodeToString(args.bytesAt(0, "data"))
        }
        context.function("encoding_base64_decode") { args ->
            val text = args.stringAt(0, "text").trim()
            try {
                if ('-' in text || '_' in text) Base64.getUrlDecoder().decode(text) else Base64.getDecoder().decode(text)
            } catch (error: IllegalArgumentException) {
                throw ScriptException.argument("Invalid Base64: ${error.message}")
            }
        }
        context.function("encoding_hex_encode") { args ->
            val digits = if (args.getOrNull(1) == true) UPPER_HEX else LOWER_HEX
            buildString {
                for (byte in args.bytesAt(0, "data")) {
                    append(digits[byte.toInt() shr 4 and 0xF])
                    append(digits[byte.toInt() and 0xF])
                }
            }
        }
        context.function("encoding_hex_decode") { args ->
            val text = args.stringAt(0, "text")
            if (text.length % 2 != 0) throw ScriptException.argument("Invalid hex: odd length")
            ByteArray(text.length / 2) { index ->
                val high = Character.digit(text[index * 2], 16)
                val low = Character.digit(text[index * 2 + 1], 16)
                if (high < 0 || low < 0) throw ScriptException.argument("Invalid hex digit near position ${index * 2}")
                (high shl 4 or low).toByte()
            }
        }
    }
}

internal object CryptoModule : ScriptModule {
    override val name = "nuke:crypto"
    override val source = """
const n = globalThis;
const c = __nuke.check;
export function randomBytes(length) { return c(n.__nk_crypto_random(length)); }
export function randomUUID() { return c(n.__nk_crypto_uuid()); }
export function digest(algorithm, data) { return c(n.__nk_crypto_digest(String(algorithm), data)); }
export function hmac(algorithm, key, data) { return c(n.__nk_crypto_hmac(String(algorithm), key, data)); }
export function timingSafeEqual(left, right) { return c(n.__nk_crypto_equal(left, right)); }
export function aesGcmEncrypt(options) { return c(n.__nk_crypto_aes_gcm(true, options.key, options.iv, options.data, options.aad)); }
export function aesGcmDecrypt(options) { return c(n.__nk_crypto_aes_gcm(false, options.key, options.iv, options.data, options.aad)); }
"""

    private val random = SecureRandom()
    private val DIGESTS = mapOf("MD5" to "MD5", "SHA-1" to "SHA-1", "SHA-256" to "SHA-256", "SHA-512" to "SHA-512")
    private val MACS = mapOf("SHA-1" to "HmacSHA1", "SHA-256" to "HmacSHA256", "SHA-512" to "HmacSHA512")
    private const val MAX_RANDOM = 64 * 1024

    override fun bind(context: ScriptModuleContext) {
        context.function("crypto_random") { args ->
            val length = args.longAt(0, "length")
            if (length !in 0..MAX_RANDOM) throw ScriptException.argument("length must be between 0 and $MAX_RANDOM")
            ByteArray(length.toInt()).also(random::nextBytes)
        }
        context.function("crypto_uuid") { UUID.randomUUID().toString() }
        context.function("crypto_digest") { args ->
            val algorithm = DIGESTS[args.stringAt(0, "algorithm").uppercase()]
                ?: throw ScriptException.argument("Unsupported digest; use one of ${DIGESTS.keys}")
            MessageDigest.getInstance(algorithm).digest(args.bytesAt(1, "data"))
        }
        context.function("crypto_hmac") { args ->
            val algorithm = MACS[args.stringAt(0, "algorithm").uppercase()]
                ?: throw ScriptException.argument("Unsupported HMAC; use one of ${MACS.keys}")
            val key = args.bytesAt(1, "key")
            if (key.isEmpty()) throw ScriptException.argument("key must not be empty")
            Mac.getInstance(algorithm).run {
                init(SecretKeySpec(key, algorithm))
                doFinal(args.bytesAt(2, "data"))
            }
        }
        context.function("crypto_equal") { args ->
            MessageDigest.isEqual(args.bytesAt(0, "left", text = false), args.bytesAt(1, "right", text = false))
        }
        context.function("crypto_aes_gcm") { args ->
            val encrypt = args.getOrNull(0) == true
            val key = args.bytesAt(1, "key", text = false)
            val iv = args.bytesAt(2, "iv", text = false)
            if (key.size !in setOf(16, 24, 32)) throw ScriptException.argument("key must be 16, 24 or 32 bytes")
            if (iv.size !in 12..16) throw ScriptException.argument("iv must be 12 to 16 bytes")
            try {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                    args.optionalBytesAt(4, "aad")?.let(::updateAAD)
                    doFinal(args.bytesAt(3, "data"))
                }
            } catch (error: GeneralSecurityException) {
                throw ScriptException.argument("AES-GCM ${if (encrypt) "encryption" else "decryption"} failed: ${error.message}")
            }
        }
    }
}
