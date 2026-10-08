import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import YuguCore

/// Requirements A-01 and A-02: retry decisions, backoff math, idempotency key reuse.
final class RetryTests: XCTestCase {
    let ok = Fixture.text("platform/native_evaluate_sentence_zh.json")
    let config = EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好", language: "zh-CN")
    let wav = Fixture.data("audio/zh_short.wav")

    private func err(_ status: Int, _ code: Int, retryAfter: String? = nil) -> FakeHTTPTransport.Reply {
        var h: [String: String] = [:]
        if let r = retryAfter { h["Retry-After"] = r }
        return .status(status, #"{"code":\#(code),"message":"m","timestamp":1}"#, h)
    }

    private func evaluate(_ t: TestClient, options: RequestOptions = RequestOptions(), advance: Int64 = 600_000) -> Result<EvalResult, YuguError>? {
        var got: Result<EvalResult, YuguError>?
        t.client.evaluate(audio: .data(wav), config: config, options: options) { got = $0 }
        t.executor.advance(advance)
        return got
    }

    // MARK: Backoff math

    func testDefaultPolicyValues() {
        let p = RetryPolicy.default
        XCTAssertEqual(p.maxRetries, 2)
        XCTAssertEqual(p.initialDelayMs, 200)
        XCTAssertEqual(p.multiplier, 2.0)
        XCTAssertEqual(p.maxDelayMs, 4000)
        XCTAssertEqual(p.jitter, 0.3)
        XCTAssertTrue(p.respectRetryAfter)
        XCTAssertEqual(p.maxRetryAfterMs, 30000)
        let r = ReconnectPolicy.default
        XCTAssertEqual([r.maxAttempts, r.initialDelayMs, r.maxDelayMs], [8, 500, 4000])
        // 0.5, 1, 2, 4, 4, 4, 4, 4 s: about 23 s, longer than a 10 s network drop.
        let waits = (1...8).map { r.delayMs(attempt: $0, unit: 0.5) }
        XCTAssertEqual(waits, [500, 1000, 2000, 4000, 4000, 4000, 4000, 4000])
        XCTAssertEqual(waits.reduce(0, +), 23_500)
        XCTAssertTrue(r.enabled)
        XCTAssertFalse(ReconnectPolicy.disabled.enabled)
        XCTAssertEqual(RetryPolicy.disabled.maxRetries, 0)
    }

    func testBaseDelaysAndCap() {
        let p = RetryPolicy.default
        XCTAssertEqual((1...7).map { p.baseDelayMs(retry: $0) }, [200, 400, 800, 1600, 3200, 4000, 4000])
        // unit 0.5 means no jitter, 0 the lower bound, almost 1 the upper bound.
        XCTAssertEqual(p.delayMs(retry: 1, unit: 0.5), 200)
        XCTAssertEqual(p.delayMs(retry: 1, unit: 0), 140)
        XCTAssertEqual(p.delayMs(retry: 1, unit: 0.999_999), 260)
        XCTAssertEqual(p.delayMs(retry: 2, unit: 0), 280)
        XCTAssertEqual(p.delayMs(retry: 2, unit: 0.999_999), 520)
        XCTAssertEqual(ReconnectPolicy.default.delayMs(attempt: 1, unit: 0.5), 500)
        XCTAssertEqual(ReconnectPolicy.default.delayMs(attempt: 3, unit: 0.5), 2000)
        XCTAssertEqual(ReconnectPolicy.default.delayMs(attempt: 9, unit: 0.5), 4000)
    }

    func testSeededJitterIsDeterministicAndBounded() {
        let p = RetryPolicy.default
        let a = SeededRandom(seed: 2026), b = SeededRandom(seed: 2026)
        let da = (1...200).map { p.delayMs(retry: ($0 % 2) + 1, unit: a.nextUnit()) }
        let db = (1...200).map { p.delayMs(retry: ($0 % 2) + 1, unit: b.nextUnit()) }
        XCTAssertEqual(da, db)
        for (i, d) in da.enumerated() {
            let base = (i + 1) % 2 + 1 == 1 ? 200.0 : 400.0
            XCTAssertGreaterThanOrEqual(Double(d), base * 0.7 - 0.5)
            XCTAssertLessThanOrEqual(Double(d), base * 1.3 + 0.5)
        }
        XCTAssertGreaterThan(Set(da).count, 50)
    }

    func testRetryAfterRules() {
        let p = RetryPolicy.default
        XCTAssertEqual(p.delayMs(retry: 1, unit: 0.5, retryAfterMs: 1000), 1000)
        XCTAssertEqual(p.delayMs(retry: 1, unit: 0.5, retryAfterMs: 100), 200)
        XCTAssertEqual(p.delayMs(retry: 1, unit: 0.5, retryAfterMs: 60_000), 30_000)
        var q = p
        q.respectRetryAfter = false
        XCTAssertEqual(q.delayMs(retry: 1, unit: 0.5, retryAfterMs: 1000), 200)
    }

    // MARK: Retry loop

    /// Acceptance 6.3 (a): HTTP 500 is retried with the same key and the backoff of the policy.
    func testServerErrorsRetriedWithSameKey() throws {
        let t = makeTestClient()
        t.http.replies = [err(500, 50000), err(503, 50200)]
        t.http.defaultReply = .status(200, ok, [:])
        let r = try XCTUnwrap(evaluate(t)).get()
        XCTAssertEqual(r.attempts, 3)
        XCTAssertEqual(t.http.requests.count, 3)
        let keys = Set(t.http.requests.compactMap { $0.header("Idempotency-Key") })
        XCTAssertEqual(keys.count, 1)
        XCTAssertEqual(keys.first, r.idempotencyKey)
        // Nonce and signature are fresh per attempt, the body is identical.
        XCTAssertEqual(Set(t.http.requests.compactMap { $0.header("X-Nonce") }).count, 3)
        XCTAssertEqual(Set(t.http.requests.compactMap { $0.body }).count, 1)
        let retries = t.events.entries.filter { $0.hasPrefix("retry:") }
        XCTAssertEqual(retries.count, 2)
        let delays = retries.map { Int($0.split(separator: ":")[3])! }
        XCTAssertTrue((140...260).contains(delays[0]), "\(delays)")
        XCTAssertTrue((280...520).contains(delays[1]), "\(delays)")
        XCTAssertEqual(t.events.entries.filter { $0.hasPrefix("start:") }, ["start:evaluate:1", "start:evaluate:2", "start:evaluate:3"])
        XCTAssertEqual(t.events.entries.last, "end:evaluate:200:3:0")
        let warns = t.logger.messages(.warn)
        XCTAssertTrue(warns.contains { $0.hasPrefix("retry 1/2 in ") && $0.hasSuffix(" ms: HTTP 500 code=50000") }, "\(warns)")
        XCTAssertTrue(warns.contains { $0.hasPrefix("retry 2/2 in ") && $0.hasSuffix(" ms: HTTP 503 code=50200") }, "\(warns)")
    }

    /// The same seed gives the same delays (deterministic random in tests).
    func testRetryDelaysReproducibleWithSeed() throws {
        func run() -> [String] {
            let t = makeTestClient()
            t.http.replies = [err(500, 50000), err(500, 50000)]
            t.http.defaultReply = .status(200, ok, [:])
            _ = evaluate(t)
            return t.events.entries.filter { $0.hasPrefix("retry:") }
        }
        XCTAssertEqual(run(), run())
    }

    /// Acceptance 6.3 (d): HTTP 400 is not retried and maps to INVALID_PARAM.
    func testBadRequestNotRetried() throws {
        let t = makeTestClient()
        t.http.replies = [err(400, 40001)]
        guard case .failure(let e)? = evaluate(t) else { return XCTFail() }
        XCTAssertEqual(t.http.requests.count, 1)
        XCTAssertEqual(e.code, 40001)
        XCTAssertEqual(e.category, .invalidParam)
        XCTAssertTrue(e.isInvalidParameter)
        XCTAssertEqual(e.attempts, 1)
        XCTAssertFalse(e.retryable)
        XCTAssertNotNil(e.idempotencyKey)
        XCTAssertTrue(t.events.entries.filter { $0.hasPrefix("retry:") }.isEmpty)
    }

    func testAuthErrorNotRetried() {
        let t = makeTestClient()
        t.http.replies = [err(401, 2003)]
        guard case .failure(let e)? = evaluate(t) else { return XCTFail() }
        XCTAssertEqual(t.http.requests.count, 1)
        XCTAssertTrue(e.isAuthError)
    }

    /// Acceptance 6.3 (c): 429 waits at least Retry-After.
    func testRateLimitHonoursRetryAfter() throws {
        let t = makeTestClient()
        t.http.replies = [err(429, 42900, retryAfter: "3")]
        t.http.defaultReply = .status(200, ok, [:])
        let r = try XCTUnwrap(evaluate(t)).get()
        XCTAssertEqual(r.attempts, 2)
        XCTAssertEqual(t.events.entries.first { $0.hasPrefix("retry:") }, "retry:evaluate:1:3000:42900")
    }

    /// Acceptance 6.3 (b): a read timeout is retried with the same key.
    func testReadTimeoutRetried() throws {
        let t = makeTestClient { $0.readTimeoutMs = 1000 }
        t.http.replies = [.hang]
        t.http.defaultReply = .status(200, ok, [:])
        var got: Result<EvalResult, YuguError>?
        t.client.evaluate(audio: .data(wav), config: config) { got = $0 }
        t.executor.advance(999)
        XCTAssertNil(got)
        XCTAssertEqual(t.http.requests.count, 1)
        t.executor.advance(5000)
        let r = try XCTUnwrap(got).get()
        XCTAssertEqual(r.attempts, 2)
        XCTAssertEqual(t.http.requests[0].header("Idempotency-Key"), t.http.requests[1].header("Idempotency-Key"))
        XCTAssertTrue(t.events.entries.contains { $0.hasPrefix("retry:evaluate:1:") && $0.hasSuffix(":90002") })
        XCTAssertEqual(t.http.requests[0].readTimeoutMs, 1000)
    }

    func testTimeoutsExhaustRetries() {
        let t = makeTestClient { $0.readTimeoutMs = 1000 }
        t.http.defaultReply = .hang
        guard case .failure(let e)? = evaluate(t) else { return XCTFail() }
        XCTAssertEqual(e.code, 90002)
        XCTAssertTrue(e.isTimeout)
        XCTAssertEqual(e.attempts, 3)
        XCTAssertEqual(t.http.requests.count, 3)
    }

    func testNetworkErrorsRetried() throws {
        let t = makeTestClient()
        t.http.replies = [.error(URLError(.networkConnectionLost)), .error(URLError(.cannotConnectToHost))]
        t.http.defaultReply = .status(200, ok, [:])
        XCTAssertEqual(try XCTUnwrap(evaluate(t)).get().attempts, 3)
    }

    func testRetriesExhaustedKeepLastError() {
        let t = makeTestClient()
        t.http.defaultReply = err(502, 50200)
        guard case .failure(let e)? = evaluate(t) else { return XCTFail() }
        XCTAssertEqual(e.code, 50200)
        XCTAssertEqual(e.httpStatus, 502)
        XCTAssertEqual(e.attempts, 3)
        XCTAssertTrue(e.retryable)
        XCTAssertTrue(e.isServerError)
        XCTAssertTrue(t.events.entries.contains("end:evaluate:502:3:50200"))
    }

    /// 40901 (key in progress) is retried; 40903 (key reused) is not.
    func testIdempotencyConflicts() throws {
        let t = makeTestClient()
        t.http.replies = [err(409, 40901, retryAfter: "1")]
        t.http.defaultReply = .status(200, ok, ["Idempotency-Replayed": "true"])
        let r = try XCTUnwrap(evaluate(t)).get()
        XCTAssertTrue(r.replayed)
        XCTAssertEqual(r.attempts, 2)
        let u = makeTestClient()
        u.http.replies = [err(409, 40903)]
        guard case .failure(let e)? = evaluate(u) else { return XCTFail() }
        XCTAssertTrue(e.isConflict)
        XCTAssertEqual(u.http.requests.count, 1)
    }

    /// The total timeout stops retries whose delay would pass the deadline.
    func testTotalTimeoutStopsRetries() {
        let t = makeTestClient { $0.totalTimeoutMs = 1500 }
        t.http.replies = [err(500, 50000)]
        t.http.defaultReply = err(429, 42900, retryAfter: "5")
        guard case .failure(let e)? = evaluate(t) else { return XCTFail() }
        XCTAssertEqual(e.code, 42900)
        XCTAssertEqual(e.attempts, 2)
        XCTAssertEqual(t.http.requests.count, 2)
    }

    /// The per-attempt timeout shrinks to the time left before the deadline.
    func testAttemptTimeoutBoundedByDeadline() {
        let t = makeTestClient { $0.totalTimeoutMs = 2500; $0.readTimeoutMs = 2000 }
        t.http.defaultReply = .hang
        guard case .failure(let e)? = evaluate(t) else { return XCTFail() }
        XCTAssertEqual(e.code, 90002)
        XCTAssertEqual(t.http.requests.map { $0.readTimeoutMs }.first, 2000)
        XCTAssertLessThan(t.http.requests.last!.readTimeoutMs, 600)
    }

    // MARK: Idempotency (A-01)

    func testCallerKeyReusedOnEveryAttempt() throws {
        let t = makeTestClient()
        t.http.replies = [err(500, 50000), err(500, 50000)]
        t.http.defaultReply = .status(200, ok, [:])
        let r = try XCTUnwrap(evaluate(t, options: RequestOptions(idempotencyKey: "order-42:attempt"))).get()
        XCTAssertEqual(r.idempotencyKey, "order-42:attempt")
        XCTAssertEqual(t.http.requests.map { $0.header("Idempotency-Key") }, Array(repeating: "order-42:attempt", count: 3))
    }

    /// Three logical submissions with one key send the same key three times.
    func testSameKeyAcrossSubmissions() {
        let t = makeTestClient()
        t.http.defaultReply = .status(200, ok, [:])
        for _ in 0..<3 { _ = evaluate(t, options: RequestOptions(idempotencyKey: "k-same")) }
        XCTAssertEqual(t.http.requests.map { $0.header("Idempotency-Key") }, ["k-same", "k-same", "k-same"])
    }

    func testGeneratedKeysDifferPerCall() {
        let t = makeTestClient()
        t.http.defaultReply = .status(200, ok, [:])
        _ = evaluate(t)
        _ = evaluate(t)
        let keys = t.http.requests.compactMap { $0.header("Idempotency-Key") }
        XCTAssertEqual(keys.count, 2)
        XCTAssertNotEqual(keys[0], keys[1])
    }

    func testInvalidCallerKeyRejectedBeforeIO() {
        for bad in ["", "has space", String(repeating: "k", count: 201), "中文", "tab\t"] {
            let t = makeTestClient()
            guard case .failure(let e)? = evaluate(t, options: RequestOptions(idempotencyKey: bad)) else { return XCTFail(bad) }
            XCTAssertEqual(e.code, 90010, bad)
            XCTAssertTrue(e.isInvalidParameter)
            XCTAssertEqual(t.http.requestCount, 0, bad)
        }
    }

    func testKeyFormatAndValidation() {
        var seen = Set<String>()
        for _ in 0..<500 {
            let k = IdempotencyKey.generate()
            XCTAssertEqual(k.count, 32)
            XCTAssertTrue(k.allSatisfy { "0123456789abcdef".contains($0) }, k)
            seen.insert(k)
        }
        XCTAssertEqual(seen.count, 500)
        XCTAssertTrue(IdempotencyKey.isValid(String(repeating: "~", count: 200)))
        XCTAssertTrue(IdempotencyKey.isValid("!"))
        XCTAssertFalse(IdempotencyKey.isValid(String(repeating: "a", count: 201)))
        XCTAssertFalse(IdempotencyKey.isValid("a b"))
        XCTAssertFalse(IdempotencyKey.isValid("\u{7F}"))
    }

    /// autoIdempotencyKey off and no key: no header, no retry, a DEBUG log says so.
    func testNoKeyMeansNoRetry() {
        let t = makeTestClient { $0.autoIdempotencyKey = false }
        t.http.defaultReply = err(500, 50000)
        guard case .failure(let e)? = evaluate(t) else { return XCTFail() }
        XCTAssertEqual(t.http.requests.count, 1)
        XCTAssertNil(t.http.requests[0].header("Idempotency-Key"))
        XCTAssertNil(e.idempotencyKey)
        XCTAssertTrue(e.retryable)
        XCTAssertTrue(t.logger.messages(.debug).contains { $0.contains("retries disabled") })
    }

    /// GET report is retried without an idempotency key.
    func testGetReportRetriedWithoutKey() throws {
        let t = makeTestClient { $0.autoIdempotencyKey = false }
        t.http.replies = [err(503, 50200)]
        t.http.defaultReply = .status(200, #"{"code":0,"data":{"overall":1}}"#, [:])
        var got: Result<ReportResult, YuguError>?
        t.client.getReport(recordId: "eval_1") { got = $0 }
        t.executor.advance(10_000)
        XCTAssertEqual(try XCTUnwrap(got).get().attempts, 2)
        XCTAssertNil(t.http.requests[0].header("Idempotency-Key"))
    }

    func testPerCallRetryOverride() {
        let t = makeTestClient()
        t.http.defaultReply = err(500, 50000)
        _ = evaluate(t, options: RequestOptions(retry: .disabled))
        XCTAssertEqual(t.http.requests.count, 1)
        let u = makeTestClient()
        u.http.defaultReply = err(500, 50000)
        _ = evaluate(u, options: RequestOptions(retry: RetryPolicy(maxRetries: 4, initialDelayMs: 10)))
        XCTAssertEqual(u.http.requests.count, 5)
    }

    func testIsRetryableOfOtherErrors() {
        XCTAssertTrue(YuguErrors.isRetryable(URLError(.timedOut)))
        XCTAssertFalse(YuguErrors.isRetryable(YuguErrors.local(90006)))
    }

    // MARK: Cancellation

    func testCancelCall() {
        let t = makeTestClient()
        t.http.defaultReply = .hang
        var got: Result<EvalResult, YuguError>?
        let call = t.client.evaluate(audio: .data(wav), config: config) { got = $0 }
        t.executor.runUntilIdle()
        XCTAssertEqual(call.operation, "evaluate")
        XCTAssertNotNil(call.idempotencyKey)
        call.cancel()
        call.cancel()
        t.executor.runUntilIdle()
        guard case .failure(let e)? = got else { return XCTFail() }
        XCTAssertEqual(e.code, 90003)
        XCTAssertTrue(e.isCancelled)
        XCTAssertEqual(e.idempotencyKey, call.idempotencyKey)
        XCTAssertEqual(t.client.core.activeCount.calls, 0)
    }

    func testCancellationTokenBeforeAndDuring() {
        let t = makeTestClient()
        t.http.defaultReply = .hang
        let token = YuguCancellationToken()
        var a: Result<EvalResult, YuguError>?
        t.client.evaluate(audio: .data(wav), config: config, options: RequestOptions(cancellationToken: token)) { a = $0 }
        t.executor.runUntilIdle()
        XCTAssertNil(a)
        token.cancel()
        XCTAssertTrue(token.isCancelled)
        t.executor.runUntilIdle()
        if case .failure(let e)? = a { XCTAssertEqual(e.code, 90003) } else { XCTFail() }
        // A cancelled token fails new calls at once.
        var b: Result<EvalResult, YuguError>?
        t.client.evaluate(audio: .data(wav), config: config, options: RequestOptions(cancellationToken: token)) { b = $0 }
        t.executor.runUntilIdle()
        if case .failure(let e)? = b { XCTAssertEqual(e.code, 90003) } else { XCTFail() }
    }

    func testAsyncTaskCancellation() async throws {
        let t = makeTestClient()
        t.http.defaultReply = .hang
        // Drive the virtual executor from one background thread for the whole test.
        let stop = YuguCancellationToken()
        let pump = Thread {
            while !stop.isCancelled {
                t.executor.runUntilIdle()
                Thread.sleep(forTimeInterval: 0.001)
            }
        }
        pump.start()
        defer { stop.cancel() }
        let task = Task { try await t.client.evaluate(audio: .data(wav), config: config) }
        let deadline = Date().addingTimeInterval(10)
        while t.http.requestCount == 0 && Date() < deadline {
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTAssertEqual(t.http.requestCount, 1, "request in flight before the cancel")
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("expected cancellation")
        } catch let e as YuguError {
            XCTAssertEqual(e.code, 90003)
        } catch {
            XCTFail("\(error)")
        }
    }

    func testValidationErrorsBeforeIO() {
        let t = makeTestClient()
        var results: [YuguError] = []
        t.client.evaluate(audio: .data(Data()), config: config) { if case .failure(let e) = $0 { results.append(e) } }
        t.client.evaluate(audio: .data(wav), config: EvaluateConfig(coreType: .sentence, referenceText: " ")) { if case .failure(let e) = $0 { results.append(e) } }
        t.client.evaluate(audio: .data(wav), config: EvaluateConfig(coreType: .sentence, referenceText: "x", slack: .nan)) { if case .failure(let e) = $0 { results.append(e) } }
        t.client.evaluate(audio: .file(URL(fileURLWithPath: "/nonexistent/a.wav")), config: config) { if case .failure(let e) = $0 { results.append(e) } }
        t.client.evaluateCompat(coreType: .pinyin, audio: .data(wav), params: CompatParams(refText: "重庆")) { if case .failure(let e) = $0 { results.append(e) } }
        t.client.evaluateCompat(coreType: CompatCoreType("../x"), audio: .data(wav), params: CompatParams(refText: "a")) { if case .failure(let e) = $0 { results.append(e) } }
        t.client.tts(TTSRequest(text: "")) { if case .failure(let e) = $0 { results.append(e) } }
        t.client.getReport(recordId: "  ") { if case .failure(let e) = $0 { results.append(e) } }
        t.executor.runUntilIdle()
        XCTAssertEqual(results.map { $0.code }, Array(repeating: 90010, count: 8))
        XCTAssertEqual(t.http.requestCount, 0)
    }

    func testInvalidBaseUrl() {
        let t = makeTestClient { $0.baseUrl = "ftp://x" }
        var got: Result<EvalResult, YuguError>?
        t.client.evaluate(audio: .data(wav), config: config) { got = $0 }
        t.executor.runUntilIdle()
        if case .failure(let e)? = got { XCTAssertEqual(e.code, 90010) } else { XCTFail() }
    }
}
