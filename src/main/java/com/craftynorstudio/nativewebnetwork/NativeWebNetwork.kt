package com.craftynorstudio.nativewebnetwork

import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.SocketTimeoutException

/**
 * Attach before navigation. Forward onPageStarted and close with the WebView.
 * HTTPS top frames only; unsupported WebViews get no legacy/insecure bridge fallback.
 */
class NativeWebNetwork internal constructor(
    private val webView: WebView,
    authorizer: NativeNetworkAuthorizer,
    gate: NativeNetworkRequestGate,
    private val config: NativeNetworkConfig,
    transport: NetworkTransport,
) : AutoCloseable {
    constructor(webView: WebView,
        authorizer: NativeNetworkAuthorizer = NativeNetworkAuthorizer { false },
        gate: NativeNetworkRequestGate = NativeNetworkRequestGate { null },
        config: NativeNetworkConfig = NativeNetworkConfig(),
    ) : this(webView, authorizer, gate, config, HttpsTransport(config))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val engine = NetworkEngine(config, authorizer, gate, transport)
    private var documentVersion = 0
    private var closed = false
    val supported: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    init {
        checkMainThread()
        if (supported) {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf("*")) { _, message, origin, mainFrame, reply ->
                val source = runCatching { NetworkPolicy.origin(origin.toString()) }.getOrNull()
                // sourceOrigin is supplied by WebView, never accepted from the page's payload.
                if (!closed && mainFrame && message.type == WebMessageCompat.TYPE_STRING && source != null &&
                    source == runCatching { NetworkPolicy.origin(webView.url.orEmpty()) }.getOrNull()) {
                    receive(source, message.data, reply)
                }
            }
        }
    }

    /** Invalidate work belonging to the previous document. */
    fun onPageStarted() { checkMainThread(); documentVersion++; cancelRequests() }
    fun cancelRequests() { checkMainThread(); jobs.values.toList().forEach { it.cancel() }; jobs.clear() }

    private fun receive(source: String, text: String?, reply: JavaScriptReplyProxy) {
        if (text == null || text.length > config.maxRequestBytes * 2 + 32 * 1024) return
        var requestId: String? = null
        try {
            val data = JSONObject(text)
            val id = data.getString("id")
            if (!id.matches(Regex("^[A-Za-z0-9_-]{1,80}$"))) return
            requestId = id
            // The SDK folder the page loaded. Messages without the field come from sdk/v1.
            if (data.optString("sdk", "v1") !in SUPPORTED_SDKS)
                throw NetworkFailure("UNSUPPORTED", "This app does not support this SDK version.")
            if (data.optString("type") == "cancel") { jobs[id]?.cancel(); return }
            if (data.optString("type") != "fetch") throw NetworkFailure("INVALID_REQUEST", "Unknown message type.")
            if (jobs.containsKey(id)) throw NetworkFailure("INVALID_REQUEST", "Duplicate request ID.")
            if (jobs.size >= config.maxConcurrentRequests) throw NetworkFailure("BUSY", "Too many active requests.")
            val body = if (!data.has("bodyBase64") || data.isNull("bodyBase64")) null
                else Base64.decode(data.getString("bodyBase64"), Base64.NO_WRAP)
            val headerData = data.optJSONObject("headers") ?: JSONObject()
            val headers = headerData.keys().asSequence().associateWith { headerData.getString(it) }
            val request = NetworkPolicy.request(NetworkRequest(data.getString("url"), data.optString("method", "GET"), headers, body), config)
            val epoch = documentVersion
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val response = engine.execute(source, request)
                    val payload = withContext(Dispatchers.Default) { JSONObject().put("id", id).put("ok", true)
                        .put("response", JSONObject().put("url", response.url).put("status", response.status)
                            .put("statusText", response.statusText).put("headers", JSONObject(response.headers))
                            .put("bodyBase64", Base64.encodeToString(response.body, Base64.NO_WRAP)).put("redirected", response.redirected)).toString() }
                    if (epoch == documentVersion && !closed) reply.postMessage(payload)
                } catch (error: CancellationException) {
                    if (error is TimeoutCancellationException && epoch == documentVersion && !closed)
                        failure(reply, id, "TIMEOUT", "Request timed out.")
                    else if (epoch == documentVersion && !closed) failure(reply, id, "ABORTED", "Request cancelled.")
                } catch (error: Exception) {
                    if (epoch == documentVersion && !closed) {
                        val code = when (error) { is NetworkFailure -> error.code; is SocketTimeoutException -> "TIMEOUT"; else -> "NETWORK_ERROR" }
                        failure(reply, id, code, if (error is NetworkFailure) error.message else "The HTTPS request failed.")
                    }
                } finally { if (jobs[id] === coroutineContext[Job]) jobs.remove(id) }
            }
            jobs[id] = job
            job.start()
        } catch (error: Exception) {
            requestId?.let { failure(reply, it, (error as? NetworkFailure)?.code ?: "INVALID_REQUEST",
                (error as? NetworkFailure)?.message ?: "Invalid request message.") }
        }
    }
    private fun failure(reply: JavaScriptReplyProxy, id: String, code: String, message: String) {
        reply.postMessage(JSONObject().put("id", id).put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message)).toString())
    }
    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true; documentVersion++; cancelRequests(); scope.cancel()
        if (supported) WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME)
    }
    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "Call on the WebView UI thread." } }
    companion object {
        private const val BRIDGE_NAME = "__craftynorstudioNativeNetwork"
        /** SDK folders this library can read. A later release adds new folders here and keeps the old ones. */
        private val SUPPORTED_SDKS = setOf("v1")
    }
}
