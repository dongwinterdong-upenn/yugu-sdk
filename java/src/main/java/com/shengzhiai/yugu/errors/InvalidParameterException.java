package com.shengzhiai.yugu.errors;

/**
 * Request parameters were rejected by the platform (40001, HTTP 400, 413, 415, 422) or by the SDK before any I/O (90010).
 *
 * <p>Category {@link ErrorCategory#INVALID_PARAM}.
 */
public class InvalidParameterException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public InvalidParameterException(ErrorDetails details) {
        super(details);
    }
}
