import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import YuguCore

/// Requirements A-03 and B-05: the session state machine with a scripted transport.
final class StreamSessionTests: XCTestCase {
    let config = EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好", language: "zh-CN")
    let pcm = tonePCM(seconds: 1.6)

    private func session(_ t: TestClient, _ listener: RecordingListener, options: StreamOptions = StreamOptions()) throws -> YuguStreamSession {
        try t.client.streamEvaluate(config: config, options: options, listener: listener)
    }

    /// Common end-of-session checks: one terminal callback, closed last, states consistent,
    /// no timers left (nothing can hang), session released by the client.
    private func assertWellTerminated(_ t: TestClient, _ l: RecordingListener, _ s: YuguStreamSession, terminal: Int = 1, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(l.terminalCallbacks.count, terminal, "terminal callbacks \(l.events)", file: file, line: line)
        XCTAssertTrue(l.callbacks.last?.hasPrefix("closed:") == true, "last callback \(l.events)", file: file, line: line)
        XCTAssertEqual(l.callbacks.filter { $0.hasPrefix("closed:") }.count, 1, file: file, line: line)
        XCTAssertTrue(l.states.last?.hasSuffix("->CLOSED") == true, "\(l.states)", file: file, line: line)
        XCTAssertEqual(s.state, .closed, file: file, line: line)
        XCTAssertFalse(s.isActive, file: file, line: line)
        for check in l.stateChecks {
            XCTAssertEqual(check.seen, check.announced, "state() inside onStateChanged", file: file, line: line)
        }
        XCTAssertFalse(l.overlapped, file: file, line: line)
        XCTAssertEqual(t.executor.liveTimers, 0, "timers left after the end", file: file, line: line)
        XCTAssertEqual(t.client.core.activeCount.sessions, 0, file: file, line: line)
        XCTAssertThrowsError(try s.sendAudio(Data([1, 2])), file: file, line: line)
    }

    private func stream(_ s: YuguStreamSession, _ t: TestClient, _ data: Data) {
        for f in frames(data) { try? s.sendAudio(f) }
        t.executor.runUntilIdle()
    }

    // MARK: Happy paths

    func testNativeHappyPathEventOrderAndFrames() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        XCTAssertEqual(s.state, .idle)
        try s.start()
        t.executor.runUntilIdle()
        XCTAssertEqual(s.state, .started)
        stream(s, t, pcm)
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.states, [
            "state:IDLE->CONNECTING", "state:CONNECTING->CONNECTED", "state:CONNECTED->STARTED", "state:STARTED->ENDING",
            "state:ENDING->COMPLETED", "state:COMPLETED->CLOSED",
        ])
        XCTAssertEqual(l.callbacks, ["connected", "started", "result", "closed:1000"])
        let r = try XCTUnwrap(l.result)
        XCTAssertEqual(r.overall, 93.7)
        XCTAssertEqual(r.idempotencyKey, s.idempotencyKey)
        XCTAssertEqual(r.attempts, 1)
        XCTAssertFalse(r.replayed)
        assertWellTerminated(t, l, s)
        // Wire: query signed, start frame with the key, audio as binary, end frame.
        let c = try XCTUnwrap(t.ws.connections.first)
        XCTAssertEqual(c.url?.path, "/api/v1/ws/evaluate")
        XCTAssertEqual(c.url?.scheme, "wss")
        XCTAssertEqual(c.url?.host, "api.example.test")
        XCTAssertEqual(c.headers["User-Agent"], "yugu-ios-sdk/2.0.0")
        var q = c.query
        let sig = q.removeValue(forKey: "signature")
        XCTAssertEqual(q["appKey"], "ak_test_key")
        XCTAssertEqual(q["idempotencyKey"], s.idempotencyKey)
        XCTAssertNotNil(q["timestamp"])
        XCTAssertEqual(q["nonce"]?.count, 16)
        XCTAssertEqual(sig, YuguSigner.sign(params: q, secretKey: "sk_test_secret"))
        let start = try JSONValue.parse(c.texts[0])
        XCTAssertEqual(start["cmd"]?.stringValue, "start")
        XCTAssertEqual(start["coreType"]?.stringValue, "sentence")
        XCTAssertEqual(start["referenceText"]?.stringValue, "今天天气很好")
        XCTAssertEqual(start["idempotencyKey"]?.stringValue, s.idempotencyKey)
        XCTAssertTrue(c.texts[0].hasPrefix(#"{"cmd":"start","coreType":"sentence""#))
        XCTAssertEqual(c.texts.last, #"{"cmd":"end"}"#)
        XCTAssertEqual(c.audio, pcm)
        XCTAssertEqual(c.audioFrames, frames(pcm).count)
        XCTAssertEqual(c.closedWith?.code, 1000)
        XCTAssertEqual(t.ws.totalBilled, 1)
        XCTAssertEqual(t.events.entries.filter { $0.hasPrefix("session:") }.count, 6)
    }

    func testCompatSessionWithPartials() throws {
        let t = makeTestClient()
        t.ws.resultFixture = "platform/compat_sent.eval.cn.json"
        let l = RecordingListener()
        let s = try t.client.streamEvaluateCompat(coreType: .sentEvalCn, params: CompatParams(refText: "今天天气很好", language: "zh-CN", realtimeFeedback: true), listener: l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        // The platform sends progress frames when realtime_feedback is set.
        t.ws.connections[0].emitJSON(#"{"eof":0,"result":{"bytes":16000}}"#)
        t.executor.runUntilIdle()
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "partial:16000", "result", "closed:1000"])
        XCTAssertEqual(l.result?.overall, 94.6)
        let c = t.ws.connections[0]
        XCTAssertEqual(c.url?.path, "/sent.eval.cn")
        let params = try JSONValue.parse(c.texts[0])
        XCTAssertEqual(params["refText"]?.stringValue, "今天天气很好")
        XCTAssertEqual(params["realtime_feedback"]?.boolValue, true)
        XCTAssertEqual(params["idempotencyKey"]?.stringValue, s.idempotencyKey)
        XCTAssertNil(params["cmd"])
        assertWellTerminated(t, l, s)
    }

    func testAudioAndEndBeforeStartedAreQueued() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        for f in frames(pcm) { try s.sendAudio(f) }
        try s.end()
        try s.end() // second end is a no-op
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "result", "closed:1000"])
        XCTAssertEqual(t.ws.connections[0].audio, pcm)
        XCTAssertEqual(t.ws.connections[0].texts.filter { $0 == #"{"cmd":"end"}"# }.count, 1)
        assertWellTerminated(t, l, s)
    }

    func testDuplicateStartedFrameIgnored() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        t.ws.connections[0].emitJSON(#"{"event":"started"}"#)
        t.ws.connections[0].emitJSON(#"{"event":"pong","ts":1}"#)
        t.ws.connections[0].emitJSON(#"{"something":"else"}"#)
        t.executor.runUntilIdle()
        XCTAssertEqual(s.state, .started)
        XCTAssertEqual(l.callbacks, ["connected", "started"])
        s.cancel()
        t.executor.runUntilIdle()
    }

    // MARK: Reconnect with REPLAY (DESIGN 2.5)

    func testReplayAfterTransportFailureBillsOnce() throws {
        let t = makeTestClient()
        t.ws.faults = [0: .killAfterAudioFrames(10)]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        XCTAssertEqual(s.state, .reconnecting)
        // Audio sent while reconnecting is kept and sent after the replay.
        let more = tonePCM(seconds: 0.4, amplitude: 4000)
        stream(s, t, more)
        try s.end()
        t.executor.advance(10_000)
        XCTAssertEqual(l.callbacks, ["connected", "started", "reconnecting:1:90001", "reconnected:1:0", "result", "closed:1000"])
        XCTAssertTrue(l.states.contains("state:STARTED->RECONNECTING"))
        XCTAssertTrue(l.states.contains("state:RECONNECTING->CONNECTING"))
        XCTAssertEqual(t.ws.connections.count, 2)
        let second = t.ws.connections[1]
        XCTAssertEqual(second.query["idempotencyKey"], s.idempotencyKey)
        XCTAssertEqual(try JSONValue.parse(second.texts[0])["idempotencyKey"]?.stringValue, s.idempotencyKey)
        XCTAssertEqual(second.audio, pcm + more, "the whole round is replayed in order")
        XCTAssertEqual(second.texts.last, #"{"cmd":"end"}"#)
        XCTAssertEqual(l.result?.attempts, 2)
        XCTAssertEqual(t.ws.totalBilled, 1)
        XCTAssertTrue(t.events.entries.contains("reconnect:1:true"))
        assertWellTerminated(t, l, s)
    }

    /// The first server session evaluated but the result frame was lost: after the result
    /// timeout the SDK reconnects with the same key and the platform replays the stored result.
    func testResultLostReplaysStoredResult() throws {
        let t = makeTestClient { $0.resultTimeoutMs = 5000 }
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        // Let the fake platform evaluate (and bill) but drop the frame.
        let first = t.ws.connections[0]
        first.onEvent = { _ in }
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(t.ws.totalBilled, 1)
        XCTAssertEqual(s.state, .ending)
        t.executor.advance(4999)
        XCTAssertEqual(s.state, .ending)
        t.executor.advance(20_000)
        XCTAssertEqual(l.callbacks, ["connected", "started", "reconnecting:1:90007", "reconnected:1:0", "result", "closed:1000"])
        XCTAssertEqual(l.result?.replayed, true)
        XCTAssertEqual(t.ws.totalBilled, 1)
        assertWellTerminated(t, l, s)
    }

    func testAbnormalCloseReconnects() throws {
        let t = makeTestClient()
        t.ws.faults = [0: .closeAfterAudioFrames(3, code: 1011)]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        try s.end()
        t.executor.advance(10_000)
        XCTAssertEqual(l.callbacks, ["connected", "started", "reconnecting:1:90001", "reconnected:1:0", "result", "closed:1000"])
        XCTAssertEqual(t.ws.connections[1].audio, pcm)
        assertWellTerminated(t, l, s)
    }

    func testRetryableErrorFrameReconnects() throws {
        let t = makeTestClient()
        t.ws.faults = [0: .errorOnEnd(code: 50200)]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        try s.end()
        t.executor.advance(10_000)
        XCTAssertEqual(l.callbacks, ["connected", "started", "reconnecting:1:50200", "reconnected:1:0", "result", "closed:1000"])
        assertWellTerminated(t, l, s)
    }

    func testReconnectExhaustedFails() throws {
        let t = makeTestClient { $0.reconnect = ReconnectPolicy(maxAttempts: 3) }
        t.ws.faults = [0: .killAfterAudioFrames(2), 1: .failConnect, 2: .failConnect, 3: .failConnect]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        t.executor.advance(60_000)
        XCTAssertEqual(l.callbacks, [
            "connected", "started", "reconnecting:1:90001", "reconnecting:2:90001", "reconnecting:3:90001", "error:90006", "closed:1006",
        ])
        let e = try XCTUnwrap(l.error)
        XCTAssertEqual(e.category, .network)
        XCTAssertFalse(e.retryable)
        XCTAssertEqual(e.attempts, 4)
        XCTAssertEqual(e.idempotencyKey, s.idempotencyKey)
        XCTAssertNotNil(e.cause)
        XCTAssertEqual(t.events.entries.filter { $0.hasPrefix("reconnect:") }, ["reconnect:1:false", "reconnect:2:false", "reconnect:3:false"])
        // Delays follow the reconnect policy: about 500, 1000, 2000 ms.
        let windows = [350...650, 700...1300, 1400...2600]
        let delays = t.executor.scheduledDelays.filter { d in windows.contains { $0.contains(d) } }
        XCTAssertGreaterThanOrEqual(delays.count, 3, "\(t.executor.scheduledDelays)")
        XCTAssertEqual(t.ws.totalBilled, 0)
        assertWellTerminated(t, l, s)
    }

    /// Every reconnect succeeds and the connection drops again at once: the consecutive count
    /// resets each time, the total cap of 3 times maxAttempts ends the loop with 90006.
    func testTotalReconnectCapStopsEndlessLoop() throws {
        let t = makeTestClient { $0.reconnect = ReconnectPolicy(maxAttempts: 2) }
        t.ws.faultProvider = { _ in .killAfterAudioFrames(1) }
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm.prefix(640 * 5))
        t.executor.advance(120_000)
        XCTAssertEqual(l.callbacks.filter { $0.hasPrefix("reconnecting:") }.count, 6, "\(l.callbacks)")
        XCTAssertEqual(l.callbacks.filter { $0.hasPrefix("reconnected:") }.count, 6)
        XCTAssertEqual(l.terminalCallbacks, ["error:90006"])
        XCTAssertTrue(l.error?.message.contains("6 reconnects") == true, l.error?.message ?? "")
        XCTAssertEqual(t.ws.connections.count, 7)
        assertWellTerminated(t, l, s)
    }

    func testTotalReconnectCapDefaultIs24() throws {
        let t = makeTestClient()
        t.ws.faultProvider = { _ in .killAfterAudioFrames(1) }
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm.prefix(640 * 2))
        t.executor.advance(600_000)
        XCTAssertEqual(l.callbacks.filter { $0.hasPrefix("reconnecting:") }.count, 24)
        XCTAssertEqual(l.terminalCallbacks, ["error:90006"])
        XCTAssertEqual(t.ws.connections.count, 25)
        assertWellTerminated(t, l, s)
    }

    /// Close codes a retry cannot fix end the session at once with 90005.
    func testProtocolCloseCodesFailAtOnce() throws {
        for code in [1002, 1003, 1007, 1008, 1009, 1010, 4000, 4321, 4999] {
            let t = makeTestClient()
            let l = RecordingListener()
            let s = try session(t, l)
            try s.start()
            t.executor.runUntilIdle()
            stream(s, t, pcm.prefix(640 * 3))
            t.ws.connections[0].emit(.closed(code: code, reason: "policy"))
            t.executor.advance(30_000)
            XCTAssertEqual(l.callbacks, ["connected", "started", "error:90005", "closed:\(code)"], "code \(code)")
            XCTAssertEqual(t.ws.connections.count, 1, "code \(code) must not reconnect")
            XCTAssertFalse(l.error?.retryable ?? true)
            assertWellTerminated(t, l, s)
        }
    }

    /// Other abnormal codes reconnect.
    func testTransientCloseCodesReconnect() throws {
        for code in [1001, 1006, 1011, 1012, 1013, 1014, 3000] {
            let t = makeTestClient()
            let l = RecordingListener()
            let s = try session(t, l)
            try s.start()
            t.executor.runUntilIdle()
            stream(s, t, pcm)
            t.ws.connections[0].emit(.closed(code: code, reason: nil))
            try s.end()
            t.executor.advance(30_000)
            XCTAssertEqual(l.callbacks, ["connected", "started", "reconnecting:1:90001", "reconnected:1:0", "result", "closed:1000"], "code \(code)")
            assertWellTerminated(t, l, s)
        }
    }

    /// One large sendAudio is split into frames of at most 32000 bytes, also when replayed.
    func testLargeChunksAreSplitIntoFrames() throws {
        let t = makeTestClient()
        t.ws.faults = [0: .killAfterAudioFrames(4)]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        let big = tonePCM(seconds: 3.125) // 100000 bytes
        XCTAssertEqual(big.count, 100_000)
        try s.sendAudio(big)
        try s.sendAudio(Data(big.prefix(640)))
        t.executor.runUntilIdle()
        XCTAssertEqual(t.ws.connections[0].frameSizes, [32000, 32000, 32000, 4000, 640])
        try s.end()
        t.executor.advance(10_000)
        XCTAssertEqual(t.ws.connections[1].frameSizes, [32000, 32000, 32000, 4000, 640])
        XCTAssertEqual(t.ws.connections[1].audio, big + big.prefix(640))
        XCTAssertEqual(l.terminalCallbacks, ["result"])
        // Slices with a non-zero start index split correctly.
        let u = makeTestClient()
        let l2 = RecordingListener()
        let s2 = try session(u, l2)
        try s2.start()
        u.executor.runUntilIdle()
        try s2.sendAudio(big.dropFirst(10))
        u.executor.runUntilIdle()
        XCTAssertEqual(u.ws.connections[0].frameSizes, [32000, 32000, 32000, 3990])
        XCTAssertEqual(u.ws.connections[0].audio, Data(big.dropFirst(10)))
        XCTAssertEqual(YuguStreamSession.maxFrameBytes, 32000)
        s2.cancel()
        u.executor.runUntilIdle()
    }

    // MARK: DROP and FAIL

    func testDropPolicyDiscardsAudioWhileReconnecting() throws {
        let t = makeTestClient { $0.audioBufferPolicy = .drop }
        t.ws.faults = [0: .killAfterAudioFrames(5)]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm.prefix(640 * 5))
        XCTAssertEqual(s.state, .reconnecting)
        let lost = tonePCM(seconds: 0.2)
        stream(s, t, lost)
        t.executor.advance(10_000)
        XCTAssertEqual(s.state, .started)
        let after = tonePCM(seconds: 1.2, amplitude: 5000)
        stream(s, t, after)
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "reconnecting:1:90001", "reconnected:1:\(lost.count)", "result", "closed:1000"])
        XCTAssertEqual(t.ws.connections[1].audio, after, "only audio sent after the reconnect")
        assertWellTerminated(t, l, s)
    }

    func testDropPolicyFailsWhenEndAlreadySent() throws {
        let t = makeTestClient { $0.audioBufferPolicy = .drop; $0.resultTimeoutMs = 1000 }
        t.ws.faults = [0: .noResult]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        try s.end()
        t.executor.advance(5000)
        XCTAssertEqual(l.callbacks, ["connected", "started", "error:90007", "closed:1001"])
        XCTAssertTrue(l.error?.retryable == true)
        assertWellTerminated(t, l, s)
    }

    func testFailPolicyDoesNotReconnect() throws {
        let t = makeTestClient { $0.audioBufferPolicy = .fail }
        t.ws.faults = [0: .killAfterAudioFrames(2)]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        XCTAssertEqual(l.callbacks, ["connected", "started", "error:90001", "closed:1006"])
        XCTAssertEqual(t.ws.connections.count, 1)
        assertWellTerminated(t, l, s)
    }

    func testReconnectDisabledOrNoKey() throws {
        for configure in [{ (o: inout YuguClientOptions) in o.reconnect = .disabled }, { (o: inout YuguClientOptions) in o.autoIdempotencyKey = false }] {
            let t = makeTestClient(configure)
            t.ws.faults = [0: .killAfterAudioFrames(2)]
            let l = RecordingListener()
            let s = try session(t, l)
            try s.start()
            t.executor.runUntilIdle()
            stream(s, t, pcm)
            XCTAssertEqual(l.callbacks, ["connected", "started", "error:90001", "closed:1006"])
            assertWellTerminated(t, l, s)
        }
    }

    func testReplayOverflowFailsWith90008() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l, options: StreamOptions(audioPrecheck: .off))
        try s.start()
        t.executor.runUntilIdle()
        let mb = Data(repeating: 1, count: 1024 * 1024)
        for _ in 0..<11 { try s.sendAudio(mb) }
        t.executor.runUntilIdle()
        XCTAssertTrue(t.logger.messages(.warn).contains { $0.contains("10 MB") })
        t.ws.connections[0].emit(.failed(URLError(.networkConnectionLost)))
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "error:90008", "closed:1006"])
        XCTAssertEqual(l.error?.category, .state)
        assertWellTerminated(t, l, s)
    }

    // MARK: Terminal errors

    func testNonRetryableErrorFrameFails() throws {
        let t = makeTestClient()
        t.ws.faults = [0: .errorOnEnd(code: 40001)]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "error:40001", "closed:1000"])
        XCTAssertEqual(l.error?.category, .invalidParam)
        XCTAssertEqual(t.ws.connections[0].closedWith?.code, 1000)
        assertWellTerminated(t, l, s)
    }

    func testErrorFrameWithoutCodeFails() throws {
        let t = makeTestClient()
        t.ws.faults = [0: .errorOnEnd(code: nil)]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "error:0", "closed:1000"])
        XCTAssertEqual(l.error?.message, "no audio")
        assertWellTerminated(t, l, s)
    }

    func testHandshakeRejectedIsNotRetried() throws {
        let t = makeTestClient()
        t.ws.faults = [0: .refuse(status: 403)]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["error:0", "closed:1006"])
        XCTAssertEqual(l.error?.category, .permission)
        XCTAssertEqual(l.error?.httpStatus, 403)
        XCTAssertEqual(t.ws.connections.count, 1)
        assertWellTerminated(t, l, s)
    }

    func testHandshake503IsRetried() throws {
        let t = makeTestClient()
        t.ws.faults = [0: .refuse(status: 503)]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.advance(5000)
        XCTAssertEqual(l.callbacks, ["reconnecting:1:0", "connected", "reconnected:1:0", "started"])
        s.cancel()
        t.executor.runUntilIdle()
        assertWellTerminated(t, l, s, terminal: 0)
    }

    func testNormalCloseBeforeResultFails() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        t.ws.connections[0].emit(.closed(code: 1000, reason: "bye"))
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "error:90005", "closed:1000"])
        XCTAssertTrue(l.error?.message.contains("bye") == true)
        assertWellTerminated(t, l, s)
    }

    func testMalformedFrameFails() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        t.ws.connections[0].emit(.binary(Data("{not json".utf8)))
        t.executor.runUntilIdle()
        XCTAssertEqual(l.error?.code, 90005)
        assertWellTerminated(t, l, s)
    }

    // MARK: Timers: no silent hang (V-04)

    func testHeartbeatTimeoutReconnects() throws {
        let t = makeTestClient { $0.pingIntervalMs = 15_000; $0.pongTimeoutMs = 30_000 }
        t.ws.faults = [0: .noPong]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        t.executor.advance(15_000)
        XCTAssertEqual(t.ws.connections[0].pings, 1)
        t.executor.advance(29_000)
        XCTAssertEqual(s.state, .started)
        t.executor.advance(1_000)
        XCTAssertEqual(s.state, .reconnecting)
        XCTAssertEqual(l.callbacks.last, "reconnecting:1:90002")
        try s.end()
        t.executor.advance(10_000)
        XCTAssertEqual(l.callbacks.suffix(3), ["reconnected:1:0", "result", "closed:1000"])
        XCTAssertEqual(t.ws.connections[1].audio, pcm)
        assertWellTerminated(t, l, s)
    }

    func testPingsAnsweredKeepSessionAlive() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.advance(100_000)
        XCTAssertEqual(s.state, .started)
        XCTAssertGreaterThanOrEqual(t.ws.connections[0].pings, 6)
        s.cancel()
        t.executor.runUntilIdle()
        assertWellTerminated(t, l, s, terminal: 0)
    }

    func testHandshakeTimeoutReconnects() throws {
        let t = makeTestClient { $0.connectTimeoutMs = 10_000 }
        t.ws.faults = [0: .silentHandshake]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.advance(9_999)
        XCTAssertEqual(s.state, .connecting)
        t.executor.advance(1)
        XCTAssertEqual(s.state, .reconnecting)
        t.executor.advance(5_000)
        XCTAssertEqual(s.state, .started)
        XCTAssertEqual(l.callbacks, ["reconnecting:1:90002", "connected", "reconnected:1:0", "started"])
        s.cancel()
        t.executor.runUntilIdle()
        assertWellTerminated(t, l, s, terminal: 0)
    }

    /// The server never answers end: each result timeout reconnects and replays, every cycle is
    /// reported, and the session completes once a server session answers.
    func testSilentServerKeepsRecoveringWithCallbacks() throws {
        let t = makeTestClient { $0.resultTimeoutMs = 2000 }
        t.ws.faults = [0: .noResult, 1: .noResult, 2: .noResult, 3: .noResult, 4: .noResult]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        try s.end()
        t.executor.advance(120_000)
        XCTAssertEqual(l.callbacks.filter { $0 == "reconnecting:1:90007" }.count, 5, "\(l.callbacks)")
        XCTAssertEqual(l.terminalCallbacks, ["result"])
        XCTAssertEqual(t.ws.connections.count, 6)
        XCTAssertEqual(t.ws.connections[5].audio, pcm)
        assertWellTerminated(t, l, s)
    }

    /// Default policy: a 10 s outage (every connect fails) is ridden out and the session completes,
    /// billed once.
    func testDefaultPolicyRidesOutTenSecondOutage() throws {
        let t = makeTestClient()
        XCTAssertEqual(t.client.options.reconnect, .default)
        var outageEnd: Int64 = .max
        let clock = t.executor
        t.ws.faultProvider = { index in
            index > 0 && clock.nowMs() < outageEnd ? .failConnect : nil
        }
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm.prefix(640 * 20))
        outageEnd = t.executor.nowMs() + 10_000
        t.ws.connections[0].emit(.failed(URLError(.notConnectedToInternet)))
        t.executor.runUntilIdle()
        XCTAssertEqual(s.state, .reconnecting)
        stream(s, t, Data(pcm.dropFirst(640 * 20)))
        try s.end()
        t.executor.advance(60_000)
        let attempts = l.callbacks.filter { $0.hasPrefix("reconnecting:") }
        XCTAssertGreaterThanOrEqual(attempts.count, 4, "\(l.callbacks)")
        XCTAssertLessThan(attempts.count, 8)
        XCTAssertEqual(l.terminalCallbacks, ["result"], "\(l.events)")
        let last = try XCTUnwrap(t.ws.connections.last)
        XCTAssertEqual(last.audio, pcm, "the whole round replayed after the outage")
        XCTAssertEqual(t.ws.totalBilled, 1)
        XCTAssertEqual(t.events.entries.filter { $0.hasSuffix(":true") && $0.hasPrefix("reconnect:") }.count, 1)
        assertWellTerminated(t, l, s)
    }

    /// maxAttempts counts consecutive failures: it starts again after a successful reconnect.
    func testFailureCountResetsAfterSuccessfulReconnect() throws {
        let t = makeTestClient { $0.reconnect = ReconnectPolicy(maxAttempts: 2) }
        // Outage 1: connection 0 dies, attempt 1 fails, attempt 2 succeeds.
        // Outage 2: connection 2 dies, attempt 1 fails, attempt 2 succeeds again.
        t.ws.faults = [0: .killAfterAudioFrames(5), 1: .failConnect, 2: .killAfterAudioFrames(40), 3: .failConnect]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm.prefix(640 * 10))
        t.executor.advance(10_000)
        XCTAssertEqual(s.state, .started)
        stream(s, t, Data(pcm.dropFirst(640 * 10)))
        try s.end()
        t.executor.advance(10_000)
        XCTAssertEqual(l.callbacks.filter { $0.hasPrefix("reconnected:") }, ["reconnected:2:0", "reconnected:2:0"], "\(l.callbacks)")
        XCTAssertEqual(l.terminalCallbacks, ["result"])
        XCTAssertEqual(t.ws.connections.last?.audio, pcm)
        assertWellTerminated(t, l, s)
    }

    // MARK: Cancel, close, API misuse

    func testCancelDuringStreamingGivesNoResultOrError() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        s.cancel()
        s.cancel()
        s.close()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "closed:1000"])
        XCTAssertTrue(l.states.contains("state:STARTED->CANCELLED"))
        XCTAssertEqual(t.ws.connections[0].closedWith?.code, 1000)
        XCTAssertEqual(t.ws.totalBilled, 0)
        XCTAssertThrowsError(try s.end())
        assertWellTerminated(t, l, s, terminal: 0)
    }

    func testCancelWhileReconnecting() throws {
        let t = makeTestClient()
        t.ws.faults = [0: .killAfterAudioFrames(2)]
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        XCTAssertEqual(s.state, .reconnecting)
        s.cancel()
        t.executor.advance(60_000)
        XCTAssertEqual(t.ws.connections.count, 1)
        XCTAssertEqual(l.callbacks, ["connected", "started", "reconnecting:1:90001", "closed:1000"])
        assertWellTerminated(t, l, s, terminal: 0)
    }

    func testCancelBeforeStartAndNeverStarted() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        s.cancel()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.events, ["state:IDLE->CANCELLED", "state:CANCELLED->CLOSED", "closed:1000"])
        XCTAssertThrowsError(try s.start())
        let l2 = RecordingListener()
        let s2 = try session(t, l2)
        try s2.start()
        s2.cancel()
        t.executor.runUntilIdle()
        XCTAssertEqual(t.ws.connections.count, 0)
        XCTAssertEqual(l2.callbacks, ["closed:1000"])
    }

    func testMisuseThrowsInvalidState() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        XCTAssertThrowsError(try s.end()) { XCTAssertEqual(($0 as? YuguError)?.code, 90009) }
        try s.start()
        XCTAssertThrowsError(try s.start()) { XCTAssertEqual(($0 as? YuguError)?.code, 90009) }
        t.executor.runUntilIdle()
        try s.sendAudio(Data()) // empty audio is ignored
        try s.end()
        XCTAssertThrowsError(try s.sendAudio(Data([0, 0]))) { XCTAssertEqual(($0 as? YuguError)?.code, 90009) }
        t.executor.runUntilIdle()
        XCTAssertEqual(l.terminalCallbacks.count, 1)
    }

    func testListenerCanBeRemoved() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        s.setListener(nil)
        stream(s, t, pcm)
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started"])
        XCTAssertEqual(s.state, .closed)
    }

    func testListenerReleasedAfterClose() throws {
        let t = makeTestClient()
        weak var weakListener: RecordingListener?
        let s: YuguStreamSession
        do {
            let l = RecordingListener()
            weakListener = l
            s = try session(t, l)
        }
        XCTAssertNotNil(weakListener)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertNil(weakListener)
    }

    // MARK: Precheck and strict audio at end()

    func testPrecheckWarnsAtEnd() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, tonePCM(seconds: 0.5))
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "warning:90101", "result", "closed:1000"])
        XCTAssertEqual(l.result?.localWarnings.map { $0.code }, [90101])
        assertWellTerminated(t, l, s)
    }

    func testPrecheckRejectSendsNoEnd() throws {
        let t = makeTestClient { $0.audioPrecheck = .reject }
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, Data(count: 32000 * 2))
        try s.end()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "error:90103", "closed:1000"])
        XCTAssertFalse(t.ws.connections[0].texts.contains(#"{"cmd":"end"}"#))
        XCTAssertEqual(t.ws.totalBilled, 0)
        assertWellTerminated(t, l, s)
    }

    func testStrictAudioTurnsWarning1001IntoError() throws {
        let t = makeTestClient { $0.strictAudio = true }
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        stream(s, t, pcm)
        t.ws.connections[0].onEvent?(.text(#"{"event":"result","recordId":"eval_w","eof":1,"result":{"overall":0,"warning":[{"code":1001,"message":"No valid audio detected!"}]}}"#))
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "error:1001", "closed:1000"])
        XCTAssertEqual(l.error?.recordId, "eval_w")
        assertWellTerminated(t, l, s)
    }

    // MARK: Client lifecycle

    func testClientCloseCancelsSessions() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        t.client.close()
        t.client.close()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.callbacks, ["connected", "started", "closed:1000"])
        XCTAssertThrowsError(try session(t, RecordingListener())) { XCTAssertEqual(($0 as? YuguError)?.code, 90004) }
        assertWellTerminated(t, l, s, terminal: 0)
    }

    func testSessionStartedAfterClientCloseFails() throws {
        let t = makeTestClient()
        let l = RecordingListener()
        let s = try session(t, l)
        t.client.close()
        try s.start()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.events, ["state:IDLE->FAILED", "error:90004", "state:FAILED->CLOSED", "closed:1000"])
    }

    func testDefaultsPerSessionOverride() {
        let o = YuguClientOptions(auth: .token("t"))
        let s = SessionSettings(StreamOptions(reconnect: .disabled, audioBufferPolicy: .drop, connectTimeoutMs: 1, resultTimeoutMs: 2, pingIntervalMs: 3, pongTimeoutMs: 4, audioPrecheck: .off), o)
        XCTAssertEqual(s.reconnect, .disabled)
        XCTAssertEqual(s.bufferPolicy, .drop)
        XCTAssertEqual([s.connectTimeoutMs, s.resultTimeoutMs, s.pingIntervalMs, s.pongTimeoutMs], [1, 2, 3, 4])
        XCTAssertEqual(s.precheck, .off)
        let d = SessionSettings(StreamOptions(), o)
        XCTAssertEqual(d.reconnect, .default)
        XCTAssertEqual(d.bufferPolicy, .replay)
        XCTAssertEqual([d.connectTimeoutMs, d.resultTimeoutMs, d.pingIntervalMs, d.pongTimeoutMs], [10_000, 300_000, 15_000, 30_000])
        XCTAssertEqual(o.effectiveWsBaseUrl, "wss://open.shengzhiai.com")
        XCTAssertEqual(o.baseUrl, "https://open.shengzhiai.com")
        var p = YuguClientOptions(auth: .token("t"), baseUrl: "http://127.0.0.1:9/")
        XCTAssertEqual(p.effectiveWsBaseUrl, "ws://127.0.0.1:9")
        p.wsBaseUrl = "wss://x.test/"
        XCTAssertEqual(p.effectiveWsBaseUrl, "wss://x.test")
        XCTAssertTrue(SessionState.started.isActive)
        XCTAssertFalse(SessionState.idle.isActive)
        XCTAssertTrue(SessionState.cancelled.isTerminal)
        XCTAssertEqual(SessionState.reconnecting.description, "RECONNECTING")
    }

    func testInvalidWsBaseUrlFails() throws {
        let t = makeTestClient { $0.wsBaseUrl = "https://wrong.scheme" }
        let l = RecordingListener()
        let s = try session(t, l)
        try s.start()
        t.executor.runUntilIdle()
        XCTAssertEqual(l.error?.code, 90010)
        assertWellTerminated(t, l, s)
    }

    /// Real queues: callbacks never overlap, even when the caller's queue is concurrent.
    func testCallbacksSerialOnConcurrentQueue() throws {
        var o = YuguClientOptions(auth: .appKey("ak", secretKey: "sk"), baseUrl: "https://h.test")
        let ws = FakeWSServer()
        o.webSocketTransportFactory = ws.factory()
        o.httpTransport = FakeHTTPTransport()
        o.callbackQueue = DispatchQueue(label: "concurrent", attributes: .concurrent)
        o.logLevel = .off
        let client = YuguClient(options: o)
        final class Slow: YuguStreamListener {
            let l = RecordingListener()
            let done = DispatchSemaphore(value: 0)
            private let lock = NSLock()
            private var active = 0
            private(set) var overlapped = false
            private func guarded(_ body: () -> Void) {
                lock.sync {
                    active += 1
                    if active > 1 { overlapped = true }
                }
                Thread.sleep(forTimeInterval: 0.002)
                body()
                lock.sync { active -= 1 }
            }
            func onStateChanged(_ s: YuguStreamSession, from: SessionState, to: SessionState) { guarded { l.onStateChanged(s, from: from, to: to) } }
            func onConnected(_ s: YuguStreamSession) { guarded {} }
            func onStarted(_ s: YuguStreamSession) { guarded {} }
            func onResult(_ s: YuguStreamSession, result: EvalResult) { guarded { l.onResult(s, result: result) } }
            func onError(_ s: YuguStreamSession, error: YuguError) { guarded { l.onError(s, error: error) } }
            func onClosed(_ s: YuguStreamSession, code: Int, reason: String) {
                guarded { l.onClosed(s, code: code, reason: reason) }
                done.signal()
            }
        }
        let slow = Slow()
        let s = try client.streamEvaluate(config: config, listener: slow)
        try s.start()
        for f in frames(pcm) { try s.sendAudio(f) }
        try s.end()
        XCTAssertEqual(slow.done.wait(timeout: .now() + 10), .success)
        XCTAssertFalse(slow.overlapped)
        XCTAssertEqual(slow.l.terminalCallbacks, ["result"])
        XCTAssertEqual(slow.l.states.last, "state:COMPLETED->CLOSED")
        for c in slow.l.stateChecks { XCTAssertEqual(c.seen, c.announced) }
        client.close()
    }
}
