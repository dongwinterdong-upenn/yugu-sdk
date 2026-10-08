import Foundation

/// State of a streaming session (DESIGN 2.5).
///
/// ```
/// IDLE -> CONNECTING -> CONNECTED (server "connected") -> STARTED (server "started")
/// STARTED -> ENDING (end() sent) -> COMPLETED (final result) -> CLOSED
/// CONNECTING|CONNECTED|STARTED|ENDING -> RECONNECTING -> CONNECTING ...
/// any non-terminal -> FAILED -> CLOSED
/// any non-terminal -> CANCELLED (cancel()) -> CLOSED
/// ```
public enum SessionState: String, Sendable, CaseIterable, CustomStringConvertible {
    case idle = "IDLE"
    case connecting = "CONNECTING"
    case connected = "CONNECTED"
    case started = "STARTED"
    case ending = "ENDING"
    case reconnecting = "RECONNECTING"
    case completed = "COMPLETED"
    case failed = "FAILED"
    case cancelled = "CANCELLED"
    case closed = "CLOSED"

    /// COMPLETED, FAILED, CANCELLED and CLOSED.
    public var isTerminal: Bool {
        switch self {
        case .completed, .failed, .cancelled, .closed: return true
        default: return false
        }
    }

    /// CONNECTING, CONNECTED, STARTED, ENDING and RECONNECTING.
    public var isActive: Bool {
        switch self {
        case .connecting, .connected, .started, .ending, .reconnecting: return true
        default: return false
        }
    }

    public var description: String { rawValue }
}

/// Listener of a streaming session. Only `onResult` and `onError` are required.
///
/// Guarantees: exactly one of `onResult` and `onError` per session, none of them after
/// `cancel()`; `onClosed` is always the last callback; callbacks of a session never run at the
/// same time; `session.state` equals the state of the last `onStateChanged`.
public protocol YuguStreamListener: AnyObject {
    func onStateChanged(_ session: YuguStreamSession, from: SessionState, to: SessionState)
    /// First connection established (server `connected`).
    func onConnected(_ session: YuguStreamSession)
    /// The server accepted the parameters; audio is now forwarded. Only for the first connection,
    /// later connections report `onReconnected`.
    func onStarted(_ session: YuguStreamSession)
    /// Progress frame, compatible sessions with `realtimeFeedback` only.
    func onPartial(_ session: YuguStreamSession, partial: StreamPartial)
    /// The connection broke and reconnect `attempt` starts after `delayMs`.
    func onReconnecting(_ session: YuguStreamSession, attempt: Int, delayMs: Int, cause: YuguError)
    /// Reconnect `attempt` succeeded. With DROP, `droppedBytes` audio was discarded meanwhile.
    func onReconnected(_ session: YuguStreamSession, attempt: Int, droppedBytes: Int)
    /// Local precheck warning at `end()`.
    func onWarning(_ session: YuguStreamSession, warning: YuguWarning)
    func onResult(_ session: YuguStreamSession, result: EvalResult)
    func onError(_ session: YuguStreamSession, error: YuguError)
    /// Last callback of the session.
    func onClosed(_ session: YuguStreamSession, code: Int, reason: String)
}

extension YuguStreamListener {
    public func onStateChanged(_ session: YuguStreamSession, from: SessionState, to: SessionState) {}
    public func onConnected(_ session: YuguStreamSession) {}
    public func onStarted(_ session: YuguStreamSession) {}
    public func onPartial(_ session: YuguStreamSession, partial: StreamPartial) {}
    public func onReconnecting(_ session: YuguStreamSession, attempt: Int, delayMs: Int, cause: YuguError) {}
    public func onReconnected(_ session: YuguStreamSession, attempt: Int, droppedBytes: Int) {}
    public func onWarning(_ session: YuguStreamSession, warning: YuguWarning) {}
    public func onClosed(_ session: YuguStreamSession, code: Int, reason: String) {}
}

/// Closure based listener.
///
/// ```swift
/// let handlers = YuguStreamHandlers()
/// handlers.onResult = { result in print(result.overall ?? 0) }
/// handlers.onError = { error in print(error) }
/// ```
public final class YuguStreamHandlers: YuguStreamListener {
    public var onStateChanged: ((SessionState, SessionState) -> Void)?
    public var onConnected: (() -> Void)?
    public var onStarted: (() -> Void)?
    public var onPartial: ((StreamPartial) -> Void)?
    public var onReconnecting: ((_ attempt: Int, _ delayMs: Int, _ cause: YuguError) -> Void)?
    public var onReconnected: ((_ attempt: Int, _ droppedBytes: Int) -> Void)?
    public var onWarning: ((YuguWarning) -> Void)?
    public var onResult: ((EvalResult) -> Void)?
    public var onError: ((YuguError) -> Void)?
    public var onClosed: ((_ code: Int, _ reason: String) -> Void)?

    public init() {}

    public func onStateChanged(_ session: YuguStreamSession, from: SessionState, to: SessionState) { onStateChanged?(from, to) }
    public func onConnected(_ session: YuguStreamSession) { onConnected?() }
    public func onStarted(_ session: YuguStreamSession) { onStarted?() }
    public func onPartial(_ session: YuguStreamSession, partial: StreamPartial) { onPartial?(partial) }
    public func onReconnecting(_ session: YuguStreamSession, attempt: Int, delayMs: Int, cause: YuguError) { onReconnecting?(attempt, delayMs, cause) }
    public func onReconnected(_ session: YuguStreamSession, attempt: Int, droppedBytes: Int) { onReconnected?(attempt, droppedBytes) }
    public func onWarning(_ session: YuguStreamSession, warning: YuguWarning) { onWarning?(warning) }
    public func onResult(_ session: YuguStreamSession, result: EvalResult) { onResult?(result) }
    public func onError(_ session: YuguStreamSession, error: YuguError) { onError?(error) }
    public func onClosed(_ session: YuguStreamSession, code: Int, reason: String) { onClosed?(code, reason) }
}
