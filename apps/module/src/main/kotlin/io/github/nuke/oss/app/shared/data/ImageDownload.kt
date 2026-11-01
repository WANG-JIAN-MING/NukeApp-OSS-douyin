package io.github.nuke.oss.app.shared.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_BYTES = 2 * 1024 * 1024
private const val TIMEOUT_MS = 5_000

// Downloads one image over HTTP(S), blocking; callers run it off the main thread. Bounded in time (5 s to connect and
// to read) and size (2 MiB), kept only in memory, and nothing about it is logged. Null when it cannot be had.
internal fun downloadImage(url: String): ByteArray? {
    val connection = try {
        URL(url).openConnection() as? HttpURLConnection
    } catch (_: IOException) {
        null
    } ?: return null
    return try {
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        if (connection.responseCode != HttpURLConnection.HTTP_OK || connection.contentLengthLong > MAX_BYTES) null
        else connection.inputStream.use(::readBounded)
    } catch (_: IOException) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun readBounded(input: InputStream): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return output.toByteArray()
        if (output.size() + read > MAX_BYTES) return null
        output.write(buffer, 0, read)
    }
}
