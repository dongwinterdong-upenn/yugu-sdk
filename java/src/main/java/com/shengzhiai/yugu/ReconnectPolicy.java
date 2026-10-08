package com.shengzhiai.yugu;

import java.util.Random;

/**
 * Reconnect policy of stream sessions (requirement A-03). Delay before reconnect attempt {@code n}:
 * {@code min(maxDelayMs, initialDelayMs * multiplier^(n-1)) * (1 + U(-jitter, +jitter))}.
 *
 * <p>Defaults: enabled, 8 attempts, 500 ms, factor 2, at most 4000 ms, jitter 0.3. The waits are about
 * 0.5, 1, 2, 4, 4, 4, 4 and 4 s, about 23 s in total, so the default settings ride out a network drop of
 * 10 s. {@code maxAttempts} counts consecutive failed attempts; the counter starts again after each
 * successful reconnect, so it bounds one outage.
 */
public final class ReconnectPolicy {
    private static final ReconnectPolicy DEFAULTS = builder().build();
    private static final ReconnectPolicy DISABLED = builder().enabled(false).build();

    private final boolean enabled;
    private final int maxAttempts;
    private final long initialDelayMs;
    private final double multiplier;
    private final long maxDelayMs;
    private final double jitter;

    private ReconnectPolicy(Builder b) {
        if (b.maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0");
        }
        if (b.initialDelayMs < 0 || b.maxDelayMs < 0) {
            throw new IllegalArgumentException("delays must be >= 0");
        }
        if (b.multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1");
        }
        if (b.jitter < 0 || b.jitter > 1) {
            throw new IllegalArgumentException("jitter must be in [0, 1]");
        }
        this.enabled = b.enabled;
        this.maxAttempts = b.maxAttempts;
        this.initialDelayMs = b.initialDelayMs;
        this.multiplier = b.multiplier;
        this.maxDelayMs = b.maxDelayMs;
        this.jitter = b.jitter;
    }

    /** @return the default policy */
    public static ReconnectPolicy defaults() {
        return DEFAULTS;
    }

    /** @return a policy that never reconnects */
    public static ReconnectPolicy disabled() {
        return DISABLED;
    }

    /** @return a builder with the defaults */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder initialised with this policy */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.enabled = enabled;
        b.maxAttempts = maxAttempts;
        b.initialDelayMs = initialDelayMs;
        b.multiplier = multiplier;
        b.maxDelayMs = maxDelayMs;
        b.jitter = jitter;
        return b;
    }

    /**
     * @param n reconnect attempt, from 1
     * @return delay without jitter
     */
    public long baseDelayMs(int n) {
        double d = initialDelayMs * Math.pow(multiplier, Math.max(0, n - 1));
        return (long) Math.min((double) maxDelayMs, d);
    }

    /**
     * @param n          reconnect attempt, from 1
     * @param unitRandom uniform random value in [0, 1)
     * @return delay in milliseconds
     */
    public long delayMs(int n, double unitRandom) {
        double u = (unitRandom * 2 - 1) * jitter;
        return Math.max(0, Math.round(baseDelayMs(n) * (1 + u)));
    }

    /**
     * @param n      reconnect attempt, from 1
     * @param random random source
     * @return delay in milliseconds
     */
    public long delayMs(int n, Random random) {
        return delayMs(n, random.nextDouble());
    }

    /** @return whether sessions reconnect */
    public boolean isEnabled() {
        return enabled;
    }

    /** @return attempts per outage */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** @return first delay */
    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    /** @return factor */
    public double getMultiplier() {
        return multiplier;
    }

    /** @return cap before jitter */
    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    /** @return relative jitter */
    public double getJitter() {
        return jitter;
    }

    @Override
    public String toString() {
        return "ReconnectPolicy{enabled=" + enabled + ", maxAttempts=" + maxAttempts + ", initialDelayMs=" + initialDelayMs
                + ", multiplier=" + multiplier + ", maxDelayMs=" + maxDelayMs + ", jitter=" + jitter + '}';
    }

    /** Builder for {@link ReconnectPolicy}. */
    public static final class Builder {
        private boolean enabled = true;
        private int maxAttempts = 8;
        private long initialDelayMs = 500;
        private double multiplier = 2.0;
        private long maxDelayMs = 4000;
        private double jitter = 0.3;

        private Builder() {
        }

        /** @param v reconnect at all, default true @return this */
        public Builder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        /** @param v consecutive failed attempts allowed per outage, default 8 @return this */
        public Builder maxAttempts(int v) {
            this.maxAttempts = v;
            return this;
        }

        /** @param v first delay, default 500 ms @return this */
        public Builder initialDelayMs(long v) {
            this.initialDelayMs = v;
            return this;
        }

        /** @param v factor, default 2 @return this */
        public Builder multiplier(double v) {
            this.multiplier = v;
            return this;
        }

        /** @param v cap before jitter, default 4000 ms @return this */
        public Builder maxDelayMs(long v) {
            this.maxDelayMs = v;
            return this;
        }

        /** @param v relative jitter, default 0.3 @return this */
        public Builder jitter(double v) {
            this.jitter = v;
            return this;
        }

        /** @return the policy */
        public ReconnectPolicy build() {
            return new ReconnectPolicy(this);
        }
    }
}
