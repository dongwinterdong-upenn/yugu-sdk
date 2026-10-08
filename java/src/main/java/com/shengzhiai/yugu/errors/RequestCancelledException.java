package com.shengzhiai.yugu.errors;

/**
 * The caller cancelled the request or session (90003).
 *
 * <p>Category {@link ErrorCategory#CANCELLED}.
 */
public class RequestCancelledException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public RequestCancelledException(ErrorDetails details) {
        super(details);
    }
}
