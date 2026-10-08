package com.shengzhiai.yugu.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.errors.ErrorTable;
import com.shengzhiai.yugu.errors.WarningCode;
import com.shengzhiai.yugu.errors.YuguErrors;
import com.shengzhiai.yugu.errors.YuguException;
import com.shengzhiai.yugu.model.AsrAlignment;
import com.shengzhiai.yugu.model.ConnectedScores;
import com.shengzhiai.yugu.model.Dimensions;
import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.OpenScores;
import com.shengzhiai.yugu.model.SentenceScore;
import com.shengzhiai.yugu.model.StandardAudio;
import com.shengzhiai.yugu.model.TtsResult;
import com.shengzhiai.yugu.model.Warning;
import com.shengzhiai.yugu.model.WordScore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses native {@code SpeechEvaluationResult} responses and compat {@code {recordId, eof, result}}
 * responses into {@link EvalResult}. Neither has an envelope.
 */
public final class ResultParser {
    private ResultParser() {
    }

    /**
     * @param root response or final frame
     * @return result
     * @throws YuguException {@code ProtocolViolationException} 90005 when the JSON is not a result
     */
    public static EvalResult parseEvaluation(JsonNode root) throws YuguException {
        if (root == null || !root.isObject()) {
            throw protocol("evaluation response is not a JSON object", root);
        }
        JsonNode result = root.get("result");
        if (result == null || !result.isObject()) {
            throw protocol("evaluation response has no result object", root);
        }
        try {
            EvalResult out = new EvalResult();
            out.setRaw(root);
            out.setRecordId(text(root, "recordId"));
            if (root.hasNonNull("eof")) {
                out.setEof(root.get("eof").asInt());
            }
            out.setDims(Json.MAPPER.treeToValue(result, Dimensions.class));
            out.setWords(list(result.get("words"), WordScore.class));
            out.setSentences(list(result.get("sentences"), SentenceScore.class));
            out.setLanguage(text(result, "_language"));
            out.setCoreType(text(result, "_coreType"));
            out.setYuguScores(objectOrNull(result.get("yuguScores")));
            out.setCompositeReport(objectOrNull(result.get("compositeReport")));
            String coreType = out.getCoreType();
            if ("connected".equals(coreType) || result.has("connected_overall")) {
                out.setConnected(Json.MAPPER.treeToValue(result, ConnectedScores.class));
            }
            if ("open".equals(coreType) || (isObject(result.get("content")) && isObject(result.get("delivery")))) {
                out.setOpen(Json.MAPPER.treeToValue(result, OpenScores.class));
            }
            JsonNode asr = root.get("asrText");
            if (isObject(asr) && asr.size() > 0) {
                AsrAlignment a = Json.MAPPER.treeToValue(asr, AsrAlignment.class);
                a.setRaw(asr);
                out.setAsrText(a);
            }
            JsonNode report = root.get("report");
            if (report != null && !report.isNull()) {
                out.setReport(report);
            }
            JsonNode std = root.get("standardAudio");
            if (isObject(std) && std.size() > 0) {
                out.setStandardAudio(Json.MAPPER.treeToValue(std, StandardAudio.class));
            }
            out.setWarnings(warnings(result.get("warning"), root.get("warnings")));
            out.setReplayed(root.path("replayed").asBoolean(false));
            return out;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw protocol("evaluation response does not match the result schema: " + e.getMessage(), root);
        }
    }

    /**
     * Parses TTS {@code data}.
     *
     * @param data    data object
     * @param baseUrl REST base used to resolve relative audio URLs
     * @return result
     */
    public static TtsResult parseTts(JsonNode data, String baseUrl) {
        TtsResult r = new TtsResult();
        r.setAudioUrl(text(data, "audioUrl"));
        r.setDuration(text(data, "duration"));
        r.setFormat(text(data, "format"));
        r.setWarnings(warnings(data.get("warnings")));
        String url = r.getAudioUrl();
        if (url != null) {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                r.setAbsoluteUrl(url);
            } else if (url.startsWith("/audio/")) {
                // nginx serves synthesised audio under /tts/ (CONTRACT section 2.1)
                r.setAbsoluteUrl(baseUrl + "/tts" + url);
            } else {
                r.setAbsoluteUrl(baseUrl + (url.startsWith("/") ? "" : "/") + url);
            }
        }
        return r;
    }

    /**
     * Merges warning arrays, keeping the first occurrence of each code. Elements may be numbers,
     * numeric strings or {@code {code, message}} objects.
     *
     * @param arrays arrays, null entries are skipped
     * @return warnings
     */
    public static List<Warning> warnings(JsonNode... arrays) {
        Map<Integer, Warning> byCode = new LinkedHashMap<>();
        for (JsonNode arr : arrays) {
            if (arr == null || !arr.isArray()) {
                continue;
            }
            for (JsonNode n : arr) {
                int code;
                String message = null;
                if (n.isObject()) {
                    code = n.path("code").asInt(0);
                    message = text(n, "message");
                } else if (n.isNumber()) {
                    code = n.asInt();
                } else if (n.isTextual() && n.asText().trim().matches("\\d+")) {
                    code = Integer.parseInt(n.asText().trim());
                } else {
                    code = 0;
                    message = n.isTextual() ? n.asText() : n.toString();
                }
                if (message == null) {
                    WarningCode wc = WarningCode.fromCode(code);
                    message = wc != null ? wc.getMessage() : "warning " + code;
                }
                if (!byCode.containsKey(code)) {
                    byCode.put(code, new Warning(code, message, Warning.Source.SERVER));
                }
            }
        }
        return new ArrayList<>(byCode.values());
    }

    private static <T> List<T> list(JsonNode arr, Class<T> type) throws JsonProcessingException {
        List<T> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(Json.MAPPER.treeToValue(n, type));
            }
        }
        return out;
    }

    private static boolean isObject(JsonNode n) {
        return n != null && n.isObject();
    }

    private static JsonNode objectOrNull(JsonNode n) {
        return n != null && n.isObject() ? n : null;
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.isValueNode() ? v.asText() : v.toString();
    }

    private static YuguException protocol(String message, JsonNode root) {
        YuguException e = YuguErrors.local(ErrorTable.PROTOCOL_ERROR, message, null);
        if (root == null) {
            return e;
        }
        return YuguErrors.create(e.toDetails().toBuilder().rawBody(root.toString()).build());
    }
}
