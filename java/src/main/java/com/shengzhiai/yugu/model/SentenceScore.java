package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * Sentence level score ({@code result.sentences[]}). Sentence modes carry word {@link #getDetails()},
 * paragraph modes carry one entry per sentence with its own {@link #getOverall()} and span.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SentenceScore {
    @JsonProperty("sentence")
    private String sentence;
    @JsonProperty("text")
    private String text;
    @JsonProperty("index")
    private Integer index;
    @JsonProperty("overall")
    private Double overall;
    @JsonProperty("scores")
    private Scores scores;
    @JsonProperty("span")
    private WordScore.Span span;
    @JsonProperty("details")
    private List<WordScore> details;

    /** @return sentence text */
    public String getSentence() {
        return sentence != null ? sentence : text;
    }

    /** @return position in the paragraph, from 0 */
    public Integer getIndex() {
        return index;
    }

    /** @return sentence overall score, from {@code overall} or {@code scores.overall} */
    public Double getOverall() {
        if (overall != null) {
            return overall;
        }
        return scores == null ? null : scores.getOverall();
    }

    /** @return scores, never null */
    public Scores getScores() {
        return scores == null ? new Scores() : scores;
    }

    /** @return time span, paragraph modes only */
    public WordScore.Span getSpan() {
        return span;
    }

    /** @return word scores of the sentence, never null */
    public List<WordScore> getDetails() {
        return details == null ? Collections.emptyList() : details;
    }

    @Override
    public String toString() {
        return "SentenceScore{" + getSentence() + ", overall=" + getOverall() + '}';
    }

    /** Scores of one sentence. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Scores {
        @JsonProperty("overall")
        private Double overall;
        @JsonProperty("pronunciation")
        private Double pronunciation;
        @JsonProperty("fluency")
        private Double fluency;
        @JsonProperty("integrity")
        private Double integrity;

        /** @return overall */
        public Double getOverall() {
            return overall;
        }

        /** @return pronunciation */
        public Double getPronunciation() {
            return pronunciation;
        }

        /** @return fluency */
        public Double getFluency() {
            return fluency;
        }

        /** @return integrity */
        public Double getIntegrity() {
            return integrity;
        }
    }
}
