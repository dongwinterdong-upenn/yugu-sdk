// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Compat REST call {@code POST /{coreType}} with the shared retry semantics (DESIGN 2.2, 2.3):
 * every attempt of one logical call carries the same {@code Idempotency-Key}, a fresh
 * timestamp and nonce, and the signature of the text form fields. Retries stop at maxRetries, at
 * the first non-retryable error, or when the next back-off would pass the total deadline.
 *
 * <p>SDK 2.2.0: the JSON calls of TtsClient and ReportClient ({@link #executeApi}) go through the
 * same transport, signing, headers and retry loop; only the request and the success check differ.
 */
public final class CompatClient {

    /** One logical evaluation request. */
    public static final class Request {
        public String baseUrl;
        public String coreType;
        public String appKey;
        public String secretKey;
        public String idempotencyKey;
        public LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        public File audioFile;
        public byte[] audioBytes;
        public String audioFilename;
        public String audioContentType = "audio/wav";
        public int connectTimeoutMs = 10000;
        public int readTimeoutMs = 120000;
        public long totalTimeoutMs = 300000;
        public RetryPolicy policy = RetryPolicy.DEFAULT;
        /** Phase marks of the evaluation (attempt starts and answers), null for none. */
        public EvalTiming phases;

        public String url() {
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return base + "/" + coreType;
        }
    }

    /**
     * One logical JSON call of the platform API (SDK 2.2.0): {@code POST /api/v1/tts/generate} with a
     * JSON body and an Idempotency-Key, or {@code GET /api/v1/report/{recordId}} without either. The
     * signed parameters follow CONTRACT 2.3.
     */
    public static final class ApiRequest {
        public String method = "GET";
        public String baseUrl;
        /** Path below the base, starting with a slash, percent-encoded. */
        public String path;
        public String appKey;
        public String secretKey;
        /** Idempotency-Key of a write call, the same for every attempt; null for a read. */
        public String idempotencyKey;
        /** Signed parameters: empty for the report lookup, the top-level scalar body fields for TTS. */
        public Map<String, String> signed = new LinkedHashMap<String, String>();
        /** UTF-8 JSON body, null for none. */
        public byte[] json;
        public int connectTimeoutMs = CompatConfig.DEFAULT_CONNECT_TIMEOUT_MS;
        public int readTimeoutMs = CompatConfig.DEFAULT_READ_TIMEOUT_MS;
        public long totalTimeoutMs = CompatConfig.DEFAULT_TOTAL_TIMEOUT_MS;
        public RetryPolicy policy = RetryPolicy.DEFAULT;

        public String url() {
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return base + path;
        }
    }

    /** Successful response. */
    public static final class Result {
        public final String body;
        public final int attempts;
        public final boolean replayed;

        Result(String body, int attempts, boolean replayed) {
            this.body = body;
            this.attempts = attempts;
            this.replayed = replayed;
        }
    }

    /** Sleep hook so tests can run the retry loop without waiting. */
    public interface Sleeper {
        boolean sleep(CancelToken token, long ms);
    }

    public static final Sleeper REAL_SLEEPER = new Sleeper() {
        @Override
        public boolean sleep(CancelToken token, long ms) {
            return token.sleep(ms);
        }
    };

    /** The parts of one logical call that differ between the evaluation and the JSON calls. */
    private interface Exchange {
        /** The HTTP request of attempt {@code attempt} (1-based), with fresh timestamp and nonce. */
        HttpTransport.Request attempt(int attempt, int maxAttempts, int readTimeoutMs);

        /** Every answer, before it is judged. */
        void answered(HttpTransport.Response r);

        /** null when a 2xx answer is the result, otherwise the error it stands for. */
        CompatError checkSuccess(HttpTransport.Response r);
    }

    private final HttpTransport transport;
    private final Random random;
    private final Sleeper sleeper;

    public CompatClient(HttpTransport transport, Random random, Sleeper sleeper) {
        this.transport = transport;
        this.random = random;
        this.sleeper = sleeper;
    }

    /** Client wired to the current {@link CompatConfig}. */
    public static CompatClient fromConfig() {
        return new CompatClient(CompatConfig.transport(), CompatConfig.random(), CompatConfig.sleeper());
    }

    public Result execute(final Request req, CancelToken token) throws CompatError {
        final String signature = Signer.sign(req.fields, req.secretKey);
        return run(new Exchange() {
            @Override
            public HttpTransport.Request attempt(int attempt, int maxAttempts, int readTimeoutMs) {
                HttpTransport.Request http = new HttpTransport.Request("POST", req.url(),
                        headers(req.idempotencyKey, req.appKey, signature), body(req), req.connectTimeoutMs, readTimeoutMs);
                if (YLog.isLoggable(YLog.DEBUG)) {
                    YLog.d("POST " + req.url() + " attempt " + attempt + "/" + maxAttempts + " key=" + req.idempotencyKey
                            + " appKey=" + YLog.maskKey(req.appKey) + " fields=" + req.fields.keySet());
                }
                if (req.phases != null) {
                    req.phases.restAttempt(attempt);
                }
                return http;
            }

            @Override
            public void answered(HttpTransport.Response r) {
                if (req.phases != null) {
                    req.phases.restResponse(r);
                }
            }

            @Override
            public CompatError checkSuccess(HttpTransport.Response r) {
                return checkSuccessBody(r);
            }
        }, req.policy, req.totalTimeoutMs, req.readTimeoutMs, req.idempotencyKey, token);
    }

    /**
     * Executes a JSON call with the retry rules of {@link #execute}. The answer is the platform
     * envelope {@code {"code":0,"message":"success","data":{...}}}; an HTTP error, a non-zero code or a
     * body without a data object ends in a {@link CompatError}, retried when the table says so.
     */
    public Result executeApi(final ApiRequest req, CancelToken token) throws CompatError {
        final String signature = Signer.sign(req.signed, req.secretKey);
        return run(new Exchange() {
            @Override
            public HttpTransport.Request attempt(int attempt, int maxAttempts, int readTimeoutMs) {
                Map<String, String> h = headers(req.idempotencyKey, req.appKey, signature);
                if (YLog.isLoggable(YLog.DEBUG)) {
                    YLog.d(req.method + " " + req.url() + " attempt " + attempt + "/" + maxAttempts
                            + (req.idempotencyKey != null ? " key=" + req.idempotencyKey : "")
                            + " appKey=" + YLog.maskKey(req.appKey) + " signed=" + req.signed.keySet());
                }
                return new HttpTransport.Request(req.method, req.url(), h, req.json,
                        req.json == null ? null : "application/json; charset=utf-8", req.connectTimeoutMs, readTimeoutMs);
            }

            @Override
            public void answered(HttpTransport.Response r) {
                // no phase marks: TtsClient and ReportClient log one line per call
            }

            @Override
            public CompatError checkSuccess(HttpTransport.Response r) {
                return checkEnvelope(r);
            }
        }, req.policy, req.totalTimeoutMs, req.readTimeoutMs, req.idempotencyKey, token);
    }

    /** The retry loop shared by every call (DESIGN 2.3). */
    private Result run(Exchange x, RetryPolicy requested, long totalTimeoutMs, int readTimeoutMs, String idempotencyKey,
                       CancelToken token) throws CompatError {
        if (token == null) {
            token = new CancelToken();
        }
        RetryPolicy policy = requested == null ? RetryPolicy.DEFAULT : requested;
        long deadline = System.nanoTime() / 1000000L + totalTimeoutMs;
        int maxAttempts = policy.maxRetries + 1;
        CompatError last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (token.isCancelled()) {
                throw cancelled(attempt - 1);
            }
            long now = System.nanoTime() / 1000000L;
            int readTimeout = (int) Math.max(1L, Math.min((long) readTimeoutMs, deadline - now));
            HttpTransport.Request http = x.attempt(attempt, maxAttempts, readTimeout);
            try {
                HttpTransport.Response r = transport.execute(http, token);
                if (token.isCancelled()) {
                    throw cancelled(attempt);
                }
                x.answered(r);
                if (r.status >= 200 && r.status < 300) {
                    CompatError bad = x.checkSuccess(r);
                    if (bad == null) {
                        boolean replayed = "true".equalsIgnoreCase(r.header("Idempotency-Replayed"));
                        if (replayed) {
                            YLog.i("response replayed for Idempotency-Key " + idempotencyKey);
                        }
                        return new Result(r.body, attempt, replayed);
                    }
                    last = bad;
                } else {
                    last = ErrorMapper.fromResponse(r);
                }
            } catch (IOException e) {
                if (token.isCancelled()) {
                    throw cancelled(attempt);
                }
                last = ErrorMapper.fromException(e);
            }
            last.setAttempts(attempt);
            if (!last.retryable || attempt == maxAttempts) {
                throw last;
            }
            long delay = policy.delayMs(attempt, random.nextDouble(), last.retryAfterMs);
            if (System.nanoTime() / 1000000L + delay > deadline) {
                YLog.w("retry stopped, total timeout " + totalTimeoutMs + " ms would pass: " + last.describe());
                throw last;
            }
            YLog.w("retry " + attempt + "/" + policy.maxRetries + " in " + delay + " ms: " + last.describe());
            if (!sleeper.sleep(token, delay)) {
                throw cancelled(attempt);
            }
        }
        throw last;
    }

    private static CompatError cancelled(int attempts) {
        CompatError e = CompatError.local(ErrorTable.CANCELLED, "cancelled", null);
        e.setAttempts(attempts);
        return e;
    }

    /** A 2xx body must be a JSON object with a {@code result}; anything else is a protocol or business error. */
    static CompatError checkSuccessBody(HttpTransport.Response r) {
        Map<String, String> m;
        try {
            m = RawJson.members(r.body.trim());
        } catch (IllegalArgumentException e) {
            return new CompatError(ErrorTable.PROTOCOL_ERROR, r.status, false, -1,
                    "response is not a JSON object", r.body, e);
        }
        if (m.containsKey("result") && !"null".equals(m.get("result").trim())) {
            return null;
        }
        long code = RawJson.toLong(m.get("code"), 0);
        if (code == 0) {
            code = RawJson.toLong(m.get("errId"), 0);
        }
        if (code != 0) {
            return ErrorMapper.fromResponse(r);
        }
        return new CompatError(ErrorTable.PROTOCOL_ERROR, r.status, false, -1, "response has no result", r.body, null);
    }

    /**
     * A 2xx answer of a JSON call must be the platform envelope with code 0 (or none) and a
     * {@code data} object; a non-zero code is the business error it names.
     */
    static CompatError checkEnvelope(HttpTransport.Response r) {
        Map<String, String> m;
        try {
            m = RawJson.members(r.body.trim());
        } catch (IllegalArgumentException e) {
            return new CompatError(ErrorTable.PROTOCOL_ERROR, r.status, false, -1,
                    "response is not a JSON object", r.body, e);
        }
        if (RawJson.toLong(m.get("code"), 0) != 0) {
            return ErrorMapper.fromResponse(r);
        }
        String data = m.get("data");
        if (data == null || !data.trim().startsWith("{")) {
            return new CompatError(ErrorTable.PROTOCOL_ERROR, r.status, false, -1, "response has no data object",
                    r.body, null);
        }
        return null;
    }

    /** Headers of every call; the signature is computed once per logical call. */
    private static Map<String, String> headers(String idempotencyKey, String appKey, String signature) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (idempotencyKey != null) {
            h.put("Idempotency-Key", idempotencyKey);
        }
        h.put("X-App-Key", appKey);
        h.put("X-Timestamp", String.valueOf(System.currentTimeMillis() / 1000L));
        h.put("X-Nonce", Codec.randomHex(16));
        h.put("X-Signature", signature);
        h.put("User-Agent", CompatConfig.USER_AGENT);
        h.put("X-Yugu-SDK", CompatConfig.USER_AGENT);
        h.put("Accept", "application/json");
        return h;
    }

    private static Multipart body(Request req) {
        Multipart mp = new Multipart();
        for (Map.Entry<String, String> e : req.fields.entrySet()) {
            if (e.getValue() != null && e.getValue().length() > 0) {
                mp.addText(e.getKey(), e.getValue());
            }
        }
        String name = req.audioFilename != null ? req.audioFilename : req.idempotencyKey + ".wav";
        if (req.audioFile != null) {
            mp.addFile("audio", name, req.audioContentType, req.audioFile);
        } else {
            mp.addBytes("audio", name, req.audioContentType, req.audioBytes == null ? new byte[0] : req.audioBytes);
        }
        return mp;
    }
}
