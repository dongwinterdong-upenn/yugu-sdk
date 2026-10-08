package com.shengzhiai.yugu.errors;

/**
 * Audio quality problem: local precheck rejection (90101 to 90105) or a platform warning promoted to an error (1001 with {@code strictAudio}).
 *
 * <p>Category {@link ErrorCategory#AUDIO}.
 */
public class AudioQualityException extends YuguException {
    private static final long serialVersionUID = 2L;

    private final transient com.shengzhiai.yugu.model.EvalResult result;

    /**
     * Creates the exception with the evaluation result that carried the warning.
     *
     * @param details error details
     * @param result result returned by the platform, may be null
     */
    public AudioQualityException(ErrorDetails details, com.shengzhiai.yugu.model.EvalResult result) {
        super(details);
        this.result = result;
    }

    /**
     * Result that carried the warning when the error was raised by {@code strictAudio}; null for
     * local precheck rejections, which happen before upload.
     *
     * @return result or null
     */
    public com.shengzhiai.yugu.model.EvalResult getResult() {
        return result;
    }

    /**
     * @param details error details
     */
    public AudioQualityException(ErrorDetails details) {
        super(details);
        this.result = null;
    }
}
