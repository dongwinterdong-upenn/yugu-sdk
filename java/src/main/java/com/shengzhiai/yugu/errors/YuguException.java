package com.shengzhiai.yugu.errors;

/**
 * Base type of every error the SDK throws or reports.
 *
 * <p>Branch on the subclass ({@link AuthException}, {@link RateLimitException} and so on) or on
 * {@link #getCategory()}. {@link #isRetryable()} uses the same rules as the SDK retry loop and the
 * WebSocket reconnect logic, see {@link YuguErrors#isRetryable(Throwable)}.
 *
 * <p>The exception is unchecked. Methods still declare it in their {@code throws} clause so that it
 * shows up in the API documentation.
 */
public class YuguException extends RuntimeException {
    private static final long serialVersionUID = 2L;

    private final ErrorCategory category;
    private final int code;
    private final CodeKind codeKind;
    private final int httpStatus;
    private final boolean retryable;
    private final String rawMessage;
    private final String idempotencyKey;
    private final String recordId;
    private final int attempts;
    private final String rawBody;
    private final long retryAfterMs;

    /**
     * Creates an exception from details. The retryable flag is derived from the code, its table and
     * the HTTP status, never passed in, so every exception agrees with {@link YuguErrors#isRetryable}.
     *
     * @param details error details
     */
    public YuguException(ErrorDetails details) {
        super(describe(details), details.cause);
        this.category = details.category;
        this.code = details.code;
        this.codeKind = details.codeKind;
        this.httpStatus = details.httpStatus;
        this.retryable = YuguErrors.isRetryable(details.codeKind, details.code, details.httpStatus);
        this.rawMessage = details.message;
        this.idempotencyKey = details.idempotencyKey;
        this.recordId = details.recordId;
        this.attempts = details.attempts;
        this.rawBody = details.rawBody;
        this.retryAfterMs = details.retryAfterMs;
    }

    private static String describe(ErrorDetails d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.message == null || d.message.isEmpty() ? d.category.name() : d.message);
        sb.append(" (category=").append(d.category);
        if (d.code != 0) {
            sb.append(", code=").append(d.code);
        }
        if (d.httpStatus != 0) {
            sb.append(", httpStatus=").append(d.httpStatus);
        }
        if (d.attempts > 1) {
            sb.append(", attempts=").append(d.attempts);
        }
        if (d.idempotencyKey != null) {
            sb.append(", idempotencyKey=").append(d.idempotencyKey);
        }
        sb.append(')');
        return sb.toString();
    }

    /** @return error category */
    public ErrorCategory getCategory() {
        return category;
    }

    /**
     * Business code from the platform, or a local code 90001 to 90203. 0 when the error carried no
     * code and was classified by HTTP status or local cause.
     *
     * @return code or 0
     */
    public int getCode() {
        return code;
    }

    /** @return table of spec/errors.json the code comes from */
    public CodeKind getCodeKind() {
        return codeKind;
    }

    /** @return the constant name of the code in spec/errors.json, or null when unlisted */
    public String getCodeName() {
        ErrorTable.Entry e = YuguErrors.entry(codeKind, code);
        return e == null ? null : e.name;
    }

    /** @return HTTP status of the failed response, 0 when there was no HTTP response */
    public int getHttpStatus() {
        return httpStatus;
    }

    /** @return whether repeating the same request with the same idempotency key may succeed */
    public boolean isRetryable() {
        return retryable;
    }

    /** @return the message from the platform or the SDK, without the context suffix */
    public String getRawMessage() {
        return rawMessage;
    }

    /** @return idempotency key of the failed call, null for calls without one */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @return platform record id when known, else null */
    public String getRecordId() {
        return recordId;
    }

    /** @return attempts made before this error was raised, at least 1 */
    public int getAttempts() {
        return attempts;
    }

    /** @return raw response body or WebSocket frame, truncated to 4 KB, null when none */
    public String getRawBody() {
        return rawBody;
    }

    /** @return Retry-After of the response in milliseconds, -1 when absent */
    public long getRetryAfterMs() {
        return retryAfterMs;
    }

    /** @return the details this exception was built from, for re-wrapping with more context */
    public ErrorDetails toDetails() {
        return ErrorDetails.builder()
                .category(category)
                .code(code)
                .codeKind(codeKind)
                .httpStatus(httpStatus)
                .message(rawMessage)
                .idempotencyKey(idempotencyKey)
                .recordId(recordId)
                .attempts(attempts)
                .rawBody(rawBody)
                .retryAfterMs(retryAfterMs)
                .cause(getCause())
                .build();
    }
}
