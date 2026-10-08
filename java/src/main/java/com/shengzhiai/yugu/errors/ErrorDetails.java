package com.shengzhiai.yugu.errors;

/**
 * Immutable description of an error, used to construct {@link YuguException} and its subclasses.
 *
 * <p>Most callers never build one: the SDK creates exceptions through {@link YuguErrors}. The class
 * is public so that tests and wrappers can create SDK exceptions with full context.
 */
public final class ErrorDetails {
    final ErrorCategory category;
    final int code;
    final CodeKind codeKind;
    final int httpStatus;
    final String message;
    final String idempotencyKey;
    final String recordId;
    final int attempts;
    final String rawBody;
    final long retryAfterMs;
    final Throwable cause;

    private ErrorDetails(Builder b) {
        this.category = b.category == null ? ErrorCategory.UNKNOWN : b.category;
        this.code = b.code;
        this.codeKind = b.codeKind == null ? YuguErrors.kindOf(b.code) : b.codeKind;
        this.httpStatus = b.httpStatus;
        this.message = b.message == null ? "" : b.message;
        this.idempotencyKey = b.idempotencyKey;
        this.recordId = b.recordId;
        this.attempts = b.attempts;
        this.rawBody = truncate(b.rawBody);
        this.retryAfterMs = b.retryAfterMs;
        this.cause = b.cause;
    }

    /** Maximum length of {@link YuguException#getRawBody()}, 4 KB as required by the design. */
    public static final int RAW_BODY_LIMIT = 4096;

    static String truncate(String s) {
        if (s == null) {
            return null;
        }
        if (s.length() <= RAW_BODY_LIMIT) {
            return s;
        }
        return s.substring(0, RAW_BODY_LIMIT);
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder initialised with the values of this instance */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.category = category;
        b.code = code;
        b.codeKind = codeKind;
        b.httpStatus = httpStatus;
        b.message = message;
        b.idempotencyKey = idempotencyKey;
        b.recordId = recordId;
        b.attempts = attempts;
        b.rawBody = rawBody;
        b.retryAfterMs = retryAfterMs;
        b.cause = cause;
        return b;
    }

    /** @return error category */
    public ErrorCategory getCategory() {
        return category;
    }

    /** @return business or local code, 0 when none */
    public int getCode() {
        return code;
    }

    /** @return table the code belongs to */
    public CodeKind getCodeKind() {
        return codeKind;
    }

    /** @return HTTP status, 0 when none */
    public int getHttpStatus() {
        return httpStatus;
    }

    /** @return message */
    public String getMessage() {
        return message;
    }

    /** Builder for {@link ErrorDetails}. */
    public static final class Builder {
        private ErrorCategory category;
        private int code;
        private CodeKind codeKind;
        private int httpStatus;
        private String message;
        private String idempotencyKey;
        private String recordId;
        private int attempts = 1;
        private String rawBody;
        private long retryAfterMs = -1;
        private Throwable cause;

        private Builder() {
        }

        /** @param v category @return this builder */
        public Builder category(ErrorCategory v) {
            this.category = v;
            return this;
        }

        /** @param v business or local code, 0 for none @return this builder */
        public Builder code(int v) {
            this.code = v;
            return this;
        }

        /** @param v table of the code; derived from the code when not set @return this builder */
        public Builder codeKind(CodeKind v) {
            this.codeKind = v;
            return this;
        }

        /** @param v HTTP status, 0 for none @return this builder */
        public Builder httpStatus(int v) {
            this.httpStatus = v;
            return this;
        }

        /** @param v message @return this builder */
        public Builder message(String v) {
            this.message = v;
            return this;
        }

        /** @param v idempotency key of the call @return this builder */
        public Builder idempotencyKey(String v) {
            this.idempotencyKey = v;
            return this;
        }

        /** @param v platform record id when known @return this builder */
        public Builder recordId(String v) {
            this.recordId = v;
            return this;
        }

        /** @param v number of attempts made @return this builder */
        public Builder attempts(int v) {
            this.attempts = v;
            return this;
        }

        /** @param v raw response body or frame, truncated to 4 KB @return this builder */
        public Builder rawBody(String v) {
            this.rawBody = v;
            return this;
        }

        /** @param v server Retry-After in milliseconds, -1 for none @return this builder */
        public Builder retryAfterMs(long v) {
            this.retryAfterMs = v;
            return this;
        }

        /** @param v cause @return this builder */
        public Builder cause(Throwable v) {
            this.cause = v;
            return this;
        }

        /** @return the immutable details */
        public ErrorDetails build() {
            return new ErrorDetails(this);
        }
    }
}
