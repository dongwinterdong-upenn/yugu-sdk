package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Overall scores of one evaluation, read from the {@code result} object of native and compat
 * responses. Fields absent for a mode are null (for example {@code tone} for English). Connected
 * speech has no {@code overall}; {@link #getOverall()} then returns {@code connected_overall}.
 * Mode specific scores: {@link EvalResult#getConnected()} and {@link EvalResult#getOpen()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Dimensions {
    @JsonProperty("overall")
    private Double overall;
    @JsonProperty("connected_overall")
    private Double connectedOverall;
    @JsonProperty("pronunciation")
    private Double pronunciation;
    @JsonProperty("accuracy")
    private Double accuracy;
    @JsonProperty("integrity")
    private Double integrity;
    @JsonProperty("fluency")
    private Double fluency;
    @JsonProperty("tone")
    private Double tone;
    @JsonProperty("rhythm")
    private Double rhythm;
    @JsonProperty("emotion")
    private Double emotion;
    @JsonProperty("reading_skill")
    private Double readingSkill;
    @JsonProperty("speed")
    private Double speed;
    @JsonProperty("rear_tone")
    private String rearTone;
    @JsonProperty("numeric_duration")
    @JsonAlias("duration_s")
    private Double durationSeconds;

    /** @return overall score; for connected speech the {@code connected_overall} score */
    public Double getOverall() {
        return overall != null ? overall : connectedOverall;
    }

    /** @param v overall score */
    public void setOverall(Double v) {
        this.overall = v;
    }

    /** @return pronunciation score */
    public Double getPronunciation() {
        return pronunciation;
    }

    /** @param v pronunciation score */
    public void setPronunciation(Double v) {
        this.pronunciation = v;
    }

    /** @return accuracy score, falls back to the pronunciation score when the platform sent none */
    public Double getAccuracy() {
        return accuracy != null ? accuracy : pronunciation;
    }

    /** @param v accuracy score */
    public void setAccuracy(Double v) {
        this.accuracy = v;
    }

    /** @return integrity score */
    public Double getIntegrity() {
        return integrity;
    }

    /** @param v integrity score */
    public void setIntegrity(Double v) {
        this.integrity = v;
    }

    /** @return fluency score */
    public Double getFluency() {
        return fluency;
    }

    /** @param v fluency score */
    public void setFluency(Double v) {
        this.fluency = v;
    }

    /** @return tone score (Chinese), 0 means no tone data */
    public Double getTone() {
        return tone;
    }

    /** @param v tone score */
    public void setTone(Double v) {
        this.tone = v;
    }

    /** @return rhythm score */
    public Double getRhythm() {
        return rhythm;
    }

    /** @param v rhythm score */
    public void setRhythm(Double v) {
        this.rhythm = v;
    }

    /** @return emotion score, null for modes without it */
    public Double getEmotion() {
        return emotion;
    }

    /** @param v emotion score */
    public void setEmotion(Double v) {
        this.emotion = v;
    }

    /** @return reading skill score (pauses, stress, intonation) */
    public Double getReadingSkill() {
        return readingSkill;
    }

    /** @param v reading skill score */
    public void setReadingSkill(Double v) {
        this.readingSkill = v;
    }

    /** @return speaking speed in words or characters per minute */
    public Double getSpeed() {
        return speed;
    }

    /** @param v speed */
    public void setSpeed(Double v) {
        this.speed = v;
    }

    /** @return sentence final intonation: rise, fall or flat */
    public String getRearTone() {
        return rearTone;
    }

    /** @param v rear tone */
    public void setRearTone(String v) {
        this.rearTone = v;
    }

    /** @return audio duration in seconds */
    public Double getDurationSeconds() {
        return durationSeconds;
    }

    /** @param v duration in seconds */
    public void setDurationSeconds(Double v) {
        this.durationSeconds = v;
    }

    @Override
    public String toString() {
        return "Dimensions{overall=" + overall + ", pronunciation=" + pronunciation + ", accuracy=" + accuracy
                + ", integrity=" + integrity + ", fluency=" + fluency + ", tone=" + tone + ", rhythm=" + rhythm
                + ", emotion=" + emotion + ", readingSkill=" + readingSkill + ", speed=" + speed + '}';
    }
}
