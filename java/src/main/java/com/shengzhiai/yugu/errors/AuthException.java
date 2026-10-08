package com.shengzhiai.yugu.errors;

/**
 * Missing or invalid credentials, bad signature, expired token (HTTP 401, codes 2001 to 2011, 40100).
 *
 * <p>Category {@link ErrorCategory#AUTH}.
 */
public class AuthException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public AuthException(ErrorDetails details) {
        super(details);
    }
}
