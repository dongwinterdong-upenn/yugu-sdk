package com.shengzhiai.yugu.errors;

/**
 * Audio quality warning codes the platform puts into evaluation results ({@code result.warning} and
 * top level {@code warnings}). Warnings are not exceptions: the score is returned and the codes are
 * listed in {@link com.shengzhiai.yugu.model.EvalResult#getWarnings()}.
 *
 * <p>Codes 1004 and 1005 are also used by the platform as account error codes in error responses,
 * which is why warnings have their own enum and table.
 */
public enum WarningCode {
    /** 1001: no valid audio detected, the score is not reliable, record again. */
    NO_VALID_AUDIO(1001),
    /** 1002: volume too low. */
    VOLUME_TOO_LOW(1002),
    /** 1003: volume too high, clipping. */
    VOLUME_TOO_HIGH(1003),
    /** 1004: noticeable background noise. */
    AUDIO_NOISY(1004),
    /** 1005: audio looks incomplete, the score is indicative only. */
    AUDIO_INCOMPLETE(1005),
    /** 1009: a scoring component was degraded, submitting again may recover. */
    SCORER_DEGRADED(1009);

    private final int code;

    WarningCode(int code) {
        this.code = code;
    }

    /** @return numeric code */
    public int getCode() {
        return code;
    }

    /** @return English message the platform sends with the code */
    public String getMessage() {
        ErrorTable.Entry e = ErrorTable.WARNINGS.get(code);
        return e == null ? name() : e.message;
    }

    /** @return true when submitting the same audio again may give a better result */
    public boolean isRetryable() {
        ErrorTable.Entry e = ErrorTable.WARNINGS.get(code);
        return e != null && e.retryable;
    }

    /**
     * @param code numeric warning code
     * @return the enum constant, or null for an unknown code
     */
    public static WarningCode fromCode(int code) {
        for (WarningCode w : values()) {
            if (w.code == code) {
                return w;
            }
        }
        return null;
    }
}
