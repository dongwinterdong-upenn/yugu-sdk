// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat;

/** Answer of {@link TtsClient#tts} (SDK 2.2.0). */
public final class TtsResult {
    private final String audioUrl;
    private final String absoluteUrl;
    private final String duration;
    private final String format;
    private final String idempotencyKey;
    private final boolean replayed;
    private final int attempts;
    private final String rawJson;

    TtsResult(String audioUrl, String absoluteUrl, String duration, String format, String idempotencyKey,
              boolean replayed, int attempts, String rawJson) {
        this.audioUrl = audioUrl;
        this.absoluteUrl = absoluteUrl;
        this.duration = duration;
        this.format = format;
        this.idempotencyKey = idempotencyKey;
        this.replayed = replayed;
        this.attempts = attempts;
        this.rawJson = rawJson;
    }

    /** {@code data.audioUrl} as the platform returned it, for example {@code /audio/xxx.mp3}; null when absent. */
    public String getAudioUrl() {
        return audioUrl;
    }

    /**
     * Playable address: {@link #getAudioUrl()} resolved against the client's base URL, a path starting
     * with {@code /audio/} under {@code <base>/tts} (CONTRACT 4.1), an absolute URL unchanged.
     */
    public String getAbsoluteUrl() {
        return absoluteUrl;
    }

    /** {@code data.duration} in seconds as returned, for example {@code "1.348"}; null when absent. */
    public String getDuration() {
        return duration;
    }

    /** {@link #getDuration()} as a number, -1 when absent or not a number. */
    public double getDurationSeconds() {
        if (duration == null) {
            return -1;
        }
        try {
            double d = Double.parseDouble(duration.trim());
            return Double.isNaN(d) || Double.isInfinite(d) ? -1 : d;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** {@code data.format}: mp3, wav or ogg; null when absent. */
    public String getFormat() {
        return format;
    }

    /** Idempotency-Key the call used. */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** The platform replayed the result of an earlier call with the same key. */
    public boolean isReplayed() {
        return replayed;
    }

    /** HTTP attempts of the call, 1 without retries. */
    public int getAttempts() {
        return attempts;
    }

    /** The complete response JSON {@code {"code":0,"message":"success","data":{...}}} as received. */
    public String getRawJson() {
        return rawJson;
    }

    @Override
    public String toString() {
        return "TtsResult{audioUrl=" + audioUrl + ", duration=" + duration + ", format=" + format + ", replayed="
                + replayed + "}";
    }
}
