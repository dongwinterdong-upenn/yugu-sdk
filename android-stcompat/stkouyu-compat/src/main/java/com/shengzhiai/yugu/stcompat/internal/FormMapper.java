// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import com.stkouyu.CustomParam;
import com.stkouyu.setting.RecordSetting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * RecordSetting to compat REST form fields (DESIGN 6.1). Fields with empty values are not sent
 * and therefore not signed. {@code realtime_feedback}, VAD and recording options stay local.
 * Paragraph kernels always send {@code paragraph_need_word_score=1} (DESIGN 6, result-shape alignment).
 */
public final class FormMapper {
    private FormMapper() {
    }

    /** errId for a setting that cannot be evaluated (60003, 60006), 0 when it can. */
    public static int validate(RecordSetting s) {
        String coreType = s.getCoreType() == null ? null : s.getCoreType().trim();
        if (!CoreTypes.isSupported(coreType)) {
            return CompatErrIds.CORE_TYPE_UNSUPPORTED;
        }
        boolean hasText = !Codec.isBlank(s.getRefText());
        boolean hasPinyin = !Codec.isBlank(s.getRefPinyin());
        if (!hasText && !(hasPinyin && CoreTypes.isChinese(coreType))) {
            return CompatErrIds.REF_TEXT_EMPTY;
        }
        return 0;
    }

    public static LinkedHashMap<String, String> fields(RecordSetting s) {
        LinkedHashMap<String, String> f = new LinkedHashMap<String, String>();
        put(f, "refText", s.getRefText());
        put(f, "refPinyin", s.getRefPinyin());
        if (s.getAgegroup() > 0) {
            put(f, "agegroup", String.valueOf(s.getAgegroup()));
        }
        if (s.getScale() != null) {
            put(f, "scale", number(s.getScale()));
        }
        if (s.getPrecision() > 0) {
            put(f, "precision", number(s.getPrecision()));
        }
        if (s.getSlack() != 0) {
            put(f, "slack", number(s.getSlack()));
        }
        boolean paragraph = isParagraph(s.getCoreType());
        if (s.isNeedWordScoreInParagraph() || paragraph) {
            put(f, "paragraph_need_word_score", "1");
        }
        if (s.isNeedPhonemeOutputInWord()) {
            put(f, "phoneme_output", "1");
        }
        if (s.isNeedAttachAudioUrlInResult()) {
            put(f, "attachAudioUrl", "1");
        }
        put(f, "dict_type", s.getDict_type());
        put(f, "dict_dialect", s.getDict_dialect());
        put(f, "customized_lexicon", s.getCustomized_lexicon());
        put(f, "customized_pron", s.getCustomized_pron());
        put(f, "readtype_diagnosis", s.getReadtypeDiagnosis());
        put(f, "output_rawtext", s.getOutput_rawtext());
        put(f, "punctuate", s.getPunctuate());
        put(f, "itn", s.getItn());
        put(f, "detect_nonscorable", s.getDetect_nonscorable());
        put(f, "vad_detction", s.getVad_detction());
        put(f, "keywords", s.getKeywords());
        put(f, "keypoints", s.getKeypoints());
        if (s.getKeypoints_weight() != null) {
            put(f, "keypoints_weight", number(s.getKeypoints_weight()));
        }
        put(f, "negative_keypoints", s.getNegative_keypoints());
        put(f, "negativeReftext", s.getNegativeReftext());
        put(f, "mode", s.getMode());
        if (s.getqType() != 0) {
            put(f, "qType", String.valueOf(s.getqType()));
        }
        ArrayList<CustomParam> extra = s.getNewParams();
        if (extra != null) {
            for (CustomParam p : extra) {
                if (p != null && p.getKey() != null && !p.getKey().trim().isEmpty() && p.getValue() != null) {
                    put(f, p.getKey().trim(), valueOf(p.getValue()));
                }
            }
        }
        put(f, "request", s.getRequest());
        if (paragraph) {
            // DESIGN 6: paragraph kernels always ask for word details (Shengtong result shape)
            f.put("paragraph_need_word_score", "1");
        }
        return f;
    }

    /** para.* kernels, which always get {@code paragraph_need_word_score=1}. */
    public static boolean isParagraph(String coreType) {
        return coreType != null && coreType.trim().startsWith("para.");
    }

    private static void put(LinkedHashMap<String, String> f, String key, Object value) {
        if (value == null) {
            return;
        }
        String v = value instanceof Double || value instanceof Float ? number(((Number) value).doubleValue())
                : String.valueOf(value);
        if (!v.isEmpty()) {
            f.put(key, v);
        }
    }

    /** Plain decimal text: 100, 0.5, -0.25 (no exponent, no trailing zeros). */
    public static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return String.valueOf(d);
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    static String valueOf(Object v) {
        if (v instanceof Double || v instanceof Float) {
            return number(((Number) v).doubleValue());
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }
}
