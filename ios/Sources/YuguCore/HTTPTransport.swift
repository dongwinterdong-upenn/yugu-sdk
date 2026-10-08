import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// One HTTP request of one attempt.
public struct YuguHTTPRequest {
    public var method: String
    public var url: URL
    public var headers: [(name: String, value: String)]
    public var body: Data?
    /// Time allowed until the request body starts to flow (TCP and TLS connect).
    public var connectTimeoutMs: Int
    /// Time allowed for the whole attempt. The SDK also enforces it with its own timer.
    public var readTimeoutMs: Int

    public init(method: String, url: URL, headers: [(name: String, value: String)], body: Data?, connectTimeoutMs: Int, readTimeoutMs: Int) {
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
        self.connectTimeoutMs = connectTimeoutMs
        self.readTimeoutMs = readTimeoutMs
    }

    /// First value of a header, case-insensitive.
    public func header(_ name: String) -> String? {
        headers.first { $0.name.caseInsensitiveCompare(name) == .orderedSame }?.value
    }
}

/// One HTTP response.
public struct YuguHTTPResponse {
    public var statusCode: Int
    /// Header names in lowercase.
    public var headers: [String: String]
    public var body: Data

    public init(statusCode: Int, headers: [String: String], body: Data) {
        self.statusCode = statusCode
        var h: [String: String] = [:]
        for (k, v) in headers { h[k.lowercased()] = v }
        self.headers = h
        self.body = body
    }

    public func header(_ name: String) -> String? {
        headers[name.lowercased()]
    }
}

/// HTTP layer of the client. The default is `URLSessionHTTPTransport`.
///
/// A transport sends one attempt and reports the response or the failure. Retries, timeouts of
/// the logical call, idempotency and signing stay in the client. Failures should be `URLError`s or
/// `YuguError`s; anything else is treated as a network error.
public protocol YuguHTTPTransport: AnyObject {
    func send(_ request: YuguHTTPRequest, completion: @escaping (Result<YuguHTTPResponse, Error>) -> Void) -> YuguCancellable
    /// Releases resources. Called by `YuguClient.close()`.
    func close()
}

/// `URLSession` transport with a connect timeout: an attempt that has not started to send its
/// body after `connectTimeoutMs` fails with TIMEOUT (90002).
public final class URLSessionHTTPTransport: YuguHTTPTransport {
    private let session: URLSession
    private let delegate: SessionDelegate

    public init(configuration: URLSessionConfiguration? = nil) {
        let config = configuration ?? URLSessionHTTPTransport.defaultConfiguration()
        delegate = SessionDelegate()
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        queue.name = "yugu.http"
        session = URLSession(configuration: config, delegate: delegate, delegateQueue: queue)
    }

    deinit {
        session.invalidateAndCancel()
    }

    /// Ephemeral configuration without cache and cookies. Per-attempt timeouts come from the
    /// client, so the session-level ones are only a backstop.
    public static func defaultConfiguration() -> URLSessionConfiguration {
        let c = URLSessionConfiguration.ephemeral
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.urlCache = nil
        c.httpShouldSetCookies = false
        c.httpCookieAcceptPolicy = .never
        c.timeoutIntervalForRequest = 600
        c.timeoutIntervalForResource = 3600
        return c
    }

    public func send(_ request: YuguHTTPRequest, completion: @escaping (Result<YuguHTTPResponse, Error>) -> Void) -> YuguCancellable {
        var r = URLRequest(url: request.url)
        r.httpMethod = request.method
        for h in request.headers {
            r.setValue(h.value, forHTTPHeaderField: h.name)
        }
        // Backstop only, the client's own timer fires first.
        r.timeoutInterval = Double(max(1, request.readTimeoutMs)) / 1000.0 + 1.0
        let task: URLSessionTask
        if let body = request.body {
            task = session.uploadTask(with: r, from: body)
        } else {
            task = session.dataTask(with: r)
        }
        let state = TaskState(completion: completion)
        delegate.register(task, state)
        if request.body != nil, request.connectTimeoutMs > 0 {
            let ms = request.connectTimeoutMs
            let timer = DispatchWorkItem { [weak task, weak state] in
                if state?.fail(YuguErrors.local(ErrorCodes.timeout, "connect timeout after \(ms) ms")) == true {
                    task?.cancel()
                }
            }
            state.setConnectTimer(timer)
            DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(ms), execute: timer)
        }
        task.resume()
        return TaskHandle(task: task, state: state)
    }

    public func close() {
        session.invalidateAndCancel()
    }

    // MARK: Internals

    final class TaskState {
        private let lock = NSLock()
        private var completion: ((Result<YuguHTTPResponse, Error>) -> Void)?
        private var connected = false
        private var connectTimer: DispatchWorkItem?
        var response: HTTPURLResponse?
        var data = Data()

        init(completion: @escaping (Result<YuguHTTPResponse, Error>) -> Void) {
            self.completion = completion
        }

        func setConnectTimer(_ t: DispatchWorkItem) {
            lock.sync { connectTimer = t }
        }

        func markConnected() {
            let timer: DispatchWorkItem? = lock.sync {
                if connected { return nil }
                connected = true
                let t = connectTimer
                connectTimer = nil
                return t
            }
            timer?.cancel()
        }

        /// Completes with an error unless already completed or connected. Returns true when it did.
        func fail(_ error: Error) -> Bool {
            let c: ((Result<YuguHTTPResponse, Error>) -> Void)? = lock.sync {
                guard !connected, let c = completion else { return nil }
                completion = nil
                connectTimer = nil
                return c
            }
            c?(.failure(error))
            return c != nil
        }

        func finish(_ result: Result<YuguHTTPResponse, Error>) {
            let (c, timer): (((Result<YuguHTTPResponse, Error>) -> Void)?, DispatchWorkItem?) = lock.sync {
                let c = completion
                completion = nil
                let t = connectTimer
                connectTimer = nil
                return (c, t)
            }
            timer?.cancel()
            c?(result)
        }
    }

    final class TaskHandle: YuguCancellable {
        private weak var task: URLSessionTask?
        private let state: TaskState

        init(task: URLSessionTask, state: TaskState) {
            self.task = task
            self.state = state
        }

        func cancel() {
            state.finish(.failure(YuguErrors.local(ErrorCodes.cancelled)))
            task?.cancel()
        }
    }

    final class SessionDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
        private let lock = NSLock()
        private var states: [Int: TaskState] = [:]

        func register(_ task: URLSessionTask, _ state: TaskState) {
            lock.sync { states[task.taskIdentifier] = state }
        }

        private func state(_ task: URLSessionTask) -> TaskState? {
            lock.sync { states[task.taskIdentifier] }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
            state(task)?.markConnected()
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
            if let s = state(dataTask) {
                s.markConnected()
                s.response = response as? HTTPURLResponse
            }
            completionHandler(.allow)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            state(dataTask)?.data.append(data)
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            let s: TaskState? = lock.sync { states.removeValue(forKey: task.taskIdentifier) }
            guard let s = s else { return }
            if let error = error {
                s.finish(.failure(error))
                return
            }
            let http = s.response ?? (task.response as? HTTPURLResponse)
            guard let resp = http else {
                s.finish(.failure(YuguErrors.local(ErrorCodes.protocolError, "response is not HTTP")))
                return
            }
            var headers: [String: String] = [:]
            for (k, v) in resp.allHeaderFields {
                headers[String(describing: k).lowercased()] = String(describing: v)
            }
            s.finish(.success(YuguHTTPResponse(statusCode: resp.statusCode, headers: headers, body: s.data)))
        }
    }
}
