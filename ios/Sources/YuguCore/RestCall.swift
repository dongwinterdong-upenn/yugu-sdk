import Foundation

/// Shared internals of one client: options, transports, executor, logging, registry.
final class ClientCore {
    let options: YuguClientOptions
    let baseUrl: String
    let wsBaseUrl: String
    let http: YuguHTTPTransport
    let ownsHTTP: Bool
    let executor: YuguExecutor
    let sink: CallbackSink
    let log: LogSink
    let events: YuguEventListener?
    let signer: RequestSigner
    let random: YuguRandom
    let wsFactory: YuguWebSocketTransportFactory

    private let lock = NSLock()
    private var closed = false
    private var calls: [ObjectIdentifier: RestCallBase] = [:]
    private var sessions: [ObjectIdentifier: YuguStreamSession] = [:]

    init(
        options: YuguClientOptions, executor: YuguExecutor, sink: CallbackSink, random: YuguRandom,
        wallClock: @escaping () -> Date
    ) {
        self.options = options
        baseUrl = YuguClientOptions.trimSlash(options.baseUrl)
        wsBaseUrl = options.effectiveWsBaseUrl
        if let t = options.httpTransport {
            http = t
            ownsHTTP = false
        } else {
            http = URLSessionHTTPTransport(configuration: options.urlSessionConfiguration)
            ownsHTTP = true
        }
        self.executor = executor
        self.sink = sink
        log = LogSink(level: options.logLevel, logger: options.logger ?? ConsoleLogger(), redactor: Redactor(auth: options.auth))
        events = options.eventListener
        self.random = random
        signer = RequestSigner(auth: options.auth, random: random, wallClock: wallClock)
        let config = options.urlSessionConfiguration
        wsFactory = options.webSocketTransportFactory ?? { URLSessionWebSocketTransport(configuration: config) }
    }

    var isClosed: Bool { lock.sync { closed } }

    func register(_ c: RestCallBase) {
        lock.sync { calls[ObjectIdentifier(c)] = c }
    }

    func unregister(_ c: RestCallBase) {
        lock.sync { _ = calls.removeValue(forKey: ObjectIdentifier(c)) }
    }

    /// Registers an active session. Returns false when the client is closed.
    func register(_ s: YuguStreamSession) -> Bool {
        lock.sync {
            if closed { return false }
            sessions[ObjectIdentifier(s)] = s
            return true
        }
    }

    func unregister(_ s: YuguStreamSession) {
        lock.sync { _ = sessions.removeValue(forKey: ObjectIdentifier(s)) }
    }

    /// Marks the client closed once and returns what was active.
    func markClosed() -> ([RestCallBase], [YuguStreamSession])? {
        lock.sync {
            if closed { return nil }
            closed = true
            return (Array(calls.values), Array(sessions.values))
        }
    }

    var activeCount: (calls: Int, sessions: Int) {
        lock.sync { (calls.count, sessions.count) }
    }

    func url(path: String) throws -> URL {
        let lower = baseUrl.lowercased()
        guard lower.hasPrefix("https://") || lower.hasPrefix("http://"), let u = URL(string: baseUrl + path) else {
            throw YuguErrors.local(ErrorCodes.invalidArgument, "invalid baseUrl \(baseUrl)")
        }
        return u
    }
}

protocol RestCallBase: AnyObject {
    func abort(_ error: YuguError)
}

/// What one logical REST call sends and how its success body is parsed.
struct CallPlan<T> {
    let op: String
    let method: String
    let path: String
    let url: URL
    let body: Data?
    let contentType: String?
    let signParams: [String: String]
    let isWrite: Bool
    let parse: (YuguHTTPResponse, CallInfo) -> Result<T, YuguError>
}

struct CallInfo {
    let idempotencyKey: String?
    let attempts: Int
    let replayed: Bool
}

/// One logical REST call: attempts, per-attempt timeout, retry with backoff and Retry-After,
/// total deadline, cancellation (DESIGN 2.2, 2.3). Runs on the client executor.
final class RestCall<T>: RestCallBase {
    private let core: ClientCore
    private let plan: CallPlan<T>
    let key: String?
    private let policy: RetryPolicy
    private let retryEnabled: Bool
    private let totalMs: Int
    private let readMs: Int
    private let sink: CallbackSink
    private var completion: ((Result<T, YuguError>) -> Void)?
    private let token: YuguCancellationToken?
    private var tokenRegistration: Int?

    private var attempt = 0
    private var finished = false
    private var startMs: Int64 = 0
    private var deadlineMs: Int64 = 0
    private var inflight: YuguCancellable?
    private var watchdog: YuguCancellable?
    private var retryTimer: YuguCancellable?
    private var lastStatus = 0
    private var lastError: YuguError?

    init(core: ClientCore, plan: CallPlan<T>, key: String?, options: RequestOptions, sink: CallbackSink, completion: @escaping (Result<T, YuguError>) -> Void) {
        self.core = core
        self.plan = plan
        self.key = key
        policy = options.retry ?? core.options.retry
        retryEnabled = !plan.isWrite || key != nil
        totalMs = max(1, options.timeoutMs ?? core.options.totalTimeoutMs)
        readMs = max(1, options.readTimeoutMs ?? core.options.readTimeoutMs)
        self.sink = sink
        self.completion = completion
        token = options.cancellationToken
    }

    func start() {
        core.register(self)
        if let t = token {
            tokenRegistration = t.onCancel { [weak self] in self?.cancel() }
        }
        core.executor.async { self.begin() }
    }

    func cancel() {
        core.executor.async {
            self.finish(.failure(YuguErrors.local(ErrorCodes.cancelled, "\(self.plan.op) cancelled").with(idempotencyKey: self.key, attempts: self.attempt)))
        }
    }

    func abort(_ error: YuguError) {
        core.executor.async {
            self.finish(.failure(error.with(idempotencyKey: self.key, attempts: self.attempt)))
        }
    }

    private func begin() {
        guard !finished else { return }
        if core.isClosed {
            finish(.failure(YuguErrors.local(ErrorCodes.clientClosed).with(idempotencyKey: key, attempts: 0)))
            return
        }
        startMs = core.executor.nowMs()
        deadlineMs = startMs + Int64(totalMs)
        if !retryEnabled && policy.maxRetries > 0 {
            core.log.debug(plan.op, "no idempotency key and autoIdempotencyKey is off, retries disabled")
        }
        runAttempt()
    }

    private func runAttempt() {
        guard !finished else { return }
        let now = core.executor.nowMs()
        let remaining = deadlineMs - now
        if remaining <= 0 {
            finish(.failure(lastError ?? YuguErrors.local(ErrorCodes.timeout, "total timeout of \(totalMs) ms reached").with(idempotencyKey: key, attempts: attempt)))
            return
        }
        attempt += 1
        let n = attempt
        let attemptMs = Int(min(Int64(readMs), remaining))
        var headers: [(name: String, value: String)] = [("User-Agent", core.options.userAgent), ("Accept", "application/json")]
        if let ct = plan.contentType { headers.append(("Content-Type", ct)) }
        if let k = key { headers.append(("Idempotency-Key", k)) }
        for (name, value) in core.signer.headers(signing: plan.signParams) {
            headers.append((name, value))
        }
        let request = YuguHTTPRequest(
            method: plan.method, url: plan.url, headers: headers, body: plan.body,
            connectTimeoutMs: min(core.options.connectTimeoutMs, attemptMs), readTimeoutMs: attemptMs)
        core.events?.onRequestStart(op: plan.op, method: plan.method, path: plan.path, attempt: n)
        core.log.debug(plan.op, "\(plan.method) \(plan.path) attempt \(n), idempotencyKey \(key ?? "none")")
        watchdog = core.executor.schedule(afterMs: attemptMs) { [weak self] in
            self?.attemptTimedOut(n, attemptMs)
        }
        inflight = core.http.send(request) { result in
            self.core.executor.async { self.onResult(result, attempt: n) }
        }
    }

    private func attemptTimedOut(_ n: Int, _ ms: Int) {
        guard !finished, n == attempt, let task = inflight else { return }
        inflight = nil
        watchdog = nil
        task.cancel()
        lastStatus = 0
        handleError(YuguErrors.local(ErrorCodes.timeout, "no response within \(ms) ms"))
    }

    private func onResult(_ result: Result<YuguHTTPResponse, Error>, attempt n: Int) {
        guard !finished, n == attempt, inflight != nil else { return }
        inflight = nil
        watchdog?.cancel()
        watchdog = nil
        switch result {
        case .success(let resp):
            lastStatus = resp.statusCode
            if (200..<300).contains(resp.statusCode) {
                let replayed = resp.header("idempotency-replayed")?.lowercased() == "true"
                switch plan.parse(resp, CallInfo(idempotencyKey: key, attempts: n, replayed: replayed)) {
                case .success(let value): finish(.success(value))
                case .failure(let e): handleError(e)
                }
            } else {
                handleError(YuguErrors.fromHTTP(status: resp.statusCode, body: resp.body, headers: resp.headers))
            }
        case .failure(let error):
            lastStatus = 0
            handleError(YuguErrors.wrap(error))
        }
    }

    private func handleError(_ raw: YuguError) {
        let e = raw.with(idempotencyKey: key, attempts: attempt)
        lastError = e
        guard retryEnabled, attempt - 1 < policy.maxRetries, e.retryable else {
            finish(.failure(e))
            return
        }
        let n = attempt
        let delay = policy.delayMs(retry: n, unit: core.random.nextUnit(), retryAfterMs: e.retryAfterMs)
        if core.executor.nowMs() + Int64(delay) > deadlineMs {
            core.log.info(plan.op, "retry \(n)/\(policy.maxRetries) skipped, the \(totalMs) ms deadline would pass")
            finish(.failure(e))
            return
        }
        core.events?.onRetry(op: plan.op, attempt: n, delayMs: delay, error: e)
        core.log.warn(plan.op, "retry \(n)/\(policy.maxRetries) in \(delay) ms: \(e.summary)")
        retryTimer = core.executor.schedule(afterMs: delay) { [weak self] in
            self?.retryTimer = nil
            self?.runAttempt()
        }
    }

    private func finish(_ result: Result<T, YuguError>) {
        guard !finished else { return }
        finished = true
        watchdog?.cancel()
        watchdog = nil
        retryTimer?.cancel()
        retryTimer = nil
        inflight?.cancel()
        inflight = nil
        token?.removeHandler(tokenRegistration)
        core.unregister(self)
        let latency = startMs == 0 ? 0 : Int(core.executor.nowMs() - startMs)
        var error: YuguError?
        if case .failure(let e) = result { error = e }
        core.events?.onRequestEnd(op: plan.op, httpStatus: error?.httpStatus ?? lastStatus, latencyMs: latency, attempts: attempt, error: error)
        if let e = error {
            core.log.warn(plan.op, "failed after \(attempt) attempt(s) in \(latency) ms: \(e)")
        } else {
            core.log.info(plan.op, "done in \(latency) ms, \(attempt) attempt(s)")
        }
        let c = completion
        completion = nil
        sink.deliver { c?(result) }
    }
}
