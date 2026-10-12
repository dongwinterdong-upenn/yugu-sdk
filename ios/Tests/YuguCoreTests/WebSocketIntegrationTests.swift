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

    /// Streams the audio (`pcm` by default) in 640-byte frames, `pace` seconds apart, calling
    /// `during` after the given frame index, then ends.
    private func run(
        _ session: YuguStreamSession, waiter: Waiter, pace: TimeInterval = 0, during: (Int, () -> Void)? = nil, timeout: TimeInterval = 40,
        audio: Data? = nil
    ) throws {
        try session.start()
        for (i, f) in frames(audio ?? pcm).enumerated() {
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

    // MARK: Reading progress (2.1.0, CONTRACT 5.5)

    /// The customer sentence, 25 units, and its audio: 145920 bytes of PCM, 228 frames of 640 bytes.
    let progressPCM = Fixture.pcm("audio/zh_progress.wav")
    let progressParams = CompatParams(refText: ReadingProgressTests.customerText, language: "zh-CN", realtimeFeedback: true)
    let progressConfig = EvaluateConfig(coreType: .sentence, referenceText: ReadingProgressTests.customerText, language: "zh-CN", realtimeFeedback: true)

    /// readUnits the platform simulator reports for `progressPCM` (tools/mock-server README): a
    /// frame per 16000 bytes, one unit read per 8000 bytes, one more unit in the frame after end.
    private var expectedReadUnits: [Int] {
        let n = progressPCM.count
        return (1...(n / 16000)).map { min(25, 2 * $0) } + [min(25, n / 8000 + 1)]
    }

    /// Every progress frame of the round reaches onPartial in order with the 25 units, the last
    /// one after end() while ENDING, and the result comes after it.
    private func assertProgressRound(_ w: Waiter, file: StaticString = #filePath, line: UInt = #line) {
        let progress = w.l.progressPartials
        XCTAssertEqual(progress.map { $0.readUnits }, expectedReadUnits, "\(w.l.callbacks)", file: file, line: line)
        for p in progress {
            XCTAssertEqual(p.totalUnits, 25, file: file, line: line)
            XCTAssertEqual(p.progress.map { $0.word }, ReadingProgressTests.customerUnits, file: file, line: line)
            XCTAssertEqual(p.progress.prefix(max(0, p.readUnits)).allSatisfy { $0.read }, true, file: file, line: line)
        }
        XCTAssertEqual(w.l.partialStates.last, .ending, file: file, line: line)
        let events = w.l.events
        let ending = events.firstIndex(of: "state:STARTED->ENDING") ?? -1
        let lastPartial = events.lastIndex { $0.hasPrefix("partial:") } ?? -1
        let result = events.firstIndex(of: "result") ?? -1
        XCTAssertTrue(ending >= 0 && ending < lastPartial && lastPartial < result, "\(events)", file: file, line: line)
    }

    /// Compatible frames: empty recordId and Shengtong-shaped `words` for the units read since the
    /// previous frame; the per-unit state the SDK reports comes from `progress`.
    private func assertCompatFrames(_ w: Waiter, file: StaticString = #filePath, line: UInt = #line) {
        var previous = 0
        for p in w.l.progressPartials {
            XCTAssertEqual(p.raw["recordId"]?.stringValue, "", file: file, line: line)
            let items = p.raw["result"]?["words"]?.arrayValue ?? []
            XCTAssertEqual(items.count, p.readUnits - previous, file: file, line: line)
            XCTAssertEqual(items.compactMap { $0["word"]?.stringValue }, p.progress[(p.readUnits - items.count)..<p.readUnits].map { $0.word }, file: file, line: line)
            previous = p.readUnits
        }
    }

    func testCompatReadingProgressOnTheWire() throws {
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluateCompat(coreType: .sentEvalCn, params: progressParams, listener: w)
        try run(s, waiter: w, audio: progressPCM)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        assertProgressRound(w)
        assertCompatFrames(w)
        XCTAssertTrue(w.l.progressPartials.allSatisfy { $0.matchedUnits == $0.readUnits })
        XCTAssertEqual(w.l.partials.count, w.l.progressPartials.count, "every frame carries progress")
        XCTAssertEqual(server.billing()["records"]?[0]?["bytes"]?.intValue, progressPCM.count)
    }

    func testNativeReadingProgressOnTheWire() throws {
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: progressConfig, listener: w)
        try run(s, waiter: w, audio: progressPCM)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        assertProgressRound(w)
        XCTAssertTrue(w.l.progressPartials.allSatisfy { $0.matchedUnits == $0.readUnits })
        XCTAssertTrue(w.l.partials.allSatisfy { $0.raw["result"]?["words"] == nil && $0.raw["recordId"] == nil }, "native frames have no words")
        XCTAssertEqual(w.l.result?.overall, 93.7)
        XCTAssertEqual(server.billing()["billed"]?.intValue, 1)
    }

    /// mockFault progress-mismatch:3: unit 3 (国) read but not matched once the reading passes it.
    func testProgressMismatchOnTheWire() throws {
        server.queueFaults([("/sent.eval.cn", "progress-mismatch:3")])
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluateCompat(coreType: .sentEvalCn, params: progressParams, listener: w)
        try run(s, waiter: w, audio: progressPCM)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        assertProgressRound(w)
        assertCompatFrames(w)
        for p in w.l.progressPartials {
            XCTAssertEqual(p.matchedUnits, p.readUnits > 3 ? p.readUnits - 1 : p.readUnits)
            XCTAssertEqual(p.progress[3], ReadingWord(word: "国", read: p.readUnits > 3, matched: false))
        }
        XCTAssertEqual(wsLog("/sent.eval.cn").first?["fault"]?.stringValue, "progress-mismatch:3")
    }

    /// mockFault progress-off: the platform cannot track the round, frames carry only bytes and
    /// are delivered as such.
    func testProgressOffOnTheWire() throws {
        server.queueFaults([("/api/v1/ws/evaluate", "progress-off")])
        let net = Network()
        let c = client(net)
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: progressConfig, listener: w)
        try run(s, waiter: w, audio: progressPCM)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        let n = progressPCM.count
        XCTAssertEqual(w.l.partials.map { $0.bytes ?? -1 }, (1...(n / 16000)).map { $0 * 16000 } + [n])
        XCTAssertTrue(w.l.partials.allSatisfy { !$0.hasProgress && $0.progress.isEmpty })
        XCTAssertTrue(w.l.partials.allSatisfy { $0.readUnits == -1 && $0.matchedUnits == -1 && $0.totalUnits == -1 })
        XCTAssertEqual(w.l.partialStates.last, .ending)
    }

    /// REPLAY: the server kills the first connection after the start frame and 118 audio frames
    /// (75520 bytes, progress up to 8 units); the replay on the new server session restarts progress
    /// at zero. Delivered progress never goes back, the held back partials are logged at DEBUG.
    func testReplayReadingProgressNeverGoesBackAcrossServerKill() throws {
        server.queueFaults([("/api/v1/ws/evaluate", "ws-kill-after:120")])
        let logger = TestLogger()
        let net = Network()
        let c = client(net) {
            $0.logger = logger
            $0.logLevel = .debug
        }
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: progressConfig, listener: w)
        try run(s, waiter: w, pace: 0.005, audio: progressPCM)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        XCTAssertTrue(w.l.callbacks.contains("reconnecting:1:90001"), "\(w.l.callbacks)")
        XCTAssertTrue(w.l.callbacks.contains("reconnected:1:0"), "\(w.l.callbacks)")
        let progress = w.l.progressPartials
        let read = progress.map { $0.readUnits }
        XCTAssertEqual(read, read.sorted(), "readUnits went back: \(read)")
        XCTAssertEqual(progress.map { $0.matchedUnits }, progress.map { $0.matchedUnits }.sorted())
        XCTAssertGreaterThanOrEqual(read.first ?? 0, 2)
        XCTAssertEqual(read.last, expectedReadUnits.last)
        let dropped = logger.messages(.debug).filter { $0.contains("partial not delivered") }
        XCTAssertFalse(dropped.isEmpty, "the replay restarted the server's progress at zero")
        XCTAssertEqual(wsLog().count, 2)
        XCTAssertEqual(server.billing()["billed"]?.intValue, 1)
        XCTAssertEqual(server.billing()["records"]?[0]?["bytes"]?.intValue, progressPCM.count, "the replay delivered the whole round")
    }

    /// DROP: the server kills the first connection after the start frame and 78 audio frames
    /// (49920 bytes, progress up to 6 units); the new server session gets only the audio sent after
    /// the reconnect, so its progress starts over and is delivered although it is lower.
    func testDropReadingProgressStartsOverAcrossServerKill() throws {
        server.queueFaults([("/api/v1/ws/evaluate", "ws-kill-after:80")])
        let net = Network()
        let c = client(net) {
            $0.audioBufferPolicy = .drop
            $0.reconnect = ReconnectPolicy(initialDelayMs: 100)
        }
        defer { c.close() }
        let w = Waiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: progressConfig, listener: w)
        try run(s, waiter: w, pace: 0.005, audio: progressPCM)
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        XCTAssertTrue(w.l.callbacks.contains("reconnecting:1:90001"), "\(w.l.callbacks)")
        let around = w.l.partialsAroundReconnect
        let before = around.before.filter { $0.hasProgress }.map { $0.readUnits }
        let after = around.after.filter { $0.hasProgress }.map { $0.readUnits }
        XCTAssertGreaterThanOrEqual(before.max() ?? 0, 4, "\(before)")
        XCTAssertFalse(after.isEmpty)
        XCTAssertLessThan(after.first ?? 99, before.max() ?? 0, "progress of the new server session starts over: \(before) then \(after)")
        XCTAssertEqual(after, after.sorted())
        XCTAssertEqual(server.billing()["billed"]?.intValue, 1)
        XCTAssertLessThan(server.billing()["records"]?[0]?["bytes"]?.intValue ?? .max, progressPCM.count, "only the audio after the reconnect")
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
