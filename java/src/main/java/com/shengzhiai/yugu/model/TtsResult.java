package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Speech synthesis result, the {@code data} object of the TTS response. */
public class TtsResult {
    private String audioUrl;
    private String absoluteUrl;
    private String duration;
    private String format;
    private List<Warning> warnings = Collections.emptyList();
    private JsonNode raw;
    private String idempotencyKey;
    private boolean replayed;
    private int attempts = 1;

    /** @return audio URL as sent by the platform, may be relative */
    public String getAudioUrl() {
        return audioUrl;
    }

    /** @param v audio URL */
    public void setAudioUrl(String v) {
        this.audioUrl = v;
    }

    /** @return audio URL resolved against the base URL */
    public String getAbsoluteUrl() {
        return absoluteUrl;
    }

    /** @param v absolute URL */
    public void setAbsoluteUrl(String v) {
        this.absoluteUrl = v;
    }

    /** @return duration in seconds as sent by the platform */
    public String getDuration() {
        return duration;
    }

    /** @param v duration */
    public void setDuration(String v) {
        this.duration = v;
    }

    /** @return audio format */
    public String getFormat() {
        return format;
    }

    /** @param v format */
    public void setFormat(String v) {
        this.format = v;
    }

    /** @return warnings of the synthesis, never null */
    public List<Warning> getWarnings() {
        return warnings;
    }

    /** @param v warnings */
    public void setWarnings(List<Warning> v) {
        this.warnings = v == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(v));
    }

    /** @return the complete response JSON */
    public JsonNode getRaw() {
        return raw;
    }

    /** @param v raw JSON */
    public void setRaw(JsonNode v) {
        this.raw = v;
    }

    /** @return idempotency key of the call */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @param v key */
    public void setIdempotencyKey(String v) {
        this.idempotencyKey = v;
    }

    /** @return true when the platform replayed a stored response for the same key */
    public boolean isReplayed() {
        return replayed;
    }

    /** @param v replay flag */
    public void setReplayed(boolean v) {
        this.replayed = v;
    }

    /** @return attempts used */
    public int getAttempts() {
        return attempts;
    }

    /** @param v attempts */
    public void setAttempts(int v) {
        this.attempts = v;
    }

    @Override
    public String toString() {
        return "TtsResult{audioUrl=" + audioUrl + ", absoluteUrl=" + absoluteUrl + ", duration=" + duration
                + ", format=" + format + ", replayed=" + replayed + '}';
    }
}
