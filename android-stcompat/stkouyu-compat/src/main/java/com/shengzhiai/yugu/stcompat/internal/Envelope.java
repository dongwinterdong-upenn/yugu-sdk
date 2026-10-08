// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * JSON delivered to {@code onScore} and {@code OnRecordListener.onRecordEnd} (DESIGN 6.2).
 *
 * <pre>
 * {"tokenId","recordId","applicationId","userId","refText","eof":1,"dtLastResponse",
 *  "result":{platform compat result, byte for byte except the alignment below},"params":{app,audio,request},"audioUrl"}
 * {"tokenId","errId","error","eof":1,"applicationId"}
 * </pre>
 *
 * {@code params} only when needRequestParamsInResult is set, {@code audioUrl} only when the
 * platform response has one (top level or inside result).
 *
 * <p>Shengtong result-shape alignment (DESIGN 6): Shengtong parsers read paragraph word scores as
 * {@code result.sentences[].details[].overall}, the platform puts them at
 * {@code details[].scores.overall}. Every detail item without {@code overall} gets {@code overall}
 * copied from {@code scores.overall}, and {@code pronunciation} from {@code scores.pronunciation}
 * when present and missing on the item. Existing keys are never changed or removed; all other text
 * of {@code result} stays byte for byte as the platform returned it.
 */
public final class Envelope {
    private Envelope() {
    }

    /** {@code yyyy-MM-dd HH:mm:ss:SSS} in the device time zone. */
    public static String timestamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS", Locale.US).format(new Date(millis));
    }

    /**
     * Success envelope around the platform body.
     *
     * @throws IllegalArgumentException when the platform body is not a JSON object with a result
     */
    public static String success(String tokenId, String appKey, String userId, String refText, String platformBody,
                                 String paramsJson, long nowMillis) {
        Map<String, String> top = RawJson.members(platformBody.trim());
        String result = top.get("result");
        if (result == null) {
            throw new IllegalArgumentException("platform response has no result");
        }
        String audioUrl = top.get("audioUrl");
        if (audioUrl == null && result.trim().startsWith("{")) {
            try {
                audioUrl = RawJson.members(result.trim()).get("audioUrl");
            } catch (IllegalArgumentException ignored) {
                audioUrl = null;
            }
        }
        StringBuilder sb = new StringBuilder(platformBody.length() + 512);
        sb.append('{');
        sb.append("\"tokenId\":").append(RawJson.quote(tokenId));
        String recordId = top.get("recordId");
        if (recordId != null) {
            sb.append(",\"recordId\":").append(recordId.trim());
        }
        sb.append(",\"applicationId\":").append(RawJson.quote(nz(appKey)));
        sb.append(",\"userId\":").append(RawJson.quote(nz(userId)));
        sb.append(",\"refText\":").append(RawJson.quote(nz(refText)));
        sb.append(",\"eof\":1");
        sb.append(",\"dtLastResponse\":").append(RawJson.quote(timestamp(nowMillis)));
        sb.append(",\"result\":").append(alignResult(result.trim()));
        if (paramsJson != null) {
            sb.append(",\"params\":").append(paramsJson);
        }
        if (audioUrl != null && RawJson.isString(audioUrl) && !RawJson.unquote(audioUrl).isEmpty()) {
            sb.append(",\"audioUrl\":").append(audioUrl.trim());
        }
        return sb.append('}').toString();
    }

    /**
     * Adds Shengtong-style {@code overall} and {@code pronunciation} to {@code sentences[].details[]}
     * items, inserting raw text before each item's closing brace. Returns the input unchanged when
     * there is nothing to add or the shape is unexpected.
     */
    public static String alignResult(String result) {
        try {
            if (!result.startsWith("{")) {
                return result;
            }
            RawJson.ObjectSpan root = RawJson.objectAt(result, 0);
            int[] sentences = root.members.get("sentences");
            if (sentences == null || result.charAt(sentences[0]) != '[') {
                return result;
            }
            // insertion position -> text, applied from the end so earlier positions stay valid
            java.util.TreeMap<Integer, String> inserts = new java.util.TreeMap<Integer, String>();
            for (int[] sentence : RawJson.arrayAt(result, sentences[0])) {
                if (result.charAt(sentence[0]) != '{') {
                    continue;
                }
                int[] details = RawJson.objectAt(result, sentence[0]).members.get("details");
                if (details == null || result.charAt(details[0]) != '[') {
                    continue;
                }
                for (int[] detail : RawJson.arrayAt(result, details[0])) {
                    if (result.charAt(detail[0]) != '{') {
                        continue;
                    }
                    RawJson.ObjectSpan item = RawJson.objectAt(result, detail[0]);
                    int[] scores = item.members.get("scores");
                    if (item.members.containsKey("overall") || scores == null || result.charAt(scores[0]) != '{') {
                        continue;
                    }
                    RawJson.ObjectSpan sc = RawJson.objectAt(result, scores[0]);
                    StringBuilder add = new StringBuilder();
                    int[] overall = sc.members.get("overall");
                    if (overall != null) {
                        add.append(",\"overall\":").append(result, overall[0], overall[1]);
                    }
                    int[] pron = sc.members.get("pronunciation");
                    if (pron != null && !item.members.containsKey("pronunciation")) {
                        add.append(",\"pronunciation\":").append(result, pron[0], pron[1]);
                    }
                    if (add.length() > 0) {
                        inserts.put(item.close, item.members.isEmpty() ? add.substring(1) : add.toString());
                    }
                }
            }
            if (inserts.isEmpty()) {
                return result;
            }
            StringBuilder out = new StringBuilder(result);
            for (java.util.Map.Entry<Integer, String> e : inserts.descendingMap().entrySet()) {
                out.insert((int) e.getKey(), e.getValue());
            }
            return out.toString();
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            YLog.w("result shape alignment skipped: " + e.getMessage());
            return result;
        }
    }

    public static String error(String tokenId, int errId, String message, String appKey) {
        return "{\"tokenId\":" + RawJson.quote(nz(tokenId))
                + ",\"errId\":" + errId
                + ",\"error\":" + RawJson.quote(nz(message))
                + ",\"eof\":1"
                + ",\"applicationId\":" + RawJson.quote(nz(appKey))
                + "}";
    }

    /**
     * {@code params} member: {@code app} (applicationId, userId, timestamp), {@code audio}
     * (format of the uploaded audio) and {@code request} (coreType, tokenId and the form fields
     * sent). Never contains secretKey or signature.
     */
    public static String params(String appKey, String userId, long nowMillis, String audioType, int sampleRate,
                                int channel, String coreType, String tokenId, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"app\":{\"applicationId\":").append(RawJson.quote(nz(appKey)))
                .append(",\"userId\":").append(RawJson.quote(nz(userId)))
                .append(",\"timestamp\":").append(RawJson.quote(String.valueOf(nowMillis / 1000L))).append('}');
        sb.append(",\"audio\":{\"audioType\":").append(RawJson.quote(nz(audioType)))
                .append(",\"sampleRate\":").append(sampleRate)
                .append(",\"channel\":").append(channel)
                .append(",\"sampleBytes\":2}");
        sb.append(",\"request\":{\"coreType\":").append(RawJson.quote(nz(coreType)))
                .append(",\"tokenId\":").append(RawJson.quote(nz(tokenId)));
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if ("coreType".equals(e.getKey()) || "tokenId".equals(e.getKey())) {
                    continue;
                }
                sb.append(',').append(RawJson.quote(e.getKey())).append(':').append(RawJson.quote(e.getValue()));
            }
        }
        sb.append("}}");
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
