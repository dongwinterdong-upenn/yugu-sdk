package com.shengzhiai.yugu.errors;

/**
 * Credentials are valid but the operation is not allowed (HTTP 403, 40300, disabled or frozen accounts, microphone permission).
 *
 * <p>Category {@link ErrorCategory#PERMISSION}.
 */
public class PermissionException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public PermissionException(ErrorDetails details) {
        super(details);
    }
}
