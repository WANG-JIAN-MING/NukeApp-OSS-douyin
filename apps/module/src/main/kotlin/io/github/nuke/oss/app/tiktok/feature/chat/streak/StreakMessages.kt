package io.github.nuke.oss.app.tiktok.feature.chat.streak

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

// Chooses the day's message. Read-only: the engine stores the result before any friend is claimed.
internal object StreakMessages {
    // The journal's record of an interactive day; the content itself is built for each friend at send time.
    const val INTERACTIVE_MARK = "[interactive streak emoji]"

    fun prepare(plan: StreakPlan, fetchSentence: (String) -> String, checkInteractive: () -> Unit): PreparedMessage = when (plan.mode) {
        StreakMode.TEXT -> PreparedMessage(plan.text)
        StreakMode.INTERACTIVE -> {
            checkInteractive()
            PreparedMessage(INTERACTIVE_MARK)
        }
        // A sentence that cannot be fetched falls back to the text, and the day records why.
        StreakMode.HITOKOTO -> try {
            val sentence = fetchSentence(plan.hitokotoUrl)
            check(sentence.isNotBlank() && sentence.length <= StreakPlan.MAX_TEXT) { "The sentence API returned an invalid sentence" }
            PreparedMessage(sentence)
        } catch (error: IOException) {
            PreparedMessage(plan.text, usedFallback = true, detail = "Sentence fallback: ${error.message ?: error.javaClass.simpleName}".take(160))
        } catch (error: IllegalStateException) {
            PreparedMessage(plan.text, usedFallback = true, detail = "Sentence fallback: ${error.message}".take(160))
        }
    }
}

// Fetches one sentence from a Hitokoto-style API that answers with plain text, such as the default address. Uses the
// platform HTTP client with no cookies, redirects or retries; a failure names no address, since a custom one may hold
// a key. The body is capped and must be valid UTF-8.
internal object HitokotoClient {
    const val DEFAULT_URL = "https://v1.hitokoto.cn/?encode=text"
    private const val MAX_RESPONSE_BYTES = 32 * 1024
    private const val TIMEOUT_MS = 5_000

    fun isValidUrl(value: String): Boolean {
        if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) return false
        if (value.any { it.isWhitespace() || it <= '\u001f' || it == '\u007f' }) return false
        return try {
            !URI(value).host.isNullOrBlank()
        } catch (_: java.net.URISyntaxException) {
            false
        }
    }

    fun fetch(url: String): String {
        if (!isValidUrl(url)) throw IOException("The sentence API address is invalid")
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            throw IOException("Sentence request failed")
        }
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("Sentence service HTTP $status")
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw IOException("Sentence response is too large")
            return parse(read(connection.inputStream))
        } catch (error: IOException) {
            throw if (error.message?.startsWith("Sentence") == true) error else IOException("Sentence request failed")
        } finally {
            connection.disconnect()
        }
    }

    internal fun read(input: InputStream): String = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            // One extra byte tells a body of exactly the limit from a longer one.
            val read = it.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - total + 1))
            if (read == -1) break
            total += read
            if (total > MAX_RESPONSE_BYTES) throw IOException("Sentence response is too large")
            output.write(buffer, 0, read)
        }
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IOException("Sentence response is not UTF-8")
        }
    }

    internal fun parse(sentence: String): String {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty() || trimmed.length > StreakPlan.MAX_TEXT) throw IOException("Sentence has an invalid length")
        return trimmed
    }
}
