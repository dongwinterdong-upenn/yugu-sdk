package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;

/** Speech recognition text with per character alignment ({@code asrText}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsrAlignment {
    @JsonProperty("text")
    private String text;
    @JsonProperty("alignment")
    private List<Item> alignment;
    @JsonIgnore
    private JsonNode raw;

    /** @return recognised text */
    public String getText() {
        return text;
    }

    /** @return alignment items, never null */
    public List<Item> getAlignment() {
        return alignment == null ? Collections.emptyList() : alignment;
    }

    /**
     * Complete {@code asrText} object, including mode specific evidence such as
     * {@code overreadDetected} or {@code mismatches}.
     *
     * @return raw JSON
     */
    public JsonNode getRaw() {
        return raw;
    }

    /** @param raw raw JSON */
    public void setRaw(JsonNode raw) {
        this.raw = raw;
    }

    /** One aligned character. Times are in units of 10 ms. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        @JsonProperty("char")
        private String character;
        @JsonProperty("index")
        private Integer index;
        @JsonProperty("read_status")
        private String readStatus;
        @JsonProperty("start_time")
        private Integer startTime;
        @JsonProperty("end_time")
        private Integer endTime;
        @JsonProperty("asr_pinyin")
        private String asrPinyin;
        @JsonProperty("asr_tone")
        private Integer asrTone;
        @JsonProperty("asr_initial")
        private String asrInitial;
        @JsonProperty("asr_final")
        private String asrFinal;
        @JsonProperty("gop_score")
        private Double gopScore;

        /** @return reference character */
        public String getCharacter() {
            return character;
        }

        /** @return index in the reference text */
        public Integer getIndex() {
            return index;
        }

        /** @return correct, mispronounced, skipped or inserted */
        public String getReadStatus() {
            return readStatus;
        }

        /** @return start in 10 ms units */
        public Integer getStartTime() {
            return startTime;
        }

        /** @return end in 10 ms units */
        public Integer getEndTime() {
            return endTime;
        }

        /** @return recognised pinyin with tone number */
        public String getAsrPinyin() {
            return asrPinyin;
        }

        /** @return recognised tone */
        public Integer getAsrTone() {
            return asrTone;
        }

        /** @return recognised initial */
        public String getAsrInitial() {
            return asrInitial;
        }

        /** @return recognised final */
        public String getAsrFinal() {
            return asrFinal;
        }

        /** @return goodness of pronunciation score */
        public Double getGopScore() {
            return gopScore;
        }
    }
}
