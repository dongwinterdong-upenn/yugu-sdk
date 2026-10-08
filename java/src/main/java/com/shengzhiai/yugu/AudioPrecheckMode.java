package com.shengzhiai.yugu;

/** Behaviour of the local audio precheck, see {@link com.shengzhiai.yugu.audio.AudioPrecheck}. */
public enum AudioPrecheckMode {
    /** No precheck. */
    OFF,
    /** Default. Findings are logged and returned as {@code localWarnings}; the audio is uploaded. */
    WARN,
    /**
     * Findings 90101, 90102, 90103 and 90105 throw {@code AudioQualityException} before upload, so
     * nothing is billed; 90104 stays a warning.
     */
    REJECT
}
