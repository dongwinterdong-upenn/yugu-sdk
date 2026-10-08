import XCTest
@testable import YuguCore

/// Result parsing for every evaluation mode, from real platform responses.
final class ParsingTests: XCTestCase {
    private func parse(_ name: String) -> EvalResult {
        ResultParser.evalResult(Fixture.json("platform/" + name))
    }

    func testNativeSentenceZh() throws {
        let r = parse("native_evaluate_sentence_zh.json")
        XCTAssertEqual(r.recordId, "eval_3fb45f4c8e71")
        XCTAssertEqual(r.eof, 1)
        XCTAssertEqual(r.coreType, "sentence")
        XCTAssertEqual(r.language, "zh")
        XCTAssertEqual(r.overall, 93.7)
        XCTAssertEqual(r.dimensions.pronunciation, 100)
        XCTAssertEqual(r.dimensions.accuracy, 100)
        XCTAssertEqual(r.dimensions.tone, 83)
        XCTAssertEqual(r.dimensions.fluency, 96)
        XCTAssertEqual(r.dimensions.rhythm, 78)
        XCTAssertEqual(r.dimensions.integrity, 100)
        XCTAssertEqual(r.dimensions.readingSkill, 78)
        XCTAssertEqual(r.dimensions.emotion, 100)
        XCTAssertEqual(r.dimensions.speed, 225)
        XCTAssertEqual(r.durationSeconds, 1.921)
        XCTAssertEqual(r.words.count, 6)
        let w = r.words[0]
        XCTAssertEqual(w.word, "今")
        XCTAssertEqual(w.pinyin, "jin")
        XCTAssertEqual(w.symbolPinyin, "jīn")
        XCTAssertEqual(w.tone, "tone1")
        XCTAssertEqual(w.readStatus, "correct")
        XCTAssertEqual(w.overall, 78)
        XCTAssertNotNil(w.span?.start)
        XCTAssertFalse(w.phonemes.isEmpty)
        XCTAssertEqual(r.sentences.count, 1)
        XCTAssertEqual(r.sentences[0].text, "今天天气很好")
        XCTAssertEqual(r.sentences[0].overall, 94)
        XCTAssertEqual(r.sentences[0].details.count, 6)
        let y = try XCTUnwrap(r.yuguScores)
        XCTAssertEqual(y.overall, 93.7)
        XCTAssertEqual(y.accuracy, 100)
        XCTAssertEqual(y.fluency, 96)
        XCTAssertEqual(y.readingSkill, 78)
        XCTAssertEqual(y.raw["accuracy"]?["initial"]?.doubleValue, 83.5)
        XCTAssertNotNil(r.report?.summary)
        XCTAssertEqual(r.report?.rubricVersion, "v20260707b")
        XCTAssertEqual(r.report?.dimensionScores?["accuracy"]?.doubleValue, 100)
        XCTAssertEqual(r.asrText?.text, "今 天 天 气 很 好")
        XCTAssertEqual(r.asrText?.alignment.count, 6)
        XCTAssertEqual(r.asrText?.alignment.first?.asrPinyin, "jin1")
        XCTAssertEqual(r.asrText?.alignment.first?.readStatus, "correct")
        XCTAssertEqual(r.warnings, [])
        XCTAssertNil(r.connected)
        XCTAssertNil(r.openTask)
        XCTAssertEqual(r.resultJSON["kernel_version"]?.stringValue, "1.0.0")
        XCTAssertFalse(r.hasNoValidAudio)
    }

    func testNativeWordEn() {
        let r = parse("native_word_en.json")
        XCTAssertEqual(r.coreType, "word")
        XCTAssertEqual(r.overall, 65)
        XCTAssertEqual(r.dimensions.pronunciation, 73.73)
        XCTAssertEqual(r.dimensions.integrity, 38.64)
        XCTAssertEqual(r.dimensions.tone, 0)
        XCTAssertEqual(r.words.map { $0.word }, ["apple"])
        XCTAssertEqual(r.words[0].overall, 99)
        XCTAssertEqual(r.words[0].phonemes.count, 4)
        XCTAssertEqual(r.words[0].raw["scores"]?["stress"]?.arrayValue?.count, 2)
        XCTAssertEqual(r.yuguScores?.overall, 94.1)
        XCTAssertEqual(r.resultJSON["_overallCalibration"]?["reason"]?.stringValue, "english_word_overread_asr_evidence")
    }

    func testNativeSentenceEn() {
        let r = parse("native_sentence_en.json")
        XCTAssertEqual(r.coreType, "sentence")
        XCTAssertEqual(r.language, "en")
        XCTAssertEqual(r.overall, 93.9)
        XCTAssertEqual(r.dimensions.fluency, 97)
        XCTAssertEqual(r.dimensions.rhythm, 89)
        XCTAssertEqual(r.dimensions.readingSkill, 89.03)
        XCTAssertEqual(r.words.count, 9)
        XCTAssertEqual(r.sentences.first?.text, "The quick brown fox jumps over the lazy dog.")
        XCTAssertEqual(r.sentences.first?.details.count, 9)
        XCTAssertEqual(r.dimensions.speed, 130)
    }

    func testNativePassageZh() {
        let r = parse("native_passage_zh.json")
        XCTAssertEqual(r.coreType, "passage")
        XCTAssertEqual(r.overall, 89.1)
        XCTAssertEqual(r.dimensions.tone, 87)
        XCTAssertEqual(r.words.count, 15)
        XCTAssertEqual(r.sentences.count, 2)
        XCTAssertEqual(r.sentences.map { $0.overall }, [81, 85])
        XCTAssertEqual(r.sentences[0].text, "今天天气很好。")
        XCTAssertEqual(r.sentences[0].integrity, 100)
        XCTAssertNotNil(r.sentences[0].span)
        XCTAssertEqual(r.sentences[0].details.count, 6)
        XCTAssertEqual(r.sentences[0].details[0].word, "今")
        XCTAssertEqual(r.sentences[0].details[0].overall, 76)
        XCTAssertEqual(r.yuguScores?.raw["accuracy"]?["retroflex"]?.doubleValue, 100)
    }

    func testNativeConnectedEn() throws {
        let r = parse("native_connected_en.json")
        XCTAssertEqual(r.coreType, "connected")
        XCTAssertNil(r.resultJSON["overall"])
        // connected has no result.overall, the total is connected_overall.
        XCTAssertEqual(r.overall, 60)
        let c = try XCTUnwrap(r.connected)
        XCTAssertEqual(c.overall, 60)
        XCTAssertEqual(c.linking, 40)
        XCTAssertEqual(c.rhythm, 44)
        XCTAssertEqual(c.elision, 0)
        XCTAssertEqual(c.reduction, 95)
        XCTAssertEqual(c.boundaryCount, 4)
        XCTAssertEqual(c.boundaries.count, 4)
        let b = c.boundaries[0]
        XCTAssertEqual(b.between, ["want", "to"])
        XCTAssertEqual(b.tags, ["elision", "reduction"])
        XCTAssertEqual(b.realized, 0.82)
        XCTAssertEqual(b.startMs, 770)
        XCTAssertEqual(b.endMs, 1070)
        XCTAssertEqual(b.gapMs, 40)
        XCTAssertEqual(b.covered, true)
        XCTAssertTrue(c.summary?.hasPrefix("The overall linking is fair") == true)
        XCTAssertEqual(c.suggestions.count, 3)
        XCTAssertEqual(c.raw["raw"]?["linking_rate"]?.doubleValue, 0.704)
        XCTAssertNil(r.openTask)
        XCTAssertEqual(r.dimensions.rhythm, 44)
    }

    func testNativeOpenZh() throws {
        let r = parse("native_open_zh.json")
        XCTAssertEqual(r.coreType, "open")
        XCTAssertEqual(r.overall, 92)
        XCTAssertEqual(r.durationSeconds, 14.22)
        let o = try XCTUnwrap(r.openTask)
        XCTAssertEqual(o.overall, 92)
        XCTAssertEqual(o.taskType, "free")
        XCTAssertEqual(o.hasSpeech, true)
        XCTAssertTrue(o.transcript?.hasPrefix("大家好") == true)
        XCTAssertEqual(o.content?.overall, 98)
        XCTAssertEqual(o.content?.relevance, 100)
        XCTAssertEqual(o.content?.coherence, 95)
        XCTAssertEqual(o.content?.taskAchievement, 98)
        XCTAssertEqual(o.languageUse?.overall, 90)
        XCTAssertEqual(o.languageUse?.grammar, 96)
        XCTAssertEqual(o.languageUse?.vocabulary, 85)
        XCTAssertEqual(o.delivery?.overall, 89)
        XCTAssertEqual(o.delivery?.fluency, 100)
        XCTAssertEqual(o.delivery?.pronunciation, 72)
        XCTAssertEqual(o.delivery?.speechRate, 3.897)
        XCTAssertEqual(o.delivery?.speechRateLabel, "233.8 字/分")
        XCTAssertEqual(o.delivery?.pauseCount, 0)
        XCTAssertEqual(o.delivery?.longestPauseSeconds, 0)
        XCTAssertEqual(o.feedback?.strengths, "内容完整，紧扣主题，条理清晰流畅。")
        XCTAssertEqual(o.feedback?.suggestions.count, 2)
        XCTAssertEqual(o.audit?.promptEcho, false)
        XCTAssertEqual(o.audit?.lowContent, false)
        XCTAssertEqual(o.audit?.capApplied, false)
        XCTAssertEqual(o.rubricVersion, "v20260705a")
        XCTAssertEqual(o.raw["aggregation"]?["weights"]?["content"]?.doubleValue, 0.35)
        XCTAssertNil(r.connected)
    }

    func testNativeAlphaEn() {
        let r = parse("native_alpha_en.json")
        XCTAssertEqual(r.coreType, "alpha")
        XCTAssertEqual(r.overall, 47)
        XCTAssertEqual(r.words.map { $0.word }, ["A", "B", "C"])
        XCTAssertEqual(r.dimensions.integrity, 26.34)
        XCTAssertEqual(r.dimensions.readingSkill, 45.25)
        XCTAssertEqual(r.resultJSON["_dimensionCalibration"]?.arrayValue?.count, 3)
    }

    func testNativePinyinZh() {
        let r = parse("native_pinyin_zh.json")
        XCTAssertEqual(r.overall, 88.9)
        XCTAssertEqual(r.dimensions.tone, 83)
        XCTAssertEqual(r.dimensions.emotion, 76)
        XCTAssertEqual(r.words.count, 6)
        XCTAssertEqual(r.words.first?.pinyin, "jin")
    }

    func testCompatModes() {
        let sentEn = parse("compat_sent.eval.json")
        XCTAssertEqual(sentEn.recordId, "eval_eb3720db1bd4")
        XCTAssertEqual(sentEn.coreType, "sent.eval")
        XCTAssertEqual(sentEn.overall, 93.9)
        XCTAssertEqual(sentEn.words.count, 9)
        XCTAssertEqual(sentEn.dimensions.emotion, 98) // only in yuguScores
        let sentCn = parse("compat_sent.eval.cn.json")
        XCTAssertEqual(sentCn.overall, 94.6)
        XCTAssertEqual(sentCn.yuguScores?.overall, 88.9)
        XCTAssertEqual(parse("compat_sent_eval_cn.json").overall, 94.6)
        let word = parse("compat_word.eval.json")
        XCTAssertEqual(word.coreType, "word.eval")
        XCTAssertEqual(word.overall, 65)
        XCTAssertEqual(word.words.count, 1)
        let para = parse("compat_para.eval.cn.json")
        XCTAssertEqual(para.coreType, "para.eval.cn")
        XCTAssertEqual(para.overall, 89.8)
        XCTAssertEqual(para.sentences.map { $0.overall }, [81, 85])
        XCTAssertEqual(para.words.count, 0)
    }

    func testCompatParagraphWordDetailAndAttachAudioUrl() {
        let para = parse("compat_para.eval.cn_word_detail.json")
        XCTAssertEqual(para.coreType, "para.eval.cn")
        XCTAssertEqual(para.overall, 89.8)
        XCTAssertEqual(para.words.count, 15)
        XCTAssertEqual(para.sentences.count, 2)
        XCTAssertEqual(para.sentences[0].details.count, 6)
        XCTAssertEqual(para.sentences[0].details.first?.word, "今")
        let attach = parse("compat_sent.eval.cn_attach_audio_url.json")
        XCTAssertEqual(attach.overall, 94.6)
        XCTAssertEqual(attach.sentences.first?.details.count, 6)
    }

    /// Every captured response parses without losing the total.
    func testEveryEvaluationFixtureHasATotal() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Fixture.dir.appendingPathComponent("platform").path)
        let evals = names.filter { ($0.hasPrefix("native_") || $0.hasPrefix("compat_")) && $0.hasSuffix(".json") }
        XCTAssertGreaterThanOrEqual(evals.count, 15)
        for n in evals {
            let r = parse(n)
            XCTAssertNotNil(r.overall, n)
            XCTAssertNotNil(r.recordId, n)
            XCTAssertEqual(r.eof, 1, n)
        }
    }

    func testWebSocketFrameFixtures() throws {
        let native = Fixture.json("platform/ws_native_sentence_frames.json").arrayValue!.compactMap { $0["frame"] }
        XCTAssertEqual(native.first?["event"]?.stringValue, "connected")
        let resultFrame = try XCTUnwrap(native.last)
        XCTAssertEqual(resultFrame["event"]?.stringValue, "result")
        let r = ResultParser.evalResult(resultFrame)
        XCTAssertEqual(r.recordId, "eval_3fb45f4c8e71")
        XCTAssertEqual(r.overall, 93.7)
        let compat = Fixture.json("platform/ws_compat_sent_eval_cn_frames.json").arrayValue!.compactMap { $0["frame"] }
        XCTAssertEqual(compat.filter { $0["event"]?.stringValue == "started" }.count, 2)
        let last = try XCTUnwrap(compat.last)
        XCTAssertNil(last["event"])
        XCTAssertEqual(last["eof"]?.intValue, 1)
        XCTAssertEqual(ResultParser.evalResult(last).overall, 94.6)
    }

    func testWarningsBothShapesMerged() {
        let j: JSONValue = [
            "recordId": 12345, "eof": 1, "warnings": [1002, ["code": 1003, "message": "loud"]],
            "result": ["overall": 50, "warning": [["code": 1002, "message": "dup"], ["code": 1001, "message": "No valid audio detected!"], "x"]],
        ]
        let r = ResultParser.evalResult(j)
        XCTAssertEqual(r.recordId, "12345")
        XCTAssertEqual(r.warnings.map { $0.code }, [1002, 1003, 1001])
        XCTAssertEqual(r.warnings[0].message, "Audio volume too low!")
        XCTAssertEqual(r.warnings[1].message, "loud")
        XCTAssertEqual(r.warnings[1].warningCode, .volumeTooHigh)
        XCTAssertFalse(r.warnings[0].isLocal)
        XCTAssertTrue(r.hasNoValidAudio)
        XCTAssertEqual(r.warnings[0].description, "1002 Audio volume too low!")
    }

    func testTTSResult() {
        let fixture = Fixture.json("platform/tts_generate.json")
        let a = ResultParser.ttsResult(fixture, baseUrl: "https://open.shengzhiai.com")
        XCTAssertEqual(a.absoluteAudioUrl, "https://ygyx.dragonai.tech/tts-local/0c02b59749694e518fa8c6c6a78c2377.mp3")
        XCTAssertEqual(a.duration, 1.348)
        XCTAssertEqual(a.format, "mp3")
        let b = ResultParser.ttsResult(["code": 0, "data": ["audioUrl": "/audio/x.mp3"]], baseUrl: "https://h")
        XCTAssertEqual(b.absoluteAudioUrl, "https://h/tts/audio/x.mp3")
        XCTAssertEqual(ResultParser.ttsResult(["data": ["audioUrl": "/files/y.mp3"]], baseUrl: "https://h").absoluteAudioUrl, "https://h/files/y.mp3")
        XCTAssertEqual(ResultParser.ttsResult(["data": ["audioUrl": "z.mp3"]], baseUrl: "https://h").absoluteAudioUrl, "https://h/z.mp3")
        XCTAssertNil(ResultParser.ttsResult(["data": [:]], baseUrl: "https://h").absoluteAudioUrl)
    }

    func testEvaluationBodyChecks() {
        let info = CallInfo(idempotencyKey: "k", attempts: 2, replayed: true)
        func resp(_ s: String, _ status: Int = 200) -> YuguHTTPResponse { YuguHTTPResponse(statusCode: status, headers: [:], body: Data(s.utf8)) }
        // A 200 with a platform error body is an error.
        if case .failure(let e) = YuguClient.parseEvaluation(resp(#"{"code":50200,"message":"x"}"#), info, warnings: [], strictAudio: false) {
            XCTAssertEqual(e.code, 50200)
            XCTAssertTrue(e.retryable)
        } else { XCTFail("expected failure") }
        if case .failure(let e) = YuguClient.parseEvaluation(resp("not json"), info, warnings: [], strictAudio: false) {
            XCTAssertEqual(e.code, 90005)
        } else { XCTFail("expected failure") }
        if case .failure(let e) = YuguClient.parseEvaluation(resp("[1]"), info, warnings: [], strictAudio: false) {
            XCTAssertEqual(e.code, 90005)
        } else { XCTFail("expected failure") }
        if case .failure(let e) = YuguClient.parseEvaluation(resp("{}"), info, warnings: [], strictAudio: false) {
            XCTAssertEqual(e.code, 90005)
        } else { XCTFail("expected failure") }
        let w = [YuguWarning(code: 90104, message: "low")]
        if case .success(let r) = YuguClient.parseEvaluation(resp(Fixture.text("platform/compat_word.eval.json")), info, warnings: w, strictAudio: false) {
            XCTAssertEqual(r.idempotencyKey, "k")
            XCTAssertEqual(r.attempts, 2)
            XCTAssertTrue(r.replayed)
            XCTAssertEqual(r.localWarnings, w)
            XCTAssertTrue(r.localWarnings[0].isLocal)
        } else { XCTFail("expected success") }
        // strictAudio turns warning 1001 into an AUDIO error.
        let silent = #"{"recordId":"eval_1","eof":1,"result":{"overall":0,"warning":[{"code":1001,"message":"No valid audio detected!"}]}}"#
        if case .failure(let e) = YuguClient.parseEvaluation(resp(silent), info, warnings: [], strictAudio: true) {
            XCTAssertEqual(e.code, 1001)
            XCTAssertEqual(e.category, .audio)
            XCTAssertEqual(e.recordId, "eval_1")
        } else { XCTFail("expected failure") }
        if case .success(let r) = YuguClient.parseEvaluation(resp(silent), info, warnings: [], strictAudio: false) {
            XCTAssertTrue(r.hasNoValidAudio)
        } else { XCTFail("expected success") }
    }

    func testEnvelopeChecks() {
        func resp(_ s: String) -> YuguHTTPResponse { YuguHTTPResponse(statusCode: 200, headers: [:], body: Data(s.utf8)) }
        if case .failure(let e) = YuguClient.parseEnvelope(resp(#"{"code":42902,"message":"daily"}"#)) {
            XCTAssertEqual(e.category, .quota)
        } else { XCTFail() }
        if case .failure(let e) = YuguClient.parseEnvelope(resp(#"{"code":0}"#)) {
            XCTAssertEqual(e.code, 90005)
        } else { XCTFail() }
        if case .failure(let e) = YuguClient.parseEnvelope(resp("<html>")) {
            XCTAssertEqual(e.code, 90005)
        } else { XCTFail() }
        if case .success(let j) = YuguClient.parseEnvelope(resp(#"{"code":0,"data":{"a":1}}"#)) {
            XCTAssertEqual(j["data"]?["a"]?.intValue, 1)
        } else { XCTFail() }
    }
}
