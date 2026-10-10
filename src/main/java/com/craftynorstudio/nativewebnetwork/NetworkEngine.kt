package com.craftynorstudio.nativewebnetwork

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun interface NetworkTransport { suspend fun send(request: NetworkRequest): NetworkResponse }

internal fun readBoundedBody(input: InputStream, advertisedSize: Long, limit: Int): ByteArray {
    if (advertisedSize > limit) throw NetworkFailure("RESPONSE_TOO_LARGE", "Response body exceeds the limit.")
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = input.read(buffer)
        if (size < 0) break
        if (bytes.size() + size > limit) throw NetworkFailure("RESPONSE_TOO_LARGE", "Response body exceeds the limit.")
        bytes.write(buffer, 0, size)
    }
    return bytes.toByteArray()
}

/** No CookieManager, cookie jar, cached authenticated connection, proxy, or automatic redirects. */
internal class HttpsTransport(private val config: NativeNetworkConfig) : NetworkTransport {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = InetAddress.getAllByName(hostname).toList()
                if (addresses.isEmpty() || addresses.any { !NetworkPolicy.isPublicAddress(it) })
                    throw NetworkFailure("PRIVATE_ADDRESS", "Local network destinations are unavailable.")
                // Return these exact checked addresses to the socket; no second, unvalidated DNS lookup.
                return addresses
            }
        }).build()

    override suspend fun send(request: NetworkRequest): NetworkResponse = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        val body = if (request.method in setOf("POST", "PUT", "PATCH")) (request.body ?: ByteArray(0)).toRequestBody()
            else request.body?.toRequestBody()
        val call = client.newCall(builder.method(request.method, body).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        val bytes = it.body?.byteStream()?.use { input ->
                            readBoundedBody(input, it.body?.contentLength() ?: -1, config.maxResponseBytes)
                        } ?: ByteArray(0)
                        val headers = linkedMapOf<String, String>()
                        for (name in it.headers.names()) {
                            if (!name.equals("set-cookie", true) && !name.equals("set-cookie2", true))
                                headers[name.lowercase()] = it.headers.values(name).joinToString(", ")
                        }
                        NetworkResponse(request.url, it.code, it.message, headers, bytes)
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}

internal class NetworkEngine(
    private val config: NativeNetworkConfig,
    private val authorizer: NativeNetworkAuthorizer,
    private val gate: NativeNetworkRequestGate,
    private val transport: NetworkTransport = HttpsTransport(config),
) {
    suspend fun execute(sourceOrigin: String, input: NetworkRequest): NetworkResponse {
        val source = NetworkPolicy.origin(sourceOrigin)
        var request = NetworkPolicy.request(input, config)
        var redirects = 0
        var transferMillis = 0L
        while (true) {
            // The host can apply ad/tracking filters before showing a permission request.
            gate.rejection(request.url)?.let { throw NetworkFailure("BLOCKED", it) }
            if (!authorizer.authorize(NativeNetworkAccess(source, NetworkPolicy.origin(request.url), request.method)))
                throw NetworkFailure("PERMISSION_DENIED", "Native network access was not allowed.")
            gate.rejection(request.url)?.let { throw NetworkFailure("BLOCKED", it) }
            // User decision time is excluded, but all network hops share one bounded transfer budget.
            val started = System.nanoTime()
            val remaining = config.timeoutMillis - transferMillis
            if (remaining <= 0) throw NetworkFailure("TIMEOUT", "Request timed out.")
            val response = withTimeout(remaining) { transport.send(request) }
            transferMillis += (System.nanoTime() - started) / 1_000_000
            if (response.status !in setOf(301, 302, 303, 307, 308) || response.headers["location"] == null)
                return response.copy(redirected = redirects > 0)
            if (++redirects > config.maxRedirects) throw NetworkFailure("TOO_MANY_REDIRECTS", "Too many redirects.")
            val target = request.url.toHttpUrlOrNull()!!.resolve(response.headers.getValue("location"))
                ?: throw NetworkFailure("INVALID_REDIRECT", "Invalid redirect destination.")
            val crossOrigin = NetworkPolicy.origin(target.toString()) != NetworkPolicy.origin(request.url)
            val switchToGet = response.status == 303 && request.method != "HEAD" ||
                response.status in setOf(301, 302) && request.method == "POST"
            // Do not forward caller credentials to another origin or preserve body headers after rewriting.
            request = NetworkPolicy.request(request.copy(url = target.toString(),
                method = if (switchToGet) "GET" else request.method,
                body = if (switchToGet) null else request.body,
                headers = request.headers.filterKeys { !(crossOrigin && it == "authorization") &&
                    !(switchToGet && it in setOf("content-type", "content-encoding")) }), config)
        }
    }
}
