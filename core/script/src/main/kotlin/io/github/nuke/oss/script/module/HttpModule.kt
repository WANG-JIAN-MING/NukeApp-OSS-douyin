package io.github.nuke.oss.script.module

import io.github.nuke.oss.script.ScriptException
import io.github.nuke.oss.script.ScriptModule
import io.github.nuke.oss.script.ScriptModuleContext
import io.github.nuke.oss.script.ScriptPermissions
import io.github.nuke.oss.script.longAt
import io.github.nuke.oss.script.optionalBytesAt
import io.github.nuke.oss.script.stringAt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

// nuke:http: requests to the origins the manifest lists, or anywhere with network: true. Every redirect is checked
// like the first request, and an address in a private range needs privateNetwork. The address is checked before
// connecting, so a host whose DNS answer changes in between is not caught; this guards against mistakes, not against
// a hostile resolver. The platform's HttpURLConnection does the transfer, so TLS uses the host's trust store.
class HttpModule(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
) : ScriptModule {
    override val name = "nuke:http"
    override val source = """
import { decodeUtf8 } from "nuke:encoding";
const n = globalThis;
const c = __nuke.check;

export class Response {
  constructor(raw) {
    this.status = raw.status;
    this.ok = raw.status >= 200 && raw.status < 300;
    this.url = raw.url;
    this.headers = Object.freeze(raw.headers);
    Object.defineProperty(this, "body", { value: raw.body });
  }
  bytes() { return this.body; }
  text() { return decodeUtf8(this.body); }
  json() { return JSON.parse(this.text()); }
}

export async function fetch(url, options) {
  const settings = options || {};
  const headers = {};
  for (const [key, value] of Object.entries(settings.headers || {})) headers[String(key)] = String(value);
  const raw = c(await n.__nk_http_fetch(
    String(url), String(settings.method || "GET").toUpperCase(), headers,
    settings.body === undefined ? null : settings.body,
    settings.timeoutMs === undefined ? null : settings.timeoutMs,
  ));
  return new Response(raw);
}
"""

    override fun bind(context: ScriptModuleContext) {
        val permissions = context.script.permissions
        val origins = permissions.networkOrigins.map(::originOf)
        context.asyncFunction("http_fetch") { args ->
            context.requirePermission(ScriptPermissions.NETWORK)
            val method = args.stringAt(1, "method")
            if (method !in METHODS) throw ScriptException.argument("Unsupported method $method")
            @Suppress("UNCHECKED_CAST")
            val headers = (args.getOrNull(2) as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() }
            val body = args.optionalBytesAt(3, "body")
            val timeout = if (args.getOrNull(4) == null) DEFAULT_TIMEOUT else args.longAt(4, "timeoutMs")
            if (timeout !in 1..MAX_TIMEOUT) throw ScriptException.argument("timeoutMs must be between 1 and $MAX_TIMEOUT")
            if (body != null && body.size > MAX_BYTES) throw ScriptException.argument("The body is larger than ${MAX_BYTES / 1024 / 1024} MiB")
            withContext(io) {
                var target = args.stringAt(0, "url")
                var currentMethod = method
                var currentBody = body
                repeat(MAX_REDIRECTS + 1) {
                    val url = allowedUrl(target, origins, permissions)
                    val response = request(url, currentMethod, headers, currentBody, timeout.toInt())
                    val location = response.location
                    if (response.status in REDIRECTS && location != null) {
                        target = url.toURI().resolve(location).toString()
                        // As browsers do: 303, and 301/302 after POST, continue as a bodiless GET.
                        if (response.status == 303 || (response.status in 301..302 && currentMethod == "POST")) {
                            currentMethod = "GET"
                            currentBody = null
                        }
                        return@repeat
                    }
                    return@withContext mapOf(
                        "status" to response.status,
                        "url" to url.toString(),
                        "headers" to response.headers,
                        "body" to response.body,
                    )
                }
                throw ScriptException(ScriptException.HOST_ERROR, "More than $MAX_REDIRECTS redirects")
            }
        }
    }

    private class Answer(val status: Int, val headers: Map<String, String>, val body: ByteArray, val location: String?)

    private fun request(url: URL, method: String, headers: Map<String, String>, body: ByteArray?, timeout: Int): Answer {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.requestMethod = method
            connection.useCaches = false
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val received = stream?.use(::readBounded) ?: ByteArray(0)
            val answerHeaders = connection.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.joinToString(", ") }
            return Answer(status, answerHeaders, received, connection.getHeaderField("Location"))
        } catch (error: IOException) {
            throw ScriptException(ScriptException.HOST_ERROR, "$method ${url.host} failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_BYTES) throw ScriptException(ScriptException.LIMIT_EXCEEDED, "The response is larger than ${MAX_BYTES / 1024 / 1024} MiB")
            out.write(buffer, 0, read)
        }
    }

    private class Origin(val scheme: String, val host: String, val wildcard: Boolean, val port: Int)

    private fun originOf(text: String): Origin {
        val uri = URI(text)
        val host = uri.host ?: uri.authority.substringBefore(':')
        val wildcard = host.startsWith("*.")
        return Origin(uri.scheme, host.removePrefix("*."), wildcard, if (uri.port == -1) defaultPort(uri.scheme) else uri.port)
    }

    private fun allowedUrl(text: String, origins: List<Origin>, permissions: ScriptPermissions): URL {
        val uri = try {
            URI(text)
        } catch (error: URISyntaxException) {
            throw ScriptException.argument("Invalid URL: ${error.message}")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]")
        if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty()) throw ScriptException.argument("Only http and https URLs can be fetched")
        val port = if (uri.port == -1) defaultPort(scheme) else uri.port
        val allowed = permissions.anyNetwork || origins.any { origin ->
            origin.scheme == scheme && origin.port == port &&
                (if (origin.wildcard) host.endsWith(".${origin.host}") else host == origin.host)
        }
        if (!allowed) throw ScriptException(ScriptException.PERMISSION_DENIED, "$scheme://$host:$port is not among the script's network origins")
        if (!permissions.privateNetwork) {
            val addresses = try {
                resolve(host)
            } catch (error: IOException) {
                throw ScriptException(ScriptException.HOST_ERROR, "Cannot resolve $host: ${error.message}")
            }
            if (addresses.any(::isPrivate)) {
                throw ScriptException(ScriptException.PERMISSION_DENIED, "$host is a private address; the script did not declare privateNetwork")
            }
        }
        return uri.toURL()
    }

    private companion object {
        val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 5
        const val DEFAULT_TIMEOUT = 15_000L
        const val MAX_TIMEOUT = 60_000L
        const val MAX_BYTES = 8 * 1024 * 1024

        fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

        fun isPrivate(address: InetAddress): Boolean =
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                address.isAnyLocalAddress || address.isMulticastAddress ||
                // Unique local IPv6 (fc00::/7) is the IPv6 counterpart of the site-local ranges.
                (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC) ||
                // Carrier-grade NAT, 100.64.0.0/10.
                (address.address.size == 4 && address.address[0].toInt() and 0xFF == 100 && address.address[1].toInt() and 0xC0 == 64)
    }
}
