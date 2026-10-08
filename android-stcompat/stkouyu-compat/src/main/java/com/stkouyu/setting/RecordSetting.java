// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.setting;

import com.shengzhiai.yugu.stcompat.internal.CoreTypes;
import com.stkouyu.AudioType;
import com.stkouyu.CustomParam;
import com.stkouyu.EngineType;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording and evaluation options. Mapping to the platform form fields (DESIGN 6.1):
 * <ul>
 * <li>coreType: request path {@code /{coreType}}; refText, refPinyin, agegroup (when set to 1, 2
 * or 3), scale, precision, slack: same names</li>
 * <li>needWordScoreInParagraph: {@code paragraph_need_word_score=1}; needPhonemeOutputInWord:
 * {@code phoneme_output=1}; needAttachAudioUrlInResult: {@code attachAudioUrl=1}</li>
 * <li>dict_type, dict_dialect, customized_lexicon, customized_pron, output_rawtext, punctuate,
 * itn, detect_nonscorable, vad_detction, keywords, keypoints, keypoints_weight,
 * negative_keypoints, negativeReftext, mode, qType: same names; readtypeDiagnosis:
 * {@code readtype_diagnosis}; newParams: key=value; request: {@code request}</li>
 * <li>realtime_feedback, seek, ref_length, duration, durationInterval, VAD and audio options stay
 * local; customized_sig, customized_sig_url, refAudio, compress, protocol, chunkSize,
 * max_ogg_delay and blendPhonemeEnable are kept but not sent</li>
 * </ul>
 * Constructors accept coreType and refText in either order: a value that is a kernel name (for
 * example {@code sent.eval.cn}) is taken as coreType.
 */
public class RecordSetting {
    private String coreType;
    private String refText;
    private String refAudio;
    private String refPinyin;
    private String coreProvideType = EngineType.ENGINE_CLOUD;
    private String audioType = AudioType.WAV;
    private int sampleRate = 16000;
    private int channel = 1;
    private String compress;
    private String recordFilePath;
    private String recordName;
    private String audioPath;
    private boolean isStream;
    private Integer audioSource;
    private Double scale;
    private double precision;
    private double slack;
    private Integer seek;
    private Integer refLength;
    private Integer chunkSize;
    private Integer realtimeFeedback;
    private Integer maxOggDelay;
    private boolean needWordScoreInParagraph;
    private boolean needPhonemeOutputInWord;
    private boolean needAttachAudioUrlInResult;
    private boolean needRequestParamsInResult;
    private boolean needSoundIntensity;
    private Integer readtypeDiagnosis;
    private Integer detectNonscorable;
    private Integer vadDetction;
    private Integer punctuate;
    private Integer itn;
    private Integer outputRawtext;
    private String keywords;
    private String keypoints;
    private Double keypointsWeight;
    private String negativeKeypoints;
    private String customizedLexicon;
    private String customizedPron;
    private String customizedSig;
    private String customizedSigUrl;
    private String negativeReftext;
    private String dictType;
    private String dictDialect;
    private String mode;
    private int qType;
    private int agegroup;
    private ArrayList<CustomParam> newParams;
    private String protocol;
    private String request;
    private boolean autoRetry;
    private boolean forceRecord;
    private boolean muteMusic;
    private Integer duration;
    private Integer durationInterval;
    private Integer serverTimeout;
    private List<String> errIds = defaultErrIds();
    private boolean vadEnabled;
    private boolean blendPhonemeEnable;

    private static List<String> defaultErrIds() {
        List<String> l = new ArrayList<String>();
        l.add("20009");
        return l;
    }

    public Integer getSeek() {
        return seek;
    }

    /** VAD end-of-speech silence in 10 ms units, default 60 (600 ms). */
    public void setSeek(Integer seek) {
        this.seek = seek;
    }

    public Integer getRef_length() {
        return refLength;
    }

    /** Minimum speech length in 10 ms units before VAD may report the end of speech. */
    public void setRef_length(Integer refLength) {
        this.refLength = refLength;
    }

    public boolean isAutoRetry() {
        return autoRetry;
    }

    /** Re-submit the same audio with the same tokenId (at most 2 extra times) when the errId is in errIds. */
    public void setAutoRetry(boolean autoRetry) {
        this.autoRetry = autoRetry;
    }

    public boolean isForceRecord() {
        return forceRecord;
    }

    /** When true the VAD end of speech does not stop the recording; only stopRecord does. */
    public void setForceRecord(boolean forceRecord) {
        this.forceRecord = forceRecord;
    }

    public void setRecordName(String recordName) {
        this.recordName = recordName;
    }

    public String getRecordName() {
        return recordName;
    }

    public String getRecordFilePath() {
        return recordFilePath;
    }

    public void setRecordFilePath(String recordFilePath) {
        this.recordFilePath = recordFilePath;
    }

    public void setErrIds(List<String> errIds) {
        this.errIds = errIds;
    }

    /** errIds that trigger autoRetry, default ["20009"]. */
    public List<String> getErrIds() {
        return errIds;
    }

    @Override
    public String toString() {
        return "RecordSetting{coreType=" + coreType
                + ", refText=" + refText
                + ", refPinyin=" + refPinyin
                + ", coreProvideType=" + coreProvideType
                + ", audioType=" + audioType
                + ", sampleRate=" + sampleRate
                + ", channel=" + channel
                + ", isStream=" + isStream
                + ", audioPath=" + audioPath
                + ", recordFilePath=" + recordFilePath
                + ", recordName=" + recordName
                + ", agegroup=" + agegroup
                + ", qType=" + qType
                + ", scale=" + scale
                + ", precision=" + precision
                + ", slack=" + slack
                + ", duration=" + duration
                + ", durationInterval=" + durationInterval
                + ", seek=" + seek
                + ", ref_length=" + refLength
                + ", vadEnabled=" + vadEnabled
                + ", needSoundIntensity=" + needSoundIntensity
                + ", autoRetry=" + autoRetry
                + ", errIds=" + errIds
                + ", forceRecord=" + forceRecord
                + ", serverTimeout=" + serverTimeout
                + ", newParams=" + (newParams == null ? 0 : newParams.size())
                + "}";
    }

    /** coreType and refText, in either order (a kernel name is taken as coreType). */
    public RecordSetting(String coreType, String refText) {
        if (!CoreTypes.looksLikeCoreType(coreType) && CoreTypes.looksLikeCoreType(refText)) {
            this.coreType = refText;
            this.refText = coreType;
        } else {
            this.coreType = coreType;
            this.refText = refText;
        }
    }

    /** coreType (or refText) and question type. */
    public RecordSetting(String coreType, int qType) {
        this(coreType);
        this.qType = qType;
    }

    /** coreType; a value that is not a kernel name is taken as refText. */
    public RecordSetting(String coreType) {
        if (coreType != null && !CoreTypes.looksLikeCoreType(coreType)) {
            this.refText = coreType;
        } else {
            this.coreType = coreType;
        }
    }

    public RecordSetting() {
    }

    public boolean isNeedSoundIntensity() {
        return needSoundIntensity;
    }

    /** Report sound_intensity through onRecording even without VAD. */
    public RecordSetting setNeedSoundIntensity(boolean needSoundIntensity) {
        this.needSoundIntensity = needSoundIntensity;
        return this;
    }

    public String getAudioType() {
        return audioType;
    }

    /**
     * wav (default) or mp3. Recordings are always WAV data: the default file name is
     * {@code <tokenId>.wav}, an explicit recordName ending in .mp3 is kept as given.
     */
    public RecordSetting setAudioType(String audioType) {
        this.audioType = audioType;
        return this;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    /** Recording is always 16000 Hz; the value is used for headerless PCM files of existsAudioTrans. */
    public RecordSetting setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
        return this;
    }

    public int getChannel() {
        return channel;
    }

    public RecordSetting setChannel(int channel) {
        this.channel = channel;
        return this;
    }

    public String getAudioPath() {
        return audioPath;
    }

    /** Audio file evaluated by existsAudioTrans (wav, mp3, ogg, amr, aac, m4a, flac, or headerless pcm). */
    public RecordSetting setAudioPath(String audioPath) {
        this.audioPath = audioPath;
        return this;
    }

    public boolean isNeedRequestParamsInResult() {
        return needRequestParamsInResult;
    }

    /** Adds {@code params} (app, audio, request) to the result JSON. */
    public RecordSetting setNeedRequestParamsInResult(boolean needRequestParamsInResult) {
        this.needRequestParamsInResult = needRequestParamsInResult;
        return this;
    }

    public String getCoreType() {
        return coreType;
    }

    public void setCoreType(String coreType) {
        this.coreType = coreType;
    }

    public String getRefText() {
        return refText;
    }

    public void setRefText(String refText) {
        this.refText = refText;
    }

    public void setRefAudio(String refAudio) {
        this.refAudio = refAudio;
    }

    public String getRefAudio() {
        return refAudio;
    }

    public boolean isNeedWordScoreInParagraph() {
        return needWordScoreInParagraph;
    }

    public RecordSetting setNeedWordScoreInParagraph(boolean needWordScoreInParagraph) {
        this.needWordScoreInParagraph = needWordScoreInParagraph;
        return this;
    }

    public boolean isNeedAttachAudioUrlInResult() {
        return needAttachAudioUrlInResult;
    }

    public RecordSetting setNeedAttachAudioUrlInResult(boolean needAttachAudioUrlInResult) {
        this.needAttachAudioUrlInResult = needAttachAudioUrlInResult;
        return this;
    }

    public String getDict_type() {
        return dictType;
    }

    public RecordSetting setDict_type(String dictType) {
        this.dictType = dictType;
        return this;
    }

    public boolean isNeedPhonemeOutputInWord() {
        return needPhonemeOutputInWord;
    }

    public RecordSetting setNeedPhonemeOutputInWord(boolean needPhonemeOutputInWord) {
        this.needPhonemeOutputInWord = needPhonemeOutputInWord;
        return this;
    }

    public Double getScale() {
        return scale;
    }

    public RecordSetting setScale(int scale) {
        this.scale = (double) scale;
        return this;
    }

    public RecordSetting setScaleD(double scale) {
        this.scale = scale;
        return this;
    }

    public double getPrecision() {
        return precision;
    }

    public RecordSetting setPrecision(double precision) {
        this.precision = precision;
        return this;
    }

    public double getSlack() {
        return slack;
    }

    public RecordSetting setSlack(double slack) {
        this.slack = slack;
        return this;
    }

    public String getKeywords() {
        return keywords;
    }

    public RecordSetting setKeywords(String keywords) {
        this.keywords = keywords;
        return this;
    }

    public void setqType(int qType) {
        this.qType = qType;
    }

    public int getqType() {
        return qType;
    }

    /** 0 when not set (the field is then not sent), otherwise AgeGroup.AGEGROUP1 to AGEGROUP3. */
    public int getAgegroup() {
        return agegroup;
    }

    public void setAgegroup(int agegroup) {
        this.agegroup = agegroup;
    }

    public String getCustomized_lexicon() {
        return customizedLexicon;
    }

    public RecordSetting setCustomized_lexicon(String customizedLexicon) {
        this.customizedLexicon = customizedLexicon;
        return this;
    }

    public String getMode() {
        return mode;
    }

    public RecordSetting setMode(String mode) {
        this.mode = mode;
        return this;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getProtocol() {
        return protocol;
    }

    public ArrayList<CustomParam> getNewParams() {
        return newParams;
    }

    public RecordSetting setNewParams(ArrayList<CustomParam> newParams) {
        this.newParams = newParams;
        return this;
    }

    public boolean isMuteMusic() {
        return muteMusic;
    }

    /** Requests transient audio focus while recording so background music pauses. */
    public void setMuteMusic(boolean muteMusic) {
        this.muteMusic = muteMusic;
    }

    public String getCoreProvideType() {
        return coreProvideType;
    }

    public void setCoreProvideType(String coreProvideType) {
        this.coreProvideType = coreProvideType;
    }

    public Integer getMax_ogg_delay() {
        return maxOggDelay;
    }

    public void setMax_ogg_delay(Integer maxOggDelay) {
        this.maxOggDelay = maxOggDelay;
    }

    public Integer getRealtime_feedback() {
        return realtimeFeedback;
    }

    /** Kept for compatibility; partial results are not available on the platform compat path. */
    public void setRealtime_feedback(Integer realtimeFeedback) {
        this.realtimeFeedback = realtimeFeedback;
    }

    public String getNegativeReftext() {
        return negativeReftext;
    }

    public void setNegativeReftext(String negativeReftext) {
        this.negativeReftext = negativeReftext;
    }

    public String getDict_dialect() {
        return dictDialect;
    }

    public void setDict_dialect(String dictDialect) {
        this.dictDialect = dictDialect;
    }

    public Integer getDetect_nonscorable() {
        return detectNonscorable;
    }

    public void setDetect_nonscorable(Integer detectNonscorable) {
        this.detectNonscorable = detectNonscorable;
    }

    public String getCompress() {
        return compress;
    }

    public void setCompress(String compress) {
        this.compress = compress;
    }

    public String getCustomized_pron() {
        return customizedPron;
    }

    public void setCustomized_pron(String customizedPron) {
        this.customizedPron = customizedPron;
    }

    public Integer getOutput_rawtext() {
        return outputRawtext;
    }

    public void setOutput_rawtext(Integer outputRawtext) {
        this.outputRawtext = outputRawtext;
    }

    public String getKeypoints() {
        return keypoints;
    }

    public void setKeypoints(String keypoints) {
        this.keypoints = keypoints;
    }

    public Double getKeypoints_weight() {
        return keypointsWeight;
    }

    public void setKeypoints_weight(Double keypointsWeight) {
        this.keypointsWeight = keypointsWeight;
    }

    public String getNegative_keypoints() {
        return negativeKeypoints;
    }

    public void setNegative_keypoints(String negativeKeypoints) {
        this.negativeKeypoints = negativeKeypoints;
    }

    public Integer getVad_detction() {
        return vadDetction;
    }

    public void setVad_detction(Integer vadDetction) {
        this.vadDetction = vadDetction;
    }

    /** Per-evaluation read timeout in seconds; overrides EngineSetting.setServerTimeout. */
    public void setServerTimeout(int serverTimeout) {
        this.serverTimeout = serverTimeout;
    }

    public Integer getServerTimeout() {
        return serverTimeout;
    }

    /** Maximum recording time in ms; the recording stops by itself when it is reached. */
    public void setDuration(int duration) {
        this.duration = duration;
    }

    public Integer getDuration() {
        return duration;
    }

    /** onTick interval in ms, default 100. */
    public void setDurationInterval(int durationInterval) {
        this.durationInterval = durationInterval;
    }

    public Integer getDurationInterval() {
        return durationInterval;
    }

    public String getRefPinyin() {
        return refPinyin;
    }

    public void setRefPinyin(String refPinyin) {
        this.refPinyin = refPinyin;
    }

    public void setPunctuate(Integer punctuate) {
        this.punctuate = punctuate;
    }

    public Integer getPunctuate() {
        return punctuate;
    }

    public void setChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
    }

    public Integer getChunkSize() {
        return chunkSize;
    }

    public boolean isVADEnabled() {
        return vadEnabled;
    }

    /** Local VAD for this recording (also on when EngineSetting.setVADEnabled(true)). */
    public void setVADEnabled(boolean vadEnabled) {
        this.vadEnabled = vadEnabled;
    }

    public String getCustomized_sig_url() {
        return customizedSigUrl;
    }

    public void setCustomized_sig_url(String customizedSigUrl) {
        this.customizedSigUrl = customizedSigUrl;
    }

    public String getCustomized_sig() {
        return customizedSig;
    }

    public void setCustomized_sig(String customizedSig) {
        this.customizedSig = customizedSig;
    }

    public Integer getReadtypeDiagnosis() {
        return readtypeDiagnosis;
    }

    public void setReadtypeDiagnosis(Integer readtypeDiagnosis) {
        this.readtypeDiagnosis = readtypeDiagnosis;
    }

    /** Raw request JSON, sent as form field {@code request}; the platform merges its scalar fields. */
    public void setRequest(String request) {
        this.request = request;
    }

    public String getRequest() {
        return request;
    }

    /** true: no microphone, audio comes from SkEgnManager.feed, stopRecord evaluates. */
    public void setIsStream(boolean isStream) {
        this.isStream = isStream;
    }

    public boolean getIsStream() {
        return isStream;
    }

    public Integer getItn() {
        return itn;
    }

    public void setItn(int itn) {
        this.itn = itn;
    }

    public Integer getAudioSource() {
        return audioSource;
    }

    /** {@code MediaRecorder.AudioSource} value for the microphone, default MIC. */
    public void setAudioSource(int audioSource) {
        this.audioSource = audioSource;
    }

    public boolean getBlendPhonemeEnable() {
        return blendPhonemeEnable;
    }

    public void setBlendPhonemeEnable(boolean blendPhonemeEnable) {
        this.blendPhonemeEnable = blendPhonemeEnable;
    }
}
