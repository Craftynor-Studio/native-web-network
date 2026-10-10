/* Native Web Network SDK 0.1.0. Copyright (c) 2026 Craftynor Studio. SPDX-License-Identifier: MIT.
 * Host this standalone file on your own HTTPS server. */
(function (root) {
    'use strict';
    if (root.top !== root || root.location.protocol !== 'https:') return;
    if (root.NativeWebNetwork && root.NativeWebNetwork.version === '0.1.0') return;
    const bridge = root.__craftynorstudioNativeNetwork;
    const pending = new Map();
    let sequence = 0;
    const session = Math.random().toString(36).slice(2);
    function failure(code, message) {
        const error = new Error(message);
        error.name = code === 'ABORTED' ? 'AbortError' : 'NativeNetworkError';
        error.code = code;
        return error;
    }
    function bytesToBase64(bytes) {
        let text = '';
        for (let offset = 0; offset < bytes.length; offset += 8192)
            text += String.fromCharCode.apply(null, bytes.subarray(offset, offset + 8192));
        return btoa(text);
    }
    function base64ToBytes(text) {
        const decoded = atob(text);
        const bytes = new Uint8Array(decoded.length);
        for (let i = 0; i < decoded.length; i++) bytes[i] = decoded.charCodeAt(i);
        return bytes;
    }
    if (bridge) bridge.onmessage = function (event) {
        let message;
        try { message = JSON.parse(event.data); } catch (_) { return; }
        const item = pending.get(message.id);
        if (!item) return;
        pending.delete(message.id);
        item.cleanup();
        if (!message.ok) {
            item.reject(failure(message.error.code, message.error.message));
            return;
        }
        try {
            const value = message.response;
            const bytes = base64ToBytes(value.bodyBase64);
            const noBody = item.method === 'HEAD' || [204, 205, 304].includes(value.status);
            const response = new Response(noBody ? null : bytes, {
                status: value.status, statusText: value.statusText, headers: value.headers
            });
            Object.defineProperties(response, {
                url: { value: value.url }, redirected: { value: value.redirected }
            });
            item.resolve(response);
        } catch (_) { item.reject(failure('INVALID_RESPONSE', 'Unable to decode the native response.')); }
    };
    async function nativeFetch(input, options) {
        if (!bridge) throw failure('UNAVAILABLE', 'This browser does not provide Native Web Network.');
        options = options || {};
        if (options.credentials && options.credentials !== 'omit')
            throw failure('UNSUPPORTED', 'Version 1 does not share browser cookies. Use credentials: omit.');
        if (options.redirect && options.redirect !== 'follow')
            throw failure('UNSUPPORTED', 'Version 1 supports redirect: follow only.');
        const signal = options.signal;
        if (signal && signal.aborted) throw failure('ABORTED', 'Request cancelled.');
        const url = new URL(String(input), root.location.href).href;
        const method = String(options.method || 'GET').toUpperCase();
        const headers = Object.fromEntries(new Headers(options.headers || {}).entries());
        let bytes = null;
        const body = options.body;
        if (body != null) {
            if (typeof body === 'string') bytes = new TextEncoder().encode(body);
            else if (body instanceof URLSearchParams) {
                bytes = new TextEncoder().encode(body.toString());
                if (!headers['content-type']) headers['content-type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
            } else if (body instanceof ArrayBuffer) bytes = new Uint8Array(body);
            else if (ArrayBuffer.isView(body)) bytes = new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
            else if (body instanceof Blob) {
                bytes = new Uint8Array(await body.arrayBuffer());
                if (body.type && !headers['content-type']) headers['content-type'] = body.type;
            } else throw failure('UNSUPPORTED', 'Use a string, URLSearchParams, Blob or byte buffer body.');
            if (bytes.length > 256 * 1024) throw failure('REQUEST_TOO_LARGE', 'Request body exceeds the limit.');
        }
        if (signal && signal.aborted) throw failure('ABORTED', 'Request cancelled.');
        const id = session + '_' + (++sequence);
        return new Promise(function (resolve, reject) {
            const cancel = function () {
                if (!pending.has(id)) return;
                pending.delete(id); cleanup();
                bridge.postMessage(JSON.stringify({ type: 'cancel', id: id }));
                reject(failure('ABORTED', 'Request cancelled.'));
            };
            // Bounds permission wait as well as network work; always release listeners and Promise state.
            const timer = setTimeout(function () {
                if (!pending.has(id)) return;
                pending.delete(id); cleanup();
                bridge.postMessage(JSON.stringify({ type: 'cancel', id: id }));
                reject(failure('TIMEOUT', 'Request timed out.'));
            }, 60000);
            function cleanup() { clearTimeout(timer); if (signal) signal.removeEventListener('abort', cancel); }
            pending.set(id, { resolve: resolve, reject: reject, cleanup: cleanup, method: method });
            if (signal) signal.addEventListener('abort', cancel, { once: true });
            try {
                bridge.postMessage(JSON.stringify({ type: 'fetch', id: id, url: url, method: method,
                    headers: headers, bodyBase64: bytes == null ? null : bytesToBase64(bytes) }));
            } catch (_) {
                pending.delete(id); cleanup(); reject(failure('UNAVAILABLE', 'The native bridge is unavailable.'));
            }
        });
    }
    root.addEventListener('pagehide', function () {
        for (const [id, item] of pending) {
            try { bridge.postMessage(JSON.stringify({ type: 'cancel', id: id })); } catch (_) {}
            item.cleanup(); item.reject(failure('ABORTED', 'Page closed.'));
        }
        pending.clear();
    });
    const sdk = Object.freeze({ version: '0.1.0', available: !!bridge, fetch: nativeFetch });
    root.NativeWebNetwork = sdk;
    root.BrowseTube = root.BrowseTube || {};
    root.BrowseTube.network = sdk;
})(window);
