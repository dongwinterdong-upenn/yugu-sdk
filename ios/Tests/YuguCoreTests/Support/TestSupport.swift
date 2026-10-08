import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import YuguCore

// MARK: - Fixtures

enum Fixture {
    static var dir: URL {
        Bundle.module.url(forResource: "Fixtures", withExtension: nil)!
    }

    static func data(_ path: String) -> Data {
        try! Data(contentsOf: dir.appendingPathComponent(path))
    }

    static func json(_ path: String) -> JSONValue {
        try! JSONValue.parse(data(path))
    }

    static func text(_ path: String) -> String {
        String(decoding: data(path), as: UTF8.self)
    }

    /// The PCM samples of a WAV fixture, from its `data` chunk (some fixtures carry a LIST chunk).
    static func pcm(_ path: String) -> Data {
        let wav = data(path)
        let info = YuguWAV.parse(wav)!
        return wav.subdata(in: info.dataOffset..<(info.dataOffset + info.dataLength))
    }
}

// MARK: - Virtual time

/// Executor with virtual time. Work runs on the test thread when the test calls
/// `runUntilIdle()` or `advance(_:)`.
final class VirtualExecutor: YuguExecutor {
    final class Timer: YuguCancellable {
        let due: Int64
        let seq: Int
        let work: () -> Void
        var cancelled = false
        init(due: Int64, seq: Int, work: @escaping () -> Void) {
            self.due = due
            self.seq = seq
            self.work = work
        }
        func cancel() { cancelled = true }
    }

    private let lock = NSLock()
    private var now: Int64 = 1_000_000
    private var queue: [() -> Void] = []
    private var timers: [Timer] = []
    private var seq = 0
    /// Delays passed to `schedule`, in order.
    private(set) var scheduledDelays: [Int] = []

    func nowMs() -> Int64 { lock.sync { now } }

    func async(_ work: @escaping () -> Void) {
        lock.sync { queue.append(work) }
    }

    func schedule(afterMs ms: Int, _ work: @escaping () -> Void) -> YuguCancellable {
        lock.sync {
            seq += 1
            scheduledDelays.append(ms)
            let t = Timer(due: now + Int64(max(0, ms)), seq: seq, work: work)
            timers.append(t)
            return t
        }
    }

    func runUntilIdle() {
        while true {
            let w: (() -> Void)? = lock.sync { queue.isEmpty ? nil : queue.removeFirst() }
            guard let work = w else { return }
            work()
        }
    }

    /// Moves time forward by `ms`, firing due timers in order.
    func advance(_ ms: Int64) {
        let target = lock.sync { now + ms }
        runUntilIdle()
        while true {
            let next: Timer? = lock.sync {
                timers.removeAll { $0.cancelled }
                let due = timers.filter { $0.due <= target }
                guard let t = due.min(by: { ($0.due, $0.seq) < ($1.due, $1.seq) }) else { return nil }
                timers.removeAll { $0 === t }
                now = max(now, t.due)
                return t
            }
            guard let t = next else { break }
            if !t.cancelled { t.work() }
            runUntilIdle()
        }
        lock.sync { now = target }
        runUntilIdle()
    }

    /// Live timers, for "no silent hang" assertions.
    var liveTimers: Int {
        lock.sync { timers.filter { !$0.cancelled }.count }
    }
}

// MARK: - Fake HTTP

final class FakeHTTPTransport: YuguHTTPTransport {
    enum Reply {
        case status(Int, String, [String: String])
        case error(Error)
        case hang
    }

    final class Handle: YuguCancellable {
        var onCancel: (() -> Void)?
        func cancel() {
            let c = onCancel
            onCancel = nil
            c?()
        }
    }

    private let lock = NSLock()
    var replies: [Reply] = []
    var defaultReply: Reply = .status(200, "{}", [:])
    private(set) var requests: [YuguHTTPRequest] = []
    private(set) var closed = false

    func send(_ request: YuguHTTPRequest, completion: @escaping (Result<YuguHTTPResponse, Error>) -> Void) -> YuguCancellable {
        let reply: Reply = lock.sync {
            requests.append(request)
            return replies.isEmpty ? defaultReply : replies.removeFirst()
        }
        let h = Handle()
        switch reply {
        case .status(let code, let body, let headers):
            completion(.success(YuguHTTPResponse(statusCode: code, headers: headers, body: Data(body.utf8))))
        case .error(let e):
            completion(.failure(e))
        case .hang:
            h.onCancel = { completion(.failure(URLError(.cancelled))) }
        }
        return h
    }

    func close() {
        lock.sync { closed = true }
    }

    var requestCount: Int { lock.sync { requests.count } }
}

// MARK: - Fake WebSocket server

/// Scripted platform for streaming tests. It follows the wire protocol of the real platform
/// and the mock server, including idempotent final evaluation.
final class FakeWSServer {
    enum Fault {
        /// Transport failure after N binary frames on this connection.
        case killAfterAudioFrames(Int)
        /// Close frame with the code after N binary frames.
        case closeAfterAudioFrames(Int, code: Int)
        /// Handshake rejected with an HTTP status.
        case refuse(status: Int)
        /// Connection fails at once.
        case failConnect
        /// Never sends `connected`.
        case silentHandshake
        /// Ignores `end`.
        case noResult
        /// Answers `end` with an error frame.
        case errorOnEnd(code: Int?)
        /// No pong for pings.
        case noPong
    }

    final class Connection: YuguWebSocketTransport {
        unowned let server: FakeWSServer
        let index: Int
        var fault: Fault?
        var url: URL?
        var headers: [String: String] = [:]
        var onEvent: ((YuguWebSocketEvent) -> Void)?
        var texts: [String] = []
        var audio = Data()
        var audioFrames = 0
        var frameSizes: [Int] = []
        var pings = 0
        var closedWith: (code: Int, reason: String?)?
        var params: JSONValue?
        var pendingPongs: [(Error?) -> Void] = []

        init(server: FakeWSServer, index: Int, fault: Fault?) {
            self.server = server
            self.index = index
            self.fault = fault
        }

        var query: [String: String] {
            var out: [String: String] = [:]
            for item in URLComponents(url: url!, resolvingAgainstBaseURL: false)?.queryItems ?? [] {
                out[item.name] = item.value
            }
            return out
        }

        func emit(_ e: YuguWebSocketEvent) { onEvent?(e) }
        func emitJSON(_ s: String) { emit(.text(s)) }

        func connect(url: URL, headers: [String: String], onEvent: @escaping (YuguWebSocketEvent) -> Void) {
            self.url = url
            self.headers = headers
            self.onEvent = onEvent
            switch fault {
            case .refuse(let status)?:
                emit(.failed(YuguErrors.fromHTTP(status: status, body: nil)))
            case .failConnect?:
                emit(.failed(URLError(.cannotConnectToHost)))
            case .silentHandshake?:
                emit(.opened)
            default:
                emit(.opened)
                emitJSON(server.isNative(url) ? #"{"event":"connected","message":"stream channel ready"}"# : #"{"event":"connected","coreType":"sent.eval.cn"}"#)
            }
        }

        func send(text: String, completion: @escaping (Error?) -> Void) {
            texts.append(text)
            completion(nil)
            server.handleText(self, text)
        }

        func send(data: Data, completion: @escaping (Error?) -> Void) {
            completion(nil)
            guard closedWith == nil else { return }
            audio.append(data)
            audioFrames += 1
            frameSizes.append(data.count)
            server.allAudioFrames += 1
            switch fault {
            case .killAfterAudioFrames(let n)? where audioFrames >= n:
                fault = nil
                emit(.failed(URLError(.networkConnectionLost)))
            case .closeAfterAudioFrames(let n, let code)? where audioFrames >= n:
                fault = nil
                emit(.closed(code: code, reason: "mock"))
            default:
                break
            }
        }

        func sendPing(completion: @escaping (Error?) -> Void) {
            pings += 1
            if case .noPong? = fault {
                pendingPongs.append(completion)
            } else {
                completion(nil)
            }
        }

        func close(code: Int, reason: String?) {
            closedWith = (code, reason)
            onEvent = nil
        }
    }

    private(set) var connections: [Connection] = []
    /// Fault per connection index (0-based).
    var faults: [Int: Fault] = [:]
    /// Fault decided when a connection opens, for time based scenarios; wins over `faults`.
    var faultProvider: ((Int) -> Fault?)?
    var resultFixture = "platform/native_evaluate_sentence_zh.json"
    /// Billed evaluations per idempotency key.
    private(set) var billing: [String: Int] = [:]
    private var stored: [String: (fingerprint: String, recordId: String)] = [:]
    private var recordSeq = 0
    var allAudioFrames = 0
    var partialEvery = 0

    func factory() -> YuguWebSocketTransportFactory {
        { [unowned self] in
            let index = self.connections.count
            let c = Connection(server: self, index: index, fault: self.faultProvider?(index) ?? self.faults[index])
            self.connections.append(c)
            return c
        }
    }

    func isNative(_ url: URL) -> Bool { url.path == "/api/v1/ws/evaluate" }

    var totalBilled: Int { billing.values.reduce(0, +) }

    func handleText(_ c: Connection, _ text: String) {
        guard c.closedWith == nil, let j = try? JSONValue.parse(text) else { return }
        if j["cmd"]?.stringValue == "end" {
            switch c.fault {
            case .noResult?:
                return
            case .errorOnEnd(let code)?:
                c.fault = nil
                if let code = code {
                    c.emitJSON(#"{"event":"error","code":\#(code),"message":"mock error"}"#)
                } else {
                    c.emitJSON(#"{"event":"error","message":"no audio"}"#)
                }
                return
            default:
                break
            }
            finish(c)
            return
        }
        // Start frame (native) or parameter frame (compat).
        c.params = j
        c.audio = Data()
        c.emitJSON(isNative(c.url!) ? #"{"event":"started"}"# : #"{"event":"started","coreType":"sent.eval.cn"}"#)
    }

    private func finish(_ c: Connection) {
        guard c.audio.count > 0 else {
            c.emitJSON(#"{"event":"error","message":"no audio"}"#)
            return
        }
        let key = c.params?["idempotencyKey"]?.stringValue ?? c.query["idempotencyKey"]
        var p = c.params?.objectValue ?? [:]
        p.removeValue(forKey: "idempotencyKey")
        let fp = JSONValue.object(p).jsonText + ":" + SHA256.hash(Array(c.audio)).hexString
        var result = Fixture.json(resultFixture).objectValue!
        var replayed = false
        if let k = key, let s = stored[k] {
            if s.fingerprint != fp {
                c.emitJSON(#"{"event":"error","code":40903,"message":"key reused"}"#)
                return
            }
            result["recordId"] = .string(s.recordId)
            replayed = true
        } else {
            recordSeq += 1
            let rid = "eval_fake\(recordSeq)"
            result["recordId"] = .string(rid)
            billing[key ?? "(none)", default: 0] += 1
            if let k = key { stored[k] = (fp, rid) }
        }
        if isNative(c.url!) { result["event"] = "result" }
        if replayed { result["replayed"] = true }
        c.emitJSON(JSONValue.object(result).jsonText)
    }
}

// MARK: - Recorders

final class RecordingListener: YuguStreamListener {
    private let lock = NSLock()
    private(set) var events: [String] = []
    private(set) var result: EvalResult?
    private(set) var error: YuguError?
    private(set) var warnings: [YuguWarning] = []
    /// `session.state` read inside each `onStateChanged`, paired with `to`.
    private(set) var stateChecks: [(seen: SessionState, announced: SessionState)] = []
    private var inCallback = false
    private(set) var overlapped = false

    private func record(_ s: String) {
        lock.sync { events.append(s) }
    }

    private func enter() {
        lock.sync {
            if inCallback { overlapped = true }
            inCallback = true
        }
    }

    private func leave() {
        lock.sync { inCallback = false }
    }

    func onStateChanged(_ session: YuguStreamSession, from: SessionState, to: SessionState) {
        enter()
        defer { leave() }
        lock.sync { stateChecks.append((session.state, to)) }
        record("state:\(from)->\(to)")
    }

    func onConnected(_ session: YuguStreamSession) { enter(); record("connected"); leave() }
    func onStarted(_ session: YuguStreamSession) { enter(); record("started"); leave() }
    func onPartial(_ session: YuguStreamSession, partial: StreamPartial) { enter(); record("partial:\(partial.bytes ?? -1)"); leave() }
    func onReconnecting(_ session: YuguStreamSession, attempt: Int, delayMs: Int, cause: YuguError) {
        enter(); record("reconnecting:\(attempt):\(cause.code)"); leave()
    }
    func onReconnected(_ session: YuguStreamSession, attempt: Int, droppedBytes: Int) {
        enter(); record("reconnected:\(attempt):\(droppedBytes)"); leave()
    }
    func onWarning(_ session: YuguStreamSession, warning: YuguWarning) {
        enter(); lock.sync { warnings.append(warning) }; record("warning:\(warning.code)"); leave()
    }
    func onResult(_ session: YuguStreamSession, result: EvalResult) {
        enter(); lock.sync { self.result = result }; record("result"); leave()
    }
    func onError(_ session: YuguStreamSession, error: YuguError) {
        enter(); lock.sync { self.error = error }; record("error:\(error.code)"); leave()
    }
    func onClosed(_ session: YuguStreamSession, code: Int, reason: String) {
        enter(); record("closed:\(code)"); leave()
    }

    /// Events other than state changes.
    var callbacks: [String] { lock.sync { events.filter { !$0.hasPrefix("state:") } } }
    var states: [String] { lock.sync { events.filter { $0.hasPrefix("state:") } } }
    var terminalCallbacks: [String] { lock.sync { events.filter { $0 == "result" || $0.hasPrefix("error:") } } }
}

final class TestLogger: YuguLogger {
    private let lock = NSLock()
    private(set) var lines: [(level: YuguLogLevel, tag: String, message: String)] = []

    func log(level: YuguLogLevel, tag: String, message: String, error: Error?) {
        let m = error.map { message + " | \($0)" } ?? message
        lock.sync { lines.append((level, tag, m)) }
    }

    var all: String { lock.sync { lines.map { "\($0.level.label) \($0.tag) \($0.message)" }.joined(separator: "\n") } }
    func messages(_ level: YuguLogLevel) -> [String] { lock.sync { lines.filter { $0.level == level }.map { $0.message } } }
}

final class RecordingEvents: YuguEventListener {
    private let lock = NSLock()
    private(set) var log: [String] = []

    func onRequestStart(op: String, method: String, path: String, attempt: Int) {
        lock.sync { log.append("start:\(op):\(attempt)") }
    }
    func onRequestEnd(op: String, httpStatus: Int, latencyMs: Int, attempts: Int, error: YuguError?) {
        lock.sync { log.append("end:\(op):\(httpStatus):\(attempts):\(error?.code ?? 0)") }
    }
    func onRetry(op: String, attempt: Int, delayMs: Int, error: YuguError) {
        lock.sync { log.append("retry:\(op):\(attempt):\(delayMs):\(error.code)") }
    }
    func onSessionStateChanged(sessionId: String, from: SessionState, to: SessionState) {
        lock.sync { log.append("session:\(to)") }
    }
    func onReconnect(sessionId: String, attempt: Int, succeeded: Bool) {
        lock.sync { log.append("reconnect:\(attempt):\(succeeded)") }
    }
    var entries: [String] { lock.sync { log } }
}

// MARK: - Client factories

struct TestClient {
    let client: YuguClient
    let executor: VirtualExecutor
    let http: FakeHTTPTransport
    let ws: FakeWSServer
    let logger: TestLogger
    let events: RecordingEvents
}

func makeTestClient(_ configure: (inout YuguClientOptions) -> Void = { _ in }) -> TestClient {
    let executor = VirtualExecutor()
    let http = FakeHTTPTransport()
    let ws = FakeWSServer()
    let logger = TestLogger()
    let events = RecordingEvents()
    var o = YuguClientOptions(auth: .appKey("ak_test_key", secretKey: "sk_test_secret"), baseUrl: "https://api.example.test")
    o.httpTransport = http
    o.webSocketTransportFactory = ws.factory()
    o.logger = logger
    o.logLevel = .debug
    o.eventListener = events
    configure(&o)
    let client = YuguClient(options: o, executor: executor, sink: InlineCallbackSink(), random: SeededRandom(seed: 42))
    return TestClient(client: client, executor: executor, http: http, ws: ws, logger: logger, events: events)
}

/// 16 kHz mono PCM: a 440 Hz tone at the given amplitude.
func tonePCM(seconds: Double, amplitude: Double = 8000, sampleRate: Int = 16000) -> Data {
    let n = Int(seconds * Double(sampleRate))
    var d = Data(capacity: n * 2)
    for i in 0..<n {
        let v = Int16(amplitude * sin(2 * Double.pi * 440 * Double(i) / Double(sampleRate)))
        var le = v.littleEndian
        withUnsafeBytes(of: &le) { d.append(contentsOf: $0) }
    }
    return d
}

func frames(_ pcm: Data, size: Int = 640) -> [Data] {
    stride(from: 0, to: pcm.count, by: size).map { pcm.subdata(in: $0..<min($0 + size, pcm.count)) }
}

// MARK: - Mock platform server

/// Spawns `node tools/mock-server/server.mjs --port 0`. Returns nil when node or the server
/// script is missing, so the standalone package still tests without the monorepo.
final class MockServer {
    let port: Int
    private let stopProcess: () -> Void
    private let session = URLSession(configuration: .ephemeral)

    static let appKey = "mock-app-key"
    static let secretKey = "mock-secret-key"
    static let token = "mock-jwt-token"

    var baseUrl: String { "http://127.0.0.1:\(port)" }

    private init(port: Int, stop: @escaping () -> Void) {
        self.port = port
        self.stopProcess = stop
    }

    static func scriptPath() -> String? {
        if let p = ProcessInfo.processInfo.environment["YUGU_MOCK_SERVER"], FileManager.default.fileExists(atPath: p) { return p }
        // Tests/YuguCoreTests/Support/TestSupport.swift -> repo root
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let p = url.appendingPathComponent("tools/mock-server/server.mjs").path
        return FileManager.default.fileExists(atPath: p) ? p : nil
    }

    static func nodePath() -> String? {
        let env = ProcessInfo.processInfo.environment
        if let n = env["YUGU_NODE"], FileManager.default.isExecutableFile(atPath: n) { return n }
        for dir in (env["PATH"] ?? "").split(separator: ":") {
            let p = String(dir) + "/node"
            if FileManager.default.isExecutableFile(atPath: p) { return p }
        }
        for p in ["/usr/local/bin/node", "/opt/homebrew/bin/node", "/usr/bin/node"] where FileManager.default.isExecutableFile(atPath: p) {
            return p
        }
        return nil
    }

    /// Nil when the server cannot run here: no node, no monorepo, or a platform without
    /// `Process` (iOS Simulator; the macOS job runs the integration tests with `swift test`).
    static func launch(processingMs: Int = 50) throws -> MockServer? {
        guard let script = scriptPath() else { return nil }
        guard let spawned = try NodeProcess.spawn(script: script, arguments: ["--port", "0", "--processing-ms", String(processingMs)], quiet: false) else {
            return nil
        }
        return MockServer(port: spawned.port, stop: spawned.stop)
    }

    func stop() {
        stopProcess()
    }

    @discardableResult
    func request(_ method: String, _ path: String, body: String? = nil) -> JSONValue {
        var r = URLRequest(url: URL(string: baseUrl + path)!)
        r.httpMethod = method
        if let b = body {
            r.httpBody = Data(b.utf8)
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let sem = DispatchSemaphore(value: 0)
        final class Box: @unchecked Sendable { var value: JSONValue = .null }
        let box = Box()
        session.dataTask(with: r) { data, _, _ in
            if let d = data, let j = try? JSONValue.parse(d) { box.value = j }
            sem.signal()
        }.resume()
        _ = sem.wait(timeout: .now() + 10)
        return box.value
    }

    func reset() { request("POST", "/__mock/reset") }

    /// Queues faults, each `(pathPrefix, fault)`.
    func queueFaults(_ faults: [(String, String)]) {
        let items = faults.map { #"{"match":\#(JSONWriter.quote($0.0)),"fault":\#(JSONWriter.quote($0.1))}"# }
        request("POST", "/__mock/faults", body: #"{"faults":[\#(items.joined(separator: ","))]}"#)
    }

    func log() -> [JSONValue] { request("GET", "/__mock/log").arrayValue ?? [] }
    func billing() -> JSONValue { request("GET", "/__mock/billing") }
}


/// Spawns a Node script that prints one line `{"port": N}` once it listens.
enum NodeProcess {
    struct Spawned {
        let port: Int
        let stop: () -> Void
    }

    /// Nil when node is missing or the platform has no `Process`.
    static func spawn(script: String, arguments: [String], quiet: Bool) throws -> Spawned? {
        #if os(macOS) || os(Linux)
        guard let node = MockServer.nodePath() else { return nil }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: node)
        p.arguments = [script] + arguments
        let out = Pipe()
        p.standardOutput = out
        p.standardError = quiet ? FileHandle.nullDevice : FileHandle.standardError
        try p.run()
        let handle = out.fileHandleForReading
        var buffer = Data()
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline {
            let chunk = handle.availableData
            if chunk.isEmpty { break }
            buffer.append(chunk)
            if let nl = buffer.firstIndex(of: 0x0A) {
                let line = buffer.prefix(upTo: nl)
                if let j = try? JSONValue.parse(Data(line)), let port = j["port"]?.intValue {
                    return Spawned(port: port) {
                        if p.isRunning {
                            p.terminate()
                            p.waitUntilExit()
                        }
                    }
                }
            }
        }
        p.terminate()
        throw NSError(domain: "NodeProcess", code: 1, userInfo: [NSLocalizedDescriptionKey: "\((script as NSString).lastPathComponent) did not report a port"])
        #else
        return nil
        #endif
    }
}
