package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Intermediate frame {@code {"eof":0,"result":{...}}} of a stream that requested realtime feedback
 * ({@link EvaluateConfig#realtimeFeedback(boolean)} or {@link CompatConfig#realtimeFeedback(boolean)}),
 * delivered through {@code StreamListener.onPartial} on both channels.
 *
 * <p>When the platform tracks the reading progress of the round, {@code result.progress} lists every unit
 * of the reference text in order ({@link #getProgress()}) next to the three counts:
 * <pre>{@code
 * {"eof":0,"result":{"bytes":96000,"readUnits":19,"matchedUnits":18,"totalUnits":25,
 *  "progress":[{"word":"我","read":true,"matched":true}, ...]}}
 * }</pre>
 * Otherwise (realtime feedback off, empty reference text, audio that is not 16 kHz 16 bit mono PCM,
 * tracking unavailable) the frame is {@code {"eof":0,"result":{"bytes":n}}}: {@link #hasProgress()} is
 * false, the counts are -1 and the progress list is empty.
 *
 * <p>Compat frames follow the Shengtong {@code realtime_feedback} intermediate result and also carry
 * {@code "recordId":""}, {@code result.kernel_version}, {@code result.resource_version} and the
 * Shengtong-shaped incremental {@code result.words} (the units read since the previous frame). The SDK
 * does not map those fields; they stay available through {@link #getRaw()}.
 */
public final class StreamPartial {
    private final long bytes;
    private final JsonNode raw;
    private final int readUnits;
    private final int matchedUnits;
    private final int totalUnits;
    private final List<ReadingWord> progress;

    /**
     * @param bytes audio bytes the server has received in this server session
     * @param raw   the frame; the reading progress is read from {@code result.progress} and the counts
     */
    public StreamPartial(long bytes, JsonNode raw) {
        this.bytes = bytes;
        this.raw = raw;
        JsonNode result = raw == null ? null : raw.get("result");
        this.readUnits = count(result, "readUnits");
        this.matchedUnits = count(result, "matchedUnits");
        this.totalUnits = count(result, "totalUnits");
        JsonNode list = result == null ? null : result.get("progress");
        if (list != null && list.isArray()) {
            List<ReadingWord> units = new ArrayList<>(list.size());
            for (JsonNode u : list) {
                units.add(new ReadingWord(u.path("word").asText(""), u.path("read").asBoolean(false),
                        u.path("matched").asBoolean(false)));
            }
            this.progress = Collections.unmodifiableList(units);
        } else {
            this.progress = null;
        }
    }

    private static int count(JsonNode result, String field) {
        JsonNode n = result == null ? null : result.get(field);
        return n != null && n.isIntegralNumber() && n.canConvertToInt() ? n.intValue() : -1;
    }

    /** @return audio bytes received by the server session so far, -1 when the frame carried none */
    public long getBytes() {
        return bytes;
    }

    /** @return the raw frame, including the compat fields the SDK does not map */
    public JsonNode getRaw() {
        return raw;
    }

    /**
     * @return true when the frame carries the reading progress ({@code result.progress} present)
     * @since 2.1.0
     */
    public boolean hasProgress() {
        return progress != null;
    }

    /**
     * @return number of units whose {@code read} is true, the reading position; -1 when absent as in
     *         frames without progress
     * @since 2.1.0
     */
    public int getReadUnits() {
        return readUnits;
    }

    /**
     * @return number of units whose {@code matched} is true, -1 when absent as in frames without progress
     * @since 2.1.0
     */
    public int getMatchedUnits() {
        return matchedUnits;
    }

    /**
     * @return number of units of the reference text, -1 when absent as in frames without progress
     * @since 2.1.0
     */
    public int getTotalUnits() {
        return totalUnits;
    }

    /**
     * Reading state of every unit of the reference text in order ({@code result.progress}): characters
     * for Chinese, words for English.
     *
     * @return unmodifiable list, empty when the frame carries no progress
     * @since 2.1.0
     */
    public List<ReadingWord> getProgress() {
        return progress == null ? Collections.emptyList() : progress;
    }

    @Override
    public String toString() {
        if (progress == null) {
            return "StreamPartial{bytes=" + bytes + '}';
        }
        return "StreamPartial{bytes=" + bytes + ", readUnits=" + readUnits + ", matchedUnits=" + matchedUnits
                + ", totalUnits=" + totalUnits + '}';
    }
}
