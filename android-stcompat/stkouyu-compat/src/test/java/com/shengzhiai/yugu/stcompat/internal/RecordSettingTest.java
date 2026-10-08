package com.shengzhiai.yugu.stcompat.internal;

import com.stkouyu.AudioType;
import com.stkouyu.CoreType;
import com.stkouyu.EngineType;
import com.stkouyu.QType;
import com.stkouyu.setting.RecordSetting;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RecordSettingTest {
    @Test
    public void constructorsAcceptEitherOrder() {
        RecordSetting a = new RecordSetting(CoreType.CN_SENT_EVAL, "你好");
        assertEquals("sent.eval.cn", a.getCoreType());
        assertEquals("你好", a.getRefText());
        RecordSetting b = new RecordSetting("你好", CoreType.CN_SENT_EVAL);
        assertEquals("sent.eval.cn", b.getCoreType());
        assertEquals("你好", b.getRefText());
        RecordSetting c = new RecordSetting(CoreType.EN_OPEN_EVAL, QType.QTYPE_SITUATIONAL);
        assertEquals("open.eval", c.getCoreType());
        assertEquals(6, c.getqType());
        RecordSetting d = new RecordSetting("hello world");
        assertNull(d.getCoreType());
        assertEquals("hello world", d.getRefText());
        RecordSetting e = new RecordSetting((String) null);
        assertNull(e.getCoreType());
        RecordSetting f = new RecordSetting("abc", "def");
        assertEquals("abc", f.getCoreType());
    }

    @Test
    public void defaults() {
        RecordSetting s = new RecordSetting();
        assertEquals(AudioType.WAV, s.getAudioType());
        assertEquals(16000, s.getSampleRate());
        assertEquals(1, s.getChannel());
        assertEquals(EngineType.ENGINE_CLOUD, s.getCoreProvideType());
        assertEquals(Collections.singletonList("20009"), s.getErrIds());
        assertFalse(s.isAutoRetry());
        assertFalse(s.getIsStream());
        assertNull(s.getDuration());
        assertNull(s.getScale());
        assertEquals(0, s.getAgegroup());
        assertTrue(s.toString().startsWith("RecordSetting{coreType=null"));
    }

    @Test
    public void fluentSettersReturnThis() {
        RecordSetting s = new RecordSetting();
        assertSame(s, s.setNeedSoundIntensity(true));
        assertSame(s, s.setAudioType("mp3"));
        assertSame(s, s.setSampleRate(8000));
        assertSame(s, s.setChannel(2));
        assertSame(s, s.setAudioPath("/a"));
        assertSame(s, s.setNeedRequestParamsInResult(true));
        assertSame(s, s.setNeedWordScoreInParagraph(true));
        assertSame(s, s.setNeedAttachAudioUrlInResult(true));
        assertSame(s, s.setDict_type("CMU"));
        assertSame(s, s.setNeedPhonemeOutputInWord(true));
        assertSame(s, s.setScale(10));
        assertSame(s, s.setScaleD(5.5));
        assertSame(s, s.setPrecision(0.1));
        assertSame(s, s.setSlack(0.3));
        assertSame(s, s.setKeywords("k"));
        assertSame(s, s.setCustomized_lexicon("l"));
        assertSame(s, s.setMode("home"));
        assertSame(s, s.setNewParams(null));
        assertTrue(s.isNeedSoundIntensity());
        assertEquals("mp3", s.getAudioType());
        assertEquals(8000, s.getSampleRate());
        assertEquals(2, s.getChannel());
        assertEquals("/a", s.getAudioPath());
        assertTrue(s.isNeedRequestParamsInResult());
        assertTrue(s.isNeedWordScoreInParagraph());
        assertTrue(s.isNeedAttachAudioUrlInResult());
        assertEquals("CMU", s.getDict_type());
        assertTrue(s.isNeedPhonemeOutputInWord());
        assertEquals(5.5, s.getScale(), 0);
        assertEquals(0.1, s.getPrecision(), 0);
        assertEquals(0.3, s.getSlack(), 0);
        assertEquals("k", s.getKeywords());
        assertEquals("l", s.getCustomized_lexicon());
        assertEquals("home", s.getMode());
        assertNull(s.getNewParams());
    }

    @Test
    public void plainAccessors() {
        RecordSetting s = new RecordSetting();
        s.setSeek(10);
        s.setRef_length(5);
        s.setAutoRetry(true);
        s.setForceRecord(true);
        s.setRecordName("n.wav");
        s.setRecordFilePath("/d");
        s.setCoreType("word.eval");
        s.setRefText("apple");
        s.setRefAudio("ra");
        s.setqType(3);
        s.setAgegroup(1);
        s.setProtocol("http");
        s.setMuteMusic(true);
        s.setCoreProvideType("native");
        s.setMax_ogg_delay(48000);
        s.setRealtime_feedback(1);
        s.setNegativeReftext("neg");
        s.setDict_dialect("en_br");
        s.setDetect_nonscorable(1);
        s.setCompress("speex");
        s.setCustomized_pron("p");
        s.setOutput_rawtext(1);
        s.setKeypoints("kp");
        s.setKeypoints_weight(0.5);
        s.setNegative_keypoints("nk");
        s.setVad_detction(1);
        s.setServerTimeout(9);
        s.setDuration(3000);
        s.setDurationInterval(50);
        s.setRefPinyin("py");
        s.setPunctuate(1);
        s.setChunkSize(2);
        s.setVADEnabled(true);
        s.setCustomized_sig_url("su");
        s.setCustomized_sig("sg");
        s.setReadtypeDiagnosis(1);
        s.setRequest("{}");
        s.setIsStream(true);
        s.setItn(1);
        s.setAudioSource(6);
        s.setBlendPhonemeEnable(true);
        assertEquals(Integer.valueOf(10), s.getSeek());
        assertEquals(Integer.valueOf(5), s.getRef_length());
        assertTrue(s.isAutoRetry());
        assertTrue(s.isForceRecord());
        assertEquals("n.wav", s.getRecordName());
        assertEquals("/d", s.getRecordFilePath());
        assertEquals("word.eval", s.getCoreType());
        assertEquals("apple", s.getRefText());
        assertEquals("ra", s.getRefAudio());
        assertEquals(3, s.getqType());
        assertEquals(1, s.getAgegroup());
        assertEquals("http", s.getProtocol());
        assertTrue(s.isMuteMusic());
        assertEquals("native", s.getCoreProvideType());
        assertEquals(Integer.valueOf(48000), s.getMax_ogg_delay());
        assertEquals(Integer.valueOf(1), s.getRealtime_feedback());
        assertEquals("neg", s.getNegativeReftext());
        assertEquals("en_br", s.getDict_dialect());
        assertEquals(Integer.valueOf(1), s.getDetect_nonscorable());
        assertEquals("speex", s.getCompress());
        assertEquals("p", s.getCustomized_pron());
        assertEquals(Integer.valueOf(1), s.getOutput_rawtext());
        assertEquals("kp", s.getKeypoints());
        assertEquals(0.5, s.getKeypoints_weight(), 0);
        assertEquals("nk", s.getNegative_keypoints());
        assertEquals(Integer.valueOf(1), s.getVad_detction());
        assertEquals(Integer.valueOf(9), s.getServerTimeout());
        assertEquals(Integer.valueOf(3000), s.getDuration());
        assertEquals(Integer.valueOf(50), s.getDurationInterval());
        assertEquals("py", s.getRefPinyin());
        assertEquals(Integer.valueOf(1), s.getPunctuate());
        assertEquals(Integer.valueOf(2), s.getChunkSize());
        assertTrue(s.isVADEnabled());
        assertEquals("su", s.getCustomized_sig_url());
        assertEquals("sg", s.getCustomized_sig());
        assertEquals(Integer.valueOf(1), s.getReadtypeDiagnosis());
        assertEquals("{}", s.getRequest());
        assertTrue(s.getIsStream());
        assertEquals(Integer.valueOf(1), s.getItn());
        assertEquals(Integer.valueOf(6), s.getAudioSource());
        assertTrue(s.getBlendPhonemeEnable());
        s.setErrIds(null);
        assertNull(s.getErrIds());
        assertTrue(s.toString().contains("coreType=word.eval"));
    }
}
