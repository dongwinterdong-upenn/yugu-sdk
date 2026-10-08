import XCTest
@testable import YuguCore

/// DESIGN 2.7: levels, redaction, metrics hooks.
final class LoggingTests: XCTestCase {
    func testRedaction() {
        let r = Redactor(auth: .appKey("abcdefgh123", secretKey: "s3cr3t-value"))
        let line = r.redact("key abcdefgh123 secret s3cr3t-value url wss://h/x?appKey=abcdefgh123&signature=Ab+/c=&nonce=1 header X-Signature: Zm9v token=eyJ.x.y")
        XCTAssertFalse(line.contains("s3cr3t-value"))
        XCTAssertFalse(line.contains("abcdefgh123"))
        XCTAssertTrue(line.contains("abcd***"))
        XCTAssertTrue(line.contains("signature=***&nonce=1"), line)
        XCTAssertTrue(line.contains("X-Signature: ***"), line)
        XCTAssertTrue(line.contains("token=***"), line)
        XCTAssertFalse(line.contains("Zm9v"))
        let t = Redactor(auth: .token("jwt.secret.value"))
        XCTAssertEqual(t.redact("Bearer jwt.secret.value"), "Bearer ***")
        XCTAssertEqual(Redactor.maskParameter("signature verification failed", name: "signature"), "signature verification failed")
        XCTAssertEqual(Redactor.mask(appKey: "ak"), "ak***")
    }

    func testLevelsFilter() {
        let logger = TestLogger()
        let sink = LogSink(level: .warn, logger: logger, redactor: Redactor(auth: .token("tttt")))
        sink.debug("t", "d")
        sink.info("t", "i")
        sink.warn("t", "w")
        sink.error("t", "e", YuguErrors.local(90001))
        XCTAssertEqual(logger.lines.map { $0.message.prefix(1) }, ["w", "e"])
        XCTAssertTrue(sink.enabled(.error))
        XCTAssertFalse(sink.enabled(.off))
        let off = LogSink(level: .off, logger: logger, redactor: Redactor(auth: .token("tttt")))
        off.error("t", "x")
        XCTAssertEqual(logger.lines.count, 2)
        XCTAssertEqual(YuguLogLevel.allCases.map { $0.label }, ["OFF", "ERROR", "WARN", "INFO", "DEBUG"])
        XCTAssertTrue(YuguLogLevel.warn < .debug)
        ConsoleLogger().log(level: .info, tag: "test", message: "console logger works", error: YuguErrors.local(90003))
    }

    /// A whole REST call plus a session at DEBUG never logs the secret, a signature or the token.
    func testNoSecretsInDebugLogs() throws {
        let t = makeTestClient()
        t.http.replies = [.status(503, #"{"code":50200,"message":"x"}"#, [:])]
        t.http.defaultReply = .status(200, Fixture.text("platform/native_evaluate_sentence_zh.json"), [:])
        t.client.evaluate(audio: .data(Fixture.data("audio/zh_short.wav")), config: EvaluateConfig(coreType: .sentence, referenceText: "x")) { _ in }
        t.executor.advance(10_000)
        let l = RecordingListener()
        let s = try t.client.streamEvaluate(config: EvaluateConfig(coreType: .sentence, referenceText: "x"), listener: l)
        try s.start()
        t.executor.runUntilIdle()
        for f in frames(tonePCM(seconds: 1.2)) { try s.sendAudio(f) }
        try s.end()
        t.executor.runUntilIdle()
        let all = t.logger.all
        XCTAssertFalse(all.isEmpty)
        XCTAssertTrue(all.contains("connecting wss://api.example.test/api/v1/ws/evaluate?"), all)
        XCTAssertFalse(all.contains("sk_test_secret"))
        XCTAssertFalse(all.contains("ak_test_key"))
        XCTAssertTrue(all.contains("ak_t***"))
        for req in t.http.requests {
            XCTAssertFalse(all.contains(req.header("X-Signature")!), "signature logged")
        }
        let wsSig = t.ws.connections[0].query["signature"]!
        XCTAssertFalse(all.contains(wsSig))
        XCTAssertFalse(all.contains(RequestSigner.percentEncode(wsSig)))
    }

    func testEventListenerDefaultsAreOptional() {
        final class Empty: YuguEventListener {}
        let e = Empty()
        e.onRequestStart(op: "a", method: "GET", path: "/", attempt: 1)
        e.onRequestEnd(op: "a", httpStatus: 200, latencyMs: 1, attempts: 1, error: nil)
        e.onRetry(op: "a", attempt: 1, delayMs: 1, error: YuguErrors.local(90001))
        e.onSessionStateChanged(sessionId: "s", from: .idle, to: .connecting)
        e.onReconnect(sessionId: "s", attempt: 1, succeeded: true)
        final class Minimal: YuguStreamListener {
            func onResult(_ session: YuguStreamSession, result: EvalResult) {}
            func onError(_ session: YuguStreamSession, error: YuguError) {}
        }
        let t = makeTestClient()
        let s = try? t.client.streamEvaluate(config: EvaluateConfig(coreType: .word, referenceText: "apple"), listener: Minimal())
        try? s?.start()
        t.executor.runUntilIdle()
        s?.cancel()
        t.executor.runUntilIdle()
        XCTAssertEqual(s?.state, .closed)
        let h = YuguStreamHandlers()
        var seen: [String] = []
        h.onStateChanged = { _, to in seen.append(to.rawValue) }
        h.onConnected = { seen.append("connected") }
        h.onStarted = { seen.append("started") }
        h.onPartial = { _ in seen.append("partial") }
        h.onReconnecting = { _, _, _ in seen.append("reconnecting") }
        h.onReconnected = { _, _ in seen.append("reconnected") }
        h.onWarning = { _ in seen.append("warning") }
        h.onResult = { _ in seen.append("result") }
        h.onError = { _ in seen.append("error") }
        h.onClosed = { _, _ in seen.append("closed") }
        let u = makeTestClient()
        u.ws.faults = [0: .killAfterAudioFrames(1)]
        let s2 = try! u.client.streamEvaluate(config: EvaluateConfig(coreType: .sentence, referenceText: "x"), listener: h)
        try! s2.start()
        u.executor.runUntilIdle()
        for f in frames(tonePCM(seconds: 0.5)) { try! s2.sendAudio(f) }
        u.executor.runUntilIdle()
        u.ws.connections.first?.emitJSON(#"{"eof":0,"result":{"bytes":1}}"#)
        u.executor.advance(5000)
        u.ws.connections.last?.emitJSON(#"{"eof":0,"result":{"bytes":2}}"#)
        try! s2.end()
        u.executor.runUntilIdle()
        XCTAssertEqual(seen.filter { !$0.hasPrefix("ID") && $0 == $0.lowercased() }, ["connected", "started", "reconnecting", "reconnected", "partial", "warning", "result", "closed"])
    }
}
