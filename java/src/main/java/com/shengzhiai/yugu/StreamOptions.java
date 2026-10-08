package com.shengzhiai.yugu;

/**
 * Per session options of {@code streamEvaluate} and {@code streamEvaluateCompat}. Unset fields fall
 * back to {@link ClientOptions}.
 */
public final class StreamOptions {
    /** Default upper bound of the replay buffer, 10 MB. */
    public static final long DEFAULT_REPLAY_BUFFER_BYTES = 10L * 1024 * 1024;

    private static final StreamOptions NONE = builder().build();

    private final String idempotencyKey;
    private final ReconnectPolicy reconnect;
    private final AudioBufferPolicy bufferPolicy;
    private final Long resultTimeoutMs;
    private final Long replayBufferBytes;

    private StreamOptions(Builder b) {
        this.idempotencyKey = b.idempotencyKey;
        this.reconnect = b.reconnect;
        this.bufferPolicy = b.bufferPolicy;
        this.resultTimeoutMs = b.resultTimeoutMs;
        this.replayBufferBytes = b.replayBufferBytes;
    }

    /** @return options without overrides */
    public static StreamOptions none() {
        return NONE;
    }

    /** @return a builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return caller idempotency key, null to generate one */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @return reconnect override, null for the client default */
    public ReconnectPolicy getReconnect() {
        return reconnect;
    }

    /** @return buffer policy override, null for the client default */
    public AudioBufferPolicy getBufferPolicy() {
        return bufferPolicy;
    }

    /** @return result timeout override, null for the client default */
    public Long getResultTimeoutMs() {
        return resultTimeoutMs;
    }

    /** @return replay buffer bound override, null for 10 MB */
    public Long getReplayBufferBytes() {
        return replayBufferBytes;
    }

    /** Builder for {@link StreamOptions}. */
    public static final class Builder {
        private String idempotencyKey;
        private ReconnectPolicy reconnect;
        private AudioBufferPolicy bufferPolicy;
        private Long resultTimeoutMs;
        private Long replayBufferBytes;

        private Builder() {
        }

        /** @param v idempotency key shared by every connection of the session @return this */
        public Builder idempotencyKey(String v) {
            this.idempotencyKey = v;
            return this;
        }

        /** @param v reconnect policy @return this */
        public Builder reconnect(ReconnectPolicy v) {
            this.reconnect = v;
            return this;
        }

        /** @param v buffer policy @return this */
        public Builder bufferPolicy(AudioBufferPolicy v) {
            this.bufferPolicy = v;
            return this;
        }

        /** @param v wait for the final result after end(), in ms @return this */
        public Builder resultTimeoutMs(long v) {
            this.resultTimeoutMs = v;
            return this;
        }

        /** @param v replay buffer bound in bytes @return this */
        public Builder replayBufferBytes(long v) {
            this.replayBufferBytes = v;
            return this;
        }

        /** @return options */
        public StreamOptions build() {
            return new StreamOptions(this);
        }
    }
}
