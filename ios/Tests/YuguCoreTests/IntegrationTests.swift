import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import YuguCore

/// Integration tests against the Node mock platform (tools/mock-server), real URLSession
/// transport, real queues. Acceptance 6.2 (V-02) and 6.3 (V-03).
final class IntegrationTests: XCTestCase {
    static var server: MockServer?
    static var launchError: Error?

    override class func setUp() {
        super.setUp()
        do {
            server = try MockServer.launch(processingMs: 50)
        } catch {
            launchError = error
        }
    }

    override class func tearDown() {
        server?.stop()
        server = nil
        super.tearDown()
    }

    private var server: MockServer!
    private var logger: TestLogger!
    private var events: RecordingEvents!

    override func setUpWithError() throws {
        if let e = IntegrationTests.launchError { throw e }
        guard let s = IntegrationTests.server else {
            throw XCTSkip("node or tools/mock-server/server.mjs not found; set YUGU_MOCK_SERVER and YUGU_NODE")
        }
        server = s
        server.reset()
        logger = TestLogger()
        events = RecordingEvents()
    }

    private func client(_ configure: (inout YuguClientOptions) -> Void = { _ in }) -> YuguClient {
        var o = YuguClientOptions(auth: .appKey(MockServer.appKey, secretKey: MockServer.secretKey), baseUrl: server.baseUrl)
        o.logger = logger
        o.logLevel = .debug
        o.eventListener = events
        o.callbackQueue = DispatchQueue(label: "integration.callbacks")
        configure(&o)
        return YuguClient(options: o)
    }

    let zh = EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好", language: "zh-CN")
    var wav: AudioInput { .file(Fixture.dir.appendingPathComponent("audio/zh_short.wav")) }

    private func evaluateRequests() -> [JSONValue] {
        server.log().filter { $0["path"]?.stringValue == "/api/v1/evaluate" }
    }

    // MARK: Main flows

    func testNativeEvaluateSignedConfigPart() async throws {
        let c = client()
        defer { c.close() }
        let r = try await c.evaluate(audio: wav, config: zh)
        XCTAssertEqual(r.overall, 93.7)
        XCTAssertNotNil(r.recordId)
        XCTAssertEqual(r.attempts, 1)
        XCTAssertFalse(r.replayed)
        let log = evaluateRequests()
        XCTAssertEqual(log.count, 1)
        XCTAssertEqual(log[0]["idempotencyKey"]?.stringValue, r.idempotencyKey)
        XCTAssertEqual(log[0]["userAgent"]?.stringValue, "yugu-ios-sdk/2.0.0")
        XCTAssertEqual(log[0]["config"]?["referenceText"]?.stringValue, "今天天气很好")
    }

    /// The mock answers the fixture of each mode; the parsed totals follow the mode.
    func testEveryNativeModeParsesAgainstMock() async throws {
        let c = client()
        defer { c.close() }
        let cases: [(EvaluateConfig, String, Double)] = [
            (EvaluateConfig(coreType: .word, referenceText: "apple", language: "en-US"), "word", 65),
            (EvaluateConfig(coreType: .sentence, referenceText: "The quick brown fox jumps over the lazy dog.", language: "en-US"), "sentence", 93.9),
            (EvaluateConfig(coreType: .passage, referenceText: "今天天气很好。我们一起去公园散步。", language: "zh-CN", paragraphNeedWordScore: 1), "passage", 89.1),
            (EvaluateConfig(coreType: .connected, referenceText: "I want to eat an apple", language: "en-US"), "connected", 60),
            (EvaluateConfig(coreType: .open, referenceText: "介绍一下自己", language: "zh-CN", taskType: "free"), "open", 92),
            (EvaluateConfig(coreType: .alpha, referenceText: "A B C", language: "en-US"), "alpha", 47),
            (EvaluateConfig(coreType: .pinyin, referenceText: "今天天气很好", language: "zh-CN", refPinyin: "jin1 tian1 tian1 qi4 hen3 hao3"), "sentence", 88.9),
        ]
        for (config, coreType, overall) in cases {
            let r = try await c.evaluate(audio: wav, config: config)
            XCTAssertEqual(r.coreType, coreType, config.coreType.rawValue)
            XCTAssertEqual(r.overall, overall, config.coreType.rawValue)
        }
        let connected = try await c.evaluate(audio: wav, config: cases[3].0)
        XCTAssertEqual(connected.connected?.boundaries.count, 4)
        let open = try await c.evaluate(audio: wav, config: cases[4].0)
        XCTAssertEqual(open.openTask?.content?.overall, 98)
    }

    func testTokenAuth() async throws {
        let c = client { $0.auth = .token(MockServer.token) }
        defer { c.close() }
        let r = try await c.evaluate(audio: wav, config: zh)
        XCTAssertEqual(r.overall, 93.7)
    }

    func testCompatEvaluate() async throws {
        let c = client()
        defer { c.close() }
        let r = try await c.evaluateCompat(coreType: .sentEvalCn, audio: wav, params: CompatParams(refText: "今天天气很好", language: "zh-CN"))
        XCTAssertEqual(r.eof, 1)
        XCTAssertEqual(r.overall, 94.6)
        let word = try await c.evaluateCompat(coreType: .wordEval, audio: .file(Fixture.dir.appendingPathComponent("audio/en_apple.wav")), params: CompatParams(refText: "apple"))
        XCTAssertEqual(word.overall, 65)
        let entry = server.log().first { $0["path"]?.stringValue == "/sent.eval.cn" }
        XCTAssertEqual(entry?["fields"]?["refText"]?.stringValue, "今天天气很好")
        XCTAssertNotNil(entry?["idempotencyKey"]?.stringValue)
    }

    func testTTSAndReport() async throws {
        let c = client()
        defer { c.close() }
        let t = try await c.tts(TTSRequest(text: "你好世界", language: "zh-CN", voice: "xiaoyan", format: "mp3", speed: 50, pitch: 50, volume: 50))
        XCTAssertTrue(t.absoluteAudioUrl?.hasPrefix(server.baseUrl + "/tts/audio/mock-") == true, t.absoluteAudioUrl ?? "")
        XCTAssertEqual(t.duration, 1.348)
        let report = try await c.getReport(recordId: "eval_abc123")
        XCTAssertEqual(report.data["overall"]?.doubleValue, 93.7)
    }

    func testWrongSecretIsAuthAndNotRetried() async throws {
        let c = client { $0.auth = .appKey(MockServer.appKey, secretKey: "wrong") }
        defer { c.close() }
        do {
            _ = try await c.evaluate(audio: wav, config: zh)
            XCTFail()
        } catch let e as YuguError {
            XCTAssertEqual(e.code, 2003)
            XCTAssertEqual(e.httpStatus, 401)
            XCTAssertTrue(e.isAuthError)
            XCTAssertEqual(e.attempts, 1)
        }
        XCTAssertEqual(evaluateRequests().count, 1)
        let unknown = client { $0.auth = .appKey("nobody", secretKey: "x") }
        defer { unknown.close() }
        do {
            _ = try await unknown.getReport(recordId: "eval_1")
            XCTFail()
        } catch let e as YuguError {
            XCTAssertEqual(e.code, 2010)
            XCTAssertTrue(e.isAuthError)
        }
    }

    // MARK: V-02 idempotency and billing

    /// Acceptance 6.2: the same audio submitted 3 times with one key, one submission hits an
    /// injected read timeout and is retried inside the SDK. Billing must be 1.
    func testV02SameKeyThreeSubmissionsBillOnce() async throws {
        let c = client { $0.readTimeoutMs = 800 }
        defer { c.close() }
        let key = "v02-" + IdempotencyKey.generate()
        server.queueFaults([("/api/v1/evaluate", "delay:1500")])
        let first = try await c.evaluate(audio: wav, config: zh, options: RequestOptions(idempotencyKey: key))
        XCTAssertEqual(first.attempts, 2, "the read timeout was retried")
        let second = try await c.evaluate(audio: wav, config: zh, options: RequestOptions(idempotencyKey: key))
        let third = try await c.evaluate(audio: wav, config: zh, options: RequestOptions(idempotencyKey: key))
        XCTAssertEqual(second.recordId, first.recordId)
        XCTAssertEqual(third.recordId, first.recordId)
        XCTAssertTrue(second.replayed)
        XCTAssertTrue(third.replayed)
        // Wait for the abandoned first attempt to finish on the server, then count.
        try await Task.sleep(nanoseconds: 1_200_000_000)
        let billing = server.billing()
        XCTAssertEqual(billing["billed"]?.intValue, 1, "\(billing)")
        XCTAssertEqual(billing["byKey"]?[key]?.intValue, 1)
        let keys = evaluateRequests().compactMap { $0["idempotencyKey"]?.stringValue }
        XCTAssertEqual(keys, [key, key, key, key])
        XCTAssertTrue(events.entries.contains { $0.hasPrefix("retry:evaluate:1:") && $0.hasSuffix(":90002") })
    }

    /// V-02 with a slow server: the first attempt registers the key and processes slowly, the
    /// SDK times out and retries with the same key, the platform waits for the first result and
    /// replays it. Billing stays 1.
    func testV02SlowServerRetryGetsReplay() async throws {
        let c = client { $0.readTimeoutMs = 900 }
        defer { c.close() }
        let key = "v02slow-" + IdempotencyKey.generate()
        server.queueFaults([("/api/v1/evaluate", "slow:1200")])
        let first = try await c.evaluate(audio: wav, config: zh, options: RequestOptions(idempotencyKey: key))
        XCTAssertEqual(first.attempts, 2)
        XCTAssertTrue(first.replayed, "the retry received the stored result of the slow first attempt")
        let second = try await c.evaluate(audio: wav, config: zh, options: RequestOptions(idempotencyKey: key))
        let third = try await c.evaluate(audio: wav, config: zh, options: RequestOptions(idempotencyKey: key))
        XCTAssertEqual(Set([first.recordId, second.recordId, third.recordId]).count, 1)
        let billing = server.billing()
        XCTAssertEqual(billing["billed"]?.intValue, 1, "\(billing)")
        XCTAssertEqual(billing["byKey"]?[key]?.intValue, 1)
        XCTAssertEqual(evaluateRequests().compactMap { $0["idempotencyKey"]?.stringValue }, [key, key, key, key])
    }

    /// Every attempt carries a fresh nonce: the platform rejects a reused X-Nonce within 300 s.
    func testFreshNoncePerAttempt() async throws {
        let c = client()
        defer { c.close() }
        server.queueFaults([("/api/v1/evaluate", "status:503"), ("/api/v1/evaluate", "status:503")])
        let r = try await c.evaluate(audio: wav, config: zh)
        XCTAssertEqual(r.attempts, 3)
        for _ in 0..<3 {
            _ = try await c.tts(TTSRequest(text: "你好"))
        }
    }

    // MARK: V-03 retry policy

    private func timestamps(_ entries: [JSONValue]) -> [Double] {
        entries.compactMap { $0["t"]?.doubleValue }
    }

    /// (a) HTTP 500 retried with the same key and the policy's backoff.
    func testV03aServerErrorRetried() async throws {
        let c = client()
        defer { c.close() }
        server.queueFaults([("/api/v1/evaluate", "status:500"), ("/api/v1/evaluate", "status:500")])
        let r = try await c.evaluate(audio: wav, config: zh)
        XCTAssertEqual(r.attempts, 3)
        let entries = evaluateRequests()
        XCTAssertEqual(entries.count, 3)
        XCTAssertEqual(Set(entries.compactMap { $0["idempotencyKey"]?.stringValue }), [r.idempotencyKey!])
        let t = timestamps(entries)
        XCTAssertGreaterThanOrEqual(t[1] - t[0], 140, "first backoff about 200 ms")
        XCTAssertGreaterThanOrEqual(t[2] - t[1], 280, "second backoff about 400 ms")
        XCTAssertEqual(server.billing()["billed"]?.intValue, 1)
    }

    /// (b) read timeout retried with the same key.
    func testV03bReadTimeoutRetried() async throws {
        let c = client { $0.readTimeoutMs = 700 }
        defer { c.close() }
        server.queueFaults([("/api/v1/evaluate", "hang")])
        let r = try await c.evaluate(audio: wav, config: zh)
        XCTAssertEqual(r.attempts, 2)
        let entries = evaluateRequests()
        XCTAssertEqual(entries.count, 2)
        XCTAssertEqual(entries[0]["idempotencyKey"], entries[1]["idempotencyKey"])
    }

    /// (c) HTTP 429 retried after Retry-After with the same key.
    func testV03cRateLimitRetriedAfterRetryAfter() async throws {
        let c = client()
        defer { c.close() }
        server.queueFaults([("/api/v1/evaluate", "status:429:code=42900:retryAfter=1")])
        let r = try await c.evaluate(audio: wav, config: zh)
        XCTAssertEqual(r.attempts, 2)
        let entries = evaluateRequests()
        XCTAssertEqual(entries[0]["idempotencyKey"], entries[1]["idempotencyKey"])
        let t = timestamps(entries)
        XCTAssertGreaterThanOrEqual(t[1] - t[0], 1000, "Retry-After 1 s honoured")
        XCTAssertTrue(events.entries.contains("retry:evaluate:1:1000:42900"))
    }

    /// (d) HTTP 400 is not retried and raises INVALID_PARAM at once.
    func testV03dBadRequestNotRetried() async throws {
        let c = client()
        defer { c.close() }
        server.queueFaults([("/api/v1/evaluate", "status:400:code=40001")])
        do {
            _ = try await c.evaluate(audio: wav, config: zh)
            XCTFail()
        } catch let e as YuguError {
            XCTAssertEqual(e.code, 40001)
            XCTAssertTrue(e.isInvalidParameter)
            XCTAssertEqual(e.attempts, 1)
            XCTAssertNotNil(e.idempotencyKey)
        }
        XCTAssertEqual(evaluateRequests().count, 1)
        XCTAssertTrue(events.entries.filter { $0.hasPrefix("retry:") }.isEmpty)
    }

    func testDroppedConnectionRetried() async throws {
        let c = client()
        defer { c.close() }
        server.queueFaults([("/api/v1/evaluate", "drop")])
        let r = try await c.evaluate(audio: wav, config: zh)
        XCTAssertEqual(r.attempts, 2)
    }

    func testRetriesExhausted() async throws {
        let c = client()
        defer { c.close() }
        server.queueFaults([("/api/v1/evaluate", "status:502:detail=bad%20gateway"), ("/api/v1/evaluate", "status:503"), ("/api/v1/evaluate", "status:500")])
        do {
            _ = try await c.evaluate(audio: wav, config: zh)
            XCTFail()
        } catch let e as YuguError {
            XCTAssertEqual(e.attempts, 3)
            XCTAssertEqual(e.httpStatus, 500)
            XCTAssertEqual(e.code, 50000)
            XCTAssertTrue(e.retryable)
        }
        XCTAssertEqual(server.billing()["billed"]?.intValue, 0)
    }

    func testCompletionHandlerOnCallbackQueue() throws {
        let queue = DispatchQueue(label: "my.queue")
        let key = DispatchSpecificKey<Int>()
        queue.setSpecific(key: key, value: 7)
        let c = client { $0.callbackQueue = queue }
        defer { c.close() }
        let done = expectation(description: "completion")
        c.evaluate(audio: wav, config: zh) { result in
            XCTAssertEqual(DispatchQueue.getSpecific(key: key), 7)
            XCTAssertEqual(try? result.get().overall, 93.7)
            done.fulfill()
        }
        wait(for: [done], timeout: 20)
    }

    func testConnectTimeoutToBlackhole() async throws {
        // 192.0.2.1 is TEST-NET-1 (RFC 5737), never assigned: the connect phase never completes,
        // or the network reports it unreachable at once.
        var o = YuguClientOptions(auth: .token("t"), baseUrl: "http://192.0.2.1:9")
        o.connectTimeoutMs = 300
        o.retry = .disabled
        o.logLevel = .off
        let c = YuguClient(options: o)
        defer { c.close() }
        let start = Date()
        do {
            _ = try await c.evaluate(audio: wav, config: zh)
            XCTFail()
        } catch let e as YuguError {
            XCTAssertTrue(e.isTimeout || e.isNetworkError, "\(e)")
        }
        XCTAssertLessThan(Date().timeIntervalSince(start), 10)
    }

    // MARK: WebSocket with the URLSession transport

    /// On Linux the URLSession of swift-corelibs-foundation 6.0.3 has no WebSocket support with
    /// the system libcurl: the session must fail fast and cleanly. On Apple platforms the same
    /// test runs the full session against the mock server.
    func testWebSocketWithURLSessionTransport() throws {
        let c = client()
        defer { c.close() }
        let l = RecordingListener()
        let closed = expectation(description: "closed")
        final class Waiter: YuguStreamListener {
            let inner: RecordingListener
            let done: XCTestExpectation
            init(_ l: RecordingListener, _ d: XCTestExpectation) { inner = l; done = d }
            func onStateChanged(_ s: YuguStreamSession, from: SessionState, to: SessionState) { inner.onStateChanged(s, from: from, to: to) }
            func onConnected(_ s: YuguStreamSession) { inner.onConnected(s) }
            func onStarted(_ s: YuguStreamSession) { inner.onStarted(s) }
            func onReconnecting(_ s: YuguStreamSession, attempt: Int, delayMs: Int, cause: YuguError) { inner.onReconnecting(s, attempt: attempt, delayMs: delayMs, cause: cause) }
            func onReconnected(_ s: YuguStreamSession, attempt: Int, droppedBytes: Int) { inner.onReconnected(s, attempt: attempt, droppedBytes: droppedBytes) }
            func onResult(_ s: YuguStreamSession, result: EvalResult) { inner.onResult(s, result: result) }
            func onError(_ s: YuguStreamSession, error: YuguError) { inner.onError(s, error: error) }
            func onClosed(_ s: YuguStreamSession, code: Int, reason: String) { inner.onClosed(s, code: code, reason: reason); done.fulfill() }
        }
        let s = try c.streamEvaluate(config: zh, listener: Waiter(l, closed))
        try s.start()
        let pcm = Fixture.pcm("audio/zh_short.wav")
        for f in frames(pcm) { try? s.sendAudio(f) }
        try? s.end()
        wait(for: [closed], timeout: 30)
        XCTAssertEqual(l.terminalCallbacks.count, 1)
        XCTAssertEqual(l.callbacks.last?.hasPrefix("closed:"), true)
        #if canImport(FoundationNetworking)
        XCTAssertEqual(l.error?.code, 90010, "\(l.events)")
        XCTAssertTrue(l.error?.message.contains("WebSocket") == true)
        XCTAssertFalse(l.callbacks.contains { $0.hasPrefix("reconnecting") }, "a platform limitation is not retried")
        #else
        XCTAssertEqual(l.result?.overall, 93.7, "\(l.events)")
        XCTAssertEqual(server.billing()["billed"]?.intValue, 1)
        #endif
    }
}
