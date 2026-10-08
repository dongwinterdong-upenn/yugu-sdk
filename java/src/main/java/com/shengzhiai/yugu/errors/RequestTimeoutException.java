package com.shengzhiai.yugu.errors;

/**
 * Connect or read timeout (90002), HTTP 408, or no final result after end() (90007).
 *
 * <p>Category {@link ErrorCategory#TIMEOUT}.
 */
public class RequestTimeoutException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public RequestTimeoutException(ErrorDetails details) {
        super(details);
    }
}
