package com.shengzhiai.yugu.internal;

import com.shengzhiai.yugu.CancellationToken;
import com.shengzhiai.yugu.EventListener;
import com.shengzhiai.yugu.RequestOptions;
import com.shengzhiai.yugu.RetryPolicy;
import com.shengzhiai.yugu.errors.ErrorTable;
import com.shengzhiai.yugu.errors.YuguErrors;
import com.shengzhiai.yugu.errors.YuguException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes one logical REST call: idempotency key, per attempt signing, timeouts, the retry loop of
 * DESIGN 2.3, events and logs.
 */
public final class HttpEngine {
    private static final String TAG = "http";

    /** One REST call. */
    public interface Call<T> {
        /** @return operation name for events and logs */
        String op();

        /** @return HTTP method */
        String method();

        /** @return path below the base URL */
        String path();

        /** @return true for write calls that carry an idempotency key */
        boolean isWrite();

        /** @return body, null for none */
        byte[] body();

        /** @return content type of the body, null for none */
        String contentType();

        /** @return business parameters to sign */
        Map<String, String> signParams();

        /**
         * Parses a 2xx response.
         *
         * @param response response
         * @param key      idempotency key used, may be null
         * @param attempts attempts made
         * @return result
         * @throws YuguException for responses that carry an error or cannot be parsed
         */
        T parse(Response response, String key, int attempts) throws YuguException;
    }

    /** A received response. */
    public static final class Response {
        private final int status;
        private final HttpHeaders headers;
        private final byte[] body;

        Response(int status, HttpHeaders headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body == null ? new byte[0] : body;
        }

        /** @return status */
        public int status() {
            return status;
        }

        /** @param name header @return first value or null */
        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        /** @return body bytes */
        public byte[] body() {
            return body;
        }

        /** @return body as UTF-8 text */
        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** @return true when the response carries {@code Idempotency-Replayed: true} */
        public boolean replayed() {
            return "true".equalsIgnoreCase(header("Idempotency-Replayed"));
        }
    }

    /** Settings of the engine. */
    public static final class Settings {
        final String baseUrl;
        final String userAgent;
        final long readTimeoutMs;
        final long totalTimeoutMs;
        final RetryPolicy retry;
        final boolean autoIdempotencyKey;

        /**
         * @param baseUrl            REST base
         * @param userAgent          User-Agent
         * @param readTimeoutMs      per attempt
         * @param totalTimeoutMs     per call
         * @param retry              policy
         * @param autoIdempotencyKey generate keys for write calls
         */
        public Settings(String baseUrl, String userAgent, long readTimeoutMs, long totalTimeoutMs, RetryPolicy retry,
                        boolean autoIdempotencyKey) {
            this.baseUrl = baseUrl;
            this.userAgent = userAgent;
            this.readTimeoutMs = readTimeoutMs;
            this.totalTimeoutMs = totalTimeoutMs;
            this.retry = retry;
            this.autoIdempotencyKey = autoIdempotencyKey;
        }
    }

    private final HttpClient http;
    private final Settings settings;
    private final Auth auth;
    private final Log log;
    private final EventListener events;
    private final Random random;
    private final Set<CompletableFuture<?>> inflight = ConcurrentHashMap.newKeySet();
    private final Set<CountDownLatch> sleepers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * @param http     client
     * @param settings settings
     * @param auth     credentials
     * @param log      log
     * @param events   listener, may be null
     * @param random   jitter source
     */
    public HttpEngine(HttpClient http, Settings settings, Auth auth, Log log, EventListener events, Random random) {
        this.http = http;
        this.settings = settings;
        this.auth = auth;
        this.log = log;
        this.events = events;
        this.random = random;
    }

    /**
     * Runs the call.
     *
     * @param call    call
     * @param options per call options
     * @param <T>     result type
     * @return result
     * @throws YuguException the last error once retries are exhausted or not allowed
     */
    public <T> T execute(Call<T> call, RequestOptions options) throws YuguException {
        RequestOptions ro = options == null ? RequestOptions.none() : options;
        ensureOpen(null, 1);
        String key = call.isWrite() ? Ids.resolve(ro.getIdempotencyKey(), settings.autoIdempotencyKey) : null;
        RetryPolicy policy = ro.getRetry() != null ? ro.getRetry() : settings.retry;
        long totalMs = ro.getTotalTimeoutMs() != null ? ro.getTotalTimeoutMs() : settings.totalTimeoutMs;
        long readMs = ro.getReadTimeoutMs() != null ? ro.getReadTimeoutMs() : settings.readTimeoutMs;
        if (totalMs <= 0 || readMs <= 0) {
            throw YuguErrors.local(ErrorTable.INVALID_ARGUMENT, "timeouts must be > 0", null);
        }
        boolean mayRetry = !call.isWrite() || key != null;
        if (!mayRetry && policy.getMaxRetries() > 0) {
            log.debug(TAG, call.op() + ": no idempotency key, retries disabled for this write call");
        }
        CancellationToken token = ro.getCancellation();
        long startNs = System.nanoTime();
        long deadlineNs = startNs + TimeUnit.MILLISECONDS.toNanos(totalMs);
        int attempt = 0;
        int lastStatus = 0;
        while (true) {
            attempt++;
            YuguException err;
            try {
                checkCancelled(token, key, attempt);
                ensureOpen(key, attempt);
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
                if (remainingMs <= 0) {
                    throw YuguErrors.create(YuguErrors.local(ErrorTable.TIMEOUT, "total timeout of " + totalMs + " ms exceeded", null)
                            .toDetails().toBuilder().idempotencyKey(key).attempts(attempt).build());
                }
                long attemptMs = Math.min(readMs, remainingMs);
                fireStart(call, attempt);
                Response resp = send(build(call, key, attemptMs, attempt), attemptMs, token, key, attempt);
                lastStatus = resp.status();
                if (resp.status() >= 200 && resp.status() < 300) {
                    T out = call.parse(resp, key, attempt);
                    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
                    if (log.isInfo()) {
                        log.info(TAG, call.op() + " " + resp.status() + " in " + ms + " ms, attempts=" + attempt
                                + (resp.replayed() ? ", replayed" : ""));
                    }
                    fireEnd(call, resp.status(), startNs, attempt, null);
                    return out;
                }
                long retryAfter = RetryAfter.parseMs(resp.header("Retry-After"), System.currentTimeMillis());
                err = YuguErrors.fromHttpResponse(resp.status(), resp.bodyText(), retryAfter, key, attempt);
            } catch (YuguException e) {
                err = e;
            }
            int code = err.getCode();
            boolean stop = code == ErrorTable.CANCELLED || code == ErrorTable.CLIENT_CLOSED || code == ErrorTable.INVALID_ARGUMENT;
            boolean retryable = YuguErrors.isRetryable(err);
            if (stop || !mayRetry || !retryable || attempt > policy.getMaxRetries()) {
                return fail(call, err, lastStatus, startNs, attempt);
            }
            long delay = policy.delayMs(attempt, random, err.getRetryAfterMs());
            long remainingNs = deadlineNs - System.nanoTime();
            if (TimeUnit.MILLISECONDS.toNanos(delay) > remainingNs) {
                log.debug(TAG, call.op() + ": no retry, the wait of " + delay + " ms would pass the total timeout");
                return fail(call, err, lastStatus, startNs, attempt);
            }
            log.warn(TAG, "retry " + attempt + "/" + policy.getMaxRetries() + " in " + delay + " ms: " + describe(err), null);
            fireRetry(call, attempt, delay, err);
            sleep(delay, token, key, attempt);
        }
    }

    private <T> T fail(Call<T> call, YuguException err, int lastStatus, long startNs, int attempts) {
        log.debug(TAG, call.op() + " failed after " + attempts + " attempt(s): " + describe(err));
        fireEnd(call, err.getHttpStatus() != 0 ? err.getHttpStatus() : lastStatus, startNs, attempts, err);
        throw err;
    }

    /**
     * @param e error
     * @return short form used in retry logs, for example {@code HTTP 503 code=50200}
     */
    public static String describe(YuguException e) {
        StringBuilder sb = new StringBuilder();
        if (e.getHttpStatus() != 0) {
            sb.append("HTTP ").append(e.getHttpStatus());
            if (e.getCode() != 0) {
                sb.append(" code=").append(e.getCode());
            }
        } else {
            sb.append(e.getCategory());
            if (e.getCode() != 0) {
                sb.append(" code=").append(e.getCode());
            }
        }
        String m = e.getRawMessage();
        if (m != null && !m.isEmpty()) {
            sb.append(' ').append(m.length() > 120 ? m.substring(0, 120) : m);
        }
        return sb.toString();
    }

    private HttpRequest build(Call<?> call, String key, long timeoutMs, int attempt) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(settings.baseUrl + call.path()))
                .timeout(Duration.ofMillis(timeoutMs));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", settings.userAgent);
        headers.put("Accept", "application/json");
        if (key != null) {
            headers.put("Idempotency-Key", key);
        }
        headers.putAll(auth.restHeaders(call.signParams()));
        if (call.body() != null) {
            headers.put("Content-Type", call.contentType());
            b.method(call.method(), HttpRequest.BodyPublishers.ofByteArray(call.body()));
        } else {
            b.method(call.method(), HttpRequest.BodyPublishers.noBody());
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            b.header(h.getKey(), h.getValue());
        }
        if (log.isDebug()) {
            log.debug(TAG, call.method() + " " + call.path() + " attempt " + attempt + " timeout " + timeoutMs + " ms"
                    + (call.body() != null ? " body " + call.body().length + " bytes" : "")
                    + " headers " + Redact.headers(headers));
        }
        return b.build();
    }

    private Response send(HttpRequest req, long timeoutMs, CancellationToken token, String key, int attempt) {
        CompletableFuture<HttpResponse<byte[]>> f;
        try {
            f = http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (RuntimeException e) {
            throw YuguErrors.fromThrowable(e, key, attempt);
        }
        inflight.add(f);
        Runnable unregister = token == null ? () -> { } : token.onCancel(() -> f.cancel(true));
        try {
            // the request timeout normally fires first; this bound also covers reading the body
            HttpResponse<byte[]> r = f.get(timeoutMs + 250, TimeUnit.MILLISECONDS);
            return new Response(r.statusCode(), r.headers(), r.body());
        } catch (TimeoutException e) {
            f.cancel(true);
            throw YuguErrors.fromThrowable(new HttpTimeoutException("read timeout after " + timeoutMs + " ms"), key, attempt);
        } catch (ExecutionException e) {
            if (closed) {
                throw closedError(key, attempt);
            }
            throw YuguErrors.fromThrowable(e.getCause(), key, attempt);
        } catch (CancellationException e) {
            if (closed) {
                throw closedError(key, attempt);
            }
            throw cancelledError(key, attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            throw cancelledError(key, attempt);
        } finally {
            inflight.remove(f);
            unregister.run();
        }
    }

    private void sleep(long ms, CancellationToken token, String key, int attempt) {
        if (ms <= 0) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        sleepers.add(latch);
        Runnable unregister = token == null ? () -> { } : token.onCancel(latch::countDown);
        try {
            if (closed) {
                latch.countDown();
            }
            latch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw cancelledError(key, attempt);
        } finally {
            sleepers.remove(latch);
            unregister.run();
        }
        ensureOpen(key, attempt);
        checkCancelled(token, key, attempt);
    }

    private void checkCancelled(CancellationToken token, String key, int attempt) {
        if ((token != null && token.isCancelled()) || Thread.currentThread().isInterrupted()) {
            throw cancelledError(key, attempt);
        }
    }

    private void ensureOpen(String key, int attempt) {
        if (closed) {
            throw closedError(key, attempt);
        }
    }

    private static YuguException cancelledError(String key, int attempt) {
        return YuguErrors.create(YuguErrors.local(ErrorTable.CANCELLED, "request cancelled", null).toDetails().toBuilder()
                .idempotencyKey(key).attempts(attempt).build());
    }

    private static YuguException closedError(String key, int attempt) {
        return YuguErrors.create(YuguErrors.local(ErrorTable.CLIENT_CLOSED, "client is closed", null).toDetails().toBuilder()
                .idempotencyKey(key).attempts(attempt).build());
    }

    /**
     * Rejects new calls, aborts running attempts and ends retry waits. Idempotent.
     */
    public void close() {
        closed = true;
        for (CompletableFuture<?> f : inflight) {
            f.cancel(true);
        }
        for (CountDownLatch l : sleepers) {
            l.countDown();
        }
    }

    /** @return true after {@link #close()} */
    public boolean isClosed() {
        return closed;
    }

    private void fireStart(Call<?> call, int attempt) {
        if (events != null) {
            try {
                events.onRequestStart(call.op(), call.method(), call.path(), attempt);
            } catch (RuntimeException e) {
                log.warn(TAG, "eventListener.onRequestStart threw", e);
            }
        }
    }

    private void fireEnd(Call<?> call, int status, long startNs, int attempts, YuguException error) {
        if (events != null) {
            try {
                events.onRequestEnd(call.op(), status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs), attempts, error);
            } catch (RuntimeException e) {
                log.warn(TAG, "eventListener.onRequestEnd threw", e);
            }
        }
    }

    private void fireRetry(Call<?> call, int attempt, long delay, YuguException err) {
        if (events != null) {
            try {
                events.onRetry(call.op(), attempt, delay, err);
            } catch (RuntimeException e) {
                log.warn(TAG, "eventListener.onRetry threw", e);
            }
        }
    }
}
