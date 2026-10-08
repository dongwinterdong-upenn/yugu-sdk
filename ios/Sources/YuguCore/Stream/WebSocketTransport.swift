import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Event of a WebSocket connection.
public enum YuguWebSocketEvent {
    /// The handshake completed.
    case opened
    case text(String)
    case binary(Data)
    /// The peer closed the connection. `code` is the close code, 1006 when no close frame came.
    case closed(code: Int, reason: String?)
    /// The connection failed, also when the handshake was rejected.
    case failed(Error)
}

/// WebSocket layer of streaming sessions. The default is `URLSessionWebSocketTransport`.
///
/// A transport carries one connection; every reconnect asks the factory for a new one. Events
/// may be delivered on any thread but in order. After `.closed`, `.failed` or a call to `close`,
/// no further events matter. A handshake rejected with an HTTP status should fail with
/// `YuguErrors.fromHTTP(status:body:)` so that a 401 or 403 is not retried.
public protocol YuguWebSocketTransport: AnyObject {
    func connect(url: URL, headers: [String: String], onEvent: @escaping (YuguWebSocketEvent) -> Void)
    func send(text: String, completion: @escaping (Error?) -> Void)
    func send(data: Data, completion: @escaping (Error?) -> Void)
    /// Sends a protocol ping; `completion` runs when the pong arrives or the ping fails.
    func sendPing(completion: @escaping (Error?) -> Void)
    func close(code: Int, reason: String?)
}

public typealias YuguWebSocketTransportFactory = () -> YuguWebSocketTransport

/// `URLSessionWebSocketTask` transport, one URLSession per connection.
///
/// On Linux, swift-corelibs-foundation builds its WebSocket support on libcurl; with a libcurl
/// that lacks `ws` and `wss` (Ubuntu 24.04 ships 8.5.0 without them) every connection fails with
/// `NSURLErrorDomain -1002` and the session reports INVALID_ARGUMENT (90010). Inject another
/// transport through `YuguClientOptions.webSocketTransportFactory` there.
public final class URLSessionWebSocketTransport: NSObject, YuguWebSocketTransport, URLSessionWebSocketDelegate, @unchecked Sendable {
    private let configuration: URLSessionConfiguration?
    private let lock = NSLock()
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var onEvent: ((YuguWebSocketEvent) -> Void)?
    private var finished = false
    #if canImport(FoundationNetworking)
    private var sendQueue: [(URLSessionWebSocketTask.Message, (Error?) -> Void)] = []
    private var sending = false
    #endif

    public init(configuration: URLSessionConfiguration? = nil) {
        self.configuration = configuration
        super.init()
    }

    public func connect(url: URL, headers: [String: String], onEvent: @escaping (YuguWebSocketEvent) -> Void) {
        // A copy, so the caller's configuration object is never changed.
        let config = (configuration?.copy() as? URLSessionConfiguration) ?? URLSessionConfiguration.ephemeral
        // The session's own timers (handshake, heartbeat, result) bound every wait.
        config.timeoutIntervalForRequest = 3600
        config.timeoutIntervalForResource = 24 * 3600
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        queue.name = "yugu.ws"
        let s = URLSession(configuration: config, delegate: self, delegateQueue: queue)
        var request = URLRequest(url: url)
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        let t = s.webSocketTask(with: request)
        t.maximumMessageSize = 16 * 1024 * 1024
        lock.sync {
            session = s
            task = t
            self.onEvent = onEvent
        }
        t.resume()
        receiveNext(t)
    }

    private func emit(_ event: YuguWebSocketEvent, final: Bool = false) {
        let handler: ((YuguWebSocketEvent) -> Void)? = lock.sync {
            if finished { return nil }
            if final {
                finished = true
                let h = onEvent
                onEvent = nil
                return h
            }
            return onEvent
        }
        handler?(event)
        if final { teardown() }
    }

    private func teardown() {
        let s: URLSession? = lock.sync {
            let s = session
            session = nil
            return s
        }
        s?.finishTasksAndInvalidate()
    }

    private func receiveNext(_ t: URLSessionWebSocketTask) {
        receive(t) { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .success(let message):
                switch message {
                case .string(let s): self.emit(.text(s))
                case .data(let d): self.emit(.binary(d))
                @unknown default: break
                }
                self.receiveNext(t)
            case .failure(let error):
                self.emit(self.failureEvent(t, error), final: true)
            }
        }
    }

    /// Close frame when one arrived, else the handshake status, else the error.
    private func failureEvent(_ t: URLSessionWebSocketTask, _ error: Error) -> YuguWebSocketEvent {
        if t.closeCode != .invalid {
            return .closed(code: t.closeCode.rawValue, reason: t.closeReason.flatMap { String(data: $0, encoding: .utf8) })
        }
        if let http = t.response as? HTTPURLResponse, http.statusCode != 101 {
            return .failed(YuguErrors.fromHTTP(status: http.statusCode, body: nil))
        }
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain && ns.code == URLError.unsupportedURL.rawValue {
            return .failed(YuguErrors.local(
                ErrorCodes.invalidArgument,
                "this platform's URLSession has no WebSocket support (NSURLErrorDomain -1002); set webSocketTransportFactory",
                cause: error))
        }
        return .failed(error)
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        emit(.opened)
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        emit(.closed(code: closeCode.rawValue, reason: reason.flatMap { String(data: $0, encoding: .utf8) }), final: true)
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let t = task as? URLSessionWebSocketTask else { return }
        if let error = error {
            emit(failureEvent(t, error), final: true)
        } else {
            emit(.closed(code: t.closeCode == .invalid ? 1006 : t.closeCode.rawValue, reason: nil), final: true)
        }
    }

    public func send(text: String, completion: @escaping (Error?) -> Void) {
        send(.string(text), completion: completion)
    }

    public func send(data: Data, completion: @escaping (Error?) -> Void) {
        send(.data(data), completion: completion)
    }

    public func sendPing(completion: @escaping (Error?) -> Void) {
        guard let t = lock.sync({ task }) else {
            completion(YuguErrors.local(ErrorCodes.networkError, "not connected"))
            return
        }
        t.sendPing { error in completion(error) }
    }

    public func close(code: Int, reason: String?) {
        let t: URLSessionWebSocketTask? = lock.sync {
            finished = true
            onEvent = nil
            let t = task
            task = nil
            return t
        }
        let closeCode = URLSessionWebSocketTask.CloseCode(rawValue: code) ?? .normalClosure
        t?.cancel(with: closeCode, reason: reason.map { Data($0.utf8) })
        teardown()
    }

    // MARK: Platform differences

    #if canImport(FoundationNetworking)
    // swift-corelibs-foundation offers only the async variants. Sends go through one pump so
    // frames keep their order.
    private func send(_ message: URLSessionWebSocketTask.Message, completion: @escaping (Error?) -> Void) {
        let start: Bool = lock.sync {
            sendQueue.append((message, completion))
            if sending { return false }
            sending = true
            return true
        }
        if start { pump() }
    }

    private func pump() {
        Task {
            while true {
                let next: (URLSessionWebSocketTask.Message, (Error?) -> Void, URLSessionWebSocketTask?)? = self.lock.sync {
                    if self.sendQueue.isEmpty {
                        self.sending = false
                        return nil
                    }
                    let (m, c) = self.sendQueue.removeFirst()
                    return (m, c, self.task)
                }
                guard let item = next else { return }
                let (message, completion, task) = item
                guard let t = task else {
                    completion(YuguErrors.local(ErrorCodes.networkError, "not connected"))
                    continue
                }
                do {
                    try await t.send(message)
                    completion(nil)
                } catch {
                    completion(error)
                }
            }
        }
    }

    private func receive(_ t: URLSessionWebSocketTask, _ handler: @escaping (Result<URLSessionWebSocketTask.Message, Error>) -> Void) {
        Task {
            do {
                handler(.success(try await t.receive()))
            } catch {
                handler(.failure(error))
            }
        }
    }
    #else
    private func send(_ message: URLSessionWebSocketTask.Message, completion: @escaping (Error?) -> Void) {
        guard let t = lock.sync({ task }) else {
            completion(YuguErrors.local(ErrorCodes.networkError, "not connected"))
            return
        }
        // URLSession keeps the order of sends on one task.
        t.send(message) { error in completion(error) }
    }

    private func receive(_ t: URLSessionWebSocketTask, _ handler: @escaping (Result<URLSessionWebSocketTask.Message, Error>) -> Void) {
        t.receive { result in handler(result) }
    }
    #endif
}
