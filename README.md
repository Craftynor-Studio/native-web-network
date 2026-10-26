# Native Web Network

An Android WebView networking library and hosted JavaScript SDK maintained by **Craftynor Studio**.

Android apps add the library and define which websites and destinations can make native HTTPS requests. Websites load the hosted SDK and use `NativeWebNetwork.fetch()` to receive a standard JavaScript `Response`.

The native transport does not apply browser CORS checks. Access remains subject to the app's policy and the destination server's authentication and request rules. The library provides no permission dialogs or other UI.

## Requirements

- Android 8.0 / API 26 or later.
- Java 21 and Android SDK 36 for Android integration.
- Android System WebView with `WEB_MESSAGE_LISTENER` support.
- An HTTPS website running in the WebView's top-level frame, with JavaScript enabled.

## Android installation

Add JitPack to your repositories in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.Craftynor-Studio:native-web-network:1.0.0")
}
```

## Android integration

Create one bridge per WebView on its UI thread, before loading your website. Replace the example origins with the website and API you want to authorize:

```kotlin
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import com.craftynorstudio.nativewebnetwork.NativeNetworkAuthorizer
import com.craftynorstudio.nativewebnetwork.NativeWebNetwork

webView.settings.javaScriptEnabled = true

val network = NativeWebNetwork(
    webView = webView,
    authorizer = NativeNetworkAuthorizer { request ->
        request.sourceOrigin == "https://your-site.example" &&
            request.targetOrigin == "https://api.example"
    },
)

webView.webViewClient = object : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        network.onPageStarted()
    }
}

webView.loadUrl("https://your-site.example")
```

If your app already has a `WebViewClient`, add `network.onPageStarted()` to its existing callback.

The default authorizer denies every request. Your authorizer receives the calling website's origin, the destination origin and the HTTP method. It is also checked for each redirect. You can supply an optional `NativeNetworkRequestGate` to apply a request blocker; returning a reason rejects the request, and returning `null` allows it to continue.

Connect the bridge to your WebView's lifecycle:

| When | Call |
| --- | --- |
| A navigation starts | `network.onPageStarted()` |
| The WebView becomes inactive or the app enters the background | `network.cancelRequests()` |
| Before destroying the WebView | `network.close()` |

Use `network.supported` to check native bridge support. Unsupported WebViews do not receive a legacy JavaScript-interface fallback.

## Website integration

Include the hosted SDK on your HTTPS page:

```html
<script src="https://cdn.jsdelivr.net/gh/Craftynor-Studio/native-web-network@main/sdk/v1/native-web-network.js"></script>
```

The SDK is served by jsDelivr straight from this repository's `main` branch; no separate hosting setup is required.

### SDK versions

Each major SDK version has its own folder on `main`, and websites load it through `@main`:

| Folder | Status |
| --- | --- |
| `sdk/v1/` | Current |

- Compatible fixes replace the file inside its folder, so a page that loads `sdk/v1/` gets them automatically (jsDelivr refreshes `@main` within 12 hours).
- A change that would break existing websites gets a new folder (`sdk/v2/`). Earlier folders stay available while the library supports them.
- Every message the SDK sends names its folder (`"sdk": "v1"`), so one library release can serve pages that load different folders. A library that does not support a folder rejects its requests with `UNSUPPORTED`. `NativeWebNetwork.sdk` shows the loaded folder.

Check support before making a request:

```javascript
async function loadData() {
    if (!window.NativeWebNetwork?.available) {
        // Use your website's usual request path or fallback.
        return null;
    }

    const response = await NativeWebNetwork.fetch("https://api.example/data");

    if (!response.ok) {
        throw new Error("HTTP " + response.status);
    }

    return response.json();
}
```

The native library must be installed and enabled by the Android app. Loading the script in an ordinary browser does not add native networking. `available` confirms bridge support; each request still needs authorization from the app.

## Requests and responses

Pass request options as the second argument. For example, send JSON with cancellation support:

```javascript
const controller = new AbortController();

const response = await NativeWebNetwork.fetch("https://api.example/data", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query: "hello" }),
    signal: controller.signal,
    credentials: "omit",
});

const data = await response.json();
// Call controller.abort() while a request is pending to cancel it.
```

The returned `Response` provides `status`, `statusText`, `headers`, `url`, `redirected`, and the usual `text()`, `json()`, `blob()` and `arrayBuffer()` readers. HTTP error responses resolve normally, so check `response.ok`.

Supported methods are GET, HEAD, POST, PUT, PATCH, DELETE and OPTIONS. Supported bodies are strings, `URLSearchParams`, `Blob`, `ArrayBuffer` and typed array views. Redirects use `follow`; app authorization and request gates are checked again at each destination.

Request objects, `FormData`, streaming bodies, service workers and WebSockets are not supported in version 1. The SDK does not replace the page's normal `fetch()` or `XMLHttpRequest`.

## Error handling

Transport, validation and policy failures reject the promise. Errors include a `code` property:

| Code | Meaning |
| --- | --- |
| `UNAVAILABLE` | A compatible native bridge is unavailable. |
| `UNSUPPORTED` | The request uses an option or SDK folder that this version doesn't support. |
| `PERMISSION_DENIED` | The Android app's access policy rejected the request. |
| `BLOCKED` | The app's request gate blocked the URL. |
| `PRIVATE_ADDRESS` | The destination uses a local, private or reserved address. |
| `TIMEOUT` | The request exceeded its time limit. |
| `REQUEST_TOO_LARGE` / `RESPONSE_TOO_LARGE` | A transfer exceeded its size limit. |
| `ABORTED` | The request was cancelled or its page closed. |

Cancellation errors use the name `AbortError`; other library errors use `NativeNetworkError`.

## Request limits and privacy

- Requests use HTTPS with standard certificate and hostname validation. URL credentials, localhost, private networks and reserved addresses are rejected.
- Browser cookies are not shared, native cookies are not stored, and `Set-Cookie` is not exposed. Use `credentials: "omit"`. Explicit `Authorization` headers are supported and removed on cross-origin redirects.
- Cookie, Host, Origin, Referer, proxy and connection-control request headers cannot be supplied by the website.
- Default native limits are a 256 KiB request body, a 2 MiB response body, four concurrent requests per WebView, five redirects and a shared 15-second transfer budget. Android apps can configure native limits through `NativeNetworkConfig`; the SDK limits request bodies to 256 KiB and pending requests to 60 seconds.
- Responses are buffered. This API is intended for API data rather than streaming media or large file downloads.
- Navigation, page closure, inactive WebViews and app lifecycle hooks cancel outstanding work. Only the top-level website frame can use the bridge.

## License

[MIT](LICENSE) — maintained by Craftynor Studio.
