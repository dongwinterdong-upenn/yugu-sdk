package com.shengzhiai.yugu.model;

import com.shengzhiai.yugu.errors.WarningCode;

import java.util.Objects;

/**
 * One audio quality warning: a platform warning from the result ({@link Source#SERVER}, codes 1001
 * to 1009) or a local precheck finding ({@link Source#LOCAL}, codes 90101 to 90105).
 */
public final class Warning {
    /** Where the warning comes from. */
    public enum Source {
        /** Reported by the platform in the evaluation result. */
        SERVER,
        /** Found by the SDK audio precheck before upload. */
        LOCAL
    }

    private final int code;
    private final String message;
    private final Source source;

    /**
     * @param code    warning code
     * @param message message
     * @param source  origin
     */
    public Warning(int code, String message, Source source) {
        this.code = code;
        this.message = message == null ? "" : message;
        this.source = source == null ? Source.SERVER : source;
    }

    /** @return numeric code */
    public int getCode() {
        return code;
    }

    /** @return message */
    public String getMessage() {
        return message;
    }

    /** @return origin of the warning */
    public Source getSource() {
        return source;
    }

    /** @return the platform warning enum, null for local warnings and unknown codes */
    public WarningCode getWarningCode() {
        return source == Source.SERVER ? WarningCode.fromCode(code) : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Warning)) {
            return false;
        }
        Warning w = (Warning) o;
        return code == w.code && source == w.source && message.equals(w.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message, source);
    }

    @Override
    public String toString() {
        return "Warning{" + code + ", " + source + ", " + message + '}';
    }
}
