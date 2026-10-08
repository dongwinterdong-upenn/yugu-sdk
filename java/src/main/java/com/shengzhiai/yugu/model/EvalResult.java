package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.errors.WarningCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Evaluation result of {@code evaluate}, {@code evaluateCompat} and both streaming calls. Native and
 * compat responses are parsed into the same structure; the complete response stays available in
 * {@link #getRaw()}.
 */
public class EvalResult {
    private String recordId;
    private Integer eof;
    private Dimensions dims = new Dimensions();
    private List<WordScore> words = Collections.emptyList();
    private List<SentenceScore> sentences = Collections.emptyList();
    private AsrAlignment asrText;
    private JsonNode report;
    private StandardAudio standardAudio;
    private List<Warning> warnings = Collections.emptyList();
    private List<Warning> localWarnings = Collections.emptyList();
    private String language;
    private String coreType;
    private JsonNode yuguScores;
    private JsonNode compositeReport;
    private ConnectedScores connected;
    private OpenScores open;
    private JsonNode raw;
    private String idempotencyKey;
    private boolean replayed;
    private int attempts = 1;

    /** @return platform record id, use it with {@code getReport} */
    public String getRecordId() {
        return recordId;
    }

    /** @param v record id */
    public void setRecordId(String v) {
        this.recordId = v;
    }

    /** @return 1 for a final result, 0 for a progress frame */
    public Integer getEof() {
        return eof;
    }

    /** @param v eof flag */
    public void setEof(Integer v) {
        this.eof = v;
    }

    /** @return true for a final result */
    public boolean isFinal() {
        return eof == null || eof == 1;
    }

    /** @return overall scores, never null */
    public Dimensions getDims() {
        return dims;
    }

    /** @param v overall scores */
    public void setDims(Dimensions v) {
        this.dims = v == null ? new Dimensions() : v;
    }

    /** @return overall score, shortcut for {@code getDims().getOverall()} */
    public Double getOverall() {
        return dims.getOverall();
    }

    /** @return word scores, never null */
    public List<WordScore> getWords() {
        return words;
    }

    /** @param v word scores */
    public void setWords(List<WordScore> v) {
        this.words = v == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(v));
    }

    /** @return sentence scores, never null */
    public List<SentenceScore> getSentences() {
        return sentences;
    }

    /** @param v sentence scores */
    public void setSentences(List<SentenceScore> v) {
        this.sentences = v == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(v));
    }

    /** @return recognised text and alignment, null unless {@code includeAsrText} was set */
    public AsrAlignment getAsrText() {
        return asrText;
    }

    /** @param v asr text */
    public void setAsrText(AsrAlignment v) {
        this.asrText = v;
    }

    /** @return detailed report, structure depends on the mode, null unless requested */
    public JsonNode getReport() {
        return report;
    }

    /** @param v report */
    public void setReport(JsonNode v) {
        this.report = v;
    }

    /** @return reference audio, null unless {@code includeStandardAudio} was set */
    public StandardAudio getStandardAudio() {
        return standardAudio;
    }

    /** @param v standard audio */
    public void setStandardAudio(StandardAudio v) {
        this.standardAudio = v;
    }

    /** @return audio quality warnings reported by the platform, never null */
    public List<Warning> getWarnings() {
        return warnings;
    }

    /** @param v warnings */
    public void setWarnings(List<Warning> v) {
        this.warnings = v == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(v));
    }

    /** @return codes of {@link #getWarnings()} */
    public List<Integer> getWarningCodes() {
        List<Integer> out = new ArrayList<>(warnings.size());
        for (Warning w : warnings) {
            out.add(w.getCode());
        }
        return out;
    }

    /**
     * @param code warning
     * @return true when the platform reported this warning
     */
    public boolean hasWarning(WarningCode code) {
        for (Warning w : warnings) {
            if (w.getCode() == code.getCode()) {
                return true;
            }
        }
        return false;
    }

    /** @return warnings of the local audio precheck (codes 90101 to 90105), never null */
    public List<Warning> getLocalWarnings() {
        return localWarnings;
    }

    /** @param v local warnings */
    public void setLocalWarnings(List<Warning> v) {
        this.localWarnings = v == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(v));
    }

    /** @return language reported by the platform, for example zh or en */
    public String getLanguage() {
        return language;
    }

    /** @param v language */
    public void setLanguage(String v) {
        this.language = v;
    }

    /** @return core type reported by the platform */
    public String getCoreType() {
        return coreType;
    }

    /** @param v core type */
    public void setCoreType(String v) {
        this.coreType = v;
    }

    /** @return the platform structured dimension tree {@code result.yuguScores}, may be null */
    public JsonNode getYuguScores() {
        return yuguScores;
    }

    /** @param v yugu scores */
    public void setYuguScores(JsonNode v) {
        this.yuguScores = v;
    }

    /** @return {@code result.compositeReport}, may be null */
    public JsonNode getCompositeReport() {
        return compositeReport;
    }

    /** @param v composite report */
    public void setCompositeReport(JsonNode v) {
        this.compositeReport = v;
    }

    /** @return scores of connected speech, null for other modes */
    public ConnectedScores getConnected() {
        return connected;
    }

    /** @param v connected speech scores */
    public void setConnected(ConnectedScores v) {
        this.connected = v;
    }

    /** @return scores of open questions, null for other modes */
    public OpenScores getOpen() {
        return open;
    }

    /** @param v open question scores */
    public void setOpen(OpenScores v) {
        this.open = v;
    }

    /** @return the {@code result} object of the response */
    public JsonNode getResultNode() {
        return raw == null ? null : raw.get("result");
    }

    /** @return the complete response JSON */
    public JsonNode getRaw() {
        return raw;
    }

    /** @param v raw JSON */
    public void setRaw(JsonNode v) {
        this.raw = v;
    }

    /** @return idempotency key the call or session used, null when none was sent */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @param v idempotency key */
    public void setIdempotencyKey(String v) {
        this.idempotencyKey = v;
    }

    /**
     * True when the platform answered from its idempotency store: the same key and the same request
     * were seen before, the stored first result was returned and nothing was billed again.
     *
     * @return replay flag
     */
    public boolean isReplayed() {
        return replayed;
    }

    /** @param v replay flag */
    public void setReplayed(boolean v) {
        this.replayed = v;
    }

    /** @return attempts or connections used to get this result, at least 1 */
    public int getAttempts() {
        return attempts;
    }

    /** @param v attempts */
    public void setAttempts(int v) {
        this.attempts = v;
    }

    @Override
    public String toString() {
        return "EvalResult{recordId=" + recordId + ", overall=" + getOverall() + ", words=" + words.size()
                + ", sentences=" + sentences.size() + ", warnings=" + getWarningCodes()
                + ", replayed=" + replayed + ", attempts=" + attempts + '}';
    }
}
