package com.shengzhiai.yugu;

import com.shengzhiai.yugu.errors.ErrorTable;
import com.shengzhiai.yugu.errors.YuguErrors;
import com.shengzhiai.yugu.internal.Redact;
import com.shengzhiai.yugu.internal.WsTransport;

import java.net.URI;
import java.util.Locale;
import java.util.Random;

/**
 * Immutable client configuration. Build it with {@link #builder()} or directly through
 * {@link YuguClient#builder()}.
 *
 * <table>
 *   <caption>Defaults</caption>
 *   <tr><th>Option</th><th>Default</th></tr>
 *   <tr><td>baseUrl</td><td>{@code https://open.shengzhiai.com}</td></tr>
 *   <tr><td>wsBaseUrl</td><td>{@code wss://open.shengzhiai.com}, or derived from baseUrl when only baseUrl is set</td></tr>
 *   <tr><td>connectTimeoutMs</td><td>10000</td></tr>
 *   <tr><td>readTimeoutMs</td><td>120000 per attempt</td></tr>
 *   <tr><td>totalTimeoutMs</td><td>300000 per call including retries</td></tr>
 *   <tr><td>retry</td><td>{@link RetryPolicy#defaults()}</td></tr>
 *   <tr><td>autoIdempotencyKey</td><td>true</td></tr>
 *   <tr><td>logLevel</td><td>WARN</td></tr>
 *   <tr><td>logger</td><td>standard error</td></tr>
 *   <tr><td>audioPrecheck</td><td>WARN</td></tr>
 *   <tr><td>userAgent</td><td>{@code yugu-java-sdk/2.0.0}</td></tr>
 *   <tr><td>reconnect</td><td>{@link ReconnectPolicy#defaults()}</td></tr>
 *   <tr><td>audioBufferPolicy</td><td>REPLAY</td></tr>
 *   <tr><td>pingIntervalMs, pongTimeoutMs</td><td>15000, 30000</td></tr>
 *   <tr><td>resultTimeoutMs</td><td>300000 after end()</td></tr>
 * </table>
 */
public final class ClientOptions {
    /** Default REST base. */
    public static final String DEFAULT_BASE_URL = "https://open.shengzhiai.com";
    /** Default WebSocket base. */
    public static final String DEFAULT_WS_BASE_URL = "wss://open.shengzhiai.com";

    private final String baseUrl;
    private final String wsBaseUrl;
    private final String token;
    private final String appKey;
    private final String secretKey;
    private final long connectTimeoutMs;
    private final long readTimeoutMs;
    private final long totalTimeoutMs;
    private final RetryPolicy retry;
    private final boolean autoIdempotencyKey;
    private final LogLevel logLevel;
    private final YuguLogger logger;
    private final EventListener eventListener;
    private final AudioPrecheckMode audioPrecheck;
    private final boolean strictAudio;
    private final String userAgent;
    private final ReconnectPolicy reconnect;
    private final AudioBufferPolicy audioBufferPolicy;
    private final long pingIntervalMs;
    private final long pongTimeoutMs;
    private final long resultTimeoutMs;
    private final WsTransport transport;
    private final Random random;

    private ClientOptions(BaseBuilder<?> b) {
        boolean hasToken = b.token != null && !b.token.isEmpty();
        boolean hasKey = b.appKey != null && !b.appKey.isEmpty() && b.secretKey != null && !b.secretKey.isEmpty();
        if (!hasToken && !hasKey) {
            throw invalid("credentials missing: set token(...) or apiKey(appKey, secretKey)");
        }
        this.baseUrl = stripSlash(checkUrl(b.baseUrl == null ? DEFAULT_BASE_URL : b.baseUrl, "baseUrl", "http", "https"));
        String ws = b.wsBaseUrl;
        if (ws == null) {
            ws = b.baseUrl == null ? DEFAULT_WS_BASE_URL : deriveWs(this.baseUrl);
        }
        this.wsBaseUrl = stripSlash(checkUrl(ws, "wsBaseUrl", "ws", "wss"));
        this.token = hasKey ? null : b.token;
        this.appKey = hasKey ? b.appKey : null;
        this.secretKey = hasKey ? b.secretKey : null;
        this.connectTimeoutMs = positive(b.connectTimeoutMs, "connectTimeoutMs");
        this.readTimeoutMs = positive(b.readTimeoutMs, "readTimeoutMs");
        this.totalTimeoutMs = positive(b.totalTimeoutMs, "totalTimeoutMs");
        this.retry = b.retry == null ? RetryPolicy.defaults() : b.retry;
        this.autoIdempotencyKey = b.autoIdempotencyKey;
        this.logLevel = b.logLevel == null ? LogLevel.WARN : b.logLevel;
        this.logger = b.logger;
        this.eventListener = b.eventListener;
        this.audioPrecheck = b.audioPrecheck == null ? AudioPrecheckMode.WARN : b.audioPrecheck;
        this.strictAudio = b.strictAudio;
        this.userAgent = b.userAgent == null || b.userAgent.isEmpty() ? YuguClient.USER_AGENT : b.userAgent;
        this.reconnect = b.reconnect == null ? ReconnectPolicy.defaults() : b.reconnect;
        this.audioBufferPolicy = b.audioBufferPolicy == null ? AudioBufferPolicy.REPLAY : b.audioBufferPolicy;
        this.pingIntervalMs = positive(b.pingIntervalMs, "pingIntervalMs");
        this.pongTimeoutMs = positive(b.pongTimeoutMs, "pongTimeoutMs");
        this.resultTimeoutMs = positive(b.resultTimeoutMs, "resultTimeoutMs");
        this.transport = b.transport;
        this.random = b.random;
    }

    private static RuntimeException invalid(String message) {
        return YuguErrors.local(ErrorTable.INVALID_ARGUMENT, message, null);
    }

    private static long positive(long v, String name) {
        if (v <= 0) {
            throw invalid(name + " must be > 0, got " + v);
        }
        return v;
    }

    private static String checkUrl(String url, String name, String... schemes) {
        try {
            URI u = new URI(url);
            String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            for (String ok : schemes) {
                if (ok.equals(s) && u.getHost() != null) {
                    return url;
                }
            }
        } catch (java.net.URISyntaxException e) {
            throw invalid(name + " is not a valid URL: " + url);
        }
        throw invalid(name + " must use " + String.join(" or ", schemes) + ": " + url);
    }

    private static String deriveWs(String base) {
        if (base.regionMatches(true, 0, "https://", 0, 8)) {
            return "wss://" + base.substring(8);
        }
        return "ws://" + base.substring(base.indexOf("://") + 3);
    }

    private static String stripSlash(String s) {
        String r = s;
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    /** @return a builder with the defaults */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder initialised with these options */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.copyFrom(this);
        return b;
    }

    /** @return REST base without trailing slash */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** @return WebSocket base without trailing slash */
    public String getWsBaseUrl() {
        return wsBaseUrl;
    }

    /** @return true for token (JWT) authentication */
    public boolean isTokenAuth() {
        return token != null;
    }

    /** @return app key, null for token authentication */
    public String getAppKey() {
        return appKey;
    }

    /** @return JWT, null for key authentication; SDK internal */
    String getToken() {
        return token;
    }

    /** @return secret key; SDK internal */
    String getSecretKey() {
        return secretKey;
    }

    /** @return connect timeout */
    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /** @return read timeout per attempt */
    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /** @return deadline per call */
    public long getTotalTimeoutMs() {
        return totalTimeoutMs;
    }

    /** @return retry policy */
    public RetryPolicy getRetry() {
        return retry;
    }

    /** @return whether write calls get a generated idempotency key */
    public boolean isAutoIdempotencyKey() {
        return autoIdempotencyKey;
    }

    /** @return log level */
    public LogLevel getLogLevel() {
        return logLevel;
    }

    /** @return logger, null for standard error */
    public YuguLogger getLogger() {
        return logger;
    }

    /** @return metrics listener, may be null */
    public EventListener getEventListener() {
        return eventListener;
    }

    /** @return precheck mode */
    public AudioPrecheckMode getAudioPrecheck() {
        return audioPrecheck;
    }

    /** @return whether warning 1001 in a result is thrown as {@code AudioQualityException} */
    public boolean isStrictAudio() {
        return strictAudio;
    }

    /** @return User-Agent */
    public String getUserAgent() {
        return userAgent;
    }

    /** @return reconnect policy of streams */
    public ReconnectPolicy getReconnect() {
        return reconnect;
    }

    /** @return buffer policy of streams */
    public AudioBufferPolicy getAudioBufferPolicy() {
        return audioBufferPolicy;
    }

    /** @return protocol ping interval */
    public long getPingIntervalMs() {
        return pingIntervalMs;
    }

    /** @return pong timeout after which the connection counts as lost */
    public long getPongTimeoutMs() {
        return pongTimeoutMs;
    }

    /** @return wait for the final result after end() */
    public long getResultTimeoutMs() {
        return resultTimeoutMs;
    }

    WsTransport getTransport() {
        return transport;
    }

    Random getRandom() {
        return random;
    }

    @Override
    public String toString() {
        return "ClientOptions{baseUrl=" + baseUrl + ", wsBaseUrl=" + wsBaseUrl
                + ", auth=" + (token != null ? "token(***)" : "appKey(" + Redact.appKey(appKey) + ")")
                + ", connectTimeoutMs=" + connectTimeoutMs + ", readTimeoutMs=" + readTimeoutMs
                + ", totalTimeoutMs=" + totalTimeoutMs + ", retry=" + retry + ", autoIdempotencyKey=" + autoIdempotencyKey
                + ", logLevel=" + logLevel + ", audioPrecheck=" + audioPrecheck + ", strictAudio=" + strictAudio
                + ", userAgent=" + userAgent + ", reconnect=" + reconnect + ", audioBufferPolicy=" + audioBufferPolicy
                + ", pingIntervalMs=" + pingIntervalMs + ", pongTimeoutMs=" + pongTimeoutMs
                + ", resultTimeoutMs=" + resultTimeoutMs + '}';
    }

    /** Builder that produces {@link ClientOptions}. */
    public static final class Builder extends BaseBuilder<Builder> {
        Builder() {
        }

        /** @return the options */
        public ClientOptions build() {
            return buildOptions();
        }
    }

    /**
     * Setters shared by {@link ClientOptions.Builder} and {@link YuguClient.Builder}.
     *
     * @param <B> concrete builder type
     */
    public abstract static class BaseBuilder<B extends BaseBuilder<B>> {
        private String baseUrl;
        private String wsBaseUrl;
        private String token;
        private String appKey;
        private String secretKey;
        private long connectTimeoutMs = 10_000;
        private long readTimeoutMs = 120_000;
        private long totalTimeoutMs = 300_000;
        private RetryPolicy retry;
        private boolean autoIdempotencyKey = true;
        private LogLevel logLevel;
        private YuguLogger logger;
        private EventListener eventListener;
        private AudioPrecheckMode audioPrecheck;
        private boolean strictAudio;
        private String userAgent;
        private ReconnectPolicy reconnect;
        private AudioBufferPolicy audioBufferPolicy;
        private long pingIntervalMs = 15_000;
        private long pongTimeoutMs = 30_000;
        private long resultTimeoutMs = 300_000;
        private WsTransport transport;
        private Random random;

        BaseBuilder() {
        }

        @SuppressWarnings("unchecked")
        private B self() {
            return (B) this;
        }

        void copyFrom(ClientOptions o) {
            baseUrl = o.baseUrl;
            wsBaseUrl = o.wsBaseUrl;
            token = o.token;
            appKey = o.appKey;
            secretKey = o.secretKey;
            connectTimeoutMs = o.connectTimeoutMs;
            readTimeoutMs = o.readTimeoutMs;
            totalTimeoutMs = o.totalTimeoutMs;
            retry = o.retry;
            autoIdempotencyKey = o.autoIdempotencyKey;
            logLevel = o.logLevel;
            logger = o.logger;
            eventListener = o.eventListener;
            audioPrecheck = o.audioPrecheck;
            strictAudio = o.strictAudio;
            userAgent = o.userAgent;
            reconnect = o.reconnect;
            audioBufferPolicy = o.audioBufferPolicy;
            pingIntervalMs = o.pingIntervalMs;
            pongTimeoutMs = o.pongTimeoutMs;
            resultTimeoutMs = o.resultTimeoutMs;
            transport = o.transport;
            random = o.random;
        }

        ClientOptions buildOptions() {
            return new ClientOptions(this);
        }

        /**
         * REST base, default {@code https://open.shengzhiai.com}. When {@link #wsBaseUrl(String)} is not
         * set, the WebSocket base is derived from it (http to ws, https to wss).
         *
         * @param v URL
         * @return this
         */
        public B baseUrl(String v) {
            this.baseUrl = v;
            return self();
        }

        /** @param v WebSocket base, default {@code wss://open.shengzhiai.com} @return this */
        public B wsBaseUrl(String v) {
            this.wsBaseUrl = v;
            return self();
        }

        /** @param v JWT for Bearer authentication @return this */
        public B token(String v) {
            this.token = v;
            return self();
        }

        /**
         * Key pair for signature authentication, recommended for servers.
         *
         * @param appKey    app key
         * @param secretKey secret key, never sent or logged
         * @return this
         */
        public B apiKey(String appKey, String secretKey) {
            this.appKey = appKey;
            this.secretKey = secretKey;
            return self();
        }

        /** @param v connect timeout in ms, default 10000 @return this */
        public B connectTimeoutMs(long v) {
            this.connectTimeoutMs = v;
            return self();
        }

        /** @param v read timeout per attempt in ms, default 120000 @return this */
        public B readTimeoutMs(long v) {
            this.readTimeoutMs = v;
            return self();
        }

        /** @param v deadline per call including retries in ms, default 300000 @return this */
        public B totalTimeoutMs(long v) {
            this.totalTimeoutMs = v;
            return self();
        }

        /** @param v retry policy, default {@link RetryPolicy#defaults()} @return this */
        public B retry(RetryPolicy v) {
            this.retry = v;
            return self();
        }

        /**
         * Generate an idempotency key for write calls without one, default true. When false and the
         * caller passes no key, write calls are not retried.
         *
         * @param v flag
         * @return this
         */
        public B autoIdempotencyKey(boolean v) {
            this.autoIdempotencyKey = v;
            return self();
        }

        /** @param v log level, default WARN @return this */
        public B logLevel(LogLevel v) {
            this.logLevel = v;
            return self();
        }

        /** @param v log sink, default standard error @return this */
        public B logger(YuguLogger v) {
            this.logger = v;
            return self();
        }

        /** @param v metrics listener @return this */
        public B eventListener(EventListener v) {
            this.eventListener = v;
            return self();
        }

        /** @param v audio precheck mode, default WARN @return this */
        public B audioPrecheck(AudioPrecheckMode v) {
            this.audioPrecheck = v;
            return self();
        }

        /**
         * Throw {@code AudioQualityException} when the platform reports warning 1001 (no valid audio)
         * instead of returning the result, default false.
         *
         * @param v flag
         * @return this
         */
        public B strictAudio(boolean v) {
            this.strictAudio = v;
            return self();
        }

        /** @param v User-Agent, default {@code yugu-java-sdk/2.0.0} @return this */
        public B userAgent(String v) {
            this.userAgent = v;
            return self();
        }

        /** @param v reconnect policy of streams @return this */
        public B reconnect(ReconnectPolicy v) {
            this.reconnect = v;
            return self();
        }

        /** @param v buffer policy of streams, default REPLAY @return this */
        public B audioBufferPolicy(AudioBufferPolicy v) {
            this.audioBufferPolicy = v;
            return self();
        }

        /** @param v protocol ping interval in ms, default 15000 @return this */
        public B pingIntervalMs(long v) {
            this.pingIntervalMs = v;
            return self();
        }

        /** @param v pong timeout in ms, default 30000 @return this */
        public B pongTimeoutMs(long v) {
            this.pongTimeoutMs = v;
            return self();
        }

        /** @param v wait for the final result after end() in ms, default 300000 @return this */
        public B resultTimeoutMs(long v) {
            this.resultTimeoutMs = v;
            return self();
        }

        /** Test hook: replaces the WebSocket transport. */
        B transport(WsTransport v) {
            this.transport = v;
            return self();
        }

        /** Test hook: random source of the backoff jitter. */
        B random(Random v) {
            this.random = v;
            return self();
        }
    }
}
