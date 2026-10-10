package com.craftynorstudio.nativewebnetwork

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkEngineTest {
    private val config = NativeNetworkConfig()
    private val allow = NativeNetworkAuthorizer { true }
    private val pass = NativeNetworkRequestGate { null }
    private fun response(request: NetworkRequest, status: Int = 200, headers: Map<String, String> = emptyMap()) =
        NetworkResponse(request.url, status, "OK", headers, "hello".toByteArray())
    private fun failure(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: NetworkFailure) { assertEquals(code, error.code) }
    }
    @Test fun onlyPublicHttpsWithoutEmbeddedCredentialsIsAccepted() {
        for (url in listOf("http://example.com", "file:///etc/passwd", "https://user:secret@example.com", "not a url"))
            failure("INVALID_REQUEST") { NetworkPolicy.url(url) }
        for (url in listOf("https://localhost", "https://foo.local", "https://10.0.0.1", "https://127.0.0.1",
            "https://[::1]", "https://[::ffff:192.168.0.1]", "https://169.254.169.254", "https://100.64.0.1"))
            failure("PRIVATE_ADDRESS") { NetworkPolicy.url(url) }
        assertEquals("https://example.com/path", NetworkPolicy.url("https://example.com/path#fragment").toString())
        assertEquals("https://example.com:8443", NetworkPolicy.origin("https://EXAMPLE.com:8443/path"))
    }
    @Test fun advertisedAndUnadvertisedResponseBodiesAreBounded() {
        failure("RESPONSE_TOO_LARGE") { readBoundedBody(java.io.ByteArrayInputStream(byteArrayOf(1)), 100, 8) }
        failure("RESPONSE_TOO_LARGE") { readBoundedBody(java.io.ByteArrayInputStream(ByteArray(9)), -1, 8) }
        assertArrayEquals(ByteArray(8), readBoundedBody(java.io.ByteArrayInputStream(ByteArray(8)), -1, 8))
    }
    @Test fun dnsRejectsNonPublicIpv4AndIpv6() {
        for (ip in listOf("0.0.0.0", "172.16.1.1", "192.168.1.1", "198.18.0.1", "224.1.1.1", "255.255.255.255",
            "::", "fc00::1", "fe80::1", "2001:db8::1", "64:ff9b::a00:1", "2002:0a00:0001::1"))
            assertFalse(ip, NetworkPolicy.isPublicAddress(InetAddress.getByName(ip)))
        for (ip in listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"))
            assertTrue(ip, NetworkPolicy.isPublicAddress(InetAddress.getByName(ip)))
    }
    @Test fun requestBodiesHeadersAndMethodsAreBounded() {
        for (name in listOf("Cookie", "Host", "Origin", "Referer", "Sec-Fetch-Site", "Proxy-Authorization", "Content-Length"))
            failure("INVALID_REQUEST") { NetworkPolicy.request(NetworkRequest("https://example.com", headers = mapOf(name to "value")), config) }
        failure("INVALID_REQUEST") { NetworkPolicy.request(NetworkRequest("https://example.com", method = "CONNECT"), config) }
        failure("INVALID_REQUEST") { NetworkPolicy.request(NetworkRequest("https://example.com", body = byteArrayOf(1)), config) }
        failure("INVALID_REQUEST") { NetworkPolicy.request(NetworkRequest("https://example.com", headers = mapOf("X-A" to "a\r\nb")), config) }
        failure("REQUEST_TOO_LARGE") { NetworkPolicy.request(NetworkRequest("https://example.com", "POST", body = ByteArray(config.maxRequestBytes + 1)), config) }
        val request = NetworkPolicy.request(NetworkRequest("https://example.com", "post", mapOf("Content-Type" to "application/json"), "{}".toByteArray()), config)
        assertEquals("POST", request.method); assertEquals("application/json", request.headers["content-type"])
    }
    @Test fun hostAppCanDenyWithoutAnyNetworkCall() = runTest {
        var sent = false
        val engine = NetworkEngine(config, NativeNetworkAuthorizer { false }, pass, NetworkTransport { sent = true; response(it) })
        try { engine.execute("https://site.test", NetworkRequest("https://example.com")); fail() }
        catch (error: NetworkFailure) { assertEquals("PERMISSION_DENIED", error.code) }
        assertFalse(sent)
    }
    @Test fun blockingRunsBeforeAccessPolicyAndForRedirects() = runTest {
        val checked = mutableListOf<String>(); val sent = mutableListOf<String>()
        val engine = NetworkEngine(config, allow, NativeNetworkRequestGate {
            checked += it; if (it.contains("ads.test")) "Blocked tracker" else null
        }, NetworkTransport { sent += it.url; response(it, 302, mapOf("location" to "https://ads.test/pixel")) })
        try { engine.execute("https://site.test", NetworkRequest("https://example.com")); fail() }
        catch (error: NetworkFailure) { assertEquals("BLOCKED", error.code) }
        assertEquals(1, sent.size); assertTrue(checked.contains("https://ads.test/pixel"))
    }
    @Test fun crossOriginRedirectRechecksPolicyAndStripsAuthorization() = runTest {
        val allowed = mutableListOf<NativeNetworkAccess>(); val sent = mutableListOf<NetworkRequest>()
        val engine = NetworkEngine(config, NativeNetworkAuthorizer { allowed += it; true }, pass, NetworkTransport {
            sent += it
            if (sent.size == 1) response(it, 302, mapOf("location" to "https://second.test/result")) else response(it, 404)
        })
        val result = engine.execute("https://site.test/path", NetworkRequest("https://first.test", "POST",
            mapOf("Authorization" to "Bearer secret", "Content-Type" to "application/json"), "{}".toByteArray()))
        assertEquals(listOf("https://first.test", "https://second.test"), allowed.map { it.targetOrigin })
        assertTrue(allowed.all { it.sourceOrigin == "https://site.test" })
        assertEquals("GET", sent.last().method); assertNull(sent.last().body)
        assertFalse(sent.last().headers.containsKey("authorization")); assertFalse(sent.last().headers.containsKey("content-type"))
        assertEquals(404, result.status); assertTrue(result.redirected)
    }
    @Test fun sameOrigin307KeepsExplicitBodyAndAuthorization() = runTest {
        val sent = mutableListOf<NetworkRequest>()
        val engine = NetworkEngine(config, allow, pass, NetworkTransport {
            sent += it; if (sent.size == 1) response(it, 307, mapOf("location" to "/other")) else response(it)
        })
        engine.execute("https://site.test", NetworkRequest("https://first.test", "PUT", mapOf("Authorization" to "token"), "body".toByteArray()))
        assertEquals("PUT", sent.last().method); assertEquals("token", sent.last().headers["authorization"])
        assertArrayEquals("body".toByteArray(), sent.last().body)
    }
    @Test fun redirectCannotDowngradeOrReachPrivateNetwork() = runTest {
        for ((target, code) in listOf("http://second.test" to "INVALID_REQUEST", "https://192.168.0.1" to "PRIVATE_ADDRESS")) {
            var calls = 0
            val engine = NetworkEngine(config, allow, pass, NetworkTransport { calls++; response(it, 302, mapOf("location" to target)) })
            try { engine.execute("https://site.test", NetworkRequest("https://first.test")); fail() }
            catch (error: NetworkFailure) { assertEquals(code, error.code) }
            assertEquals(1, calls)
        }
    }
    @Test fun redirectLoopsAreBounded() = runTest {
        var calls = 0
        val engine = NetworkEngine(config.copy(maxRedirects = 2), allow, pass, NetworkTransport { calls++; response(it, 302, mapOf("location" to "/again")) })
        try { engine.execute("https://site.test", NetworkRequest("https://first.test")); fail() }
        catch (error: NetworkFailure) { assertEquals("TOO_MANY_REDIRECTS", error.code) }
        assertEquals(3, calls)
    }
    @Test fun timeoutAndCallerCancellationStopTransfers() = runTest {
        var cancelled = false
        val engine = NetworkEngine(config.copy(timeoutMillis = 1000), allow, pass, NetworkTransport {
            try { delay(10_000); response(it) } finally { cancelled = true }
        })
        try { engine.execute("https://site.test", NetworkRequest("https://first.test")); fail() }
        catch (_: TimeoutCancellationException) { }
        assertTrue(cancelled); cancelled = false
        val work = launch { engine.execute("https://site.test", NetworkRequest("https://first.test")) }
        runCurrent(); work.cancelAndJoin(); assertTrue(cancelled)
    }
}
