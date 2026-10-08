package com.shengzhiai.yugu.errors;

/**
 * Response or WebSocket frame could not be understood (90005).
 *
 * <p>Category {@link ErrorCategory#PROTOCOL}.
 */
public class ProtocolViolationException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public ProtocolViolationException(ErrorDetails details) {
        super(details);
    }
}
