package com.shengzhiai.yugu.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.errors.ProtocolViolationException;
import com.shengzhiai.yugu.errors.WarningCode;
import com.shengzhiai.yugu.errors.YuguException;
import com.shengzhiai.yugu.model.ConnectedScores;
import com.shengzhiai.yugu.model.Dimensions;
import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.OpenScores;
import com.shengzhiai.yugu.model.SentenceScore;
import com.shengzhiai.yugu.model.TtsResult;
import com.shengzhiai.yugu.model.Warning;
import com.shengzhiai.yugu.model.WordScore;
import com.shengzhiai.yugu.testing.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Result parsing for every evaluation mode, from real platform responses (spec/fixtures/platform). */
class ResultParserTest {

    private static EvalResult parse(String fixture) {
        return ResultParser.parseEvaluation(Fixtures.json("fixtures/platform/" + fixture));
    }

    private static void assertCommon(EvalResult r, String recordId, String coreType, String language) {
        assertEquals(recordId, r.getRecordId());
        assertEquals(coreType, r.getCoreType());
        assertEquals(language, r.getLanguage());
        assertTrue(r.isFinal());
        assertNotNull(r.getRaw());
        assertNotNull(r.getResultNode());
        assertFalse(r.isReplayed());
    }

    // ------------------------------------------------------------------ native modes

    @Test
    void nativeSentenceZh() {
        EvalResult r = parse("native_evaluate_sentence_zh.json");
        assertCommon(r, "eval_3fb45f4c8e71", "sentence", "zh");
        Dimensions d = r.getDims();
        assertEquals(93.7, r.getOverall());
        assertEquals(100.0, d.getPronunciation());
        assertEquals(100.0, d.getAccuracy());
        assertEquals(83.0, d.getTone());
        assertEquals(96.0, d.getFluency());
        assertEquals(78.0, d.getRhythm());
        assertEquals(100.0, d.getIntegrity());
        assertEquals(225.0, d.getSpeed());
        assertEquals(100.0, d.getEmotion());
        assertEquals(78.0, d.getReadingSkill());
        assertEquals("fall", d.getRearTone());
        assertEquals(1.921, d.getDurationSeconds());
        assertEquals(6, r.getWords().size());
        WordScore w = r.getWords().get(0);
        assertEquals("今", w.getWord());
        assertEquals("jin", w.getPinyin());
        assertEquals("jin1", w.getRawPinyin());
        assertEquals("jīn", w.getSymbolPinyin());
        assertEquals("tone1", w.getTone());
        assertEquals(0, w.getCharType());
        assertEquals(78.0, w.getScores().getOverall());
        assertEquals(78.0, w.getScores().getPronunciation());
        assertEquals(100.0, w.getScores().getTone());
        assertEquals(78.0, w.getScores().getOverallPron());
        assertEquals(0.0, w.getScores().getProminence());
        assertEquals(15, w.getSpan().getStart());
        assertEquals(31, w.getSpan().getEnd());
        assertEquals(0, w.getPause().getType());
        assertEquals(2, w.getPhonemes().size());
        assertEquals("J", w.getPhonemes().get(0).getPhoneme());
        assertEquals("j", w.getPhonemes().get(0).getPhone());
        assertEquals(78.0, w.getPhonemes().get(0).getPronunciation());
        assertEquals(1, w.getPhonemes().get(1).getCategory());
        assertEquals("1", w.getPhonemes().get(1).getToneIndex());
        assertEquals(15, w.getPhonemes().get(0).getSpan().getStart());
        assertEquals("correct", w.getReadStatus());
        assertEquals(1, r.getSentences().size());
        SentenceScore s = r.getSentences().get(0);
        assertEquals("今天天气很好", s.getSentence());
        assertEquals(0, s.getIndex());
        assertEquals(94.0, s.getOverall());
        assertEquals(100.0, s.getScores().getPronunciation());
        assertEquals(96.0, s.getScores().getFluency());
        assertEquals(100.0, s.getScores().getIntegrity());
        assertEquals(6, s.getDetails().size());
        assertEquals("今 天 天 气 很 好", r.getAsrText().getText());
        assertEquals(6, r.getAsrText().getAlignment().size());
        assertEquals("今", r.getAsrText().getAlignment().get(0).getCharacter());
        assertEquals("correct", r.getAsrText().getAlignment().get(0).getReadStatus());
        assertEquals("jin1", r.getAsrText().getAlignment().get(0).getAsrPinyin());
        assertEquals(1, r.getAsrText().getAlignment().get(0).getAsrTone());
        assertEquals("j", r.getAsrText().getAlignment().get(0).getAsrInitial());
        assertEquals("in", r.getAsrText().getAlignment().get(0).getAsrFinal());
        assertEquals(100.0, r.getAsrText().getAlignment().get(0).getGopScore());
        assertEquals(18, r.getAsrText().getAlignment().get(0).getStartTime());
        assertEquals(22, r.getAsrText().getAlignment().get(0).getEndTime());
        assertEquals(0, r.getAsrText().getAlignment().get(0).getIndex());
        assertNotNull(r.getAsrText().getRaw());
        assertNotNull(r.getReport());
        assertEquals(93.7, r.getReport().get("scores").get("compositeScore").asDouble());
        assertEquals(93.7, r.getYuguScores().get("overall").asDouble());
        assertEquals(93.7, r.getCompositeReport().get("compositeScore").asDouble());
        assertTrue(r.getWarnings().isEmpty());
        assertNull(r.getConnected());
        assertNull(r.getOpen());
    }

    @Test
    void nativeWordEn() {
        EvalResult r = parse("native_word_en.json");
        assertCommon(r, "eval_90e628af274b", "word", "en");
        assertEquals(65.0, r.getOverall());
        assertEquals(73.73, r.getDims().getPronunciation());
        assertEquals(73.73, r.getDims().getAccuracy());
        assertEquals(77.28, r.getDims().getFluency());
        assertEquals(38.64, r.getDims().getIntegrity());
        assertEquals(79.0, r.getDims().getRhythm());
        assertEquals(0.0, r.getDims().getTone());
        assertEquals("flat", r.getDims().getRearTone());
        assertEquals(29.0, r.getDims().getSpeed());
        assertEquals(60.74, r.getDims().getReadingSkill());
        assertNull(r.getDims().getEmotion());
        assertEquals(1, r.getWords().size());
        WordScore w = r.getWords().get(0);
        assertEquals("apple", w.getWord());
        assertEquals(1, w.getCharType());
        assertEquals(99.0, w.getScores().getOverall());
        assertEquals(2, w.getScores().getStress().size());
        assertEquals("AE", w.getScores().getStress().get(0).getPhonetic());
        assertEquals(1, w.getScores().getStress().get(0).getRefStress());
        assertEquals(1, w.getScores().getStress().get(0).getStress());
        assertEquals(100.0, w.getScores().getStress().get(0).getOverall());
        assertEquals(4, w.getPhonemes().size());
        assertEquals(5, w.getPhonics().size());
        assertEquals("a", w.getPhonics().get(0).getSpell());
        assertEquals(List.of("AE"), w.getPhonics().get(0).getPhoneme());
        assertEquals(100.0, w.getPhonics().get(0).getOverall());
        assertEquals(2, w.getSyllables().size());
        assertEquals("ap", w.getSyllables().get(0).getGrapheme());
        assertEquals("aep", w.getSyllables().get(0).getSyllable());
        assertEquals(100.0, w.getSyllables().get(0).getAccuracy());
        assertTrue(r.getSentences().isEmpty());
        assertEquals("i want to eat an apple", r.getAsrText().getText());
        assertTrue(r.getAsrText().getRaw().get("overreadDetected").asBoolean());
        assertNull(r.getStandardAudio(), "empty standardAudio object is treated as absent");
    }

    @Test
    void nativeSentenceEn() {
        EvalResult r = parse("native_sentence_en.json");
        assertCommon(r, "eval_1d7dacfebd48", "sentence", "en");
        assertEquals(93.9, r.getOverall());
        assertEquals(91.08, r.getDims().getPronunciation());
        assertEquals(97.0, r.getDims().getFluency());
        assertEquals(100.0, r.getDims().getIntegrity());
        assertEquals(89.0, r.getDims().getRhythm());
        assertEquals(130.0, r.getDims().getSpeed());
        assertEquals(9, r.getWords().size());
        assertEquals("The", r.getWords().get(0).getWord());
        assertEquals(80.0, r.getWords().get(0).getScores().getOverall());
        SentenceScore s = r.getSentences().get(0);
        assertEquals("The quick brown fox jumps over the lazy dog.", s.getSentence());
        assertEquals(94.0, s.getOverall());
        assertEquals(91.0, s.getScores().getPronunciation());
        assertEquals(9, s.getDetails().size());
        assertEquals("quick", s.getDetails().get(1).getWord());
        assertEquals("The quick brown fox jumps over the lazy dog.", r.getAsrText().getText());
        assertFalse(r.getAsrText().getAlignment().isEmpty());
    }

    @Test
    void nativePassageZhWithWordScoresPerSentence() {
        EvalResult r = parse("native_passage_zh.json");
        assertCommon(r, "eval_1302c19b92b5", "passage", "zh");
        assertEquals(89.1, r.getOverall());
        assertEquals(100.0, r.getDims().getPronunciation());
        assertEquals(84.0, r.getDims().getFluency());
        assertEquals(87.0, r.getDims().getTone());
        assertEquals(69.0, r.getDims().getRhythm());
        assertEquals("rise", r.getDims().getRearTone());
        assertEquals(257.0, r.getDims().getSpeed());
        assertEquals(15, r.getWords().size());
        assertEquals(2, r.getSentences().size());
        SentenceScore s0 = r.getSentences().get(0);
        assertEquals("今天天气很好。", s0.getSentence());
        assertEquals(81.0, s0.getOverall());
        assertEquals(19, s0.getSpan().getStart());
        assertEquals(163, s0.getSpan().getEnd());
        assertEquals(6, s0.getDetails().size());
        SentenceScore s1 = r.getSentences().get(1);
        assertEquals(1, s1.getIndex());
        assertEquals(85.0, s1.getOverall());
        assertEquals(9, s1.getDetails().size());
        assertNull(r.getAsrText(), "empty asrText object is treated as absent");
    }

    @Test
    void nativeConnectedEnHasNoOverallButConnectedOverall() {
        EvalResult r = parse("native_connected_en.json");
        assertCommon(r, "conn_91c5cbc879dc", "connected", "en");
        assertEquals(60.0, r.getOverall(), "overall falls back to connected_overall");
        assertEquals(44.0, r.getDims().getRhythm());
        ConnectedScores c = r.getConnected();
        assertNotNull(c);
        assertEquals(60.0, c.getConnectedOverall());
        assertEquals(40.0, c.getLinking());
        assertEquals(44.0, c.getRhythm());
        assertEquals(0.0, c.getElision());
        assertEquals(95.0, c.getReduction());
        assertEquals(4, c.getBoundaryCount());
        assertEquals(4, c.getBoundaries().size());
        ConnectedScores.Boundary b = c.getBoundaries().get(0);
        assertEquals(List.of("want", "to"), b.getBetween());
        assertEquals(List.of("elision", "reduction"), b.getTags());
        assertEquals(0.82, b.getRealized());
        assertEquals(0.818, b.getContinuity());
        assertEquals(40.0, b.getGapMs());
        assertEquals(770.0, b.getStartMs());
        assertEquals(1070.0, b.getEndMs());
        assertTrue(b.getCovered());
        assertEquals(List.of("linking_VV"), c.getBoundaries().get(1).getTags());
        assertEquals(0.704, c.getMetrics().get("linking_rate").asDouble());
        assertEquals(1.0, c.getCoverage().get("ratio").asDouble());
        assertTrue(c.getReport().get("summary").asText().contains("linking"));
        assertTrue(r.getWords().isEmpty());
        assertTrue(r.getSentences().isEmpty());
        assertNull(r.getOpen());
    }

    @Test
    void nativeOpenZhExposesSubScores() {
        EvalResult r = parse("native_open_zh.json");
        assertCommon(r, "open_7d6567a9860d", "open", "zh");
        assertEquals(92.0, r.getOverall());
        assertEquals(14.22, r.getDims().getDurationSeconds());
        OpenScores o = r.getOpen();
        assertNotNull(o);
        assertEquals(92.0, o.getOverall());
        assertEquals("free", o.getTaskType());
        assertEquals("zh", o.getLanguage());
        assertEquals(14.22, o.getDurationSeconds());
        assertTrue(o.getHasSpeech());
        assertTrue(o.getTranscript().startsWith("大家好"));
        assertEquals(98.0, o.getContent().getOverall());
        assertEquals(100.0, o.getContent().getRelevance());
        assertEquals(95.0, o.getContent().getCoherence());
        assertEquals(98.0, o.getContent().getTaskAchievement());
        assertEquals(90.0, o.getLanguageUse().getOverall());
        assertEquals(96.0, o.getLanguageUse().getGrammar());
        assertEquals(85.0, o.getLanguageUse().getVocabulary());
        assertEquals(89.0, o.getDelivery().getOverall());
        assertEquals(100.0, o.getDelivery().getFluency());
        assertEquals(72.0, o.getDelivery().getPronunciation());
        assertEquals(3.897, o.getDelivery().getSpeechRate());
        assertEquals("233.8 字/分", o.getDelivery().getSpeechRateLabel());
        assertEquals(0, o.getDelivery().getPauseCount());
        assertEquals(0.0, o.getDelivery().getLongestPauseSeconds());
        assertEquals(13.0, o.getDelivery().getVoicedSeconds());
        assertEquals("内容完整，紧扣主题，条理清晰流畅。", o.getFeedback().getStrengths());
        assertTrue(o.getFeedback().getWeaknesses().contains("词汇"));
        assertEquals(2, o.getFeedback().getSuggestions().size());
        assertFalse(o.getOpenTaskAudit().getPromptEcho());
        assertFalse(o.getOpenTaskAudit().getLowContent());
        assertFalse(o.getOpenTaskAudit().getOffTopic());
        assertFalse(o.getOpenTaskAudit().getLowYield());
        assertFalse(o.getOpenTaskAudit().getCapApplied());
        assertEquals(53, o.getOpenTaskAudit().getAnswerUnits());
        assertEquals(4.66, o.getAudioQuality().getMos());
        assertEquals(91.0, o.getAudioQuality().getQuality());
        assertEquals(0.35, o.getAggregation().get("weights").get("content").asDouble());
        assertEquals("v20260705a", o.getRubricVersion());
        assertTrue(o.getScoringSource().contains("llm"));
        assertNull(r.getAsrText());
        assertNull(r.getStandardAudio());
        assertNull(r.getConnected());
        assertTrue(r.getReport().get("rubricBackfilled").asBoolean());
    }

    @Test
    void nativeAlphaEn() {
        EvalResult r = parse("native_alpha_en.json");
        assertCommon(r, "eval_539eda643075", "alpha", "en");
        assertEquals(47.0, r.getOverall());
        assertEquals(37.51, r.getDims().getPronunciation());
        assertEquals(78.22, r.getDims().getFluency());
        assertEquals(26.34, r.getDims().getIntegrity());
        assertEquals(3, r.getWords().size());
        assertEquals("A", r.getWords().get(0).getWord());
        assertEquals(49.0, r.getWords().get(0).getScores().getOverall());
        assertEquals("C", r.getWords().get(2).getWord());
        assertEquals("A, B, G.", r.getAsrText().getText());
        assertTrue(r.getAsrText().getRaw().get("substitutionDetected").asBoolean());
    }

    @Test
    void nativePinyinZh() {
        EvalResult r = parse("native_pinyin_zh.json");
        // the platform scores pinyin questions with the sentence engine
        assertCommon(r, "eval_b9e861a07049", "sentence", "zh");
        assertEquals(88.9, r.getOverall());
        assertEquals(83.0, r.getDims().getTone());
        assertEquals(76.0, r.getDims().getEmotion());
        assertEquals(6, r.getWords().size());
        assertEquals("jin1", r.getWords().get(0).getRawPinyin());
        assertEquals("tone1", r.getWords().get(0).getTone());
        assertEquals(1, r.getSentences().size());
        assertEquals(89.0, r.getSentences().get(0).getOverall());
    }

    // ------------------------------------------------------------------ compat modes

    @Test
    void compatWordEval() {
        EvalResult r = parse("compat_word.eval.json");
        assertEquals("eval_77c081e19541", r.getRecordId());
        assertEquals("word.eval", r.getCoreType());
        assertEquals(65.0, r.getOverall());
        assertEquals(38.64, r.getDims().getIntegrity());
        assertEquals("apple", r.getWords().get(0).getWord());
        assertEquals(2, r.getWords().get(0).getSyllables().size());
        assertNull(r.getReport());
        assertNull(r.getAsrText());
    }

    @Test
    void compatSentEval() {
        EvalResult r = parse("compat_sent.eval.json");
        assertEquals("sent.eval", r.getCoreType());
        assertEquals(93.9, r.getOverall());
        assertEquals(9, r.getWords().size());
        assertEquals(1, r.getSentences().size());
        assertEquals(9, r.getSentences().get(0).getDetails().size());
    }

    @Test
    void compatSentEvalCn() {
        for (String f : new String[]{"compat_sent.eval.cn.json", "compat_sent_eval_cn.json"}) {
            EvalResult r = parse(f);
            assertEquals("sent.eval.cn", r.getCoreType());
            assertEquals(94.6, r.getOverall());
            assertEquals(83.0, r.getDims().getTone());
            assertEquals(6, r.getWords().size());
            assertEquals("jīn", r.getWords().get(0).getSymbolPinyin());
            assertEquals(1, r.getSentences().size());
        }
    }

    @Test
    void compatParaEvalCn() {
        EvalResult r = parse("compat_para.eval.cn.json");
        assertEquals("para.eval.cn", r.getCoreType());
        assertEquals(89.8, r.getOverall());
        assertEquals(86.0, r.getDims().getTone());
        assertTrue(r.getWords().isEmpty());
        assertEquals(2, r.getSentences().size());
        assertEquals(81.0, r.getSentences().get(0).getOverall());
        assertEquals("我们一起去公园散步。", r.getSentences().get(1).getSentence());
        assertTrue(r.getSentences().get(1).getDetails().isEmpty());
        assertEquals(203, r.getSentences().get(1).getSpan().getStart());
    }

    // ------------------------------------------------------------------ WebSocket frames

    @Test
    void nativeWsFrames() {
        JsonNode frames = Fixtures.json("fixtures/platform/ws_native_sentence_frames.json");
        assertEquals("connected", frames.get(0).get("frame").get("event").asText());
        assertEquals("started", frames.get(1).get("frame").get("event").asText());
        JsonNode last = frames.get(frames.size() - 1).get("frame");
        assertEquals("result", last.get("event").asText());
        EvalResult r = ResultParser.parseEvaluation(last);
        assertEquals("eval_3fb45f4c8e71", r.getRecordId());
        assertEquals(93.7, r.getOverall());
        assertEquals(6, r.getWords().size());
        assertNotNull(r.getAsrText());
    }

    @Test
    void compatWsFrames() {
        JsonNode frames = Fixtures.json("fixtures/platform/ws_compat_sent_eval_cn_frames.json");
        JsonNode last = frames.get(frames.size() - 1).get("frame");
        assertNull(last.get("event"), "compat final frame has no event field");
        EvalResult r = ResultParser.parseEvaluation(last);
        assertEquals("eval_a5ddd0c68840", r.getRecordId());
        assertEquals(94.6, r.getOverall());
        assertEquals(1, r.getEof());
    }

    // ------------------------------------------------------------------ warnings and errors

    @Test
    void warningsInEveryShape() {
        JsonNode root = Json.read("{\"recordId\":\"r\",\"eof\":1,\"result\":{\"overall\":10,"
                + "\"warning\":[{\"code\":1002,\"message\":\"Audio volume too low!\"},1001]},"
                + "\"warnings\":[1001,\"1003\",{\"code\":1009},\"text only\"],\"replayed\":true}");
        EvalResult r = ResultParser.parseEvaluation(root);
        assertEquals(List.of(1002, 1001, 1003, 1009, 0), r.getWarningCodes());
        assertTrue(r.hasWarning(WarningCode.NO_VALID_AUDIO));
        assertFalse(r.hasWarning(WarningCode.AUDIO_NOISY));
        Warning w = r.getWarnings().get(1);
        assertEquals("No valid audio detected!", w.getMessage());
        assertEquals(Warning.Source.SERVER, w.getSource());
        assertSame(WarningCode.NO_VALID_AUDIO, w.getWarningCode());
        assertEquals("text only", r.getWarnings().get(4).getMessage());
        assertTrue(r.isReplayed(), "WS frames carry replayed=true");
        assertEquals(new Warning(1002, "Audio volume too low!", Warning.Source.SERVER), r.getWarnings().get(0));
        assertTrue(r.toString().contains("warnings=[1002, 1001, 1003, 1009, 0]"));
    }

    @Test
    void protocolErrors() {
        assertThrows(ProtocolViolationException.class, () -> ResultParser.parseEvaluation(Json.read("[1]")));
        YuguException e = assertThrows(ProtocolViolationException.class,
                () -> ResultParser.parseEvaluation(Json.read("{\"code\":0,\"recordId\":\"x\"}")));
        assertEquals(90005, e.getCode());
        assertNotNull(e.getRawBody());
        assertThrows(ProtocolViolationException.class,
                () -> ResultParser.parseEvaluation(Json.read("{\"result\":{\"overall\":\"abc\"}}")));
        assertThrows(ProtocolViolationException.class, () -> Json.read("not json"));
        assertThrows(ProtocolViolationException.class, () -> Json.read(""));
        assertThrows(ProtocolViolationException.class, () -> ResultParser.parseEvaluation(null));
    }

    @Test
    void ttsData() {
        JsonNode root = Fixtures.json("fixtures/platform/tts_generate.json");
        TtsResult t = ResultParser.parseTts(root.get("data"), "https://open.shengzhiai.com");
        assertEquals("https://ygyx.dragonai.tech/tts-local/0c02b59749694e518fa8c6c6a78c2377.mp3", t.getAudioUrl());
        assertEquals(t.getAudioUrl(), t.getAbsoluteUrl());
        assertEquals("1.348", t.getDuration());
        assertEquals("mp3", t.getFormat());
        assertTrue(t.getWarnings().isEmpty());
        TtsResult rel = ResultParser.parseTts(Json.read("{\"audioUrl\":\"/audio/a.mp3\"}"), "http://h");
        assertEquals("http://h/tts/audio/a.mp3", rel.getAbsoluteUrl());
        TtsResult other = ResultParser.parseTts(Json.read("{\"audioUrl\":\"x/a.mp3\"}"), "http://h");
        assertEquals("http://h/x/a.mp3", other.getAbsoluteUrl());
        TtsResult none = ResultParser.parseTts(Json.read("{}"), "http://h");
        assertNull(none.getAbsoluteUrl());
    }
}
