package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;

/**
 * Scores of open questions ({@code coreType=open}): content, language use and delivery, with the
 * transcript, feedback and the task audit.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenScores {
    @JsonProperty("overall")
    private Double overall;
    @JsonProperty("taskType")
    private String taskType;
    @JsonProperty("language")
    private String language;
    @JsonProperty("duration_s")
    private Double durationSeconds;
    @JsonProperty("transcript")
    private String transcript;
    @JsonProperty("hasSpeech")
    private Boolean hasSpeech;
    @JsonProperty("content")
    private Content content;
    @JsonProperty("languageUse")
    private LanguageUse languageUse;
    @JsonProperty("delivery")
    private Delivery delivery;
    @JsonProperty("feedback")
    private Feedback feedback;
    @JsonProperty("openTaskAudit")
    private TaskAudit openTaskAudit;
    @JsonProperty("audioQuality")
    private AudioQuality audioQuality;
    @JsonProperty("aggregation")
    private JsonNode aggregation;
    @JsonProperty("rubricVersion")
    private String rubricVersion;
    @JsonProperty("scoringSource")
    private String scoringSource;

    /** @return overall score */
    public Double getOverall() {
        return overall;
    }

    /** @return picture, situational or free */
    public String getTaskType() {
        return taskType;
    }

    /** @return language of the answer */
    public String getLanguage() {
        return language;
    }

    /** @return duration in seconds */
    public Double getDurationSeconds() {
        return durationSeconds;
    }

    /** @return recognised answer */
    public String getTranscript() {
        return transcript;
    }

    /** @return whether speech was detected */
    public Boolean getHasSpeech() {
        return hasSpeech;
    }

    /** @return content scores, may be null */
    public Content getContent() {
        return content;
    }

    /** @return language use scores, may be null */
    public LanguageUse getLanguageUse() {
        return languageUse;
    }

    /** @return delivery scores, may be null */
    public Delivery getDelivery() {
        return delivery;
    }

    /** @return feedback, may be null */
    public Feedback getFeedback() {
        return feedback;
    }

    /** @return prompt echo, low content and off topic audit; caps apply when flagged, may be null */
    public TaskAudit getOpenTaskAudit() {
        return openTaskAudit;
    }

    /** @return audio quality estimate, may be null */
    public AudioQuality getAudioQuality() {
        return audioQuality;
    }

    /** @return how the overall score was aggregated and capped, may be null */
    public JsonNode getAggregation() {
        return aggregation;
    }

    /** @return rubric version */
    public String getRubricVersion() {
        return rubricVersion;
    }

    /** @return scoring components used */
    public String getScoringSource() {
        return scoringSource;
    }

    /** Content dimension. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {
        @JsonProperty("overall")
        private Double overall;
        @JsonProperty("relevance")
        private Double relevance;
        @JsonProperty("coherence")
        private Double coherence;
        @JsonProperty("task_achievement")
        private Double taskAchievement;

        /** @return content score */
        public Double getOverall() {
            return overall;
        }

        /** @return relevance to the prompt */
        public Double getRelevance() {
            return relevance;
        }

        /** @return coherence */
        public Double getCoherence() {
            return coherence;
        }

        /** @return task achievement */
        public Double getTaskAchievement() {
            return taskAchievement;
        }
    }

    /** Language use dimension. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LanguageUse {
        @JsonProperty("overall")
        private Double overall;
        @JsonProperty("grammar")
        private Double grammar;
        @JsonProperty("vocabulary")
        private Double vocabulary;

        /** @return language use score */
        public Double getOverall() {
            return overall;
        }

        /** @return grammar */
        public Double getGrammar() {
            return grammar;
        }

        /** @return vocabulary */
        public Double getVocabulary() {
            return vocabulary;
        }
    }

    /** Delivery dimension. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Delivery {
        @JsonProperty("overall")
        private Double overall;
        @JsonProperty("fluency")
        private Double fluency;
        @JsonProperty("pronunciation")
        private Double pronunciation;
        @JsonProperty("speech_rate")
        private Double speechRate;
        @JsonProperty("speech_rate_label")
        private String speechRateLabel;
        @JsonProperty("n_pauses")
        private Integer pauseCount;
        @JsonProperty("longest_pause_s")
        private Double longestPauseSeconds;
        @JsonProperty("voiced_s")
        private Double voicedSeconds;

        /** @return delivery score */
        public Double getOverall() {
            return overall;
        }

        /** @return fluency */
        public Double getFluency() {
            return fluency;
        }

        /** @return pronunciation */
        public Double getPronunciation() {
            return pronunciation;
        }

        /** @return speech rate in units per second */
        public Double getSpeechRate() {
            return speechRate;
        }

        /** @return speech rate for display, for example {@code 233.8 字/分} */
        public String getSpeechRateLabel() {
            return speechRateLabel;
        }

        /** @return number of long pauses */
        public Integer getPauseCount() {
            return pauseCount;
        }

        /** @return longest pause in seconds */
        public Double getLongestPauseSeconds() {
            return longestPauseSeconds;
        }

        /** @return voiced time in seconds */
        public Double getVoicedSeconds() {
            return voicedSeconds;
        }
    }

    /** Textual feedback. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Feedback {
        @JsonProperty("strengths")
        private String strengths;
        @JsonProperty("weaknesses")
        private String weaknesses;
        @JsonProperty("suggestions")
        private List<String> suggestions;

        /** @return strengths */
        public String getStrengths() {
            return strengths;
        }

        /** @return weaknesses */
        public String getWeaknesses() {
            return weaknesses;
        }

        /** @return suggestions, never null */
        public List<String> getSuggestions() {
            return suggestions == null ? Collections.emptyList() : suggestions;
        }
    }

    /** Task audit of open questions. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskAudit {
        @JsonProperty("promptEcho")
        private Boolean promptEcho;
        @JsonProperty("lowContent")
        private Boolean lowContent;
        @JsonProperty("offTopic")
        private Boolean offTopic;
        @JsonProperty("lowYield")
        private Boolean lowYield;
        @JsonProperty("capApplied")
        private Boolean capApplied;
        @JsonProperty("answerUnits")
        private Integer answerUnits;

        /** @return the answer repeated the prompt; overall capped at 60 */
        public Boolean getPromptEcho() {
            return promptEcho;
        }

        /** @return the answer had little content; overall capped at 70 */
        public Boolean getLowContent() {
            return lowContent;
        }

        /** @return the answer was off topic */
        public Boolean getOffTopic() {
            return offTopic;
        }

        /** @return the answer was too short */
        public Boolean getLowYield() {
            return lowYield;
        }

        /** @return whether a cap was applied to the overall score */
        public Boolean getCapApplied() {
            return capApplied;
        }

        /** @return counted answer units */
        public Integer getAnswerUnits() {
            return answerUnits;
        }
    }

    /** Audio quality estimate. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AudioQuality {
        @JsonProperty("mos")
        private Double mos;
        @JsonProperty("quality")
        private Double quality;

        /** @return mean opinion score estimate, 1 to 5 */
        public Double getMos() {
            return mos;
        }

        /** @return quality score */
        public Double getQuality() {
            return quality;
        }
    }
}
