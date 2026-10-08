package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * Word or character level score ({@code result.words[]} and {@code result.sentences[].details[]}).
 * Times are in units of 10 ms.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WordScore {
    @JsonProperty("word")
    private String word;
    @JsonProperty("pinyin")
    private String pinyin;
    @JsonProperty("rawpinyin")
    private String rawPinyin;
    @JsonProperty("symbolpinyin")
    private String symbolPinyin;
    @JsonProperty("charType")
    private Integer charType;
    @JsonProperty("readType")
    private Integer readType;
    @JsonProperty("tone")
    private String tone;
    @JsonProperty("scores")
    private Scores scores;
    @JsonProperty("span")
    private Span span;
    @JsonProperty("pause")
    private Pause pause;
    @JsonProperty("phonemes")
    private List<Phoneme> phonemes;
    @JsonProperty("phonics")
    private List<Phonic> phonics;
    @JsonProperty("syllables")
    private List<Syllable> syllables;
    @JsonProperty("readStatus")
    @JsonAlias("read_status")
    private String readStatus;

    /** @return the word or character */
    public String getWord() {
        return word;
    }

    /** @return pinyin without tone, Chinese only */
    public String getPinyin() {
        return pinyin;
    }

    /** @return pinyin with tone number, for example {@code jin1} */
    public String getRawPinyin() {
        return rawPinyin;
    }

    /** @return pinyin with tone mark, for example {@code jīn} */
    public String getSymbolPinyin() {
        return symbolPinyin;
    }

    /** @return 0 for Chinese characters, 1 for English words */
    public Integer getCharType() {
        return charType;
    }

    /** @return read type reported by the engine */
    public Integer getReadType() {
        return readType;
    }

    /** @return expected tone such as {@code tone3} */
    public String getTone() {
        return tone;
    }

    /** @return scores, never null */
    public Scores getScores() {
        return scores == null ? new Scores() : scores;
    }

    /** @return time span */
    public Span getSpan() {
        return span;
    }

    /** @return pause after the word */
    public Pause getPause() {
        return pause;
    }

    /** @return phoneme scores, never null */
    public List<Phoneme> getPhonemes() {
        return phonemes == null ? Collections.emptyList() : phonemes;
    }

    /** @return letter to sound scores for English words, never null */
    public List<Phonic> getPhonics() {
        return phonics == null ? Collections.emptyList() : phonics;
    }

    /** @return syllable scores for English words, never null */
    public List<Syllable> getSyllables() {
        return syllables == null ? Collections.emptyList() : syllables;
    }

    /** @return correct, mispronounced, skipped or inserted */
    public String getReadStatus() {
        return readStatus;
    }

    @Override
    public String toString() {
        return "WordScore{" + word + ", overall=" + getScores().getOverall() + ", readStatus=" + readStatus + '}';
    }

    /** Scores of one word. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Scores {
        @JsonProperty("overall")
        private Double overall;
        @JsonProperty("pronunciation")
        private Double pronunciation;
        @JsonProperty("tone")
        private Double tone;
        @JsonProperty("overall_pron")
        private Double overallPron;
        @JsonProperty("prominence")
        private Double prominence;
        @JsonProperty("stress")
        private List<Stress> stress;

        /** @return overall word score */
        public Double getOverall() {
            return overall;
        }

        /** @return pronunciation score */
        public Double getPronunciation() {
            return pronunciation;
        }

        /** @return tone score, Chinese only */
        public Double getTone() {
            return tone;
        }

        /** @return pronunciation score before tone weighting */
        public Double getOverallPron() {
            return overallPron;
        }

        /** @return prominence */
        public Double getProminence() {
            return prominence;
        }

        /** @return word stress detail for English, never null */
        public List<Stress> getStress() {
            return stress == null ? Collections.emptyList() : stress;
        }
    }

    /** Stress of one syllable of an English word. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stress {
        @JsonProperty("ref_stress")
        private Integer refStress;
        @JsonProperty("stress")
        private Integer stress;
        @JsonProperty("overall")
        private Double overall;
        @JsonProperty("phonetic")
        private String phonetic;

        /** @return expected stress */
        public Integer getRefStress() {
            return refStress;
        }

        /** @return detected stress */
        public Integer getStress() {
            return stress;
        }

        /** @return stress score */
        public Double getOverall() {
            return overall;
        }

        /** @return phonetic symbol of the stressed vowel */
        public String getPhonetic() {
            return phonetic;
        }
    }

    /** Time span in units of 10 ms. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Span {
        @JsonProperty("start")
        private Integer start;
        @JsonProperty("end")
        private Integer end;

        /** @return start in 10 ms units */
        public Integer getStart() {
            return start;
        }

        /** @return end in 10 ms units */
        public Integer getEnd() {
            return end;
        }
    }

    /** Pause after a word. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pause {
        @JsonProperty("type")
        private Integer type;
        @JsonProperty("duration")
        private Double duration;

        /** @return pause type */
        public Integer getType() {
            return type;
        }

        /** @return pause duration */
        public Double getDuration() {
            return duration;
        }
    }

    /** Phoneme level score. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Phoneme {
        @JsonProperty("phoneme")
        private String phoneme;
        @JsonProperty("phone")
        private String phone;
        @JsonProperty("category")
        private Integer category;
        @JsonProperty("pronunciation")
        private Double pronunciation;
        @JsonProperty("tone_index")
        private String toneIndex;
        @JsonProperty("span")
        private Span span;

        /** @return phoneme symbol */
        public String getPhoneme() {
            return phoneme;
        }

        /** @return phone label */
        public String getPhone() {
            return phone;
        }

        /** @return 0 initial or consonant, 1 final or vowel */
        public Integer getCategory() {
            return category;
        }

        /** @return phoneme pronunciation score */
        public Double getPronunciation() {
            return pronunciation;
        }

        /** @return tone index */
        public String getToneIndex() {
            return toneIndex;
        }

        /** @return time span */
        public Span getSpan() {
            return span;
        }
    }

    /** Letter to sound score of an English word. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Phonic {
        @JsonProperty("spell")
        private String spell;
        @JsonProperty("phoneme")
        private List<String> phoneme;
        @JsonProperty("overall")
        private Double overall;

        /** @return letters */
        public String getSpell() {
            return spell;
        }

        /** @return phonemes of the letters, never null */
        public List<String> getPhoneme() {
            return phoneme == null ? Collections.emptyList() : phoneme;
        }

        /** @return score */
        public Double getOverall() {
            return overall;
        }
    }

    /** Syllable score of an English word. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Syllable {
        @JsonProperty("syllable")
        private String syllable;
        @JsonProperty("grapheme")
        private String grapheme;
        @JsonProperty("accuracy")
        private Double accuracy;

        /** @return syllable phonemes */
        public String getSyllable() {
            return syllable;
        }

        /** @return letters of the syllable */
        public String getGrapheme() {
            return grapheme;
        }

        /** @return accuracy */
        public Double getAccuracy() {
            return accuracy;
        }
    }
}
