// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Answer of {@link ReportClient#getReport} (SDK 2.2.0): the {@code data} object of
 * {@code GET /api/v1/report/{recordId}}.
 *
 * <pre>
 * {"recordId":"eval_…","score":{…same as the evaluation result…},
 *  "report":{"source":"llm","summary":"…","dimensions":{…},"dimensionSuggestions":{…},"suggestions":["…"],
 *            "skillInsights":[{"aspect":"…","observation":"…","quote":"…","confidence":"medium"}]},
 *  "reportStatus":"final","timing":{"receivedAt":…,"totalMs":…,"engineMs":…,"platformMs":…,"channel":"ws"}}
 * </pre>
 *
 * {@code reportStatus}: {@link #STATUS_FINAL} the AI final draft, {@link #STATUS_PENDING} the instant
 * draft while the AI draft is generated, {@code partial} (SDK 2.3.0) the AI summary and suggestions
 * are ready while the comments per dimension are still written, {@link #STATUS_INSTANT} the AI draft
 * will not come, {@link #STATUS_NONE} no report. Open questions ({@code open.eval.cn}) have their
 * feedback in {@code score.feedback} and are always final.
 */
public final class ReportResult {
    public static final String STATUS_FINAL = "final";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_INSTANT = "instant";
    public static final String STATUS_NONE = "none";
    /** AI summary and suggestions ready, comments per dimension still written (SDK 2.3.0); not public API. */
    static final String STATUS_PARTIAL = "partial";

    private final String recordId;
    private final String reportStatus;
    private final JSONObject report;
    private final JSONObject score;
    private final JSONObject timing;
    private final Double overall;
    private final String summary;
    private final List<String> suggestions;
    private final String dataJson;
    private int lookups;

    private ReportResult(String recordId, JSONObject data, String dataJson, int lookups) {
        String id = text(data, "recordId");
        this.recordId = id != null && !id.isEmpty() ? id : recordId;
        this.reportStatus = text(data, "reportStatus");
        this.report = data.optJSONObject("report");
        this.score = data.optJSONObject("score");
        this.timing = data.optJSONObject("timing");
        Double o = score != null ? number(score, "overall") : null;
        this.overall = o != null ? o : number(data, "overall");
        this.summary = report != null ? text(report, "summary") : null;
        List<String> s = new ArrayList<String>();
        JSONArray a = report != null ? report.optJSONArray("suggestions") : null;
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof String) {
                    s.add((String) v);
                }
            }
        }
        this.suggestions = Collections.unmodifiableList(s);
        this.dataJson = dataJson;
        this.lookups = lookups;
    }

    /** Parses the raw {@code data} object; {@code recordId} is the one asked for. */
    static ReportResult parse(String recordId, String dataJson, int lookups) throws JSONException {
        return new ReportResult(recordId, new JSONObject(dataJson), dataJson, lookups);
    }

    /** {@code data.recordId}, or the recordId asked for when the answer has none. */
    public String getRecordId() {
        return recordId;
    }

    /** {@code data.reportStatus}: final, pending, instant or none; null when the answer has none. */
    public String getReportStatus() {
        return reportStatus;
    }

    /** {@code reportStatus} is {@code final}: the AI final draft. {@code partial} is not final. */
    public boolean isFinal() {
        return STATUS_FINAL.equals(reportStatus);
    }

    /** {@code reportStatus} is {@code pending}: an instant draft, the AI draft is still being generated. */
    public boolean isPending() {
        return STATUS_PENDING.equals(reportStatus);
    }

    /**
     * The AI final draft is still to come: {@code pending}, or {@code partial} (SDK 2.3.0), the AI
     * summary and suggestions without the comments per dimension. Waiting goes on while this holds.
     */
    boolean awaitingFinal() {
        return STATUS_PENDING.equals(reportStatus) || STATUS_PARTIAL.equals(reportStatus);
    }

    /** {@code report.summary}, the overall comment; null when absent. */
    public String getSummary() {
        return summary;
    }

    /** {@code report.suggestions}, the numbered suggestions in order; empty when absent. */
    public List<String> getSuggestions() {
        return suggestions;
    }

    /** {@code score.overall} (or {@code data.overall}), the total score; null when absent. */
    public Double getOverall() {
        return overall;
    }

    /**
     * The {@code report} object: {@code summary}, {@code dimensions} (comment per dimension),
     * {@code dimensionSuggestions}, {@code suggestions}, {@code skillInsights} (aspect, observation,
     * quote, confidence) and more; null when absent.
     */
    public JSONObject getReport() {
        return report;
    }

    /** The {@code score} object, the same as the result of the evaluation; null when absent. */
    public JSONObject getScore() {
        return score;
    }

    /**
     * {@code data.timing} of a compat evaluation, kept 7 days: the {@code timing} object of the
     * evaluation result plus {@code channel} ({@code rest} or {@code ws}); null when absent.
     */
    public JSONObject getTiming() {
        return timing;
    }

    /** The {@code data} object as received. */
    public String getDataJson() {
        return dataJson;
    }

    /** Lookups the call made: 1 without waiting, more while the answer stayed pending. */
    public int getLookups() {
        return lookups;
    }

    /** The call made more lookups after this answer, the last of them failed. */
    void setLookups(int n) {
        lookups = n;
    }

    @Override
    public String toString() {
        return "ReportResult{recordId=" + recordId + ", reportStatus=" + reportStatus + ", overall=" + overall
                + ", lookups=" + lookups + "}";
    }

    private static String text(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : null;
    }

    private static Double number(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return Double.isNaN(d) ? null : d;
        }
        if (v instanceof String) {
            try {
                return Double.valueOf(((String) v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
