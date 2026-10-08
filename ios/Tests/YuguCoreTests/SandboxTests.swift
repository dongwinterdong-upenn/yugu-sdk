import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import YuguCore

/// End to end against the sandbox tenant (SANDBOX.md, DESIGN 10), mirroring the Java and Android
/// sandbox tests.
///
/// Runs only when `YUGU_SANDBOX_APPKEY` and `YUGU_SANDBOX_SECRET` are set, for example in the
/// nightly CI; push builds skip every case and never call the platform. `YUGU_SANDBOX_BASE` (or
/// `YUGU_SANDBOX_BASE_URL`) overrides the default base `https://open.shengzhiai.com`.
///
/// Call budget: 4 platform calls per run (the replayed second evaluation is not counted against the
/// daily quota), 5 at most if the stream needs its single allowed reconnect. Retries are off and
/// reconnects are limited to keep a run within 6 calls. Nothing here prints the keys; SDK logs mask
/// the appKey and never contain the secret or a signature.
final class SandboxTests: XCTestCase {
    struct Sandbox {
        let appKey: String
        let secret: String
        let base: String
    }

    static let sandbox: Sandbox? = {
        let env = ProcessInfo.processInfo.environment
        guard let key = env["YUGU_SANDBOX_APPKEY"], !key.isEmpty, let secret = env["YUGU_SANDBOX_SECRET"], !secret.isEmpty else {
            return nil
        }
        var base = env["YUGU_SANDBOX_BASE"] ?? ""
        if base.isEmpty { base = env["YUGU_SANDBOX_BASE_URL"] ?? "" }
        if base.isEmpty { base = YuguClientOptions.defaultBaseUrl }
        return Sandbox(appKey: key, secret: secret, base: base)
    }()

    private var sb: Sandbox!

    override func setUpWithError() throws {
        guard let s = SandboxTests.sandbox else {
            throw XCTSkip("sandbox keys not set: export YUGU_SANDBOX_APPKEY and YUGU_SANDBOX_SECRET to run the end-to-end tests against the sandbox")
        }
        sb = s
    }

    private func options() -> YuguClientOptions {
        var o = YuguClientOptions(auth: .appKey(sb.appKey, secretKey: sb.secret), baseUrl: sb.base)
        o.retry = .disabled
        o.reconnect = ReconnectPolicy(maxAttempts: 1)
        o.callbackQueue = DispatchQueue(label: "sandbox.callbacks")
        return o
    }

    private let zh = EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好", language: "zh-CN")
    private var wav: AudioInput { .file(Fixture.dir.appendingPathComponent("audio/zh_short.wav")) }

    /// Native REST evaluate twice with one idempotency key: the second is a replay of the first.
    func test1NativeEvaluateTwiceWithOneKeyIsReplayed() async throws {
        let c = YuguClient(options: options())
        defer { c.close() }
        let key = "sandbox-ios-" + IdempotencyKey.generate()
        let first = try await c.evaluate(audio: wav, config: zh, options: RequestOptions(idempotencyKey: key))
        let second = try await c.evaluate(audio: wav, config: zh, options: RequestOptions(idempotencyKey: key))
        let overall = try XCTUnwrap(first.overall, "the first result has no overall")
        XCTAssertGreaterThan(overall, 0)
        XCTAssertLessThanOrEqual(overall, 100)
        XCTAssertNotNil(first.recordId)
        XCTAssertFalse(first.replayed)
        XCTAssertTrue(second.replayed, "the second submission with the same key is a replay")
        XCTAssertEqual(second.recordId, first.recordId)
        XCTAssertEqual(second.overall, first.overall)
        XCTAssertEqual(first.attempts, 1)
        print("[sandbox] native evaluate overall \(overall), recordId \(first.recordId ?? "-"), second replayed \(second.replayed)")
    }

    /// Engine compatible REST evaluate `sent.eval.cn`.
    func test2CompatEvaluateSentEvalCn() async throws {
        let c = YuguClient(options: options())
        defer { c.close() }
        let r = try await c.evaluateCompat(coreType: .sentEvalCn, audio: wav, params: CompatParams(refText: "今天天气很好", language: "zh-CN"))
        let overall = try XCTUnwrap(r.overall, "the compat result has no overall")
        XCTAssertGreaterThan(overall, 0)
        XCTAssertLessThanOrEqual(overall, 100)
        XCTAssertEqual(r.eof, 1)
        XCTAssertFalse(r.words.isEmpty)
        print("[sandbox] compat sent.eval.cn overall \(overall), recordId \(r.recordId ?? "-")")
    }

    /// Native streaming end to end: callbacks in order, a numeric overall.
    func test3NativeStreamingEndToEnd() throws {
        var o = options()
        #if canImport(FoundationNetworking)
        // Linux: the URLSession of swift-corelibs-foundation cannot open WebSockets, so the SDK's own
        // session runs over the test-only POSIX WebSocket client (Support/PosixWebSocket.swift). That
        // client speaks plain ws, so a local Node bridge terminates TLS towards the sandbox; the signed
        // handshake query passes through unchanged. On Apple platforms the real URLSession transport
        // connects to the sandbox directly.
        guard let bridge = try WssBridge.start(target: WssBridge.wsBase(of: sb.base)) else {
            throw XCTSkip("node or tools/mock-server/node_modules/ws not found, the Linux streaming sandbox test needs them")
        }
        defer { bridge.stop() }
        o.wsBaseUrl = "ws://127.0.0.1:\(bridge.port)"
        o.webSocketTransportFactory = { PosixWebSocketTransport() }
        #endif
        let c = YuguClient(options: o)
        defer { c.close() }
        let w = SandboxWaiter(expectation(description: "closed"))
        let s = try c.streamEvaluate(config: zh, listener: w)
        try s.start()
        // The session may fail early (a refused handshake); the callbacks below then tell why.
        for f in frames(Fixture.pcm("audio/zh_short.wav")) {
            try? s.sendAudio(f)
            Thread.sleep(forTimeInterval: 0.005)
        }
        try? s.end()
        wait(for: [w.closed], timeout: 120)

        let callbacks = w.l.callbacks
        XCTAssertEqual(w.l.terminalCallbacks, ["result"], "\(w.l.events)")
        XCTAssertEqual(callbacks.first, "connected")
        XCTAssertEqual(callbacks.dropFirst().first, "started")
        XCTAssertEqual(callbacks.last, "closed:1000")
        XCTAssertEqual(callbacks.dropLast().last, "result")
        XCTAssertEqual(w.l.states.first, "state:IDLE->CONNECTING")
        XCTAssertEqual(w.l.states.suffix(2), ["state:ENDING->COMPLETED", "state:COMPLETED->CLOSED"])
        XCTAssertFalse(w.l.overlapped)
        for check in w.l.stateChecks { XCTAssertEqual(check.seen, check.announced) }
        let result = try XCTUnwrap(w.l.result)
        let overall = try XCTUnwrap(result.overall, "the streaming result has no overall")
        XCTAssertGreaterThan(overall, 0)
        XCTAssertLessThanOrEqual(overall, 100)
        XCTAssertEqual(result.idempotencyKey, s.idempotencyKey)
        XCTAssertEqual(s.state, .closed)
        print("[sandbox] native streaming overall \(overall), recordId \(result.recordId ?? "-"), connections \(result.attempts), callbacks \(callbacks.joined(separator: " "))")
    }
}

/// Forwards every callback to a recording listener and signals `onClosed`.
final class SandboxWaiter: YuguStreamListener {
    let l = RecordingListener()
    let closed: XCTestExpectation

    init(_ closed: XCTestExpectation) {
        self.closed = closed
    }

    func onStateChanged(_ s: YuguStreamSession, from: SessionState, to: SessionState) { l.onStateChanged(s, from: from, to: to) }
    func onConnected(_ s: YuguStreamSession) { l.onConnected(s) }
    func onStarted(_ s: YuguStreamSession) { l.onStarted(s) }
    func onPartial(_ s: YuguStreamSession, partial: StreamPartial) { l.onPartial(s, partial: partial) }
    func onReconnecting(_ s: YuguStreamSession, attempt: Int, delayMs: Int, cause: YuguError) { l.onReconnecting(s, attempt: attempt, delayMs: delayMs, cause: cause) }
    func onReconnected(_ s: YuguStreamSession, attempt: Int, droppedBytes: Int) { l.onReconnected(s, attempt: attempt, droppedBytes: droppedBytes) }
    func onWarning(_ s: YuguStreamSession, warning: YuguWarning) { l.onWarning(s, warning: warning) }
    func onResult(_ s: YuguStreamSession, result: EvalResult) { l.onResult(s, result: result) }
    func onError(_ s: YuguStreamSession, error: YuguError) { l.onError(s, error: error) }
    func onClosed(_ s: YuguStreamSession, code: Int, reason: String) {
        l.onClosed(s, code: code, reason: reason)
        closed.fulfill()
    }
}

#if os(macOS) || os(Linux)
/// Local `ws://` to remote `wss://` relay for the Linux streaming test. Frames, close codes and the
/// handshake query (with its signature) pass through unchanged. The relay prints nothing but its
/// port, so no URL or key reaches the logs.
final class WssBridge {
    let port: Int
    private let stopProcess: () -> Void
    private let scriptURL: URL

    private init(port: Int, stop: @escaping () -> Void, scriptURL: URL) {
        self.port = port
        self.stopProcess = stop
        self.scriptURL = scriptURL
    }

    func stop() {
        stopProcess()
        try? FileManager.default.removeItem(at: scriptURL)
    }

    /// `https://host` becomes `wss://host`, `http://host` becomes `ws://host`.
    static func wsBase(of base: String) -> String {
        var b = base
        while b.hasSuffix("/") { b.removeLast() }
        if b.lowercased().hasPrefix("https://") { return "wss://" + b.dropFirst("https://".count) }
        if b.lowercased().hasPrefix("http://") { return "ws://" + b.dropFirst("http://".count) }
        return b
    }

    /// Nil when node or the `ws` package of tools/mock-server is missing.
    static func start(target: String) throws -> WssBridge? {
        guard let mock = MockServer.scriptPath() else { return nil }
        let wsDir = URL(fileURLWithPath: mock).deletingLastPathComponent().appendingPathComponent("node_modules/ws")
        guard FileManager.default.fileExists(atPath: wsDir.appendingPathComponent("package.json").path) else { return nil }
        let script = FileManager.default.temporaryDirectory.appendingPathComponent("yugu-wss-bridge-\(UUID().uuidString).mjs")
        try source.write(to: script, atomically: true, encoding: .utf8)
        guard let spawned = try NodeProcess.spawn(script: script.path, arguments: [wsDir.path, target], quiet: true) else {
            try? FileManager.default.removeItem(at: script)
            return nil
        }
        return WssBridge(port: spawned.port, stop: spawned.stop, scriptURL: script)
    }

    static let source = #"""
    import { createRequire } from 'node:module';
    const [, , wsDir, target] = process.argv;
    const require = createRequire(wsDir + '/package.json');
    const WebSocket = require(wsDir);
    const Server = WebSocket.WebSocketServer || WebSocket.Server;
    const server = new Server({ host: '127.0.0.1', port: 0, maxPayload: 16 * 1024 * 1024 });
    server.on('listening', () => process.stdout.write(JSON.stringify({ port: server.address().port }) + '\n'));
    const sendable = (c) => (c >= 1000 && c <= 1003) || (c >= 1007 && c <= 1014) || (c >= 3000 && c <= 4999);
    const shut = (ws, code, reason) => {
      try {
        if (ws.readyState > 1) return;
        if (sendable(code)) ws.close(code, reason); else ws.terminate();
      } catch (e) { try { ws.terminate(); } catch (_) {} }
    };
    server.on('connection', (client, req) => {
      const upstream = new WebSocket(target + req.url, { headers: { 'User-Agent': req.headers['user-agent'] || 'yugu-ios-sdk-test' } });
      const queue = [];
      upstream.on('open', () => { for (const [d, b] of queue) upstream.send(d, { binary: b }); queue.length = 0; });
      client.on('message', (d, b) => { if (upstream.readyState === 1) upstream.send(d, { binary: b }); else queue.push([d, b]); });
      upstream.on('message', (d, b) => { if (client.readyState === 1) client.send(d, { binary: b }); });
      upstream.on('close', (code, reason) => shut(client, code, reason));
      client.on('close', (code, reason) => shut(upstream, code, reason));
      upstream.on('unexpected-response', (r, res) => { shut(client, 1008, 'handshake ' + res.statusCode); try { r.destroy(); } catch (_) {} });
      upstream.on('error', () => { try { client.terminate(); } catch (_) {} });
      client.on('error', () => { try { upstream.terminate(); } catch (_) {} });
    });
    process.on('SIGTERM', () => process.exit(0));
    """#
}
#endif
