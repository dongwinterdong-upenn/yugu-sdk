package com.shengzhiai.yugu.errors;

/**
 * Rate or concurrency limit (HTTP 429, 42900, 42901, 3001 to 3003). Retryable; honour {@link #getRetryAfterMs()}.
 *
 * <p>Category {@link ErrorCategory#RATE_LIMIT}.
 */
public class RateLimitException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public RateLimitException(ErrorDetails details) {
        super(details);
    }
}
