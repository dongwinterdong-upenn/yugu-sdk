// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link HttpTransport} on {@link HttpURLConnection}; no third-party HTTP client. */
public final class UrlConnectionTransport implements HttpTransport {
    /** Responses larger than this are truncated (results are a few dozen KB). */
    static final int MAX_BODY = 8 * 1024 * 1024;

    /**
     * Stream the request body (fixed length) on Android. The desktop JDK implementation turns a
     * 401 answer to a streamed body into an HttpRetryException and drops the error body, which
     * would hide platform codes such as 2003; there the body is buffered instead (tests, JVM use).
     */
    static final boolean STREAM_BODY = isAndroidRuntime();

    /** java.vm.name is "Dalvik" on Dalvik and ART, java.vendor is "The Android Project". */
    static boolean isAndroidRuntime() {
        String vm = System.getProperty("java.vm.name", "").toLowerCase(java.util.Locale.ROOT);
        String vendor = System.getProperty("java.vendor", "").toLowerCase(java.util.Locale.ROOT);
        return vm.contains("dalvik") || vendor.contains("android");
    }

    @Override
    public Response execute(Request request, CancelToken token) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(request.url).openConnection();
        Closeable abort = new Closeable() {
            @Override
            public void close() {
                c.disconnect();
            }
        };
        if (token != null && !token.attach(abort)) {
            throw new IOException("cancelled");
        }
        try {
            c.setConnectTimeout(request.connectTimeoutMs);
            c.setReadTimeout(request.readTimeoutMs);
            c.setRequestMethod(request.method);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> h : request.headers.entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            if (request.body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", request.body.contentType());
                if (STREAM_BODY) {
                    c.setFixedLengthStreamingMode(request.body.contentLength());
                }
                OutputStream out = c.getOutputStream();
                try {
                    request.body.writeTo(out);
                } finally {
                    out.close();
                }
            }
            int status;
            try {
                status = c.getResponseCode();
            } catch (HttpRetryException e) {
                // authentication challenge on a streamed body: the status is all that is left
                return new Response(e.responseCode(), new LinkedHashMap<String, String>(), "");
            }
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readAll(in);
            Map<String, String> headers = new LinkedHashMap<String, String>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    headers.put(e.getKey(), e.getValue().get(0));
                }
            }
            return new Response(status, headers, body);
        } finally {
            if (token != null) {
                token.detach(abort);
            }
            c.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > MAX_BODY) {
                    bos.write(buf, 0, MAX_BODY - bos.size());
                    break;
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), Codec.UTF_8);
        } finally {
            in.close();
        }
    }
}
