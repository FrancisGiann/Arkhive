package mihon.readingapp.novels.lnreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine

/** Strict public HTTPS client shared by repository metadata, bundles and extension fetch requests. */
class LnReaderHttpBroker : LnReaderHostFetch {
    override suspend fun fetch(request: LnReaderHttpRequest, allowedOrigins: Set<String>, maxBytes: Int): LnReaderHttpResponse =
        withContext(Dispatchers.IO) {
            val jobContext = coroutineContext
            require(maxBytes in 1..MAX_BODY_BYTES)
            require(allowedOrigins.isNotEmpty() && allowedOrigins.size <= 24)
            var url = parsePublicHttps(request.url)
            var method = request.method.uppercase()
            var body = request.body
            var outgoingHeaders = request.headers
            var followed = 0
            while (true) {
                coroutineContext.ensureActive()
                val origin = url.originKey()
                require(origin in allowedOrigins) { "The request origin is outside the approved source origins." }
                require(url.host !in DENIED_HOSTNAMES &&
                    DENIED_HOST_SUFFIXES.none { suffix -> url.host.endsWith(suffix) }) { "Local and private network hosts are unsupported." }
                val headers = safeHeaders(outgoingHeaders)
                require(method in setOf("GET", "HEAD", "POST")) { "This request method is unsupported." }
                require(body == null || body.size <= MAX_REQUEST_BODY_BYTES) { "The request body exceeds its limit." }
                require(method != "POST" || body != null) { "POST requests need a bounded text body." }
                require(method == "POST" || body == null) { "Request bodies are only supported for POST." }
                val checkedAddresses = resolvePublicBounded(url.host)
                val pinnedDns = Dns { host ->
                    if (!host.equals(url.host, ignoreCase = true)) throw UnknownHostException("Unapproved redirect host")
                    checkedAddresses
                }
                val client = OkHttpClient.Builder()
                    .dns(pinnedDns)
                    .proxy(java.net.Proxy.NO_PROXY)
                    .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                    .cache(null)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(15, TimeUnit.SECONDS)
                    .build()
                val requestBody = body?.toRequestBody(headers["content-type"]?.toMediaTypeOrNull())
                val callRequest = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .method(method, if (method == "GET" || method == "HEAD") null else requestBody)
                    .build()
                var redirectedTo: HttpUrl? = null
                var completedResponse: LnReaderHttpResponse? = null
                val call = client.newCall(callRequest)
                // invokeOnCompletion runs after normal completion too. A child watcher stays
                // suspended while the response body is read and closes the socket immediately
                // when its parent operation is cancelled.
                val cancellationWatcher = CoroutineScope(jobContext).launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                call.awaitResponse().use { response ->
                    if (response.code in REDIRECT_CODES) {
                        require(followed++ < MAX_REDIRECTS) { "The source redirected too many times." }
                        val location = response.header("Location") ?: error("The source redirect omitted its destination.")
                        val destination = parsePublicHttps(url.resolve(location)?.toString() ?: error("The source redirect is invalid."))
                        require(destination.originKey() in allowedOrigins) { "The redirect leaves the approved source origins." }
                        redirectedTo = destination
                        if ((response.code == 303 && method != "HEAD") ||
                            ((response.code == 301 || response.code == 302) && method == "POST")) {
                            method = "GET"
                            body = null
                            outgoingHeaders = outgoingHeaders.filterKeys { it.lowercase() !in setOf("content-type", "content-length") }
                        }
                    } else {
                        val bodyBytes = if (method == "HEAD") byteArrayOf() else response.body?.byteStream()?.use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                jobContext.ensureActive()
                                val count = stream.read(buffer)
                                if (count < 0) break
                                require(out.size() + count <= maxBytes) { "The source response exceeds its size limit." }
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        } ?: byteArrayOf()
                        val visibleHeaders = linkedMapOf<String, String>()
                        response.headers.names().asSequence().take(64).forEach { name ->
                            val normalized = name.lowercase()
                            if (normalized !in HIDDEN_RESPONSE_HEADERS) {
                                response.header(name)?.take(4096)?.let { visibleHeaders[normalized] = it }
                            }
                        }
                        completedResponse = LnReaderHttpResponse(
                            url = response.request.url.toString(), status = response.code,
                            statusText = response.message.take(128), headers = visibleHeaders, body = bodyBytes,
                        )
                    }
                }
                } finally {
                    try {
                        cancellationWatcher.cancelAndJoin()
                    } finally {
                        // Each redirect hop owns a new client/pool; don't retain idle sockets.
                        client.connectionPool.evictAll()
                    }
                }
                if (redirectedTo != null) { url = requireNotNull(redirectedTo); continue }
                return@withContext requireNotNull(completedResponse)
            }
            error("Unreachable redirect loop state.")
        }

    private fun safeHeaders(headers: Map<String, String>): Map<String, String> {
        require(headers.size <= MAX_HEADERS)
        return headers.entries.associate { (name, value) ->
            val normalized = name.trim().lowercase()
            require(normalized.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]{1,64}"))) { "A request header name is invalid." }
            require(normalized !in DENIED_REQUEST_HEADERS && DENIED_SECRET_HEADER !in normalized) {
                "Credentials and browser session headers are unsupported."
            }
            val cleanValue = value.trim()
            require(cleanValue.length <= 2048 && cleanValue.none { it == '\r' || it == '\n' }) { "A request header value is invalid." }
            normalized to cleanValue
        }
    }

    private suspend fun resolvePublicBounded(host: String): List<InetAddress> {
        val lookup = try {
            dnsExecutor.submit<List<InetAddress>> { InetAddress.getAllByName(host).toList() }
        } catch (failure: RejectedExecutionException) {
            error("Source DNS lookup capacity is temporarily full.")
        }
        val addresses = try {
            runInterruptible(Dispatchers.IO) { lookup.get(DNS_TIMEOUT_MS, MILLISECONDS) }
        } catch (failure: Throwable) {
            lookup.cancel(true)
            if (failure is java.util.concurrent.TimeoutException) error("Source DNS lookup timed out.")
            if (failure is CancellationException) throw failure
            throw failure
        }
        require(addresses.isNotEmpty() && addresses.size <= 16) { "The source host has no usable public address." }
        require(addresses.all(::isPublicAddress)) { "The source host resolves to a local or private address." }
        return addresses
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (address is Inet4Address) {
            val a = bytes[0].toInt() and 255; val b = bytes[1].toInt() and 255
            val c = bytes[2].toInt() and 255
            return when {
                a == 0 || a == 10 || a == 127 || a >= 224 -> false
                a == 100 && b in 64..127 -> false
                a == 169 && b == 254 -> false
                a == 172 && b in 16..31 -> false
                a == 192 && b == 168 -> false
                a == 192 && b == 0 && c == 0 -> false
                a == 192 && b == 0 && c == 2 -> false
                a == 192 && b == 88 && c == 99 -> false
                a == 198 && b in 18..19 -> false
                a == 198 && b == 51 && c == 100 -> false
                a == 203 && b == 0 && c == 113 -> false
                else -> true
            }
        }
        if (address is Inet6Address) {
            // Allow only global-unicast 2000::/3; this rejects ULA, link-local, NAT64,
            // IPv4-compatible and other special-purpose ranges before any socket is opened.
            if ((bytes[0].toInt() and 0xe0) != 0x20) return false
            if (bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() &&
                (bytes[2].toInt() and 255) <= 1) return false // IANA special-purpose 2001::/23
            if (bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() &&
                bytes[2] == 0x0d.toByte() && bytes[3] == 0xb8.toByte()) return false // documentation range
            if (bytes[0] == 0x20.toByte() && bytes[1] == 0x02.toByte()) return false // 6to4 embeds arbitrary IPv4
        }
        return true
    }

    companion object {
        const val MAX_BODY_BYTES = 2_000_000
        const val MAX_REQUEST_BODY_BYTES = 64 * 1024
        private const val DNS_TIMEOUT_MS = 3_000L
        private const val MAX_HEADERS = 40
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val USER_AGENT = "Arkhive/0.4.1 LNReader isolated source client"
        private const val DENIED_SECRET_HEADER = "authorization"
        private val DENIED_HOSTNAMES = setOf("localhost", "localhost.localdomain", "ip6-localhost")
        private val DENIED_HOST_SUFFIXES = setOf(".localhost", ".localdomain", ".local")
        private val DENIED_REQUEST_HEADERS = setOf("cookie", "cookie2", "proxy-authorization", "host", "connection", "content-length", "origin")
        private val HIDDEN_RESPONSE_HEADERS = setOf("set-cookie", "set-cookie2", "www-authenticate", "proxy-authenticate")
        private val dnsExecutor = ThreadPoolExecutor(
            2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
            { runnable -> Thread(runnable, "lnreader-public-dns").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response) { _, lateResponse, _ -> lateResponse.close() }
            } else response.close()
        }
    })
}

internal fun parsePublicHttps(raw: String): HttpUrl {
    val value = raw.trim()
    require(value.length in 9..4096 && value.none { it <= ' ' || it == '\\' }) { "Only public HTTPS source URLs are supported." }
    val url = value.toHttpUrlOrNullCompat() ?: error("The source URL is invalid.")
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "Only public HTTPS URLs without credentials are supported." }
    require(!url.host.startsWith("[") && ':' !in url.host && !url.host.matches(Regex("[0-9.]+"))) {
        "IP-literal source URLs are unsupported."
    }
    return url
}

/** Normalize a user-entered HTTPS page/source URL to the exact public origin it uses. */
internal fun normalizePublicHttpsOrigin(raw: String): String {
    val value = raw.trim()
    require(!value.startsWith("http://", ignoreCase = true)) {
        "Only public HTTPS hosts can be approved; HTTP origins cannot be trusted."
    }
    require(value.startsWith("https://", ignoreCase = true)) {
        "Enter a full HTTPS page or source URL."
    }
    return parsePublicHttps(value).originKey()
}

internal fun normalizePublicHttpsOriginLines(raw: String): Set<String> = raw.lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map(::normalizePublicHttpsOrigin)
    .toSet()

private fun String.toHttpUrlOrNullCompat(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

internal fun HttpUrl.originKey(): String = "https://${host.lowercase()}:${port}"

