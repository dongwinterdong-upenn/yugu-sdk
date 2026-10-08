package com.shengzhiai.yugu.errors;

/**
 * Operation not allowed in the current state: client closed (90004), invalid session state (90009), replay buffer overflow (90008).
 *
 * <p>Category {@link ErrorCategory#STATE}.
 */
public class IllegalSessionStateException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public IllegalSessionStateException(ErrorDetails details) {
        super(details);
    }
}
