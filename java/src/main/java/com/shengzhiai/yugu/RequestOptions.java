package com.shengzhiai.yugu;

/**
 * Per call options of the REST methods. Unset fields fall back to {@link ClientOptions}.
 */
public final class RequestOptions {
    private static final RequestOptions NONE = builder().build();

    private final String idempotencyKey;
    private final Long totalTimeoutMs;
    private final Long readTimeoutMs;
    private final RetryPolicy retry;
    private final CancellationToken cancellation;

    private RequestOptions(Builder b) {
        this.idempotencyKey = b.idempotencyKey;
        this.totalTimeoutMs = b.totalTimeoutMs;
        this.readTimeoutMs = b.readTimeoutMs;
        this.retry = b.retry;
        this.cancellation = b.cancellation;
    }

    /** @return options without overrides */
    public static RequestOptions none() {
        return NONE;
    }

    /** @return a builder */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Shortcut for a call with a caller chosen idempotency key.
     *
     * @param key 1 to 200 printable ASCII characters
     * @return options
     */
    public static RequestOptions withIdempotencyKey(String key) {
        return builder().idempotencyKey(key).build();
    }

    /** @return caller idempotency key, null to generate one */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @return total timeout override, null for the client default */
    public Long getTotalTimeoutMs() {
        return totalTimeoutMs;
    }

    /** @return per attempt read timeout override, null for the client default */
    public Long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /** @return retry override, null for the client default */
    public RetryPolicy getRetry() {
        return retry;
    }

    /** @return cancellation token, may be null */
    public CancellationToken getCancellation() {
        return cancellation;
    }

    /** Builder for {@link RequestOptions}. */
    public static final class Builder {
        private String idempotencyKey;
        private Long totalTimeoutMs;
        private Long readTimeoutMs;
        private RetryPolicy retry;
        private CancellationToken cancellation;

        private Builder() {
        }

        /**
         * Uses this key instead of a generated one. It must match {@code ^[\x21-\x7E]{1,200}$}, otherwise
         * the call throws {@code InvalidParameterException} (90010) before any I/O. Reuse the same key
         * when the same audio is submitted again, so the platform bills it once.
         *
         * @param v key
         * @return this
         */
        public Builder idempotencyKey(String v) {
            this.idempotencyKey = v;
            return this;
        }

        /** @param v deadline of the call including retries and waits, in ms @return this */
        public Builder totalTimeoutMs(long v) {
            this.totalTimeoutMs = v;
            return this;
        }

        /** @param v read timeout of each attempt, in ms @return this */
        public Builder readTimeoutMs(long v) {
            this.readTimeoutMs = v;
            return this;
        }

        /** @param v retry policy of this call @return this */
        public Builder retry(RetryPolicy v) {
            this.retry = v;
            return this;
        }

        /** @param v token that cancels the call @return this */
        public Builder cancellation(CancellationToken v) {
            this.cancellation = v;
            return this;
        }

        /** @return options */
        public RequestOptions build() {
            return new RequestOptions(this);
        }
    }
}
