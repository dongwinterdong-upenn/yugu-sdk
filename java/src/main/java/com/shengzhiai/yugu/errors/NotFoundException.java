package com.shengzhiai.yugu.errors;

/**
 * Resource does not exist (HTTP 404, 40400).
 *
 * <p>Category {@link ErrorCategory#NOT_FOUND}.
 */
public class NotFoundException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public NotFoundException(ErrorDetails details) {
        super(details);
    }
}
