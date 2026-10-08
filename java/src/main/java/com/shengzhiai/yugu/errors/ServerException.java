package com.shengzhiai.yugu.errors;

/**
 * Platform side failure: internal error (50000, HTTP 500), feature not implemented (50010) or the
 * upstream scoring service being unavailable (50200, HTTP 502, 503, 504).
 *
 * <p>Categories {@link ErrorCategory#SERVER} and {@link ErrorCategory#UPSTREAM}.
 */
public class ServerException extends YuguException {
    private static final long serialVersionUID = 2L;

    /**
     * @param details error details
     */
    public ServerException(ErrorDetails details) {
        super(details);
    }

    /** @return true when the upstream scoring service failed rather than the platform itself */
    public boolean isUpstream() {
        return getCategory() == ErrorCategory.UPSTREAM;
    }
}
