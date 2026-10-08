import Foundation

/// Effective settings of one session.
struct SessionSettings {
    var reconnect: ReconnectPolicy
    var bufferPolicy: AudioBufferPolicy
    var connectTimeoutMs: Int
    var resultTimeoutMs: Int
    var pingIntervalMs: Int
    var pongTimeoutMs: Int
    var precheck: AudioPrecheckMode
    var strictAudio: Bool

    init(_ o: StreamOptions, _ c: YuguClientOptions) {
        reconnect = o.reconnect ?? c.reconnect
        bufferPolicy = o.audioBufferPolicy ?? c.audioBufferPolicy
        connectTimeoutMs = o.connectTimeoutMs ?? c.connectTimeoutMs
        resultTimeoutMs = o.resultTimeoutMs ?? c.resultTimeoutMs
        pingIntervalMs = o.pingIntervalMs ?? c.pingIntervalMs
        pongTimeoutMs = o.pongTimeoutMs ?? c.pongTimeoutMs
        precheck = o.audioPrecheck ?? c.audioPrecheck
        strictAudio = c.strictAudio
    }
}

/// A streaming evaluation over WebSocket (DESIGN 2.5, requirements A-03 and B-05).
///
/// Create it with `YuguClient.streamEvaluate` or `streamEvaluateCompat`, call `start()`, feed
/// 16 kHz mono 16-bit PCM with `sendAudio(_:)` (640 bytes = 20 ms per call is a good size), then
/// call `end()`. The listener receives the result or the error, then `onClosed`.
///
/// Reconnect: a transport failure, an abnormal close, a missing pong, the handshake timeout, the
/// result timeout or a retryable error frame opens a new server session with the same parameters
/// and idempotency key. The platform cannot resume a server session, so with REPLAY the SDK sends
/// the start frame again, replays the audio of the round, sends the audio queued meanwhile, and
/// sends `end` again when `end()` was already called. The same key makes the platform bill once.
public final class YuguStreamSession {
    enum Kind {
        case native(EvaluateConfig)
        case compat(CompatCoreType, CompatParams)
    }

    /// Local id, also used in `YuguEventListener.onSessionStateChanged`.
    public let sessionId: String
    /// Idempotency key sent in the handshake and the start frame of every connection.
    public let idempotencyKey: String?

    /// REPLAY keeps at most this many bytes.
    public static let maxReplayBytes = 10 * 1024 * 1024
    /// Audio given to `sendAudio` is sent in frames of at most this many bytes; the platform
    /// closes frames above 128 KB with 1009.
    public static let maxFrameBytes = 32000
    /// Close codes that end the session at once with PROTOCOL_ERROR (90005): a new connection
    /// would be refused the same way. 4000 to 4999 are included as well.
    public static let terminalCloseCodes: Set<Int> = [1002, 1003, 1007, 1008, 1009, 1010]

    // Public side, guarded by `lock`.
    private let lock = NSLock()
    private var announced: SessionState = .idle
    private var listener: YuguStreamListener?
    private var startRequested = false
    private var endRequestedPublic = false
    private var cancelRequested = false
    private var terminalPublic = false

    // Executor side.
    private let core: ClientCore
    private let kind: Kind
    private let settings: SessionSettings
    private let op: String
    private var phase: SessionState = .idle
    private var transport: YuguWebSocketTransport?
    private var connectionSeq = 0
    private var connections = 0
    /// Reconnect attempts since the last successful connection; reset when a reconnect succeeds.
    private var failedAttempts = 0
    /// Reconnect attempts in the whole session, at most 3 times `maxAttempts`.
    private var totalReconnects = 0
    private var inReconnectRound = false
    private var everConnected = false
    private var everStarted = false
    private var endRequested = false
    private var endSent = false
    private var unsent: [Data] = []
    private var unsentBytes = 0
    private var replay: [Data] = []
    private var replayBytes = 0
    private var overflowed = false
    private var droppedBytes = 0
    private var stats = PCMStats()
    private var localWarnings: [YuguWarning] = []
    private var lastCloseCode: Int?
    private var awaitingPong = false
    private var handshakeTimer: YuguCancellable?
    private var pingTimer: YuguCancellable?
    private var pongTimer: YuguCancellable?
    private var resultTimer: YuguCancellable?
    private var reconnectTimer: YuguCancellable?

    init(core: ClientCore, kind: Kind, idempotencyKey: String?, settings: SessionSettings, listener: YuguStreamListener) {
        self.core = core
        self.kind = kind
        self.idempotencyKey = idempotencyKey
        self.settings = settings
        self.listener = listener
        sessionId = "ws-" + core.random.hex(byteCount: 6)
        switch kind {
        case .native: op = "streamEvaluate"
        case .compat: op = "streamEvaluateCompat"
        }
    }

    // MARK: Public API

    /// State announced by the last `onStateChanged`.
    public var state: SessionState { lock.sync { announced } }

    /// State announced by the last `onStateChanged`.
    public func getState() -> SessionState { lock.sync { announced } }

    /// True while connecting, connected, started, ending or reconnecting.
    public var isActive: Bool { getState().isActive }

    /// Replaces or removes (`nil`) the listener.
    public func setListener(_ l: YuguStreamListener?) {
        lock.sync { listener = l }
    }

    /// Opens the connection. Throws INVALID_STATE (90009) when called twice or after the end.
    public func start() throws {
        try lock.sync {
            if cancelRequested || terminalPublic { throw YuguErrors.local(ErrorCodes.invalidState, "session already ended") }
            if startRequested { throw YuguErrors.local(ErrorCodes.invalidState, "start() already called") }
            startRequested = true
        }
        core.executor.async { self.handleStart() }
    }

    /// Queues 16 kHz mono 16-bit little-endian PCM. Thread-safe. Audio given before the server
    /// accepted the parameters is held and sent in order. Throws INVALID_STATE (90009) after
    /// `end()`, `cancel()` or the end of the session.
    public func sendAudio(_ data: Data) throws {
        guard !data.isEmpty else { return }
        try lock.sync {
            if cancelRequested || terminalPublic { throw YuguErrors.local(ErrorCodes.invalidState, "session is not active") }
            if endRequestedPublic { throw YuguErrors.local(ErrorCodes.invalidState, "sendAudio after end()") }
        }
        core.executor.async { self.handleAudio(data) }
    }

    /// No more audio; asks for the final result. A second call does nothing. Throws
    /// INVALID_STATE (90009) before `start()` or after the end of the session.
    public func end() throws {
        let first: Bool = try lock.sync {
            if !startRequested { throw YuguErrors.local(ErrorCodes.invalidState, "end() before start()") }
            if cancelRequested || terminalPublic { throw YuguErrors.local(ErrorCodes.invalidState, "session is not active") }
            if endRequestedPublic { return false }
            endRequestedPublic = true
            return true
        }
        if first { core.executor.async { self.handleEnd() } }
    }

    /// Stops the session without a result: no `onResult`, no `onError`, then `onClosed`.
    /// Idempotent.
    public func cancel() {
        let first: Bool = lock.sync {
            if cancelRequested || terminalPublic { return false }
            cancelRequested = true
            return true
        }
        if first { core.executor.async { self.handleCancel() } }
    }

    /// Cancels the session when it is still active. Idempotent.
    public func close() {
        cancel()
    }

    /// Called by `YuguClient.close()`.
    func cancelByClient() {
        cancel()
    }

    // MARK: Delivery

    private func deliver(_ body: @escaping (YuguStreamListener) -> Void) {
        core.sink.deliver {
            let l: YuguStreamListener? = self.lock.sync { self.listener }
            if let l = l { body(l) }
        }
    }

    private func transition(_ next: SessionState) {
        let old = phase
        guard old != next else { return }
        phase = next
        if next.isTerminal { lock.sync { terminalPublic = true } }
        core.events?.onSessionStateChanged(sessionId: sessionId, from: old, to: next)
        core.log.debug(op, "\(sessionId) \(old) -> \(next)")
        core.sink.deliver {
            let l: YuguStreamListener? = self.lock.sync {
                self.announced = next
                return self.listener
            }
            l?.onStateChanged(self, from: old, to: next)
        }
    }

    // MARK: Handlers (executor)

    private func handleStart() {
        guard phase == .idle, !cancelRequestedNow else { return }
        if !core.register(self) {
            transition(.failed)
            let e = YuguErrors.local(ErrorCodes.clientClosed).with(idempotencyKey: idempotencyKey, attempts: 0)
            deliver { $0.onError(self, error: e) }
            finishClose(code: 1000, reason: e.message)
            return
        }
        if idempotencyKey == nil && settings.reconnect.enabled && settings.bufferPolicy != .fail {
            core.log.debug(op, "\(sessionId) has no idempotency key, reconnect disabled")
        }
        transition(.connecting)
        openConnection()
    }

    private var cancelRequestedNow: Bool { lock.sync { cancelRequested } }

    private func openConnection() {
        connectionSeq += 1
        connections += 1
        let seq = connectionSeq
        endSent = false
        awaitingPong = false
        lastCloseCode = nil
        let url: URL
        do {
            url = try buildURL()
        } catch {
            fail(YuguErrors.wrap(error))
            return
        }
        let t = core.wsFactory()
        transport = t
        core.log.debug(op, "\(sessionId) connecting \(url.absoluteString), connection \(connections)")
        t.connect(url: url, headers: ["User-Agent": core.options.userAgent]) { [weak self] event in
            guard let self = self else { return }
            self.core.executor.async { self.onTransportEvent(seq, event) }
        }
        let ms = settings.connectTimeoutMs
        handshakeTimer = core.executor.schedule(afterMs: ms) { [weak self] in
            guard let self = self, seq == self.connectionSeq, self.phase == .connecting || self.phase == .connected else { return }
            self.connectionLost(YuguErrors.local(ErrorCodes.timeout, "no started frame within \(ms) ms"))
        }
    }

    private func buildURL() throws -> URL {
        let path: String
        switch kind {
        case .native: path = "/api/v1/ws/evaluate"
        case .compat(let ct, _): path = "/" + ct.rawValue
        }
        var business: [(String, String)] = []
        if let k = idempotencyKey { business.append(("idempotencyKey", k)) }
        let query = RequestSigner.encodeQuery(core.signer.webSocketQuery(business))
        let base = core.wsBaseUrl
        let lower = base.lowercased()
        guard lower.hasPrefix("wss://") || lower.hasPrefix("ws://"), let url = URL(string: base + path + "?" + query) else {
            throw YuguErrors.local(ErrorCodes.invalidArgument, "invalid wsBaseUrl \(base)")
        }
        return url
    }

    private func onTransportEvent(_ seq: Int, _ event: YuguWebSocketEvent) {
        guard seq == connectionSeq, !phase.isTerminal, phase != .reconnecting else { return }
        switch event {
        case .opened:
            core.log.debug(op, "\(sessionId) socket open")
        case .text(let s):
            handleFrame(s)
        case .binary(let d):
            if let s = String(data: d, encoding: .utf8) { handleFrame(s) }
        case .closed(let code, let reason):
            lastCloseCode = code
            releaseDeadTransport()
            let why = reason.map { ", \($0)" } ?? ""
            if code == 1000 {
                fail(YuguErrors.local(ErrorCodes.protocolError, "server closed the connection (1000\(why)) before the result"))
            } else if YuguStreamSession.terminalCloseCodes.contains(code) || (4000...4999).contains(code) {
                fail(YuguErrors.local(ErrorCodes.protocolError, "server closed the connection with code \(code)\(why), not retried"))
            } else {
                connectionLost(YuguErrors.local(ErrorCodes.networkError, "connection closed with code \(code)\(why)"))
            }
        case .failed(let error):
            releaseDeadTransport()
            connectionLost(YuguErrors.wrap(error))
        }
    }

    /// The peer closed or the connection failed: drop the transport and let it free its
    /// resources. The close code reported to the listener stays the peer's.
    private func releaseDeadTransport() {
        let t = transport
        transport = nil
        connectionSeq += 1
        t?.close(code: 1000, reason: nil)
    }

    private func handleFrame(_ text: String) {
        let json: JSONValue
        do {
            json = try JSONValue.parse(text)
        } catch {
            fail(YuguErrors.local(ErrorCodes.protocolError, "frame is not JSON", cause: error))
            return
        }
        switch json["event"]?.stringValue {
        case "connected":
            guard phase == .connecting else { return }
            transition(.connected)
            if !everConnected {
                everConnected = true
                deliver { $0.onConnected(self) }
            }
            sendStartFrame()
        case "started":
            guard phase == .connected else {
                core.log.debug(op, "\(sessionId) extra started frame ignored")
                return
            }
            handshakeTimer?.cancel()
            handshakeTimer = nil
            transition(.started)
            startHeartbeat()
            if inReconnectRound {
                inReconnectRound = false
                let attempt = failedAttempts
                failedAttempts = 0
                let dropped = droppedBytes
                droppedBytes = 0
                core.events?.onReconnect(sessionId: sessionId, attempt: attempt, succeeded: true)
                core.log.info(op, "\(sessionId) reconnect \(attempt) succeeded")
                deliver { $0.onReconnected(self, attempt: attempt, droppedBytes: dropped) }
            }
            if !everStarted {
                everStarted = true
                deliver { $0.onStarted(self) }
            }
            flushAudio()
            if endRequested { sendEnd() }
        case "pong":
            return
        case "error":
            let e = YuguErrors.fromErrorFrame(json)
            if e.retryable {
                connectionLost(e)
            } else {
                fail(e)
            }
        case "result":
            complete(json)
        default:
            if let eof = json["eof"]?.intValue {
                if eof == 1 {
                    complete(json)
                } else {
                    let p = StreamPartial(bytes: json["result"]?["bytes"]?.intValue, raw: json)
                    deliver { $0.onPartial(self, partial: p) }
                }
            } else {
                core.log.debug(op, "\(sessionId) unknown frame ignored")
            }
        }
    }

    private func send(text: String) {
        let seq = connectionSeq
        transport?.send(text: text) { [weak self] error in
            guard let self = self, let error = error else { return }
            self.core.executor.async {
                guard seq == self.connectionSeq else { return }
                self.connectionLost(YuguErrors.wrap(error))
            }
        }
    }

    private func sendStartFrame() {
        var frame: JSONObjectBuilder
        switch kind {
        case .native(let config):
            frame = JSONObjectBuilder()
            frame.set("cmd", "start")
            for e in config.jsonObject().entries {
                frame.set(e.key, e.value)
            }
            frame.set("idempotencyKey", idempotencyKey)
        case .compat(_, let params):
            frame = params.parameterFrame(idempotencyKey: idempotencyKey)
        }
        send(text: frame.text)
    }

    private func flushAudio() {
        guard phase == .started || phase == .ending, let t = transport else { return }
        let seq = connectionSeq
        let chunks = unsent
        unsent.removeAll()
        unsentBytes = 0
        for chunk in chunks {
            t.send(data: chunk) { [weak self] error in
                guard let self = self, let error = error else { return }
                self.core.executor.async {
                    guard seq == self.connectionSeq else { return }
                    self.connectionLost(YuguErrors.wrap(error))
                }
            }
            if settings.bufferPolicy == .replay && !overflowed {
                replay.append(chunk)
                replayBytes += chunk.count
            }
        }
        checkOverflow()
    }

    private func enqueue(_ data: Data) {
        unsent.append(data)
        unsentBytes += data.count
    }

    private func checkOverflow() {
        guard settings.bufferPolicy == .replay, !overflowed, replayBytes + unsentBytes > YuguStreamSession.maxReplayBytes else { return }
        overflowed = true
        replay.removeAll()
        replayBytes = 0
        core.log.warn(op, "\(sessionId) replay buffer passed 10 MB, a reconnect would fail with 90008")
    }

    private func handleAudio(_ data: Data) {
        guard !phase.isTerminal, !endRequested else { return }
        if inReconnectRound && settings.bufferPolicy == .drop {
            droppedBytes += data.count
            return
        }
        stats.add(data)
        switch phase {
        case .idle, .connecting, .connected, .reconnecting:
            enqueueFrames(data)
            checkOverflow()
        case .started:
            enqueueFrames(data)
            flushAudio()
        default:
            break
        }
    }

    /// Queues the audio in frames of at most `maxFrameBytes`, so no frame, replayed ones
    /// included, can exceed the platform's frame limit.
    private func enqueueFrames(_ data: Data) {
        let limit = YuguStreamSession.maxFrameBytes
        if data.count <= limit {
            enqueue(data)
            return
        }
        var offset = data.startIndex
        while offset < data.endIndex {
            let end = min(offset + limit, data.endIndex)
            enqueue(data.subdata(in: offset..<end))
            offset = end
        }
    }

    private func handleEnd() {
        guard !phase.isTerminal, !endRequested else { return }
        endRequested = true
        if settings.precheck != .off {
            let report = AudioPrecheck.check(stats: stats)
            do {
                let warnings = try AudioPrecheck.apply(report, mode: settings.precheck)
                for w in warnings {
                    localWarnings.append(w)
                    core.log.warn(op, "\(sessionId) audio precheck: \(w)")
                    deliver { $0.onWarning(self, warning: w) }
                }
            } catch {
                // Rejected: end is never sent, so the platform does not evaluate or bill.
                fail(YuguErrors.wrap(error))
                return
            }
        }
        if phase == .started { sendEnd() }
    }

    private func sendEnd() {
        guard phase == .started, transport != nil else { return }
        flushAudio()
        send(text: "{\"cmd\":\"end\"}")
        endSent = true
        transition(.ending)
        let seq = connectionSeq
        let ms = settings.resultTimeoutMs
        resultTimer = core.executor.schedule(afterMs: ms) { [weak self] in
            guard let self = self, seq == self.connectionSeq, self.phase == .ending else { return }
            self.connectionLost(YuguErrors.local(ErrorCodes.resultTimeout, "no result within \(ms) ms after end()"))
        }
    }

    private func startHeartbeat() {
        pingTimer?.cancel()
        pingTimer = nil
        guard settings.pingIntervalMs > 0 else { return }
        let seq = connectionSeq
        pingTimer = core.executor.schedule(afterMs: settings.pingIntervalMs) { [weak self] in
            self?.heartbeat(seq)
        }
    }

    private func heartbeat(_ seq: Int) {
        guard seq == connectionSeq, phase == .started || phase == .ending, let t = transport else { return }
        if !awaitingPong {
            awaitingPong = true
            t.sendPing { [weak self] error in
                guard let self = self else { return }
                self.core.executor.async {
                    guard seq == self.connectionSeq, !self.phase.isTerminal else { return }
                    if let error = error {
                        self.connectionLost(YuguErrors.wrap(error))
                    } else {
                        self.awaitingPong = false
                        self.pongTimer?.cancel()
                        self.pongTimer = nil
                    }
                }
            }
            let ms = settings.pongTimeoutMs
            pongTimer = core.executor.schedule(afterMs: ms) { [weak self] in
                guard let self = self, seq == self.connectionSeq, self.awaitingPong else { return }
                self.connectionLost(YuguErrors.local(ErrorCodes.timeout, "no pong within \(ms) ms"))
            }
        }
        pingTimer = core.executor.schedule(afterMs: settings.pingIntervalMs) { [weak self] in
            self?.heartbeat(seq)
        }
    }

    private func cancelConnectionTimers() {
        handshakeTimer?.cancel()
        pingTimer?.cancel()
        pongTimer?.cancel()
        resultTimer?.cancel()
        handshakeTimer = nil
        pingTimer = nil
        pongTimer = nil
        resultTimer = nil
        awaitingPong = false
    }

    private func closeTransport(code: Int, reason: String) {
        connectionSeq += 1
        if let t = transport {
            transport = nil
            lastCloseCode = code
            t.close(code: code, reason: reason)
        }
    }

    /// A reconnect trigger: decide between a new connection and failure.
    private func connectionLost(_ cause: YuguError) {
        guard !phase.isTerminal, phase != .reconnecting else { return }
        cancelConnectionTimers()
        closeTransport(code: 1001, reason: "reconnect")
        if inReconnectRound {
            core.events?.onReconnect(sessionId: sessionId, attempt: failedAttempts, succeeded: false)
        }
        if !cause.retryable {
            fail(cause)
            return
        }
        if settings.bufferPolicy == .fail || !settings.reconnect.enabled || idempotencyKey == nil {
            fail(cause)
            return
        }
        if settings.bufferPolicy == .drop && endSent {
            // The audio is gone and end was sent: a new server session would have nothing to score.
            fail(cause)
            return
        }
        if settings.bufferPolicy == .replay && overflowed {
            fail(YuguErrors.local(ErrorCodes.replayBufferOverflow, "replay buffer passed 10 MB, cannot reconnect", cause: cause))
            return
        }
        if failedAttempts >= settings.reconnect.maxAttempts {
            fail(YuguErrors.local(
                ErrorCodes.reconnectExhausted,
                "\(settings.reconnect.maxAttempts) reconnect attempt(s) failed, last cause: \(cause.message)", cause: cause))
            return
        }
        let totalLimit = 3 * max(1, settings.reconnect.maxAttempts)
        if totalReconnects >= totalLimit {
            fail(YuguErrors.local(
                ErrorCodes.reconnectExhausted,
                "\(totalLimit) reconnects in this session, the limit, last cause: \(cause.message)", cause: cause))
            return
        }
        totalReconnects += 1
        failedAttempts += 1
        inReconnectRound = true
        endSent = false
        if settings.bufferPolicy == .replay {
            unsent = replay + unsent
            unsentBytes += replayBytes
            replay.removeAll()
            replayBytes = 0
        }
        let attempt = failedAttempts
        let delay = settings.reconnect.delayMs(attempt: attempt, unit: core.random.nextUnit())
        transition(.reconnecting)
        core.log.warn(op, "\(sessionId) reconnect \(attempt)/\(settings.reconnect.maxAttempts) in \(delay) ms: \(cause.summary) \(cause.message)")
        deliver { $0.onReconnecting(self, attempt: attempt, delayMs: delay, cause: cause) }
        reconnectTimer = core.executor.schedule(afterMs: delay) { [weak self] in
            guard let self = self, self.phase == .reconnecting else { return }
            self.reconnectTimer = nil
            self.transition(.connecting)
            self.openConnection()
        }
    }

    private func fail(_ error: YuguError) {
        guard !phase.isTerminal else { return }
        cancelConnectionTimers()
        reconnectTimer?.cancel()
        reconnectTimer = nil
        closeTransport(code: 1000, reason: "error")
        let e = error.with(idempotencyKey: idempotencyKey, attempts: connections)
        transition(.failed)
        core.log.warn(op, "\(sessionId) failed: \(e)")
        deliver { $0.onError(self, error: e) }
        finishClose(code: lastCloseCode ?? 1006, reason: e.message)
    }

    private func complete(_ json: JSONValue) {
        guard phase == .connected || phase == .started || phase == .ending else { return }
        var result = ResultParser.evalResult(json)
        result.localWarnings = localWarnings
        result.idempotencyKey = idempotencyKey
        result.replayed = json["replayed"]?.boolValue ?? false
        result.attempts = connections
        if settings.strictAudio && result.hasNoValidAudio {
            fail(YuguErrors.fromWarning(WarningCode.noValidAudio.rawValue).with(recordId: result.recordId))
            return
        }
        cancelConnectionTimers()
        closeTransport(code: 1000, reason: "completed")
        transition(.completed)
        core.log.info(op, "\(sessionId) completed, record \(result.recordId ?? "-"), connections \(connections)")
        deliver { $0.onResult(self, result: result) }
        finishClose(code: 1000, reason: "completed")
    }

    private func handleCancel() {
        guard !phase.isTerminal else { return }
        cancelConnectionTimers()
        reconnectTimer?.cancel()
        reconnectTimer = nil
        closeTransport(code: 1000, reason: "cancelled")
        transition(.cancelled)
        finishClose(code: 1000, reason: "cancelled")
    }

    private func finishClose(code: Int, reason: String) {
        transition(.closed)
        unsent.removeAll()
        replay.removeAll()
        core.unregister(self)
        core.sink.deliver {
            let l: YuguStreamListener? = self.lock.sync {
                let l = self.listener
                self.listener = nil
                return l
            }
            l?.onClosed(self, code: code, reason: reason)
        }
    }
}
