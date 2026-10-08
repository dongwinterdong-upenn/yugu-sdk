package com.shengzhiai.yugu.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parameters of the Shengtong compatible endpoints: {@code POST /{coreType}} (form fields) and
 * {@code wss://host/{coreType}} (parameter frame). The core type goes into the path.
 */
public class CompatConfig {
    /** Core types the platform registers for the compat endpoints. */
    public static final List<String> CORE_TYPES = Collections.unmodifiableList(Arrays.asList(
            "word.eval", "word.eval.pro", "sent.eval", "sent.eval.pro", "para.eval",
            "alpha.eval", "word.eval.cn", "sent.eval.cn", "para.eval.cn", "pinyin"));

    private String coreType;
    private String refText;
    private String language;
    private String refPinyin;
    private boolean realtimeFeedback;
    private final Map<String, Object> params = new LinkedHashMap<>();

    /** Creates an empty configuration; set at least the core type and refText. */
    public CompatConfig() {
    }

    /**
     * @param coreType one of {@link #CORE_TYPES}
     * @param refText  reference text
     */
    public CompatConfig(String coreType, String refText) {
        this.coreType = coreType;
        this.refText = refText;
    }

    /** @param v one of {@link #CORE_TYPES} @return this */
    public CompatConfig coreType(String v) {
        this.coreType = v;
        return this;
    }

    /** @param v reference text @return this */
    public CompatConfig refText(String v) {
        this.refText = v;
        return this;
    }

    /** @param v language, for example zh-CN or en-US @return this */
    public CompatConfig language(String v) {
        this.language = v;
        return this;
    }

    /** @param v reference pinyin, required for the pinyin core type @return this */
    public CompatConfig refPinyin(String v) {
        this.refPinyin = v;
        return this;
    }

    /**
     * Streams only: ask the server for progress frames {@code {"eof":0,"result":{"bytes":n}}}.
     *
     * @param v flag
     * @return this
     */
    public CompatConfig realtimeFeedback(boolean v) {
        this.realtimeFeedback = v;
        return this;
    }

    /**
     * Adds a pass through field such as {@code paragraph_need_word_score}, {@code agegroup},
     * {@code scale}, {@code precision}, {@code slack}, {@code phoneme_output} or {@code attachAudioUrl}.
     * REST sends it as a form field, streams put it into the parameter frame.
     *
     * @param name  field name
     * @param value value; null removes the field
     * @return this
     */
    public CompatConfig param(String name, Object value) {
        if (value == null) {
            params.remove(name);
        } else {
            params.put(name, value);
        }
        return this;
    }

    /** @return core type */
    public String getCoreType() {
        return coreType;
    }

    /** @return reference text */
    public String getRefText() {
        return refText;
    }

    /** @return language */
    public String getLanguage() {
        return language;
    }

    /** @return reference pinyin */
    public String getRefPinyin() {
        return refPinyin;
    }

    /** @return whether progress frames are requested */
    public boolean isRealtimeFeedback() {
        return realtimeFeedback;
    }

    /** @return pass through fields */
    public Map<String, Object> getParams() {
        return Collections.unmodifiableMap(params);
    }

    /**
     * Form fields of the REST call in the order they are sent. Empty values are left out because the
     * signature skips them too.
     *
     * @return field name to value
     */
    public Map<String, String> toFormFields() {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "refText", refText);
        put(m, "language", language);
        put(m, "refPinyin", refPinyin);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            put(m, e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return m;
    }

    /**
     * Parameter frame of the compat stream, without the idempotency key.
     *
     * @return frame fields
     */
    public Map<String, Object> toParameterFrame() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (refText != null) {
            m.put("refText", refText);
        }
        if (language != null && !language.isEmpty()) {
            m.put("language", language);
        }
        if (refPinyin != null && !refPinyin.isEmpty()) {
            m.put("refPinyin", refPinyin);
        }
        if (realtimeFeedback) {
            m.put("realtime_feedback", Boolean.TRUE);
        }
        m.putAll(params);
        return m;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isEmpty()) {
            m.put(k, v);
        }
    }
}
