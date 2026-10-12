import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import YuguCore

/// SDK 2.1.0 reading progress (CONTRACT 5.5): progress frames recorded from the production platform,
/// the `realtimeFeedback` start frame, delivery up to the final result, and the rules for delivered
/// progress after a reconnect (REPLAY never goes back, DROP starts over).
final class ReadingProgressTests: XCTestCase {
    /// The customer's reference text and its 25 units, one per character, punctuation removed.
    static let customerText = "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。"
    static let customerUnits = "我是中国人我是中国人我们都是中国人中华民族是一家人".map { String($0) }
    /// `Nice to see you again. I am a student, and I like reading books every day.` by word.
    static let englishUnits = ["Nice", "to", "see", "you", "again", "I", "am", "a", "student", "and", "I", "like", "reading", "books", "every", "day"]

    let progressConfig = EvaluateConfig(coreType: .sentence, referenceText: ReadingProgressTests.customerText, language: "zh-CN", realtimeFeedback: true)

    // MARK: Frames

    private func recorded(_ name: String) -> [JSONValue] {
        Fixture.json("platform/" + name).arrayValue!.compactMap { $0["frame"] }
    }

    private func progressFrames(_ name: String) -> [JSONValue] {
        recorded(name).filter { $0["eof"]?.intValue == 0 }
    }

    /// `result` of a progress frame over `units`: the first `read` units read, the first `matched` matched.
    static func progressResult(bytes: Int, read: Int, matched: Int, units: [String]) -> [String: JSONValue] {
        let progress: [JSONValue] = units.enumerated().map { i, w in ["word": .string(w), "read": .bool(i < read), "matched": .bool(i < matched)] }
        return [
            "bytes": .number(Double(bytes)), "readUnits": .number(Double(read)), "matchedUnits": .number(Double(matched)),
            "totalUnits": .number(Double(units.count)), "progress": .array(progress),
        ]
    }

    /// Native progress frame.
    static func frame(bytes: Int, read: Int, matched: Int, units: [String] = ReadingProgressTests.customerUnits) -> String {
        JSONValue.object(["eof": 0, "result": .object(progressResult(bytes: bytes, read: read, matched: matched, units: units))]).jsonText
    }

    /// Compatible progress frame: the native members plus an empty `recordId`, engine versions and
    /// Shengtong-shaped `words` for the units read since `since`.
    static func compatFrame(bytes: Int, read: Int, matched: Int, since: Int, units: [String] = ReadingProgressTests.customerUnits) -> String {
        var result = progressResult(bytes: bytes, read: read, matched: matched, units: units)
        result["kernel_version"] = "2.4.1"
        result["resource_version"] = "1.8.7"
        result["words"] = .array((since..<read).map { i in
            let score: JSONValue = .number(i < matched ? 100 : 0)
            let scores: JSONValue = ["pronunciation": score, "skipped": .number(i < matched ? 0 : 1), "tone": score, "overall": score, "overall_pron": score]
            let part: JSONValue = ["beginIndex": .number(Double(i)), "charType": 0, "endIndex": .number(Double(i)), "part": .string(units[i])]
            return ["charType": 0, "scores": scores, "word": .string(units[i]), "word_parts": [part]]
        })
        return JSONValue.object(["eof": 0, "recordId": "", "result": .object(result)]).jsonText
    }

    /// A 2.0.0 style progress frame without reading progress.
    static func bytesOnly(_ bytes: Int) -> String { #"{"eof":0,"result":{"bytes":\#(bytes)}}"# }

    /// CONTRACT 5.5 over a whole recorded round: once progress appears every frame carries it, the
    /// counts agree with `progress`, matched implies read, read units form a prefix, and progress
    /// never goes back within one server session. Returns the partials with progress.
    @discardableResult
    private func assertRound(_ frames: [JSONValue], units: [String], file: StaticString = #filePath, line: UInt = #line) -> [StreamPartial] {
        let partials = frames.map(ResultParser.streamPartial)
        let first = partials.firstIndex { $0.hasProgress } ?? partials.count
        XCTAssertLessThan(first, partials.count, "no frame with progress", file: file, line: line)
        XCTAssertTrue(partials[first...].allSatisfy { $0.hasProgress }, "progress stays present once it appeared", file: file, line: line)
        var last = (read: 0, matched: 0)
        for p in partials[first...] {
            XCTAssertNotNil(p.bytes, file: file, line: line)
            XCTAssertEqual(p.progress.map { $0.word }, units, file: file, line: line)
            XCTAssertEqual(p.totalUnits, units.count, file: file, line: line)
            XCTAssertEqual(p.readUnits, p.progress.filter { $0.read }.count, file: file, line: line)
            XCTAssertEqual(p.matchedUnits, p.progress.filter { $0.matched }.count, file: file, line: line)
            XCTAssertTrue(p.progress.allSatisfy { $0.read || !$0.matched }, "matched implies read", file: file, line: line)
            XCTAssertTrue(p.progress.prefix(max(0, p.readUnits)).allSatisfy { $0.read }, "read units form a prefix", file: file, line: line)
            XCTAssertGreaterThanOrEqual(p.readUnits, last.read, file: file, line: line)
            XCTAssertGreaterThanOrEqual(p.matchedUnits, last.matched, file: file, line: line)
            last = (p.readUnits, p.matchedUnits)
        }
        return Array(partials[first...])
    }

    /// Compatible frames carry an empty `recordId`, the engine versions and Shengtong-shaped
    /// `words` for the units read since the previous frame, covering `[readUnits - count, readUnits)`;
    /// matched units score with `skipped` 0, the others `skipped` 1.
    private func assertCompatShape(_ frames: [JSONValue], units: [String], file: StaticString = #filePath, line: UInt = #line) {
        var previous = 0
        for f in frames where f["result"]?["progress"] != nil {
            XCTAssertEqual(f["recordId"]?.stringValue, "", file: file, line: line)
            let r = f["result"] ?? .null
            XCTAssertNotNil(r["kernel_version"]?.stringValue, file: file, line: line)
            XCTAssertNotNil(r["resource_version"]?.stringValue, file: file, line: line)
            let read = r["readUnits"]?.intValue ?? 0
            let items = r["words"]?.arrayValue ?? []
            XCTAssertEqual(items.count, read - previous, "words are the units read since the previous frame", file: file, line: line)
            let progress = ResultParser.streamPartial(f).progress
            for (k, item) in items.enumerated() {
                let i = read - items.count + k
                XCTAssertEqual(item["word"]?.stringValue, units[i], file: file, line: line)
                XCTAssertEqual(item["scores"]?["skipped"]?.intValue, progress[i].matched ? 0 : 1, file: file, line: line)
            }
            previous = read
        }
    }

    // MARK: Frames recorded from the platform

    func testCustomerSentenceCompatRecording() throws {
        let frames = progressFrames("ws_compat_sent_eval_cn_progress_frames.json")
        let partials = assertRound(frames, units: Self.customerUnits)
        assertCompatShape(frames, units: Self.customerUnits)
        XCTAssertEqual(partials.first?.readUnits, 0)
        // A recognition: the read units are read and matched, the next one not reached yet.
        let early = try XCTUnwrap(partials.first { $0.readUnits > 0 && $0.readUnits < 25 })
        XCTAssertEqual(early.progress[early.readUnits - 1].matched, true)
        XCTAssertEqual(early.progress[early.readUnits], ReadingWord(word: Self.customerUnits[early.readUnits], read: false, matched: false))
        let last = try XCTUnwrap(partials.last)
        XCTAssertEqual([last.readUnits, last.matchedUnits, last.totalUnits], [25, 25, 25])
        XCTAssertEqual(last.raw, frames.last)
        let final = try XCTUnwrap(recorded("ws_compat_sent_eval_cn_progress_frames.json").last)
        XCTAssertEqual(final["eof"]?.intValue, 1)
        XCTAssertNotNil(ResultParser.evalResult(final).overall)
    }

    /// The second sentence's 中 (unit 7) was read as 外: read once the reading passed it, never matched.
    func testMisreadCharacterIsReadButNotMatched() throws {
        let frames = progressFrames("ws_compat_sent_eval_cn_progress_misread_frames.json")
        let partials = assertRound(frames, units: Self.customerUnits)
        assertCompatShape(frames, units: Self.customerUnits)
        let passed = partials.filter { $0.readUnits > 7 }
        XCTAssertFalse(passed.isEmpty)
        for p in passed {
            XCTAssertEqual(p.progress[7], ReadingWord(word: "中", read: true, matched: false))
        }
        let last = try XCTUnwrap(partials.last)
        XCTAssertEqual(last.readUnits, 25)
        XCTAssertEqual(last.progress.indices.filter { !last.progress[$0].matched }, [7])
        XCTAssertNotNil(ResultParser.evalResult(try XCTUnwrap(recorded("ws_compat_sent_eval_cn_progress_misread_frames.json").last)).overall)
    }

    /// English counts words.
    func testEnglishRecordingCountsWords() throws {
        let frames = progressFrames("ws_compat_sent_eval_progress_frames.json")
        let partials = assertRound(frames, units: Self.englishUnits)
        assertCompatShape(frames, units: Self.englishUnits)
        let last = try XCTUnwrap(partials.last)
        XCTAssertEqual(last.totalUnits, 16)
        XCTAssertEqual(last.readUnits, 16)
        XCTAssertNotNil(ResultParser.evalResult(try XCTUnwrap(recorded("ws_compat_sent_eval_progress_frames.json").last)).overall)
    }

    /// The native channel sends `progress` without `words` and without `recordId`, then the result frame.
    func testNativeRecording() throws {
        let frames = recorded("ws_native_sentence_progress_frames.json")
        XCTAssertEqual(frames.first?["event"]?.stringValue, "connected")
        let progress = progressFrames("ws_native_sentence_progress_frames.json")
        XCTAssertTrue(progress.allSatisfy { $0["event"] == nil && $0["recordId"] == nil && $0["result"]?["words"] == nil })
        let partials = assertRound(progress, units: Self.customerUnits)
        XCTAssertEqual(partials.last.map { [$0.readUnits, $0.matchedUnits] }, [25, 25])
        let final = try XCTUnwrap(frames.last)
        XCTAssertEqual(final["event"]?.stringValue, "result")
        XCTAssertNotNil(ResultParser.evalResult(final).overall)
    }

    // MARK: Parsing, value semantics

    func testFramesWithoutProgressHaveNoProgress() throws {
        let p = ResultParser.streamPartial(try JSONValue.parse(Self.bytesOnly(16000)))
        XCTAssertEqual(p.bytes, 16000)
        XCTAssertFalse(p.hasProgress)
        XCTAssertEqual([p.readUnits, p.matchedUnits, p.totalUnits], [-1, -1, -1])
        XCTAssertEqual(p.progress, [])
        let bare = ResultParser.streamPartial(try JSONValue.parse(#"{"eof":0}"#))
        XCTAssertNil(bare.bytes)
        XCTAssertFalse(bare.hasProgress)
        XCTAssertEqual(bare.totalUnits, -1)
        // `progress` decides: present without counts is progress with -1 counts; missing members default.
        let partial = ResultParser.streamPartial(try JSONValue.parse(#"{"eof":0,"result":{"bytes":1,"progress":[{"word":"我","read":true},{}]}}"#))
        XCTAssertTrue(partial.hasProgress)
        XCTAssertEqual(partial.readUnits, -1)
        XCTAssertEqual(partial.progress, [ReadingWord(word: "我", read: true, matched: false), ReadingWord(word: "", read: false, matched: false)])
    }

    /// The per-unit state comes from `result.progress` only, never from `result.words`.
    func testWordsAreNotTheProgress() throws {
        let compat = ResultParser.streamPartial(try JSONValue.parse(Self.compatFrame(bytes: 32000, read: 5, matched: 4, since: 2)))
        XCTAssertTrue(compat.hasProgress)
        XCTAssertEqual(compat.progress.count, 25)
        XCTAssertEqual([compat.readUnits, compat.matchedUnits, compat.totalUnits], [5, 4, 25])
        XCTAssertEqual(compat.progress[4], ReadingWord(word: "人", read: true, matched: false))
        XCTAssertEqual(compat.raw["recordId"]?.stringValue, "")
        XCTAssertEqual(compat.raw["result"]?["words"]?.arrayValue?.compactMap { $0["word"]?.stringValue }, ["中", "国", "人"])
        // Shengtong-shaped increments alone: no reading progress.
        var onlyWords = try JSONValue.parse(Self.compatFrame(bytes: 32000, read: 5, matched: 5, since: 0)).objectValue!
        var result = onlyWords["result"]!.objectValue!
        result["progress"] = nil
        onlyWords["result"] = .object(result)
        let increments = ResultParser.streamPartial(.object(onlyWords))
        XCTAssertFalse(increments.hasProgress)
        XCTAssertEqual(increments.progress, [])
        // Per-unit state under `words` is not read either.
        let unitWords = ResultParser.streamPartial(try JSONValue.parse(#"{"eof":0,"result":{"bytes":96000,"words":[{"word":"我","read":true,"matched":true}]}}"#))
        XCTAssertFalse(unitWords.hasProgress)
        XCTAssertEqual(unitWords.progress, [])
    }

    func testReadingWordValueSemantics() {
        let w = ReadingWord(word: "华", read: true, matched: false)
        XCTAssertEqual(w, ReadingWord(word: "华", read: true, matched: false))
        XCTAssertNotEqual(w, ReadingWord(word: "中", read: true, matched: false))
        XCTAssertNotEqual(w, ReadingWord(word: "华", read: false, matched: false))
        XCTAssertNotEqual(w, ReadingWord(word: "华", read: true, matched: true))
        XCTAssertEqual(w.description, #"ReadingWord(word: "华", read: true, matched: false)"#)
        XCTAssertEqual(String(describing: ReadingWord(word: "Nice", read: true, matched: true)), #"ReadingWord(word: "Nice", read: true, matched: true)"#)
    }

    // MARK: Start frame

    /// Off by default: the start frame stays byte for byte the 2.0.0 frame. On: one member
    /// `"realtimeFeedback": true`, also when `extra` names it. Never in the REST `config` part.
    func testStartFrameCarriesRealtimeFeedbackOnlyWhenAsked() throws {
        let plain = EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好", language: "zh-CN")
        var on = plain
        on.realtimeFeedback = true
        XCTAssertFalse(plain.realtimeFeedback)
        XCTAssertNotEqual(plain, on)
        XCTAssertEqual(EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好", language: "zh-CN", realtimeFeedback: true), on)
        XCTAssertEqual(try on.jsonText(), try plain.jsonText())
        var viaExtra = on
        viaExtra.extra = ["realtimeFeedback": false, "zz": 1]
        let cases: [(EvaluateConfig, String)] = [
            (plain, ""),
            (on, #","realtimeFeedback":true"#),
            (viaExtra, #","realtimeFeedback":true,"zz":1"#),
        ]
        for (config, members) in cases {
            let t = makeTestClient()
            let s = try t.client.streamEvaluate(config: config, listener: RecordingListener())
            try s.start()
            t.executor.runUntilIdle()
            let key = try XCTUnwrap(s.idempotencyKey)
            let expected = #"{"cmd":"start","coreType":"sentence","referenceText":"今天天气很好","language":"zh-CN""# + members + #","idempotencyKey":""# + key + #""}"#
            XCTAssertEqual(t.ws.connections[0].texts.first, expected)
            s.cancel()
            t.executor.runUntilIdle()
        }
    }

    func testRestEvaluateDoesNotSendRealtimeFeedback() throws {
        let t = makeTestClient()
        t.http.defaultReply = .status(200, Fixture.text("platform/native_evaluate_sentence_zh.json"), [:])
        let config = EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好", language: "zh-CN", realtimeFeedback: true)
        var got: Result<EvalResult, YuguError>?
        t.client.evaluate(audio: .data(Fixture.data("audio/zh_short.wav")), config: config) { got = $0 }
        t.executor.runUntilIdle()
        XCTAssertEqual(try XCTUnwrap(got).get().overall, 93.7)
        let body = try XCTUnwrap(t.http.requests.first?.body)
        XCTAssertNotNil(body.range(of: Data(#"{"coreType":"sentence","referenceText":"今天天气很好","language":"zh-CN"}"#.utf8)))
        XCTAssertNil(body.range(of: Data("realtimeFeedback".utf8)))
    }

    // MARK: Delivery through the session

    /// The recorded native round through a session: every progress frame reaches onPartial in
    /// order, the last two sent after end() while ENDING, then the result, then closed.
    func testRecordedNativeRoundThroughSession() throws {
        let t = makeTestClient()
        let recordedProgress = progressFrames("ws_native_sentence_progress_frames.json")
        XCTAssertGreaterThan(recordedProgress.count, 2)
        let texts = recordedProgress.map { $0.jsonText }
        t.ws.framesBeforeResult = Array(texts.suffix(2))
        let l = RecordingListener()
        let s = try t.client.streamEvaluate(config: progressConfig, listener: l)
        try s.start()
        t.executor.runUntilIdle()
        for f in frames(tonePCM(seconds: 1.6)) { try s.sendAudio(f) }
        for text in texts.dropLast(2) { t.ws.connections[0].emitJSON(text) }
        t.executor.runUntilIdle()
        XCTAssertEqual(s.state, .started)
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.terminalCallbacks, ["result"])
        XCTAssertEqual(l.partials.map { $0.raw }, recordedProgress)
        XCTAssertEqual(l.partials.map { $0.readUnits }, recordedProgress.map { $0["result"]?["readUnits"]?.intValue ?? -1 })
        XCTAssertEqual(l.partialStates, Array(repeating: SessionState.started, count: recordedProgress.count - 2) + [.ending, .ending])
        let lastBytes = recordedProgress.suffix(2).map { "partial:\($0["result"]?["bytes"]?.intValue ?? -1)" }
        XCTAssertEqual(Array(l.callbacks.suffix(4)), lastBytes + ["result", "closed:1000"])
        XCTAssertEqual(l.partials.last?.progress.map { $0.word }, Self.customerUnits)
        XCTAssertEqual(try JSONValue.parse(t.ws.connections[0].texts[0])["realtimeFeedback"]?.boolValue, true)
        XCTAssertEqual(s.state, .closed)
        XCTAssertEqual(t.executor.liveTimers, 0)
    }

    /// REPLAY: after a reconnect the new server session counts progress from zero while the audio
    /// is replayed; progress partials behind the delivered progress are dropped with a DEBUG line
    /// until it catches up, partials without progress still arrive.
    func testReplayProgressNeverGoesBackAfterReconnect() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try t.client.streamEvaluate(config: progressConfig, listener: l)
        try s.start()
        t.executor.runUntilIdle()
        let pcm = tonePCM(seconds: 1.6)
        for f in frames(pcm) { try s.sendAudio(f) }
        let first = t.ws.connections[0]
        for text in [Self.frame(bytes: 16000, read: 2, matched: 2), Self.frame(bytes: 32000, read: 5, matched: 5), Self.bytesOnly(48000)] {
            first.emitJSON(text)
        }
        t.executor.runUntilIdle()
        first.emit(.failed(URLError(.networkConnectionLost)))
        t.executor.advance(5000)
        XCTAssertEqual(s.state, .started)
        let second = t.ws.connections[1]
        XCTAssertEqual(second.audio, pcm, "the round is replayed")
        XCTAssertEqual(try JSONValue.parse(second.texts[0])["realtimeFeedback"]?.boolValue, true)
        for text in [
            Self.frame(bytes: 16000, read: 0, matched: 0), // behind: dropped
            Self.bytesOnly(16000), // no reading progress: delivered
            Self.frame(bytes: 32000, read: 2, matched: 2), // behind: dropped
            Self.frame(bytes: 40000, read: 5, matched: 4), // matched behind: dropped
            Self.frame(bytes: 48000, read: 5, matched: 5), // caught up: delivered
            Self.frame(bytes: 51200, read: 7, matched: 6), // ahead: delivered
        ] {
            second.emitJSON(text)
        }
        t.executor.runUntilIdle()
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.terminalCallbacks, ["result"])
        XCTAssertEqual(l.callbacks.filter { $0.hasPrefix("reconnect") }, ["reconnecting:1:90001", "reconnected:1:0"])
        XCTAssertEqual(l.partials.map { $0.bytes ?? -1 }, [16000, 32000, 48000, 16000, 48000, 51200])
        XCTAssertEqual(l.partials.map { $0.hasProgress }, [true, true, false, false, true, true])
        XCTAssertEqual(l.progressPartials.map { [$0.readUnits, $0.matchedUnits] }, [[2, 2], [5, 5], [5, 5], [7, 6]])
        let dropped = t.logger.messages(.debug).filter { $0.contains("partial not delivered") }
        XCTAssertEqual(dropped.count, 3, "\(dropped)")
        XCTAssertTrue(dropped[0].hasSuffix("progress read 0 matched 0 is behind the delivered read 5 matched 5, partial not delivered"), dropped[0])
        XCTAssertTrue(dropped[2].contains("read 5 matched 4"), dropped[2])
        XCTAssertEqual(t.ws.totalBilled, 1)
        XCTAssertEqual(t.executor.liveTimers, 0)
    }

    /// DROP: the new server session covers only the audio sent after the reconnect, so the
    /// remembered progress is cleared when the reconnect succeeds; within the new server session
    /// progress again never goes back.
    func testDropProgressStartsOverAfterReconnect() throws {
        let t = makeTestClient { $0.audioBufferPolicy = .drop }
        let l = RecordingListener()
        let s = try t.client.streamEvaluate(config: progressConfig, listener: l)
        try s.start()
        t.executor.runUntilIdle()
        for f in frames(tonePCM(seconds: 0.8)) { try s.sendAudio(f) }
        let first = t.ws.connections[0]
        first.emitJSON(Self.frame(bytes: 16000, read: 2, matched: 2))
        first.emitJSON(Self.frame(bytes: 25600, read: 5, matched: 5))
        t.executor.runUntilIdle()
        first.emit(.failed(URLError(.networkConnectionLost)))
        t.executor.advance(5000)
        XCTAssertEqual(s.state, .started)
        let after = tonePCM(seconds: 1.2, amplitude: 5000)
        for f in frames(after) { try s.sendAudio(f) }
        t.executor.runUntilIdle()
        let second = t.ws.connections[1]
        XCTAssertEqual(second.audio, after, "only the audio sent after the reconnect")
        for text in [
            Self.frame(bytes: 16000, read: 1, matched: 1), // new server session: delivered
            Self.frame(bytes: 19200, read: 0, matched: 0), // behind within it: dropped
            Self.frame(bytes: 32000, read: 3, matched: 2), // delivered
        ] {
            second.emitJSON(text)
        }
        t.executor.runUntilIdle()
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.terminalCallbacks, ["result"])
        XCTAssertEqual(l.callbacks.filter { $0.hasPrefix("reconnect") }, ["reconnecting:1:90001", "reconnected:1:0"])
        let around = l.partialsAroundReconnect
        XCTAssertEqual(around.before.map { [$0.readUnits, $0.matchedUnits] }, [[2, 2], [5, 5]])
        XCTAssertEqual(around.after.map { [$0.readUnits, $0.matchedUnits] }, [[1, 1], [3, 2]])
        XCTAssertEqual(t.logger.messages(.debug).filter { $0.contains("partial not delivered") }.count, 1)
    }

    /// The memory of delivered progress belongs to one logical session.
    func testNewSessionStartsWithoutProgressMemory() throws {
        let t = makeTestClient()
        var delivered: [[Int]] = []
        for (index, read) in [10, 2].enumerated() {
            let l = RecordingListener()
            let s = try t.client.streamEvaluate(config: progressConfig, listener: l)
            try s.start()
            t.executor.runUntilIdle()
            for f in frames(tonePCM(seconds: 1.2)) { try s.sendAudio(f) }
            t.ws.connections[index].emitJSON(Self.frame(bytes: 16000, read: read, matched: read))
            t.executor.runUntilIdle()
            try s.end()
            t.executor.runUntilIdle()
            XCTAssertEqual(l.terminalCallbacks, ["result"])
            delivered.append(l.progressPartials.map { $0.readUnits })
        }
        XCTAssertEqual(delivered, [[10], [2]])
    }

    /// Compatible session: progress comes from `result.progress` of the compatible frames, and
    /// partials without reading progress are delivered as in 2.0.0, before and after progress,
    /// also when the byte count goes down.
    func testCompatFramesAndBytesOnlyPartials() throws {
        let t = makeTestClient()
        t.ws.resultFixture = "platform/compat_sent.eval.cn.json"
        let l = RecordingListener()
        let params = CompatParams(refText: Self.customerText, language: "zh-CN", realtimeFeedback: true)
        let s = try t.client.streamEvaluateCompat(coreType: .sentEvalCn, params: params, listener: l)
        try s.start()
        t.executor.runUntilIdle()
        for f in frames(tonePCM(seconds: 1.6)) { try s.sendAudio(f) }
        let c = t.ws.connections[0]
        for text in [
            Self.bytesOnly(16000), Self.compatFrame(bytes: 32000, read: 3, matched: 3, since: 0), Self.bytesOnly(48000), Self.bytesOnly(8000),
            Self.compatFrame(bytes: 51200, read: 3, matched: 3, since: 3),
        ] {
            c.emitJSON(text)
        }
        t.executor.runUntilIdle()
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "partial:16000", "partial:32000", "partial:48000", "partial:8000", "partial:51200", "result", "closed:1000"])
        XCTAssertEqual(l.partials.map { $0.hasProgress }, [false, true, false, false, true])
        XCTAssertEqual(l.partials[0].readUnits, -1)
        XCTAssertEqual(l.partials[0].progress, [])
        XCTAssertEqual(l.partials[1].progress.prefix(4).map { $0.read }, [true, true, true, false])
        XCTAssertEqual(l.partials[4].raw["result"]?["words"]?.arrayValue, [], "no unit became read since the previous frame")
        XCTAssertEqual(l.result?.overall, 94.6)
        let params0 = try JSONValue.parse(c.texts[0])
        XCTAssertEqual(params0["realtime_feedback"]?.boolValue, true)
        XCTAssertNil(params0["realtimeFeedback"])
    }
}
