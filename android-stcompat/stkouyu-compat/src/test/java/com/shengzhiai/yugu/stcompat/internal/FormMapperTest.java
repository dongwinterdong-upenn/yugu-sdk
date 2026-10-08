package com.shengzhiai.yugu.stcompat.internal;

import com.stkouyu.AgeGroup;
import com.stkouyu.CoreType;
import com.stkouyu.CustomParam;
import com.stkouyu.Mode;
import com.stkouyu.QType;
import com.stkouyu.setting.RecordSetting;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** DESIGN 6.1: every RecordSetting field, sent or kept local. */
public class FormMapperTest {

    private static RecordSetting everything() {
        RecordSetting s = new RecordSetting(CoreType.EN_PARA_EVAL, "How are you");
        s.setRefPinyin("ni3 hao3");
        s.setAgegroup(AgeGroup.AGEGROUP2);
        s.setScale(100);
        s.setPrecision(0.5);
        s.setSlack(-0.25);
        s.setNeedWordScoreInParagraph(true);
        s.setNeedPhonemeOutputInWord(true);
        s.setNeedAttachAudioUrlInResult(true);
        s.setDict_type("KK");
        s.setDict_dialect("en_us");
        s.setCustomized_lexicon("{\"apple\":[\"ae p ax l\"]}");
        s.setCustomized_pron("{\"x\":1}");
        s.setReadtypeDiagnosis(1);
        s.setOutput_rawtext(1);
        s.setPunctuate(1);
        s.setItn(1);
        s.setDetect_nonscorable(1);
        s.setVad_detction(1);
        s.setKeywords("apple|pear");
        s.setKeypoints("[\"a\"]");
        s.setKeypoints_weight(0.6);
        s.setNegative_keypoints("[\"b\"]");
        s.setNegativeReftext("Who are you");
        s.setMode(Mode.SCHOOL);
        s.setqType(QType.QTYPE_SITUATIONAL);
        ArrayList<CustomParam> extra = new ArrayList<>();
        extra.add(new CustomParam("language", "en-US"));
        extra.add(new CustomParam("toneWeight", 0.2));
        extra.add(new CustomParam("flag", true));
        extra.add(new CustomParam("n", 3));
        extra.add(new CustomParam("big", new BigDecimal("1.500")));
        extra.add(new CustomParam(" ", "ignored"));
        extra.add(new CustomParam(null, "ignored"));
        extra.add(new CustomParam("nullValue", null));
        extra.add(null);
        s.setNewParams(extra);
        s.setRequest("{\"slack\":0.1}");
        // kept local, never sent
        s.setRealtime_feedback(1);
        s.setSeek(50);
        s.setRef_length(20);
        s.setChunkSize(4);
        s.setMax_ogg_delay(9600);
        s.setCustomized_sig("sig");
        s.setCustomized_sig_url("https://sig.example.com");
        s.setRefAudio("https://a.example.com/a.mp3");
        s.setCompress("raw");
        s.setProtocol("http");
        s.setBlendPhonemeEnable(true);
        s.setCoreProvideType("cloud");
        s.setAudioType("wav");
        s.setAudioPath("/tmp/x.wav");
        s.setIsStream(true);
        s.setAudioSource(1);
        s.setNeedSoundIntensity(true);
        s.setNeedRequestParamsInResult(true);
        s.setAutoRetry(true);
        s.setErrIds(Arrays.asList("20009", "50000"));
        s.setForceRecord(true);
        s.setMuteMusic(true);
        s.setServerTimeout(30);
        s.setDuration(5000);
        s.setDurationInterval(200);
        s.setVADEnabled(true);
        s.setRecordFilePath("/tmp");
        s.setRecordName("r.wav");
        s.setSampleRate(16000);
        s.setChannel(1);
        return s;
    }

    @Test
    public void everyFieldMapsAsSpecified() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("refText", "How are you");
        expected.put("refPinyin", "ni3 hao3");
        expected.put("agegroup", "2");
        expected.put("scale", "100");
        expected.put("precision", "0.5");
        expected.put("slack", "-0.25");
        expected.put("paragraph_need_word_score", "1");
        expected.put("phoneme_output", "1");
        expected.put("attachAudioUrl", "1");
        expected.put("dict_type", "KK");
        expected.put("dict_dialect", "en_us");
        expected.put("customized_lexicon", "{\"apple\":[\"ae p ax l\"]}");
        expected.put("customized_pron", "{\"x\":1}");
        expected.put("readtype_diagnosis", "1");
        expected.put("output_rawtext", "1");
        expected.put("punctuate", "1");
        expected.put("itn", "1");
        expected.put("detect_nonscorable", "1");
        expected.put("vad_detction", "1");
        expected.put("keywords", "apple|pear");
        expected.put("keypoints", "[\"a\"]");
        expected.put("keypoints_weight", "0.6");
        expected.put("negative_keypoints", "[\"b\"]");
        expected.put("negativeReftext", "Who are you");
        expected.put("mode", "school");
        expected.put("qType", "6");
        expected.put("language", "en-US");
        expected.put("toneWeight", "0.2");
        expected.put("flag", "true");
        expected.put("n", "3");
        expected.put("big", "1.5");
        expected.put("request", "{\"slack\":0.1}");
        assertEquals(expected, FormMapper.fields(everything()));
    }

    @Test
    public void minimalSettingSendsOnlyRefText() {
        RecordSetting s = new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好");
        Map<String, String> f = FormMapper.fields(s);
        assertEquals(1, f.size());
        assertEquals("今天天气很好", f.get("refText"));
        s.setScaleD(4.5);
        s.setRefPinyin("");
        assertEquals("4.5", FormMapper.fields(s).get("scale"));
        assertFalse(FormMapper.fields(s).containsKey("refPinyin"));
    }

    /** DESIGN 6: paragraph kernels always ask for word details so sentences[].details[] exists. */
    @Test
    public void paragraphKernelsAlwaysSendWordScores() {
        RecordSetting para = new RecordSetting(CoreType.EN_PARA_EVAL, "Hello there. How are you.");
        assertEquals("1", FormMapper.fields(para).get("paragraph_need_word_score"));
        RecordSetting cn = new RecordSetting(CoreType.CN_PARA_EVAL, "今天天气很好。");
        ArrayList<CustomParam> extra = new ArrayList<>();
        extra.add(new CustomParam("paragraph_need_word_score", 0));
        cn.setNewParams(extra);
        assertEquals("1", FormMapper.fields(cn).get("paragraph_need_word_score"));
        RecordSetting sent = new RecordSetting(CoreType.EN_SENT_EVAL, "Hello");
        assertFalse(FormMapper.fields(sent).containsKey("paragraph_need_word_score"));
        sent.setNeedWordScoreInParagraph(true);
        assertEquals("1", FormMapper.fields(sent).get("paragraph_need_word_score"));
        assertTrue(FormMapper.isParagraph(" para.eval.cn"));
        assertFalse(FormMapper.isParagraph(null));
    }

    @Test
    public void newParamsOverrideStandardFields() {
        RecordSetting s = new RecordSetting(CoreType.EN_WORD_EVAL, "apple");
        ArrayList<CustomParam> extra = new ArrayList<>();
        extra.add(new CustomParam("refText", "pear"));
        s.setNewParams(extra);
        assertEquals("pear", FormMapper.fields(s).get("refText"));
    }

    @Test
    public void numbersArePlainDecimals() {
        assertEquals("100", FormMapper.number(100.0));
        assertEquals("0.1", FormMapper.number(0.1));
        assertEquals("-3", FormMapper.number(-3.0));
        assertEquals("0.00001", FormMapper.number(1e-5));
        assertEquals("1000000000000000", FormMapper.number(1e15).replace("E15", ""));
        assertEquals("NaN", FormMapper.number(Double.NaN));
        assertEquals("Infinity", FormMapper.number(Double.POSITIVE_INFINITY));
        assertEquals("2.5", FormMapper.valueOf(2.5f));
        assertEquals("x", FormMapper.valueOf("x"));
    }

    @Test
    public void validation() {
        assertEquals(0, FormMapper.validate(new RecordSetting(CoreType.EN_SENT_EVAL, "hi")));
        assertEquals(60003, FormMapper.validate(new RecordSetting(CoreType.EN_OPEN_EVAL, "hi")));
        assertEquals(60003, FormMapper.validate(new RecordSetting(CoreType.FR_WORD_EVAL, "bonjour")));
        assertEquals(60003, FormMapper.validate(new RecordSetting()));
        assertEquals(60006, FormMapper.validate(new RecordSetting(CoreType.EN_SENT_EVAL, " ")));
        RecordSetting cn = new RecordSetting(CoreType.CN_WORD_EVAL);
        assertEquals(60006, FormMapper.validate(cn));
        cn.setRefPinyin("hai3");
        assertEquals(0, FormMapper.validate(cn));
        RecordSetting en = new RecordSetting(CoreType.EN_WORD_EVAL);
        en.setRefPinyin("x");
        assertEquals(60006, FormMapper.validate(en));
        RecordSetting py = new RecordSetting("pinyin");
        py.setRefPinyin("chong2 qing4");
        assertEquals(0, FormMapper.validate(py));
        assertEquals(0, FormMapper.validate(new RecordSetting(" sent.eval.cn ", "x")));
        for (String ct : CoreTypes.SUPPORTED) {
            assertEquals(ct, 0, FormMapper.validate(new RecordSetting(ct, "x")));
        }
        String[] unsupported = {CoreType.EN_CHOICE_REC, CoreType.EN_OPEN_EVAL, CoreType.EN_ASR_REC, CoreType.EN_ASR_EVAL,
            CoreType.EN_ALIGN_EVAL, CoreType.FR_SENT_EVAL, CoreType.FR_PARA_EVAL, CoreType.JP_WORD_EVAL,
            CoreType.JP_SENT_EVAL, CoreType.JP_PARA_EVAL, CoreType.KR_WORD_EVAL, CoreType.KR_SENT_EVAL, CoreType.KR_PARA_EVAL};
        for (String ct : unsupported) {
            assertEquals(ct, 60003, FormMapper.validate(new RecordSetting(ct, "x")));
        }
    }

    @Test
    public void coreTypeDetection() {
        assertTrue(CoreTypes.looksLikeCoreType("sent.eval.cn"));
        assertTrue(CoreTypes.looksLikeCoreType("pinyin"));
        assertTrue(CoreTypes.looksLikeCoreType("word.eval.de"));
        assertTrue(CoreTypes.looksLikeCoreType("speak.rec"));
        assertFalse(CoreTypes.looksLikeCoreType("hello"));
        assertFalse(CoreTypes.looksLikeCoreType("e.g"));
        assertFalse(CoreTypes.looksLikeCoreType("U.S."));
        assertFalse(CoreTypes.looksLikeCoreType("今天天气很好"));
        assertFalse(CoreTypes.looksLikeCoreType(null));
        assertTrue(CoreTypes.isChinese("para.eval.cn"));
        assertTrue(CoreTypes.isChinese("pinyin"));
        assertFalse(CoreTypes.isChinese("sent.eval"));
        assertFalse(CoreTypes.isChinese(null));
        assertFalse(CoreTypes.isSupported(null));
    }
}
