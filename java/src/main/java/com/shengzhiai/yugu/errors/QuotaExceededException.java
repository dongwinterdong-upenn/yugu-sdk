package com.shengzhiai.yugu.errors;

/**
 * Quota, balance or daily limit exhausted (40902, 42902, 42903, 1310, 2012). Not retryable.
 *
 * <p>Category {@link ErrorCategory#QUOTA}.
 */
public class QuotaExceededException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public QuotaExceededException(ErrorDetails details) {
        super(details);
    }
}
