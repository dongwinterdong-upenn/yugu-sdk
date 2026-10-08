package com.shengzhiai.yugu.errors;

/**
 * Conflict: idempotency key in progress (40901, retryable), key reused for another request (40903), ledger conflict (1311).
 *
 * <p>Category {@link ErrorCategory#CONFLICT}.
 */
public class ConflictException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public ConflictException(ErrorDetails details) {
        super(details);
    }
}
