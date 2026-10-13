// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * {@link HttpTransport} on {@link HttpURLConnection}; no third-party HTTP client.
 *
 * <p>Requests carry {@code Accept-Encoding: gzip} and gzip answers are decompressed here instead of
 * by the platform's HttpURLConnection, so the timing log knows the bytes on the wire as well as
 * the bytes after decompression (SDK 2.1.1). Every attempt returns {@link HttpTransport.Metrics}.
 */
public final class UrlConnectionTransport implements HttpTransport {
    /** Responses larger than this are truncated (results are a few dozen KB), after decompression too. */
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
        Metrics m = new Metrics();
        m.startNanos = System.nanoTime();
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
            boolean acceptEncoding = false;
            for (Map.Entry<String, String> h : request.headers.entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
                acceptEncoding |= "accept-encoding".equalsIgnoreCase(h.getKey());
            }
            if (!acceptEncoding) {
                // decompressed below: an explicit header turns off the transparent gzip of Android
                c.setRequestProperty("Accept-Encoding", "gzip");
            }
            if (request.body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", request.body.contentType());
                long length = request.body.contentLength();
                if (STREAM_BODY) {
                    c.setFixedLengthStreamingMode(length);
                }
                OutputStream out = c.getOutputStream();
                try {
                    request.body.writeTo(out);
                } finally {
                    out.close();
                }
                m.uploadBytes = length;
                m.uploadDoneNanos = System.nanoTime();
            } else if (request.bytes != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", request.bytesContentType != null ? request.bytesContentType
                        : "application/octet-stream");
                if (STREAM_BODY) {
                    c.setFixedLengthStreamingMode(request.bytes.length);
                }
                OutputStream out = c.getOutputStream();
                try {
                    out.write(request.bytes);
                } finally {
                    out.close();
                }
                m.uploadBytes = request.bytes.length;
                m.uploadDoneNanos = System.nanoTime();
            }
            int status;
            try {
                status = c.getResponseCode();
            } catch (HttpRetryException e) {
                // authentication challenge on a streamed body: the status is all that is left
                return new Response(e.responseCode(), new LinkedHashMap<String, String>(), "");
            }
            m.headersNanos = System.nanoTime();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String encoding = c.getContentEncoding();
            m.contentEncoding = encoding == null || encoding.trim().isEmpty() ? null
                    : encoding.trim().toLowerCase(Locale.ROOT);
            String body;
            if (in == null) {
                body = "";
                m.wireBytes = 0;
                m.bodyBytes = 0;
            } else {
                byte[] b = readBody(in, m);
                body = new String(b, Codec.UTF_8);
            }
            m.bodyDoneNanos = System.nanoTime();
            Map<String, String> headers = new LinkedHashMap<String, String>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    headers.put(e.getKey(), "server-timing".equalsIgnoreCase(e.getKey()) ? join(e.getValue())
                            : e.getValue().get(0));
                }
            }
            return new Response(status, headers, body, m);
        } finally {
            if (token != null) {
                token.detach(abort);
            }
            c.disconnect();
        }
    }

    /** Server-Timing may come in several header lines; they form one comma separated list. */
    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (v == null || v.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(v.trim());
        }
        return sb.toString();
    }

    /**
     * Reads the body, gunzips it when the answer is gzip encoded and records the bytes on the wire
     * and after decompression in {@code m}. Both are capped at {@link #MAX_BODY}.
     */
    static byte[] readBody(InputStream raw, Metrics m) throws IOException {
        CountingInputStream counted = new CountingInputStream(raw);
        InputStream in = counted;
        try {
            if ("gzip".equals(m.contentEncoding) || "x-gzip".equals(m.contentEncoding)) {
                PushbackInputStream p = new PushbackInputStream(counted, 1);
                int first = p.read();
                if (first < 0) {
                    m.wireBytes = 0;
                    m.bodyBytes = 0;
                    return new byte[0];
                }
                p.unread(first);
                in = new GZIPInputStream(p, 8192);
            }
            byte[] b = readCapped(in);
            m.wireBytes = counted.count;
            m.bodyBytes = b.length;
            return b;
        } finally {
            in.close();
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
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
        return bos.toByteArray();
    }

    /** Counts the bytes read through it. */
    static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            if (s > 0) {
                count += s;
            }
            return s;
        }
    }
}
