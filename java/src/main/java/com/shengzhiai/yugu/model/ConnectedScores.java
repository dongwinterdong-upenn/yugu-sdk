package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;

/**
 * Scores of connected speech ({@code coreType=connected}). The total is {@code connected_overall};
 * the result has no {@code overall} field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectedScores {
    @JsonProperty("connected_overall")
    private Double connectedOverall;
    @JsonProperty("linking")
    private Double linking;
    @JsonProperty("rhythm")
    private Double rhythm;
    @JsonProperty("elision")
    private Double elision;
    @JsonProperty("reduction")
    private Double reduction;
    @JsonProperty("n_boundaries")
    private Integer boundaryCount;
    @JsonProperty("boundaries")
    private List<Boundary> boundaries;
    @JsonProperty("raw")
    private JsonNode metrics;
    @JsonProperty("coverage")
    private JsonNode coverage;
    @JsonProperty("report")
    private JsonNode report;

    /** @return total score of connected speech */
    public Double getConnectedOverall() {
        return connectedOverall;
    }

    /** @return linking score */
    public Double getLinking() {
        return linking;
    }

    /** @return rhythm score */
    public Double getRhythm() {
        return rhythm;
    }

    /** @return elision score */
    public Double getElision() {
        return elision;
    }

    /** @return reduction score */
    public Double getReduction() {
        return reduction;
    }

    /** @return number of word boundaries assessed */
    public Integer getBoundaryCount() {
        return boundaryCount;
    }

    /** @return assessed word boundaries, never null */
    public List<Boundary> getBoundaries() {
        return boundaries == null ? Collections.emptyList() : boundaries;
    }

    /** @return raw acoustic metrics such as {@code linking_rate} and {@code nPVI_V}, may be null */
    public JsonNode getMetrics() {
        return metrics;
    }

    /** @return word coverage of the recognition, may be null */
    public JsonNode getCoverage() {
        return coverage;
    }

    /** @return textual feedback inside the result ({@code summary}, {@code suggestions}), may be null */
    public JsonNode getReport() {
        return report;
    }

    /** One boundary between two words. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Boundary {
        @JsonProperty("between")
        private List<String> between;
        @JsonProperty("tags")
        private List<String> tags;
        @JsonProperty("realized")
        private Double realized;
        @JsonProperty("continuity")
        private Double continuity;
        @JsonProperty("gap_ms")
        private Double gapMs;
        @JsonProperty("start_ms")
        private Double startMs;
        @JsonProperty("end_ms")
        private Double endMs;
        @JsonProperty("covered")
        private Boolean covered;

        /** @return the two words, never null */
        public List<String> getBetween() {
            return between == null ? Collections.emptyList() : between;
        }

        /** @return expected phenomena such as {@code linking_CV}, {@code elision}, {@code reduction}, never null */
        public List<String> getTags() {
            return tags == null ? Collections.emptyList() : tags;
        }

        /** @return how far the phenomena were realised, 0 to 1 */
        public Double getRealized() {
            return realized;
        }

        /** @return acoustic continuity, 0 to 1 */
        public Double getContinuity() {
            return continuity;
        }

        /** @return silence between the words in ms */
        public Double getGapMs() {
            return gapMs;
        }

        /** @return start in ms */
        public Double getStartMs() {
            return startMs;
        }

        /** @return end in ms */
        public Double getEndMs() {
            return endMs;
        }

        /** @return whether both words were recognised */
        public Boolean getCovered() {
            return covered;
        }
    }
}
