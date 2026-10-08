import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import YuguCore

/// Streaming sessions on the wire against the mock platform (acceptance 6.4, V-04): server kill,
/// abnormal close, network switch (socket reset), client network drop of 10 s, silent server,
/// retryable error frames, refused handshake, lost result with idempotent replay.
///
/// The transport is the test-only `PosixWebSocketTransport` because URLSession on Linux cannot
/// open WebSockets; the session state machine, signing, frames and reconnect logic are the
/// SDK's own.
final class WebSocketIntegrationTests: XCTestCase {
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

    override func setUpWithError() throws {
        if let e = WebSocketIntegrationTests.launchError { throw e }
        guard let s = WebSocketIntegrationTests.server else {
            throw XCTSkip("node or tools/mock-server/server.mjs not found; set YUGU_MOCK_SERVER and YUGU_NODE")
        }
        server = s
        server.reset()
    }

    /// Records the transports a client creates; can take the network down for a while.
    final class Network {
        private let lock = NSLock()
        private(set) var transports: [PosixWebSocketTransport] = []
        private var downUntil: Date?

        func takeDown(for seconds: TimeInterval) {
            lock.sync { downUntil = Date().addingTimeInterval(seconds) }
            lock.sync { transports.last }?.resetConnection()
        }

        var isDown: Bool { lock.sync { downUntil.map { Date() < $0 } ?? false } }

        func factory() -> YuguWebSocketTransportFactory {
            { [unowned self] in
                if self.isDown { return Unreachable() }
                let t = PosixWebSocketTransport()
                self.lock.sync { self.transports.append(t) }
                return t
            }
        }

        var last: PosixWebSocketTransport? { lock.sync { transports.last } }
    }

    /// Connect attempts while the network is down.
    final class Unreachable: YuguWebSocketTransport {
        func connect(url: URL, headers: [String: String], onEvent: @escaping (YuguWebSocketEvent) -> Void) {
            DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(20)) {
                onEvent(.failed(URLError(.notConnectedToInternet)))
            }
        }
        func send(text: String, completion: @escaping (Error?) -> Void) { completion(URLError(.notConnectedToInternet)) }
        func send(data: Data, completion: @escaping (Error?) -> Void) { completion(URLError(.notConnectedToInternet)) }
        func sendPing(completion: @escaping (Error?) -> Void) { completion(URLError(.notConnectedToInternet)) }
        func close(code: Int, reason: String?) {}
    }

    final class Waiter: YuguStreamListener {
        let l = RecordingListener()
        let closed: XCTestExpectation
        var onStartedHook: (() -> Void)?
        init(_ e: XCTestExpectation) { closed = e }
        func onStateChanged(_ s: YuguStreamSession, from: SessionState, to: SessionState) { l.onStateChanged(s, from: from, to: to) }
        func onConnected(_ s: YuguStreamSession) { l.onConnected(s) }
        func onStarted(_ s: YuguStreamSession) { l.onStarted(s); onStartedHook?() }
        func onPartial(_ s: YuguStreamSession, partial: StreamPartial) { l.onPartial(s, partial: partial) }
        func onReconnecting(_ s: YuguStreamSession, attempt: Int, delayMs: Int, cause: YuguError) { l.onReconnecting(s, attempt: attempt, delayMs: delayMs, cause: cause) }
        func onReconnected(_ s: YuguStreamSession, attempt: Int, droppedBytes: Int) { l.onReconnected(s, attempt: attempt, droppedBytes: droppedBytes) }
        func onWarning(_ s: YuguStreamSession, warning: YuguWarning) { l.onWarning(s, warning: warning) }
        func onResult(_ s: YuguStreamSession, result: EvalResult) { l.onResult(s, result: result) }
        func onError(_ s: YuguStreamSession, error: YuguError) { l.onError(s, error: error) }
        func onClosed(_ s: YuguStreamSession, code: Int, reason: String) { l.onClosed(s, code: code, reason: reason); closed.fulfill() }
    }

    let pcm = Fixture.pcm("audio/zh_short.wav")
    let config = EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好", language: "zh-CN")

    private func client(_ network: Network, _ configure: (inout YuguClientOptions) -> Void = { _ in }) -> YuguClient {
        var o = YuguClientOptions(auth: .appKey(MockServer.appKey, secretKey: MockServer.secretKey), baseUrl: server.baseUrl)
        o.webSocketTransportFactory = network.factory()
        o.callbackQueue = DispatchQueue(label: "ws.integration")
        o.logLevel = .off
        configure(&o)
        return YuguClient(options: o)
    }

    /// Streams the audio in 640-byte frames, `pace` seconds apart, calling `during` after the
    /// given frame index, then ends.
    private func run(
        _ session: YuguStreamSession, waiter: Waiter, pace: TimeInterval = 0, during: (Int, () -> Void)? = nil, timeout: TimeInterval = 40
    ) throws {
        try session.start()
        for (i, f) in frames(pcm).enumerated() {
            // The session may already have failed (refused handshake): then sendAudio throws.
            try? session.sendAudio(f)
            if let d = during, d.0 == i { d.1() }
            if pace > 0 { Thread.sleep(forTimeInterval: pace) }
        }
        try? session.end()
        wait(for: [waiter.closed], timeout: timeout)
    }

    private func wsLog(_ path: String = "/api/v1/ws/evaluate") -> [JSONValue] {
        server.log().filter { $0["method"]?.stringValue == "WS" && $0["path"]?.stringValue == path }
    }

    // MARK: Happy paths

    func testNativeSessionOnTheWire() throws {
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w)
        XCTAssertEqual(w.l.callbacks, ["connected", "started", "result", "closed:1000"])
        XCTAssertEqual(w.l.result?.overall, 93.7)
        XCTAssertEqual(w.l.result?.idempotencyKey, s.idempotencyKey)
        let log = wsLog()
        XCTAssertEqual(log.count, 1)
        XCTAssertEqual(log[0]["idempotencyKey"]?.stringValue, s.idempotencyKey, "signed handshake accepted with the key")
        let billing = server.billing()
        XCTAssertEqual(billing["billed"]?.intValue, 1)
        XCTAssertEqual(billing["records"]?[0]?["bytes"]?.intValue, pcm.count)
    }

    func testTokenAuthOnTheWire() throws {
        let net = Network()
        let c = client(net) { $0.auth = .token(MockServer.token) }
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"])
    }

    func testCompatSessionWithProgressFrames() throws {
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluateCompat(coreType: .sentEvalCn, params: CompatParams(refText: "今天天气很好", language: "zh-CN", realtimeFeedback: true), listener: w)
        try run(s, waiter: w)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"])
        XCTAssertEqual(w.l.result?.coreType, "sent.eval.cn")
        let partials = w.l.callbacks.filter { $0.hasPrefix("partial:") }
        XCTAssertGreaterThanOrEqual(partials.count, 3, "\(w.l.callbacks)")
        XCTAssertEqual(wsLog("/sent.eval.cn").count, 1)
    }

    func testHeartbeatPingsAnswered() throws {
        let net = Network()
        let c = client(net) { $0.pingIntervalMs = 100; $0.pongTimeoutMs = 2000 }
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w, pace: 0.008)
        XCTAssertEqual(w.l.callbacks, ["connected", "started", "result", "closed:1000"])
    }

    /// The platform closes frames above 128 KB with 1009: a 146 KB chunk given to sendAudio at
    /// once still evaluates, because the SDK sends frames of at most 32000 bytes.
    func testLargeChunkSplitOnTheWire() throws {
        let fox = Fixture.pcm("audio/en_fox.wav")
        XCTAssertGreaterThan(fox.count, 128 * 1024)
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: EvaluateConfig(coreType: .sentence, referenceText: "The quick brown fox jumps over the lazy dog.", language: "en-US"), listener: w)
        try s.start()
        try s.sendAudio(fox)
        try s.end()
        wait(for: [w.closed], timeout: 30)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        XCTAssertEqual(server.billing()["records"]?[0]?["bytes"]?.intValue, fox.count)
    }

    /// The raw transport shows the platform rule the SDK protects against.
    func testPlatformClosesOversizedFrameWith1009() throws {
        let t = PosixWebSocketTransport()
        let done = expectation(description: "closed")
        var closeCode: Int?
        let signer = RequestSigner(auth: .appKey(MockServer.appKey, secretKey: MockServer.secretKey), random: SystemRandomSource(), wallClock: Date.init)
        let query = RequestSigner.encodeQuery(signer.webSocketQuery([]))
        t.connect(url: URL(string: "ws://127.0.0.1:\(server.port)/api/v1/ws/evaluate?" + query)!, headers: [:]) { event in
            switch event {
            case .text(let s) where s.contains("connected"):
                t.send(text: #"{"cmd":"start","coreType":"sentence","referenceText":"x"}"#) { _ in }
            case .text(let s) where s.contains("started"):
                t.send(data: Data(count: 200 * 1024)) { _ in }
            case .closed(let code, _):
                closeCode = code
                done.fulfill()
            case .failed:
                done.fulfill()
            default:
                break
            }
        }
        wait(for: [done], timeout: 10)
        XCTAssertEqual(closeCode, 1009)
    }

    /// The relay used by the Linux sandbox test (SandboxTests): the SDK session over the POSIX
    /// client to the local bridge, which forwards to the platform, here the mock over plain ws.
    func testSessionThroughRelayBridge() throws {
        guard let bridge = try WssBridge.start(target: "ws://127.0.0.1:\(server.port)") else {
            throw XCTSkip("node or the ws package not found")
        }
        defer { bridge.stop() }
        var o = YuguClientOptions(auth: .appKey(MockServer.appKey, secretKey: MockServer.secretKey), baseUrl: server.baseUrl, wsBaseUrl: "ws://127.0.0.1:\(bridge.port)")
        o.webSocketTransportFactory = { PosixWebSocketTransport() }
        o.callbackQueue = DispatchQueue(label: "bridge.callbacks")
        o.logLevel = .off
        let c = YuguClient(options: o)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w)
        XCTAssertEqual(w.l.callbacks, ["connected", "started", "result", "closed:1000"])
        XCTAssertEqual(w.l.result?.overall, 93.7)
        XCTAssertEqual(wsLog().first?["idempotencyKey"]?.stringValue, s.idempotencyKey, "the signed query passed through the relay")
        XCTAssertEqual(server.billing()["records"]?[0]?["bytes"]?.intValue, pcm.count)
        // A handshake the platform refuses comes back as a policy close, not retried.
        let bad = YuguClient(options: {
            var b = o
            b.auth = .appKey(MockServer.appKey, secretKey: "wrong")
            return b
        }())
        defer { bad.close() }
        let w2 = Waiter(expectation(description: "closed bad"))
        let s2 = try bad.streamEvaluate(config: config, listener: w2)
        try s2.start()
        wait(for: [w2.closed], timeout: 10)
        XCTAssertEqual(w2.l.terminalCallbacks, ["error:90005"], "\(w2.l.events)")
    }

    // MARK: V-04 (b) server kills the connection

    func testServerKillReplaysWithSameKeyBillsOnce() throws {
        server.queueFaults([("/api/v1/ws/evaluate", "ws-kill-after:20")])
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w, pace: 0.002)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        XCTAssertTrue(w.l.callbacks.contains("reconnecting:1:90001"), "\(w.l.callbacks)")
        XCTAssertTrue(w.l.callbacks.contains("reconnected:1:0"))
        XCTAssertEqual(w.l.result?.attempts, 2)
        let log = wsLog()
        XCTAssertEqual(log.count, 2)
        XCTAssertEqual(Set(log.compactMap { $0["idempotencyKey"]?.stringValue }), [s.idempotencyKey!])
        let billing = server.billing()
        XCTAssertEqual(billing["billed"]?.intValue, 1)
        XCTAssertEqual(billing["records"]?[0]?["bytes"]?.intValue, pcm.count, "the replay delivered the whole round")
    }

    func testAbnormalClose1011Reconnects() throws {
        server.queueFaults([("/api/v1/ws/evaluate", "ws-close-after:10")])
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w, pace: 0.002)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        XCTAssertTrue(w.l.callbacks.contains { $0.hasPrefix("reconnecting:1:") })
        XCTAssertEqual(server.billing()["billed"]?.intValue, 1)
    }

    // MARK: V-04 (c) network switch: the socket is reset under the session

    func testNetworkSwitchSocketReset() throws {
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w, pace: 0.003, during: (30, { net.last?.resetConnection() }))
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        XCTAssertTrue(w.l.callbacks.contains("reconnected:1:0"), "\(w.l.callbacks)")
        XCTAssertEqual(server.billing()["billed"]?.intValue, 1)
        XCTAssertEqual(server.billing()["records"]?[0]?["bytes"]?.intValue, pcm.count)
    }

    // MARK: V-04 (a) client network drop of 10 seconds

    /// Default policy (8 attempts, about 23 s of waits): the session rides out a 10 s outage and
    /// completes, billed once, with the whole round replayed.
    func testNetworkDrop10sWithDefaultPolicyRecovers() throws {
        let net = Network()
        let c = client(net)
        defer { c.close() }
        XCTAssertEqual(c.options.reconnect, .default)
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        let start = Date()
        try run(s, waiter: w, pace: 0.002, during: (20, { net.takeDown(for: 10) }), timeout: 60)
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince(start), 10)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        XCTAssertTrue(w.l.callbacks.contains { $0.hasPrefix("reconnected:") })
        XCTAssertGreaterThanOrEqual(w.l.callbacks.filter { $0.hasPrefix("reconnecting:") }.count, 4)
        XCTAssertEqual(server.billing()["billed"]?.intValue, 1)
        XCTAssertEqual(server.billing()["records"]?[0]?["bytes"]?.intValue, pcm.count)
    }

    /// A short policy cannot outlast the outage: each attempt is reported, then
    /// RECONNECT_EXHAUSTED, no hang, nothing billed.
    func testNetworkDrop10sWithShortPolicyFailsCleanly() throws {
        let net = Network()
        let c = client(net) { $0.reconnect = ReconnectPolicy(maxAttempts: 3) }
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        let start = Date()
        try run(s, waiter: w, pace: 0.002, during: (20, { net.takeDown(for: 10) }), timeout: 30)
        XCTAssertLessThan(Date().timeIntervalSince(start), 10, "fails within the configured reconnect budget")
        XCTAssertEqual(w.l.callbacks.filter { $0.hasPrefix("reconnecting:") }.count, 3, "\(w.l.callbacks)")
        XCTAssertEqual(w.l.terminalCallbacks, ["error:90006"])
        XCTAssertEqual(w.l.callbacks.last?.hasPrefix("closed:"), true)
        XCTAssertEqual(server.billing()["billed"]?.intValue, 0)
    }

    // MARK: Silent server, error frames, refused handshake, lost result

    func testSilentServerAfterEndRecoversByResultTimeout() throws {
        let n = frames(pcm).count + 2 // start frame, audio frames, then end is swallowed
        server.queueFaults([("/api/v1/ws/evaluate", "ws-silent:\(n)")])
        let net = Network()
        let c = client(net) { $0.resultTimeoutMs = 1500 }
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        XCTAssertTrue(w.l.callbacks.contains("reconnecting:1:90007"), "\(w.l.callbacks)")
        XCTAssertEqual(server.billing()["billed"]?.intValue, 1)
    }

    func testRetryableErrorFrameReconnects() throws {
        server.queueFaults([("/api/v1/ws/evaluate", "ws-error:50200")])
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        XCTAssertTrue(w.l.callbacks.contains("reconnecting:1:50200"))
    }

    func testNonRetryableErrorFrameFails() throws {
        server.queueFaults([("/api/v1/ws/evaluate", "ws-error:40001")])
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w)
        XCTAssertEqual(w.l.terminalCallbacks, ["error:40001"])
        XCTAssertFalse(w.l.callbacks.contains { $0.hasPrefix("reconnecting") })
    }

    func testRefusedHandshakeRetried() throws {
        server.queueFaults([("/api/v1/ws/evaluate", "ws-refuse")])
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        XCTAssertEqual(w.l.callbacks.first, "reconnecting:1:0")
    }

    func testBadSignatureHandshakeFailsWithoutRetry() throws {
        let net = Network()
        let c = client(net) { $0.auth = .appKey(MockServer.appKey, secretKey: "wrong") }
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w)
        XCTAssertEqual(w.l.error?.httpStatus, 403)
        XCTAssertTrue(w.l.error?.isPermissionDenied == true)
        XCTAssertEqual(w.l.callbacks.filter { $0.hasPrefix("reconnecting") }.count, 0)
    }

    /// The first server session is still evaluating when the client gives up waiting; the
    /// reconnect with the same key gets the stored result replayed, billed once.
    func testResultTimeoutDuringEvaluationReplaysOnce() throws {
        server.queueFaults([("/api/v1/ws/evaluate", "ws-delay-result:2500")])
        let net = Network()
        let c = client(net) { $0.resultTimeoutMs = 1500 }
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try run(s, waiter: w)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        XCTAssertTrue(w.l.callbacks.contains("reconnecting:1:90007"))
        XCTAssertEqual(w.l.result?.replayed, true)
        XCTAssertEqual(server.billing()["billed"]?.intValue, 1)
    }

    func testCancelOnTheWire() throws {
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: config, listener: w)
        try s.start()
        for f in frames(pcm).prefix(10) { try s.sendAudio(f) }
        s.cancel()
        wait(for: [w.closed], timeout: 10)
        XCTAssertEqual(w.l.terminalCallbacks, [])
        XCTAssertEqual(w.l.callbacks.last, "closed:1000")
        XCTAssertEqual(server.billing()["billed"]?.intValue, 0)
    }
}
