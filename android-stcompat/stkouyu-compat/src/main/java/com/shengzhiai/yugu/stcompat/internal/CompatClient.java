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

        public String url() {
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return base + "/" + coreType;
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

    public Result execute(Request req, CancelToken token) throws CompatError {
        if (token == null) {
            token = new CancelToken();
        }
        RetryPolicy policy = req.policy == null ? RetryPolicy.DEFAULT : req.policy;
        long deadline = System.nanoTime() / 1000000L + req.totalTimeoutMs;
        String signature = Signer.sign(req.fields, req.secretKey);
        int maxAttempts = policy.maxRetries + 1;
        CompatError last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (token.isCancelled()) {
                throw cancelled(attempt - 1);
            }
            long now = System.nanoTime() / 1000000L;
            int readTimeout = (int) Math.max(1L, Math.min((long) req.readTimeoutMs, deadline - now));
            HttpTransport.Request http = new HttpTransport.Request("POST", req.url(), headers(req, signature),
                    body(req), req.connectTimeoutMs, readTimeout);
            if (YLog.isLoggable(YLog.DEBUG)) {
                YLog.d("POST " + req.url() + " attempt " + attempt + "/" + maxAttempts + " key=" + req.idempotencyKey
                        + " appKey=" + YLog.maskKey(req.appKey) + " fields=" + req.fields.keySet());
            }
            try {
                HttpTransport.Response r = transport.execute(http, token);
                if (token.isCancelled()) {
                    throw cancelled(attempt);
                }
                if (r.status >= 200 && r.status < 300) {
                    CompatError bad = checkSuccessBody(r);
                    if (bad == null) {
                        boolean replayed = "true".equalsIgnoreCase(r.header("Idempotency-Replayed"));
                        if (replayed) {
                            YLog.i("response replayed for Idempotency-Key " + req.idempotencyKey);
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
                YLog.w("retry stopped, total timeout " + req.totalTimeoutMs + " ms would pass: " + last.describe());
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

    private static Map<String, String> headers(Request req, String signature) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Idempotency-Key", req.idempotencyKey);
        h.put("X-App-Key", req.appKey);
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
