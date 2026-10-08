package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Native evaluation parameters: the JSON of the {@code config} part of {@code POST /api/v1/evaluate}
 * and of the start frame of the native stream. Only non-null fields are sent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"coreType", "referenceText", "language", "slack", "scale", "precision", "agegroup",
        "includeReport", "includeStandardAudio", "includeAsrText", "toneWeight", "refPinyin", "phonemeOutput",
        "taskType", "paragraphNeedWordScore"})
public class EvaluateConfig {
    /** Single word. */
    public static final String CORE_WORD = "word";
    /** Sentence. */
    public static final String CORE_SENTENCE = "sentence";
    /** Paragraph. */
    public static final String CORE_PASSAGE = "passage";
    /** Connected speech. */
    public static final String CORE_CONNECTED = "connected";
    /** Open question; referenceText is the prompt. */
    public static final String CORE_OPEN = "open";
    /** Letters. */
    public static final String CORE_ALPHA = "alpha";
    /** Pinyin; set refPinyin. */
    public static final String CORE_PINYIN = "pinyin";

    @JsonProperty("coreType")
    private String coreType;
    @JsonProperty("referenceText")
    private String referenceText;
    @JsonProperty("language")
    private String language;
    @JsonProperty("includeReport")
    private Boolean includeReport;
    @JsonProperty("includeStandardAudio")
    private Boolean includeStandardAudio;
    @JsonProperty("includeAsrText")
    private Boolean includeAsrText;
    @JsonProperty("slack")
    private Double slack;
    @JsonProperty("scale")
    private Integer scale;
    @JsonProperty("precision")
    private Double precision;
    @JsonProperty("agegroup")
    private Integer agegroup;
    @JsonProperty("toneWeight")
    private Double toneWeight;
    @JsonProperty("refPinyin")
    private String refPinyin;
    @JsonProperty("phonemeOutput")
    private Boolean phonemeOutput;
    @JsonProperty("taskType")
    private String taskType;
    @JsonProperty("paragraphNeedWordScore")
    private Integer paragraphNeedWordScore;

    private final Map<String, Object> extra = new LinkedHashMap<>();

    /** Creates an empty configuration. */
    public EvaluateConfig() {
    }

    /**
     * @param coreType      word, sentence, passage, connected, open, alpha or pinyin
     * @param referenceText reference text, the prompt for open questions
     * @param language      en-US, en-GB or zh-CN
     */
    public EvaluateConfig(String coreType, String referenceText, String language) {
        this.coreType = coreType;
        this.referenceText = referenceText;
        this.language = language;
    }

    /** @param v core type @return this */
    public EvaluateConfig coreType(String v) {
        this.coreType = v;
        return this;
    }

    /** @param v reference text, at most 1000 characters @return this */
    public EvaluateConfig referenceText(String v) {
        this.referenceText = v;
        return this;
    }

    /** @param v en-US (platform default), en-GB or zh-CN @return this */
    public EvaluateConfig language(String v) {
        this.language = v;
        return this;
    }

    /** @param v return the detailed report @return this */
    public EvaluateConfig includeReport(Boolean v) {
        this.includeReport = v;
        return this;
    }

    /** @param v return a reference audio URL @return this */
    public EvaluateConfig includeStandardAudio(Boolean v) {
        this.includeStandardAudio = v;
        return this;
    }

    /** @param v return the recognised text @return this */
    public EvaluateConfig includeAsrText(Boolean v) {
        this.includeAsrText = v;
        return this;
    }

    /** @param v scoring leniency in [-1, 1], default 0 @return this */
    public EvaluateConfig slack(Double v) {
        this.slack = v;
        return this;
    }

    /** @param v score scale in (0, 100], default 100 @return this */
    public EvaluateConfig scale(Integer v) {
        this.scale = v;
        return this;
    }

    /** @param v score precision in (0, 1], default 1 @return this */
    public EvaluateConfig precision(Double v) {
        this.precision = v;
        return this;
    }

    /** @param v 1 preschool, 2 primary school, 3 older than 12 (default) @return this */
    public EvaluateConfig agegroup(Integer v) {
        this.agegroup = v;
        return this;
    }

    /** @param v reserved, in [0, 1], does not change the overall score @return this */
    public EvaluateConfig toneWeight(Double v) {
        this.toneWeight = v;
        return this;
    }

    /** @param v pinyin such as {@code chong2 qing4} for polyphones and pinyin questions @return this */
    public EvaluateConfig refPinyin(String v) {
        this.refPinyin = v;
        return this;
    }

    /** @param v return phoneme level output @return this */
    public EvaluateConfig phonemeOutput(Boolean v) {
        this.phonemeOutput = v;
        return this;
    }

    /** @param v open question type: picture, situational or free @return this */
    public EvaluateConfig taskType(String v) {
        this.taskType = v;
        return this;
    }

    /** @param v 1 to return word scores for passages @return this */
    public EvaluateConfig paragraphNeedWordScore(Integer v) {
        this.paragraphNeedWordScore = v;
        return this;
    }

    /**
     * Adds a field the SDK does not model yet. It is written into the config JSON as is.
     *
     * @param name  field name
     * @param value JSON compatible value, null removes the field
     * @return this
     */
    public EvaluateConfig param(String name, Object value) {
        if (value == null) {
            extra.remove(name);
        } else {
            extra.put(name, value);
        }
        return this;
    }

    /** @return extra fields added with {@link #param(String, Object)} */
    @JsonAnyGetter
    public Map<String, Object> getExtraParams() {
        return Collections.unmodifiableMap(extra);
    }

    /** @return core type */
    public String getCoreType() {
        return coreType;
    }

    /** @return reference text */
    public String getReferenceText() {
        return referenceText;
    }

    /** @return language */
    public String getLanguage() {
        return language;
    }

    /** @return includeReport */
    public Boolean getIncludeReport() {
        return includeReport;
    }

    /** @return includeStandardAudio */
    public Boolean getIncludeStandardAudio() {
        return includeStandardAudio;
    }

    /** @return includeAsrText */
    public Boolean getIncludeAsrText() {
        return includeAsrText;
    }

    /** @return slack */
    public Double getSlack() {
        return slack;
    }

    /** @return scale */
    public Integer getScale() {
        return scale;
    }

    /** @return precision */
    public Double getPrecision() {
        return precision;
    }

    /** @return age group */
    public Integer getAgegroup() {
        return agegroup;
    }

    /** @return tone weight */
    public Double getToneWeight() {
        return toneWeight;
    }

    /** @return reference pinyin */
    public String getRefPinyin() {
        return refPinyin;
    }

    /** @return phonemeOutput */
    public Boolean getPhonemeOutput() {
        return phonemeOutput;
    }

    /** @return task type */
    public String getTaskType() {
        return taskType;
    }

    /** @return paragraphNeedWordScore */
    public Integer getParagraphNeedWordScore() {
        return paragraphNeedWordScore;
    }
}
