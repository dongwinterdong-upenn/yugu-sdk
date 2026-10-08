package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Progress frame of a compat stream with {@code realtime_feedback}: {@code {"eof":0,"result":{"bytes":n}}}.
 * Native streams send no progress frames.
 */
public final class StreamPartial {
    private final long bytes;
    private final JsonNode raw;

    /**
     * @param bytes audio bytes the server has received in this server session
     * @param raw   the frame
     */
    public StreamPartial(long bytes, JsonNode raw) {
        this.bytes = bytes;
        this.raw = raw;
    }

    /** @return audio bytes received by the server session so far, -1 when the frame carried none */
    public long getBytes() {
        return bytes;
    }

    /** @return the raw frame */
    public JsonNode getRaw() {
        return raw;
    }

    @Override
    public String toString() {
        return "StreamPartial{bytes=" + bytes + '}';
    }
}
