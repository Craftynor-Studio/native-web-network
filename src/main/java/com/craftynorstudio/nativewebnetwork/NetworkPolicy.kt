package com.craftynorstudio.nativewebnetwork

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.util.Locale

/** The host app supplies its access policy and request blocker. No UI is built into the library. Deny by default. */
fun interface NativeNetworkAuthorizer {
    suspend fun authorize(request: NativeNetworkAccess): Boolean
}
data class NativeNetworkAccess(val sourceOrigin: String, val targetOrigin: String, val method: String)
fun interface NativeNetworkRequestGate {
    /** Return an explanation to reject the request, or null to allow it. Called for every redirect. */
    suspend fun rejection(url: String): String?
}
data class NativeNetworkConfig(
    val maxRequestBytes: Int = 256 * 1024,
    val maxResponseBytes: Int = 2 * 1024 * 1024,
    val maxConcurrentRequests: Int = 4,
    val timeoutMillis: Long = 15_000,
    val maxRedirects: Int = 5,
) {
    init {
        require(maxRequestBytes in 1..1024 * 1024 && maxResponseBytes in 1..8 * 1024 * 1024)
        require(maxConcurrentRequests in 1..16 && timeoutMillis in 1000..60_000 && maxRedirects in 0..10)
    }
}
internal class NetworkFailure(val code: String, override val message: String) : java.io.IOException(message)
internal data class NetworkRequest(val url: String, val method: String = "GET",
    val headers: Map<String, String> = emptyMap(), val body: ByteArray? = null)
internal data class NetworkResponse(val url: String, val status: Int, val statusText: String,
    val headers: Map<String, String>, val body: ByteArray, val redirected: Boolean = false)

internal object NetworkPolicy {
    private val forbiddenHeaders = setOf("cookie", "cookie2", "host", "origin", "referer", "content-length",
        "connection", "transfer-encoding", "proxy-authorization", "proxy-connection", "accept-encoding", "upgrade", "te", "trailer")
    private val headerName = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    fun url(input: String): HttpUrl {
        if (input.length > 8192) throw NetworkFailure("INVALID_REQUEST", "URL is too long.")
        val url = input.toHttpUrlOrNull() ?: throw NetworkFailure("INVALID_REQUEST", "Enter a valid HTTPS URL.")
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty())
            throw NetworkFailure("INVALID_REQUEST", "Only HTTPS URLs without embedded credentials are allowed.")
        val host = url.host.lowercase(Locale.ROOT).trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || !host.contains('.') && !host.contains(':'))
            throw NetworkFailure("PRIVATE_ADDRESS", "Local network destinations are unavailable.")
        if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
            if (!isPublicAddress(InetAddress.getByName(host))) throw NetworkFailure("PRIVATE_ADDRESS", "Local network destinations are unavailable.")
        }
        return url.newBuilder().fragment(null).build()
    }
    fun origin(input: String): String = url(input).let {
        "https://${if (it.host.contains(':')) "[${it.host}]" else it.host}${if (it.port == 443) "" else ":${it.port}"}"
    }
    fun request(input: NetworkRequest, config: NativeNetworkConfig): NetworkRequest {
        val method = input.method.uppercase(Locale.ROOT)
        if (method !in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
            throw NetworkFailure("INVALID_REQUEST", "Unsupported HTTP method.")
        if (input.body != null && method in setOf("GET", "HEAD")) throw NetworkFailure("INVALID_REQUEST", "GET and HEAD cannot have a body.")
        if ((input.body?.size ?: 0) > config.maxRequestBytes) throw NetworkFailure("REQUEST_TOO_LARGE", "Request body exceeds the limit.")
        if (input.headers.size > 32) throw NetworkFailure("INVALID_REQUEST", "Too many request headers.")
        val headers = linkedMapOf<String, String>()
        var headerBytes = 0
        for ((name, value) in input.headers) {
            val lower = name.lowercase(Locale.ROOT)
            if (!headerName.matches(name) || lower in forbiddenHeaders || lower.startsWith("sec-") || lower.startsWith("proxy-") ||
                value.any { it == '\r' || it == '\n' || it == '\u0000' })
                throw NetworkFailure("INVALID_REQUEST", "Header $name is not allowed.")
            headerBytes += name.length + value.toByteArray().size
            headers[lower] = value
        }
        if (headerBytes > 16 * 1024) throw NetworkFailure("INVALID_REQUEST", "Request headers exceed the limit.")
        return input.copy(url = url(input.url).toString(), method = method, headers = headers)
    }
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) return when (b[0]) {
            0, 10, 127 -> false
            100 -> b[1] !in 64..127
            169 -> b[1] != 254
            172 -> b[1] !in 16..31
            192 -> !(b[1] == 168 || b[1] == 0 || b[1] == 2)
            198 -> b[1] !in setOf(18, 19, 51)
            203 -> !(b[1] == 0 && b[2] == 113)
            else -> b[0] < 224
        }
        // Accept ordinary global IPv6 only, excluding ULA, link-local, NAT64, mapped IPv4 and transition ranges.
        return b.size == 16 && b[0] in 0x20..0x3f &&
            !(b[0] == 0x20 && b[1] == 0x01 && (b[2] == 0x0d && b[3] == 0xb8 || b[2] == 0 && b[3] == 0)) &&
            !(b[0] == 0x20 && b[1] == 0x02)
    }
}
