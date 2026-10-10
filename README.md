# Native Web Network 0.1.0

Maintained by **Craftynor Studio**. Licensed under MIT. Native package: `com.craftynorstudio.nativewebnetwork`.

An independent Android library plus a standalone JavaScript SDK. Websites call a Promise-based API; the embedded Android module makes HTTPS requests and returns readable responses without browser CORS enforcement. It does not replace the website's normal `fetch` or XMLHttpRequest.

No permission dialogs, settings screens, or other UI are included. Access is controlled by the consuming app's policy, and sites feature-detect support.

## Android dependency

Public release coordinates (JitPack):

```kotlin
// settings.gradle.kts, inside dependencyResolutionManagement.repositories
maven { url = uri("https://jitpack.io") }

// consuming module build.gradle.kts
implementation("com.github.Craftynor-Studio:native-web-network:0.1.0")
```

Import `com.craftynorstudio.nativewebnetwork.NativeWebNetwork` and its policy interfaces.

The Maven dependency includes the AndroidX WebKit, OkHttp and kotlinx-coroutines dependency metadata. Requires Android 8/API 26+, Java 21 and a WebView supporting `WEB_MESSAGE_LISTENER`.

## Android integration

Create one bridge per WebView, on its UI thread, before loading a document:

```kotlin
import com.craftynorstudio.nativewebnetwork.NativeWebNetwork
import com.craftynorstudio.nativewebnetwork.NativeNetworkAuthorizer
import com.craftynorstudio.nativewebnetwork.NativeNetworkRequestGate

val network = NativeWebNetwork(
    webView,
    authorizer = NativeNetworkAuthorizer { access ->
        // App-owned policy. Called again for each redirect destination.
        access.sourceOrigin == "https://my-site.example" &&
            access.targetOrigin == "https://api.example"
    },
    gate = NativeNetworkRequestGate { url ->
        // Return a rejection reason when your ad/tracker blocker denies this URL.
        null
    },
)

// Inside your existing WebViewClient.onPageStarted:
network.onPageStarted()

// When a tab becomes inactive or the app goes into the background:
network.cancelRequests()

// Before destroying the WebView:
network.close()
```

The default access policy denies every request. The consuming app supplies its own source/destination policy and optional request gate. Both apply again to redirects. The library has no built-in UI. No access policy can bypass the library's public-address and credential rules. The library uses WebView's `sourceOrigin` to identify the calling document and rejects subframes.

Requires Android 8/API 26 or later and a WebView supporting `WEB_MESSAGE_LISTENER`. Feature support is checked at runtime. Unsupported WebViews get no legacy JavaScript-interface fallback.

## Website SDK

Host the standalone file `sdk/native-web-network.js` on your HTTPS hosting and include it on any website. The Android AAR contains only the native bridge and transport; it neither bundles nor injects this SDK. Loading a second copy of the SDK is idempotent. Automated tests include the standalone file in their test APK assets and serve it as a separate HTTPS script fixture; this asset is absent from production packages.

```html
<script src="https://cdn.jsdelivr.net/gh/Craftynor-Studio/native-web-network@main/sdk/native-web-network.js"></script>
<script>
async function loadData() {
    if (!window.NativeWebNetwork?.available) {
        // Use your website's normal request path in other browsers.
        return;
    }
    const response = await NativeWebNetwork.fetch('https://api.example/data');
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    console.log(data);
}
</script>
```

The CDN example follows the current `main` branch. You can also host `sdk/native-web-network.js` on your own HTTPS server. For future releases, pin a new release tag when you need a fixed SDK version.

`NativeWebNetwork` is the SDK's public global. `.available` indicates bridge support; authorization still depends on the consuming app's policy. The SDK reports `UNAVAILABLE` outside a compatible app; an app policy can return `PERMISSION_DENIED` or `BLOCKED`.

```javascript
const controller = new AbortController();
const response = await NativeWebNetwork.fetch('https://api.example/data', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query: 'hello' }),
    signal: controller.signal,
    credentials: 'omit',
});
// controller.abort() cancels the underlying native request.
```

The result is a browser `Response` with status, statusText, headers, final URL, redirected flag, and text/JSON/Blob/ArrayBuffer readers. HTTP error statuses resolve normally; transport, policy and validation failures reject with a `NativeNetworkError` carrying a `.code`. Cancellation uses `AbortError` and code `ABORTED`.

Supported methods: GET, HEAD, POST, PUT, PATCH, DELETE and OPTIONS. Supported bodies: strings, URLSearchParams, Blob, ArrayBuffer and typed byte views. Request objects, FormData, streaming bodies, service workers and WebSockets are outside version 1. Redirects are followed manually, rechecking policy, HTTPS and blocking rules; cross-origin redirects remove Authorization, and standard 301/302/303 POST rewrites remove body-related headers.

## Boundaries

- No WebView cookies or native cookie jar. Cookie, Host, Origin, Referer, proxy and connection-control request headers are rejected. Set-Cookie is neither exposed nor saved. A website may supply its own Authorization header explicitly.
- HTTPS with normal certificate/hostname verification. No HTTP downgrade, URL credentials, proxy access, localhost, private/link-local IPs, or reserved addresses. DNS results are checked and the exact checked addresses are used for the connection.
- Defaults: 256 KiB request body, 2 MiB response body, four active requests per tab, five redirects and a shared 15-second transfer budget. Responses are buffered; this is for API data rather than media downloads. The SDK bounds outstanding work at 60 seconds.
- Top-frame messages only. Navigation, inactive tabs, backgrounding and destruction cancel work. Native replies use the originating frame's reply channel rather than JavaScript string interpolation.
- Server authentication, bot checks, rate limits and server-side Origin validation still apply. This API does not make every endpoint accessible.
