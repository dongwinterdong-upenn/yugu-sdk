package com.shengzhiai.yugu

import com.shengzhiai.yugu.internal.JObj
import com.shengzhiai.yugu.internal.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Score fields of every evaluation mode, parsed from real platform responses (spec/fixtures/platform). */
class ResultParsingTest {

    private fun parse(name: String, key: String? = "k", replayed: Boolean = false): EvalResult {
        val text = TestEnv.fixtureText("platform/$name")
        return ResultParser.evalResult(JObj.parseOrNull(text)!!, text, key, replayed, emptyList())
    }

    private fun near(expected: Double, actual: Double?) = assertEquals(expected, actual!!, 1e-9)

    @Test
    fun nativeWordEnglish() {
        val r = parse("native_word_en.json")
        assertEquals("word", r.coreType)
        assertEquals("en", r.language)
        near(65.0, r.overall)
        near(73.73, r.dimensions.pronunciation)
        near(73.73, r.dimensions.accuracy)
        near(77.28, r.dimensions.fluency)
        near(38.64, r.dimensions.integrity)
        near(79.0, r.dimensions.rhythm)
        near(0.0, r.dimensions.tone)
        near(60.74, r.dimensions.readingSkill)
        near(29.0, r.speed)
        assertEquals("flat", r.rearTone)
        assertEquals("2.300", r.duration)
        near(2.3, r.durationSeconds)
        assertEquals(1, r.words.size)
        val w = r.words[0]
        assertEquals("apple", w.word)
        near(99.0, w.overall)
        near(99.0, w.pronunciation)
        assertEquals("correct", w.readStatus)
        assertEquals(listOf("ae", "p", "ax", "l"), w.phonemes.map { it.phoneme })
        near(96.0, w.phonemes[3].pronunciation)
        assertEquals(Span(199, 216), w.phonemes[3].span)
        assertTrue(r.sentences.isEmpty())
        assertNull(r.connected)
        assertNull(r.open)
        assertEquals("i want to eat an apple", r.asrText!!.text)
        assertEquals(true, r.asrText!!.raw["overreadDetected"])
        assertTrue(r.report!!.summary!!.isNotEmpty())
        assertEquals(1, r.report!!.suggestions.size)
        near(73.73, r.report!!.dimensionScores["accuracy"])
        assertNull("empty standardAudio object is null", r.standardAudio)
        assertTrue(r.warnings.isEmpty())
        assertTrue(r.isFinal)
        assertEquals("k", r.idempotencyKey)
    }

    @Test
    fun nativeSentenceEnglish() {
        val r = parse("native_sentence_en.json")
        assertEquals("sentence", r.coreType)
        near(93.9, r.overall)
        near(91.08, r.dimensions.pronunciation)
        near(97.0, r.dimensions.fluency)
        near(100.0, r.dimensions.integrity)
        near(89.0, r.dimensions.rhythm)
        near(130.0, r.speed)
        assertEquals(9, r.words.size)
        assertEquals("The", r.words[0].word)
        assertEquals(1, r.sentences.size)
        val s = r.sentences[0]
        assertEquals("The quick brown fox jumps over the lazy dog.", s.text)
        near(94.0, s.overall)
        near(91.0, s.pronunciation)
        near(97.0, s.fluency)
        assertEquals(9, s.words.size)
        assertEquals("The quick brown fox jumps over the lazy dog.", r.asrText!!.text)
        assertTrue(r.asrText!!.alignment.isNotEmpty())
    }

    @Test
    fun nativeSentenceChinese() {
        val r = parse("native_evaluate_sentence_zh.json")
        assertEquals("zh", r.language)
        near(93.7, r.overall)
        near(100.0, r.dimensions.pronunciation)
        near(83.0, r.dimensions.tone)
        near(96.0, r.dimensions.fluency)
        near(100.0, r.dimensions.emotion)
        near(78.0, r.dimensions.readingSkill)
        assertEquals("fall", r.rearTone)
        assertEquals(6, r.words.size)
        val w = r.words[0]
        assertEquals("今", w.word)
        assertEquals("jin", w.pinyin)
        assertEquals("jīn", w.symbolPinyin)
        assertEquals("tone1", w.tone)
        assertNotNull(w.toneScore)
        assertEquals("今 天 天 气 很 好", r.asrText!!.text)
        val a = r.asrText!!.alignment[0]
        assertEquals("今", a.char)
        assertEquals("correct", a.readStatus)
        assertEquals("jin1", a.asrPinyin)
        assertEquals(1, a.asrTone)
        near(100.0, a.gopScore)
        assertEquals(18, a.startTime)
        assertEquals(22, a.endTime)
        assertTrue(r.report!!.summary!!.startsWith("# 朗读评测报告"))
        assertEquals(2, r.report!!.suggestions.size)
    }

    @Test
    fun nativePassageChineseWithSentenceDetails() {
        val r = parse("native_passage_zh.json")
        assertEquals("passage", r.coreType)
        near(89.1, r.overall)
        near(87.0, r.dimensions.tone)
        near(69.0, r.dimensions.rhythm)
        near(257.0, r.speed)
        assertEquals("rise", r.rearTone)
        assertEquals(15, r.words.size)
        assertEquals(2, r.sentences.size)
        val s0 = r.sentences[0]
        assertEquals(0, s0.index)
        assertEquals("今天天气很好。", s0.text)
        near(81.0, s0.overall)
        near(100.0, s0.integrity)
        assertEquals(Span(19, 163), s0.span)
        assertEquals(6, s0.words.size)
        assertEquals("今", s0.words[0].word)
        near(76.0, s0.words[0].overall)
        assertEquals(9, r.sentences[1].words.size)
        near(85.0, r.sentences[1].overall)
        assertNull("empty asrText object is null", r.asrText)
        assertEquals(53.0, r.report!!.dimensionScores["speechRate"]!!, 1e-9)
        assertNull(r.report!!.summary)
    }

    @Test
    fun nativeConnectedUsesConnectedOverall() {
        val r = parse("native_connected_en.json")
        assertEquals("connected", r.coreType)
        assertEquals("conn_91c5cbc879dc", r.recordId)
        assertFalse("connected has no result.overall", r.resultRaw.containsKey("overall"))
        near(60.0, r.overall)
        val c = r.connected!!
        near(60.0, c.overall)
        near(40.0, c.linking)
        near(44.0, c.rhythm)
        near(0.0, c.elision)
        near(95.0, c.reduction)
        assertEquals(4, c.boundaryCount)
        assertEquals(4, c.boundaries.size)
        val b = c.boundaries[0]
        assertEquals(listOf("want", "to"), b.between)
        assertEquals(listOf("elision", "reduction"), b.tags)
        near(0.82, b.realized)
        near(770.0, b.startMs)
        near(1070.0, b.endMs)
        near(40.0, b.gapMs)
        near(0.818, b.continuity)
        assertEquals(listOf("linking_CV"), c.boundaries[3].tags)
        assertTrue(c.summary!!.startsWith("The overall linking is fair"))
        assertEquals(3, c.suggestions.size)
        assertNull(r.open)
        near(44.0, r.dimensions.rhythm)
        assertNull(r.dimensions.pronunciation)
        assertTrue(r.words.isEmpty())
    }

    @Test
    fun nativeOpenTask() {
        val r = parse("native_open_zh.json")
        assertEquals("open", r.coreType)
        near(92.0, r.overall)
        assertEquals("zh", r.language)
        near(14.22, r.durationSeconds)
        val o = r.open!!
        near(92.0, o.overall)
        assertEquals("free", o.taskType)
        assertTrue(o.transcript!!.startsWith("大家好"))
        assertEquals(true, o.hasSpeech)
        near(14.22, o.durationSeconds)
        near(98.0, o.content!!.overall)
        near(100.0, o.content!!.relevance)
        near(95.0, o.content!!.coherence)
        near(98.0, o.content!!.taskAchievement)
        near(90.0, o.languageUse!!.overall)
        near(96.0, o.languageUse!!.grammar)
        near(85.0, o.languageUse!!.vocabulary)
        near(89.0, o.delivery!!.overall)
        near(100.0, o.delivery!!.fluency)
        near(72.0, o.delivery!!.pronunciation)
        near(3.897, o.delivery!!.speechRate)
        assertEquals("233.8 字/分", o.delivery!!.speechRateLabel)
        assertEquals(0, o.delivery!!.pauseCount)
        assertEquals("内容完整，紧扣主题，条理清晰流畅。", o.feedback!!.strengths)
        assertEquals("词汇使用较为基础，缺乏多样性。", o.feedback!!.weaknesses)
        assertEquals(2, o.feedback!!.suggestions.size)
        assertEquals(false, o.audit!!.promptEcho)
        assertEquals(false, o.audit!!.lowContent)
        assertEquals(false, o.audit!!.capApplied)
        assertEquals(53L, o.audit!!.raw["answerUnits"])
        assertEquals("v20260705a", o.rubricVersion)
        assertNull(r.connected)
        assertTrue(r.report!!.dimensionScores.isEmpty())
    }

    @Test
    fun nativeAlphaEnglish() {
        val r = parse("native_alpha_en.json")
        assertEquals("alpha", r.coreType)
        near(47.0, r.overall)
        near(37.51, r.dimensions.pronunciation)
        near(26.34, r.dimensions.integrity)
        near(78.22, r.dimensions.fluency)
        near(57.0, r.dimensions.rhythm)
        assertEquals("A", r.words[0].word)
        assertEquals("A, B, G.", r.asrText!!.text)
        assertEquals(true, r.asrText!!.raw["substitutionDetected"])
        assertTrue(r.asrText!!.alignment.isEmpty())
    }

    @Test
    fun nativePinyinChinese() {
        val r = parse("native_pinyin_zh.json")
        near(88.9, r.overall)
        near(83.0, r.dimensions.tone)
        near(76.0, r.dimensions.emotion)
        assertEquals(6, r.words.size)
        assertEquals(6, r.sentences[0].words.size)
        assertEquals("fall", r.rearTone)
    }

    @Test
    fun compatResponses() {
        val word = parse("compat_word.eval.json")
        assertEquals("word.eval", word.coreType)
        near(65.0, word.overall)
        assertEquals("apple", word.words[0].word)
        val sent = parse("compat_sent.eval.json")
        assertEquals("sent.eval", sent.coreType)
        near(93.9, sent.overall)
        near(91.08, sent.dimensions.accuracy)
        val cn = parse("compat_sent.eval.cn.json")
        assertEquals("sent.eval.cn", cn.coreType)
        near(94.6, cn.overall)
        val cn2 = parse("compat_sent_eval_cn.json")
        near(94.6, cn2.overall)
        assertEquals("eval_a5ddd0c68840", cn2.recordId)
        val para = parse("compat_para.eval.cn.json")
        assertEquals("para.eval.cn", para.coreType)
        near(89.8, para.overall)
        assertEquals(2, para.sentences.size)
        near(85.0, para.sentences[1].overall)
        assertEquals("我们一起去公园散步。", para.sentences[1].text)
        assertTrue(para.sentences[1].words.isEmpty())
        for (r in listOf(word, sent, cn, cn2, para)) {
            assertEquals(1, r.eof)
            assertNull(r.report)
            assertNull(r.asrText)
        }
    }

    @Test
    fun wsFrameFixtures() {
        @Suppress("UNCHECKED_CAST")
        val native = (Json.parse(TestEnv.fixtureText("platform/ws_native_sentence_frames.json")) as List<Map<String, Any?>>)
            .map { JObj(it["frame"] as Map<String, Any?>) }
        assertEquals(listOf("connected", "started", "error", "result"), native.map { it.str("event") })
        val last = native.last()
        val r = ResultParser.evalResult(last, Json.write(last.map), "key", false, emptyList())
        assertEquals("eval_3fb45f4c8e71", r.recordId)
        near(93.7, r.overall)
        val unknownCmd = YuguErrors.fromErrorFrame(Json.write(native[2].map))
        assertEquals("unknown cmd", unknownCmd.message)
        assertFalse(unknownCmd.retryable)

        @Suppress("UNCHECKED_CAST")
        val compat = (Json.parse(TestEnv.fixtureText("platform/ws_compat_sent_eval_cn_frames.json")) as List<Map<String, Any?>>)
            .map { JObj(it["frame"] as Map<String, Any?>) }
        assertEquals(listOf("connected", "started", "started", null), compat.map { it.str("event") })
        val c = ResultParser.evalResult(compat.last(), Json.write(compat.last().map), null, true, emptyList())
        assertEquals(1, c.eof)
        near(94.6, c.overall)
        assertTrue(c.replayed)
        assertNull(c.idempotencyKey)
    }

    @Test
    fun replayFlagFromFrame() {
        val text = """{"event":"result","recordId":"eval_r","eof":1,"result":{"overall":80},"replayed":true}"""
        val r = ResultParser.evalResult(JObj.parseOrNull(text)!!, text, "k", false, emptyList())
        assertTrue(r.replayed)
        assertEquals("eval_r", r.recordId)
        assertTrue(r.toString().contains("replayed=true"))
    }

    @Test
    fun errorFixtures() {
        val sig = JObj.parseOrNull(TestEnv.fixtureText("platform/error_native_bad_signature.json"))!!
        val e = YuguErrors.fromHttp(sig.int("status")!!, sig.str("body"))
        assertTrue(e is AuthException)
        assertEquals(2003, e.code)
        assertEquals(401, e.httpStatus)
        assertEquals("签名验证失败", e.message)
        val pinyin = JObj.parseOrNull(TestEnv.fixtureText("platform/error_compat_pinyin_missing_refpinyin.json"))!!
        val p = YuguErrors.fromHttp(pinyin.int("status")!!, pinyin.str("body"))
        assertTrue(p is InvalidParameterException)
        assertEquals(40001, p.code)
        assertTrue(p.message!!.contains("refPinyin"))
    }

    @Test
    fun missingFieldsAreNull() {
        val text = """{"recordId":"x"}"""
        val r = ResultParser.evalResult(JObj.parseOrNull(text)!!, text, null, false, emptyList())
        assertNull(r.overall)
        assertNull(r.dimensions.fluency)
        assertTrue(r.words.isEmpty())
        assertEquals(1, r.eof)
        assertNull(r.coreType)
        assertNull(r.standardAudio)
        val withAudio = """{"standardAudio":{"url":"/audio/standard/x.wav","format":"wav","duration":"3.50"}}"""
        val sa = ResultParser.evalResult(JObj.parseOrNull(withAudio)!!, withAudio, null, false, emptyList()).standardAudio!!
        assertEquals("/audio/standard/x.wav", sa.url)
        assertEquals("wav", sa.format)
        assertEquals("3.50", sa.duration)
    }
}
