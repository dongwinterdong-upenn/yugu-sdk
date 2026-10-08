package com.shengzhiai.yugu.errors;

/**
 * Connection failed or was reset, TLS failure (90011), or WebSocket reconnect attempts exhausted (90006).
 *
 * <p>Category {@link ErrorCategory#NETWORK}.
 */
public class NetworkException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public NetworkException(ErrorDetails details) {
        super(details);
    }
}
