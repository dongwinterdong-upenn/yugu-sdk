import XCTest
@testable import YuguCore

/// Requirement B-05: explicit release, idempotent close, calls after close.
final class LifecycleTests: XCTestCase {
    let config = EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好")
    let wav = Fixture.data("audio/zh_short.wav")

    func testCloseIsIdempotentAndBlocksNewCalls() {
        let t = makeTestClient()
        XCTAssertFalse(t.client.isClosed)
        t.client.close()
        t.client.close()
        XCTAssertTrue(t.client.isClosed)
        var errors: [Int] = []
        t.client.evaluate(audio: .data(wav), config: config) { if case .failure(let e) = $0 { errors.append(e.code) } }
        t.client.evaluateCompat(coreType: .sentEvalCn, audio: .data(wav), params: CompatParams(refText: "x")) { if case .failure(let e) = $0 { errors.append(e.code) } }
        t.client.tts(TTSRequest(text: "x")) { if case .failure(let e) = $0 { errors.append(e.code) } }
        t.client.getReport(recordId: "eval_1") { if case .failure(let e) = $0 { errors.append(e.code) } }
        t.executor.runUntilIdle()
        XCTAssertEqual(errors, [90004, 90004, 90004, 90004])
        XCTAssertEqual(t.http.requestCount, 0)
        XCTAssertThrowsError(try t.client.streamEvaluateCompat(coreType: .sentEvalCn, params: CompatParams(refText: "x"), listener: RecordingListener())) {
            XCTAssertEqual(($0 as? YuguError)?.code, 90004)
            XCTAssertTrue(($0 as? YuguError)?.isIllegalState == true)
        }
    }

    func testAsyncCallAfterCloseThrows() async {
        let t = makeTestClient()
        t.client.close()
        do {
            _ = try await t.client.tts(TTSRequest(text: "x"))
            XCTFail()
        } catch let e as YuguError {
            XCTAssertEqual(e.code, 90004)
        } catch {
            XCTFail("\(error)")
        }
    }

    func testCloseAbortsInFlightCalls() {
        let t = makeTestClient()
        t.http.defaultReply = .hang
        var got: Result<EvalResult, YuguError>?
        t.client.evaluate(audio: .data(wav), config: config) { got = $0 }
        t.executor.runUntilIdle()
        XCTAssertEqual(t.client.core.activeCount.calls, 1)
        t.client.close()
        t.executor.runUntilIdle()
        guard case .failure(let e)? = got else { return XCTFail() }
        XCTAssertEqual(e.code, 90004)
        XCTAssertNotNil(e.idempotencyKey)
        XCTAssertEqual(t.client.core.activeCount.calls, 0)
        XCTAssertFalse(t.http.closed, "a caller supplied transport is not closed by the client")
    }

    func testCallStartedBeforeCloseButQueuedFails() {
        let t = makeTestClient()
        var got: Result<EvalResult, YuguError>?
        t.client.evaluate(audio: .data(wav), config: config) { got = $0 }
        t.client.close()
        t.executor.runUntilIdle()
        if case .failure(let e)? = got { XCTAssertEqual(e.code, 90004) } else { XCTFail() }
        XCTAssertEqual(t.http.requestCount, 0)
    }

    /// 100 create, use, release cycles leave nothing behind (B-05 acceptance).
    func testHundredCyclesReleaseEverything() throws {
        var weakClients: [() -> AnyObject?] = []
        var weakSessions: [() -> AnyObject?] = []
        for _ in 0..<100 {
            try autoreleasepoolCompat {
                let t = makeTestClient()
                t.http.defaultReply = .status(200, Fixture.text("platform/native_evaluate_sentence_zh.json"), [:])
                let l = RecordingListener()
                let s = try t.client.streamEvaluate(config: config, listener: l)
                try s.start()
                t.executor.runUntilIdle()
                for f in frames(tonePCM(seconds: 1.1)) { try s.sendAudio(f) }
                try s.end()
                var done = false
                t.client.evaluate(audio: .data(wav), config: config) { _ in done = true }
                t.executor.runUntilIdle()
                XCTAssertTrue(done)
                XCTAssertEqual(l.terminalCallbacks, ["result"])
                t.client.close()
                t.executor.runUntilIdle()
                weak var wc = t.client
                weak var ws = s
                weakClients.append { wc }
                weakSessions.append { ws }
            }
        }
        XCTAssertEqual(weakClients.compactMap { $0() }.count, 0, "clients leaked")
        XCTAssertEqual(weakSessions.compactMap { $0() }.count, 0, "sessions leaked")
    }

    func testDefaultTransportIsClosedWithClient() {
        var o = YuguClientOptions(auth: .token("t"), baseUrl: "http://127.0.0.1:9")
        o.logLevel = .off
        let client = YuguClient(options: o)
        XCTAssertTrue(client.core.ownsHTTP)
        client.close()
        XCTAssertTrue(client.isClosed)
    }

    func testCancellationTokenRegistry() {
        let token = YuguCancellationToken()
        var fired = 0
        let id = token.onCancel { fired += 1 }
        XCTAssertNotNil(id)
        token.removeHandler(id)
        let id2 = token.onCancel { fired += 10 }
        token.cancel()
        token.cancel()
        XCTAssertEqual(fired, 10)
        XCTAssertNil(token.onCancel { fired += 100 })
        XCTAssertEqual(fired, 110)
        token.removeHandler(id2)
    }
}

/// `autoreleasepool` exists only on Apple platforms.
func autoreleasepoolCompat<T>(_ body: () throws -> T) rethrows -> T {
    #if canImport(ObjectiveC)
    return try autoreleasepool { try body() }
    #else
    return try body()
    #endif
}
