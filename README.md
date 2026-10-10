# Native Web Network 0.1.0

By **Craftynor Studio**, licensed under MIT. Native package: `com.craftynorstudio.nativewebnetwork`.

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

BrowseTube consumes the module with `implementation(project(":native-web-network"))`.

Build an AAR and a Maven repository with:

```powershell
.\gradlew.bat :native-web-network:publishReleasePublicationToLocalBuildRepository
```

The repository is generated under `native-web-network/build/repository`. Another Android project can use it directly, or you can upload its contents to your own Maven server:

```kotlin
// settings.gradle.kts, inside dependencyResolutionManagement.repositories
maven { url = uri("/absolute/path/to/native-web-network/build/repository") }

// consuming module build.gradle.kts
implementation("com.craftynorstudio:native-web-network:0.1.0")
```

Publishing generates the AAR, source JAR, standalone `-sdk.js` artifact and dependency metadata. A manually copied AAR requires declaring its AndroidX WebKit, OkHttp and kotlinx-coroutines dependencies separately; prefer the Maven dependency.

## Android integration

Create one bridge per WebView, on its UI thread, before loading a document:

```kotlin
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

The default access policy denies every request. BrowseTube deliberately enables public HTTPS access for its active foreground tab, with no user prompts, and runs every request/redirect through the tab's ad and tracking blocker. No access policy can bypass the library's public-address and credential rules. Do not determine a calling frame's identity from a JavaScript payload: the library uses WebView's `sourceOrigin` and rejects subframes.

Requires Android 8/API 26 or later and a WebView supporting `WEB_MESSAGE_LISTENER`. Feature support is checked at runtime. Unsupported WebViews get no legacy JavaScript-interface fallback.

## Website SDK

Host the standalone file `sdk/native-web-network.js` on your HTTPS hosting and include it on any website. The Android AAR contains only the native bridge and transport; it neither bundles nor injects this SDK. Loading a second copy of the SDK is idempotent. Automated tests include the standalone file in their test APK assets and serve it as a separate HTTPS script fixture; this asset is absent from production packages.

```html
<script src="https://cdn.jsdelivr.net/gh/Craftynor-Studio/native-web-network@0.1.0/sdk/native-web-network.js"></script>
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

`BrowseTube.network` is an alias of `NativeWebNetwork`. `.available` indicates bridge support, not that every destination is authorized by the app. The SDK reports `UNAVAILABLE` outside a compatible app; an app policy can return `PERMISSION_DENIED` or `BLOCKED`.

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

## Verification

```powershell
.\gradlew.bat :native-web-network:testDebugUnitTest :native-web-network:connectedDebugAndroidTest
```

Unit tests cover URL/header/body validation, reserved IPv4/IPv6, app-policy denial, tracker rejection, redirect policy and credentials, bounded responses, cancellation and timeouts. WebView tests cover cross-origin native responses, Unicode POST bodies, SDK feature detection/remote inclusion, subframe rejection, cancellation, concurrency and document cleanup using isolated fixtures.

An optional live test uses the real native HTTPS transport to read Google's public autocomplete response. Enable it explicitly so ordinary fixture tests remain independent of external servers:

```powershell
.\gradlew.bat :native-web-network:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.craftynorstudio.nativewebnetwork.NativeWebNetworkTest#livePublicHttpsRequestUsesTheRealNativeTransport' '-Pandroid.testInstrumentationRunnerArguments.nativeNetworkLive=true'
```

Verified on the Studio emulator: 11 library unit tests and seven WebView fixture tests pass; the separate live HTTPS test also passes. An isolated consumer compiles using only `com.craftynorstudio:native-web-network:0.1.0` from the generated Maven repository. Archive checks confirm the AAR and BrowseTube APK contain no website SDK assets.

BrowseTube builds successfully with no lint errors and its 70 JVM tests pass. Browser regressions pass; after correcting JSON-null redirect handling, all nine protection integration tests pass, including ordinary native requests, blocked-request counts and site exceptions.

## Publishing

The standalone repository contains only this library, SDK and Gradle wrapper. Run `./gradlew testDebugUnitTest assembleRelease publishToMavenLocal` to verify it. JitPack uses Java 21 and publishes the release named by the Git tag. SDK URLs pin that same tag. Do not retag a released version; increment the version for changes.

Within the BrowseTube workspace, regenerate the isolated publication project with `./native-web-network/publishing/prepare.ps1`. Its explicit file allowlist excludes the browser app, local paths, credentials, logs and generated output.
