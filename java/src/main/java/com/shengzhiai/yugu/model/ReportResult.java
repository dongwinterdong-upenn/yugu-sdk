package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Report of one evaluation record, the {@code data} object of {@code GET /api/v1/report/{recordId}}. */
public class ReportResult {
    private final String recordId;
    private final JsonNode data;
    private final JsonNode raw;
    private final int attempts;

    /**
     * @param recordId record id
     * @param data     data object, may be null
     * @param raw      complete response
     * @param attempts attempts used
     */
    public ReportResult(String recordId, JsonNode data, JsonNode raw, int attempts) {
        this.recordId = recordId;
        this.data = data;
        this.raw = raw;
        this.attempts = attempts;
    }

    /** @return record id */
    public String getRecordId() {
        return recordId;
    }

    /** @return report data; its structure depends on the evaluation mode */
    public JsonNode getData() {
        return data;
    }

    /** @return {@code data.overall} when present, else null */
    public Double getOverall() {
        JsonNode o = data == null ? null : data.get("overall");
        return o != null && o.isNumber() ? o.asDouble() : null;
    }

    /** @return the complete response JSON */
    public JsonNode getRaw() {
        return raw;
    }

    /** @return attempts used */
    public int getAttempts() {
        return attempts;
    }

    @Override
    public String toString() {
        return "ReportResult{recordId=" + recordId + ", overall=" + getOverall() + '}';
    }
}
