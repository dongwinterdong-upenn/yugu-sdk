// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

/**
 * Failure of one logical compat call. {@code code} is the platform business code when the
 * response carried one, otherwise a local code from spec/errors.json (90001 network, 90002
 * timeout, 90003 cancelled, 90005 protocol, 90011 TLS) or 0 when only the HTTP status is known.
 */
public final class CompatError extends Exception {
    private static final long serialVersionUID = 1L;

    public final int code;
    public final int httpStatus;
    public final boolean retryable;
    /** Retry-After of the response in ms, -1 when absent. */
    public final long retryAfterMs;
    /** Response body, truncated to 4 KB. */
    public final String rawBody;
    private int attempts;

    public CompatError(int code, int httpStatus, boolean retryable, long retryAfterMs, String message, String rawBody,
                       Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.retryAfterMs = retryAfterMs;
        this.rawBody = rawBody == null ? null : (rawBody.length() > 4096 ? rawBody.substring(0, 4096) : rawBody);
    }

    public static CompatError local(int code, String message, Throwable cause) {
        ErrorTable.Entry e = ErrorTable.LOCAL.get(code);
        boolean retryable = e != null && e.retryable;
        return new CompatError(code, 0, retryable, -1, message, null, cause);
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public boolean isCancelled() {
        return code == ErrorTable.CANCELLED;
    }

    /** Short description used in retry logs, for example {@code HTTP 503 code=50200}. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (httpStatus > 0) {
            sb.append("HTTP ").append(httpStatus);
            if (code != 0) {
                sb.append(" code=").append(code);
            }
        } else {
            ErrorTable.Entry e = ErrorTable.LOCAL.get(code);
            sb.append(e != null ? e.name : "code=" + code);
        }
        return sb.toString();
    }
}
