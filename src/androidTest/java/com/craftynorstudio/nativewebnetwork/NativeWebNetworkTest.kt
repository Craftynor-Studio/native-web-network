package com.craftynorstudio.nativewebnetwork

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class NativeWebNetworkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView
    private var bridge: NativeWebNetwork? = null
    private val sent = CopyOnWriteArrayList<NetworkRequest>()
    private val sources = CopyOnWriteArrayList<NativeNetworkAccess>()
    private val cancelled = AtomicBoolean()
    private var slow = false
    private val sdk get() = instrumentation.context.assets.open("native-web-network.js").bufferedReader().use { it.readText() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun page(html: String = "<html><body>Fixture</body></html>", attach: Boolean = true, concurrency: Int = 4, live: Boolean = false) {
        val loaded = CountDownLatch(1)
        // Simulate a site loading a script from a separate HTTPS host. Assets exist only in the test APK.
        val document = if (html.contains("sdk.js")) html else html.replaceFirst("<html>",
            "<html><head><script src='https://sdk.test/sdk.js'></script></head>")
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext)
            web.settings.javaScriptEnabled = true
            if (attach) {
                val config = NativeNetworkConfig(maxConcurrentRequests = concurrency)
                val transport = if (live) HttpsTransport(config) else NetworkTransport { request ->
                    sent += request
                    if (slow) try { delay(10_000) } finally { cancelled.set(true) }
                    NetworkResponse(request.url, 203, "Test", mapOf("content-type" to "application/json", "x-test" to "yes"),
                        "{\"text\":\"नमस्ते\"}".toByteArray())
                }
                bridge = NativeWebNetwork(web, NativeNetworkAuthorizer { sources += it; true }, NativeNetworkRequestGate { null },
                    config, transport)
                assertTrue("WebView message feature unavailable", bridge!!.supported)
            }
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { bridge?.onPageStarted() }
                override fun onPageFinished(view: WebView, url: String) { if (url == "https://page.test/") loaded.countDown() }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val isSdk = request.url.path == "/sdk.js"
                    val isMain = request.isForMainFrame
                    val data = if (isSdk) sdk else if (isMain) document else "{\"ordinary\":true}"
                    return WebResourceResponse(if (isSdk) "application/javascript" else if (isMain) "text/html" else "application/json",
                        "UTF-8", 200, "OK", emptyMap(), ByteArrayInputStream(data.toByteArray()))
                }
            }
            web.loadUrl("https://page.test/")
        }
        assertTrue("Fixture didn't load", loaded.await(10, TimeUnit.SECONDS))
    }
    private fun evaluate(script: String): String {
        val done = CountDownLatch(1); var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return result
    }
    private fun result(): String {
        repeat(100) {
            val value = evaluate("window.testResult || null")
            if (value != "null") return JSONTokener(value).nextValue().toString()
            Thread.sleep(50)
        }
        fail("JavaScript result timed out"); return ""
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { bridge?.close(); if (::web.isInitialized) web.destroy() }
    }

    @Test fun nativeFetchReadsCrossOriginResponseThatOrdinaryFetchCannotRead() {
        page()
        evaluate("fetch('https://target.test/data').then(()=>window.testResult='unexpected').catch(()=>window.testResult='cors')")
        assertEquals("cors", result()); evaluate("window.testResult=null")
        evaluate("NativeWebNetwork.fetch('https://target.test/data').then(async r=>window.testResult=JSON.stringify({status:r.status,url:r.url,header:r.headers.get('x-test'),data:await r.json()})).catch(e=>window.testResult=e.code)")
        val value = JSONObject(result())
        assertEquals(203, value.getInt("status")); assertEquals("yes", value.getString("header"))
        assertEquals("नमस्ते", value.getJSONObject("data").getString("text"))
        assertEquals("https://page.test", sources.single().sourceOrigin)
        assertEquals("https://target.test", sources.single().targetOrigin)
    }
    @Test fun postPreservesUtf8BodyAndHeadersWithoutShowingUi() {
        page()
        evaluate("NativeWebNetwork.fetch('https://target.test', {method:'POST',headers:{'Content-Type':'text/plain'},body:'नमस्ते'}).then(()=>window.testResult='ok').catch(e=>window.testResult=e.code)")
        assertEquals("ok", result()); assertEquals("POST", sent.single().method)
        assertEquals("नमस्ते", sent.single().body!!.toString(Charsets.UTF_8))
        assertEquals("text/plain", sent.single().headers["content-type"])
    }
    @Test fun invalidPrivateDestinationsAndBrowserCredentialsAreRejected() {
        page()
        for ((options, expected) in listOf("'https://127.0.0.1'" to "PRIVATE_ADDRESS",
            "'http://target.test'" to "INVALID_REQUEST", "'https://target.test',{headers:{Cookie:'secret'}}" to "INVALID_REQUEST",
            "'https://target.test',{credentials:'include'}" to "UNSUPPORTED")) {
            evaluate("window.testResult=null;NativeWebNetwork.fetch($options).then(()=>window.testResult='unexpected').catch(e=>window.testResult=e.code)")
            assertEquals(expected, result())
        }
        assertTrue(sent.isEmpty())
    }
    @Test fun messagesFromAnUnsupportedSdkFolderAreRejected() {
        page()
        assertEquals("\"v1\"", evaluate("NativeWebNetwork.sdk"))
        // A page whose SDK is newer than this library: the request is answered with UNSUPPORTED and never sent.
        evaluate("window.__craftynorstudioNativeNetwork.addEventListener('message',e=>{const m=JSON.parse(e.data);if(m.id==='future')window.testResult=m.ok?'sent':m.error.code});"
            + "window.__craftynorstudioNativeNetwork.postMessage(JSON.stringify({type:'fetch',sdk:'v2',id:'future',url:'https://target.test',method:'GET',headers:{}}))")
        assertEquals("UNSUPPORTED", result())
        assertTrue(sent.isEmpty())
    }
    @Test fun abortSignalCancelsNativeWork() {
        slow = true; page()
        evaluate("window.controller=new AbortController();NativeWebNetwork.fetch('https://target.test',{signal:controller.signal}).then(()=>window.testResult='unexpected').catch(e=>window.testResult=e.code)")
        repeat(50) { if (sent.isEmpty()) Thread.sleep(50) }
        assertEquals(1, sent.size)
        evaluate("controller.abort()")
        assertEquals("ABORTED", result())
        repeat(50) { if (!cancelled.get()) Thread.sleep(50) }
        assertTrue(cancelled.get())
    }
    @Test fun concurrencyLimitRejectsExtraWorkAndNavigationCancelsPreviousDocument() {
        slow = true; page(concurrency = 1)
        evaluate("NativeWebNetwork.fetch('https://target.test/first').catch(()=>{});NativeWebNetwork.fetch('https://target.test/second').catch(e=>window.testResult=e.code)")
        assertEquals("BUSY", result()); assertEquals(1, sent.size)
        instrumentation.runOnMainSync { bridge!!.onPageStarted() }
        repeat(50) { if (!cancelled.get()) Thread.sleep(50) }
        assertTrue(cancelled.get())
    }
    @Test fun iframeCannotUseNativeRequestsOrClaimAnotherOrigin() {
        page("""<html><body><iframe srcdoc="<script>window.__craftynorstudioNativeNetwork.postMessage(JSON.stringify({type:'fetch',id:'frame',url:'https://target.test',sourceOrigin:'https://page.test'}));parent.postMessage('attempted','*')</script>"></iframe><script>onmessage=()=>window.testResult='attempted'</script></body></html>""")
        assertEquals("attempted", result()); assertTrue(sent.isEmpty()); assertTrue(sources.isEmpty())
    }
    @Test fun externallyHostedSdkIsIdempotentAndFeatureDetectsOtherBrowsers() {
        page("<html><head><script src='/sdk.js'></script></head><body>SDK</body></html>", attach = false)
        assertEquals("false", evaluate("NativeWebNetwork.available"))
        evaluate("NativeWebNetwork.fetch('https://target.test').catch(e=>window.testResult=e.code)")
        assertEquals("UNAVAILABLE", result())
        // Loading the externally hosted SDK again doesn't replace it or its pending requests.
        evaluate("window.originalSdk=NativeWebNetwork")
        evaluate(sdk)
        assertEquals("true", evaluate("originalSdk===NativeWebNetwork"))
    }
    /** Opt-in live check; regular fixture tests don't require an external server. */
    @Test fun livePublicHttpsRequestUsesTheRealNativeTransport() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("nativeNetworkLive") == "true")
        page(live = true)
        evaluate("NativeWebNetwork.fetch('https://www.google.com/complete/search?client=chrome&q=hello').then(async r=>window.testResult=JSON.stringify({status:r.status,cookie:r.headers.has('set-cookie'),text:await r.text()})).catch(e=>window.testResult=JSON.stringify({error:e.code}))")
        val value = JSONObject(result())
        assertEquals(value.toString(), 200, value.optInt("status"))
        assertFalse(value.getBoolean("cookie"))
        assertTrue(value.getString("text").contains("hello"))
    }
}
