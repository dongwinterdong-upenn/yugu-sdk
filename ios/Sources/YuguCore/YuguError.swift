import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The one error type of the SDK (DESIGN 2.4, requirement B-04).
///
/// Branch on `category` or on the convenience properties such as `isRateLimited`, keep `code`
/// for reports. `retryable` follows the same rules as the SDK's own retry loop.
public struct YuguError: Error, CustomStringConvertible, CustomDebugStringConvertible, LocalizedError {
    /// Category of the error.
    public let category: ErrorCategory
    /// Platform business code, warning code or SDK local code (`90xxx`); 0 when none is known.
    public let code: Int
    /// Name of `code` in the error table, for example `IDEMPOTENCY_IN_PROGRESS`.
    public let name: String?
    /// HTTP status, 0 when the error did not come from an HTTP response.
    public let httpStatus: Int
    public let message: String
    /// Whether repeating the same call with the same idempotency key can succeed.
    public let retryable: Bool
    /// Idempotency key of the failed call. Repeat the call with this key to avoid double billing.
    public internal(set) var idempotencyKey: String?
    /// Record id when the platform already created one.
    public internal(set) var recordId: String?
    /// Attempts made, retries included. For streaming sessions, connections made.
    public internal(set) var attempts: Int
    /// Response body, at most 4 KB.
    public let rawBody: String?
    /// Server `Retry-After` in milliseconds.
    public let retryAfterMs: Int?
    /// Underlying error, for example a `URLError`.
    public let cause: Error?

    public init(
        category: ErrorCategory,
        code: Int,
        httpStatus: Int = 0,
        message: String,
        retryable: Bool,
        name: String? = nil,
        idempotencyKey: String? = nil,
        recordId: String? = nil,
        attempts: Int = 0,
        rawBody: String? = nil,
        retryAfterMs: Int? = nil,
        cause: Error? = nil
    ) {
        self.category = category
        self.code = code
        self.httpStatus = httpStatus
        self.message = message
        self.retryable = retryable
        self.name = name
        self.idempotencyKey = idempotencyKey
        self.recordId = recordId
        self.attempts = attempts
        self.rawBody = rawBody
        self.retryAfterMs = retryAfterMs
        self.cause = cause
    }

    // MARK: Convenience, one per typed exception of the Java and JS SDKs

    /// `NetworkException`.
    public var isNetworkError: Bool { category == .network }
    /// `RequestTimeoutException`.
    public var isTimeout: Bool { category == .timeout }
    /// `AuthException`.
    public var isAuthError: Bool { category == .auth }
    /// `PermissionException`.
    public var isPermissionDenied: Bool { category == .permission }
    /// `InvalidParameterException`.
    public var isInvalidParameter: Bool { category == .invalidParam }
    /// `NotFoundException`.
    public var isNotFound: Bool { category == .notFound }
    /// `ConflictException`.
    public var isConflict: Bool { category == .conflict }
    /// `RateLimitException`.
    public var isRateLimited: Bool { category == .rateLimit }
    /// `QuotaExceededException`.
    public var isQuotaExceeded: Bool { category == .quota }
    /// `ServerException`, categories SERVER and UPSTREAM.
    public var isServerError: Bool { category == .server || category == .upstream }
    /// `AudioQualityException`.
    public var isAudioQuality: Bool { category == .audio }
    /// `IllegalSessionStateException`.
    public var isIllegalState: Bool { category == .state }
    /// `RequestCancelledException`.
    public var isCancelled: Bool { category == .cancelled }
    /// `ProtocolViolationException`.
    public var isProtocolViolation: Bool { category == .protocol }

    /// Java and JS exception class name for this category.
    public var exceptionName: String {
        switch category {
        case .network: return "NetworkException"
        case .timeout: return "RequestTimeoutException"
        case .auth: return "AuthException"
        case .permission: return "PermissionException"
        case .invalidParam: return "InvalidParameterException"
        case .notFound: return "NotFoundException"
        case .conflict: return "ConflictException"
        case .rateLimit: return "RateLimitException"
        case .quota: return "QuotaExceededException"
        case .server, .upstream: return "ServerException"
        case .audio: return "AudioQualityException"
        case .state: return "IllegalSessionStateException"
        case .cancelled: return "RequestCancelledException"
        case .protocol: return "ProtocolViolationException"
        case .unknown: return "YuguException"
        }
    }

    public var description: String {
        var s = "YuguError(\(category.rawValue) code=\(code)"
        if httpStatus > 0 { s += " http=\(httpStatus)" }
        s += " retryable=\(retryable)"
        if attempts > 0 { s += " attempts=\(attempts)" }
        s += "): \(message)"
        return s
    }

    /// `description` plus key and cause. Query values `signature`, `token` and `appKey` inside
    /// the cause (a failing URL, for example) are masked.
    public var debugDescription: String {
        var s = description
        if let k = idempotencyKey { s += " idempotencyKey=\(k)" }
        if let c = cause {
            var text = String(describing: c)
            for name in ["signature", "token", "appKey", "X-Signature"] {
                text = Redactor.maskParameter(text, name: name)
            }
            s += " cause=\(text)"
        }
        return s
    }

    public var errorDescription: String? { description }

    // MARK: Internal copies

    /// `HTTP 503 code=50200` or `TIMEOUT code=90002`, used in retry logs.
    var summary: String {
        httpStatus > 0 ? "HTTP \(httpStatus) code=\(code)" : "\(category.rawValue) code=\(code)"
    }

    func with(idempotencyKey key: String?, attempts n: Int) -> YuguError {
        var e = self
        if e.idempotencyKey == nil { e.idempotencyKey = key }
        e.attempts = n
        return e
    }

    func with(recordId id: String?) -> YuguError {
        var e = self
        if e.recordId == nil { e.recordId = id }
        return e
    }
}

/// Error construction and classification shared by the SDK and its callers.
public enum YuguErrors {
    /// Raw bodies kept in errors are cut to this many bytes.
    public static let rawBodyLimit = 4096

    /// Builds the error for a code. Local codes (`90xxx`) and platform business codes come from the
    /// error table; a code found only among the warnings (1001 to 1003, 1009) gives an AUDIO error.
    /// 1004 and 1005 are business codes (`USER_DISABLED`, `USER_LOCKED`) here; use
    /// `fromWarning` for the warnings with the same numbers.
    public static func fromCode(_ code: Int, message: String? = nil, httpStatus: Int = 0) -> YuguError {
        if let e = ErrorTable.local[code] { return build(e, message: message, httpStatus: httpStatus) }
        if let e = ErrorTable.errors[code] { return build(e, message: message, httpStatus: httpStatus) }
        if let e = ErrorTable.warnings[code] { return build(e, message: message, httpStatus: httpStatus) }
        return YuguError(
            category: YuguErrors.category(forHTTPStatus: httpStatus), code: code, httpStatus: httpStatus,
            message: message ?? (httpStatus > 0 ? "HTTP \(httpStatus)" : "error \(code)"),
            retryable: ErrorTable.retryableHttp.contains(httpStatus))
    }

    /// Builds the AUDIO error for an audio quality warning code (1001 to 1005, 1009).
    public static func fromWarning(_ code: Int, message: String? = nil) -> YuguError {
        if let e = ErrorTable.warnings[code] { return build(e, message: message, httpStatus: 0) }
        return YuguError(category: .audio, code: code, message: message ?? "audio warning \(code)", retryable: false)
    }

    /// Builds an SDK local error (`90xxx`).
    public static func local(_ code: Int, _ message: String? = nil, cause: Error? = nil) -> YuguError {
        guard let e = ErrorTable.local[code] else {
            return YuguError(category: .unknown, code: code, message: message ?? "error \(code)", retryable: false, cause: cause)
        }
        return YuguError(
            category: e.category, code: code, message: message ?? e.message, retryable: e.retryable,
            name: e.name, cause: cause)
    }

    /// Category of an HTTP status: `httpFallback` of the error table, then by class, any other
    /// 4xx is INVALID_PARAM, any other 5xx is SERVER, anything else UNKNOWN (DESIGN 2.4).
    public static func category(forHTTPStatus status: Int) -> ErrorCategory {
        if let c = ErrorTable.httpFallback[status] { return c }
        switch status {
        case 400..<500: return .invalidParam
        case 500..<600: return .server
        default: return .unknown
        }
    }

    /// `true` when a failed call can be repeated with the same idempotency key (DESIGN 2.3):
    /// 1. local codes: 90001, 90002 and 90007 are retryable, every other local code is not;
    /// 2. a known business code: its `retryable` flag;
    /// 3. otherwise HTTP 408, 425, 429, 500, 502, 503 and 504.
    public static func isRetryable(_ error: Error) -> Bool {
        if let y = error as? YuguError { return y.retryable }
        return wrap(error).retryable
    }

    /// Maps an HTTP error response (DESIGN 2.4): body `code` through the table, then the engine
    /// pattern `[2001] ...` of a FastAPI `detail`, then the HTTP status.
    public static func fromHTTP(status: Int, body: Data?, headers: [String: String] = [:]) -> YuguError {
        let parsed = body.flatMap { try? JSONValue.parse($0) }
        let retryAfter = headers.first { $0.key.lowercased() == "retry-after" }.flatMap { parseRetryAfter($0.value) }
        return fromBody(status: status, json: parsed, rawBody: body.map(truncatedText), retryAfterMs: retryAfter)
    }

    /// Maps a WebSocket error frame `{"event":"error","code":N,"message":"..."}`.
    public static func fromErrorFrame(_ frame: JSONValue) -> YuguError {
        let code = frame["code"]?.intValue ?? 0
        let message = frame["message"]?.stringValue ?? frame["error"]?.stringValue ?? "server error frame"
        let raw = truncatedText(Data(frame.jsonText.utf8))
        if code != 0 {
            let base = fromCode(code, message: message)
            return YuguError(
                category: base.category, code: code, message: message, retryable: base.retryable, name: base.name,
                rawBody: raw)
        }
        // No code: not retryable (rule 3 needs an HTTP status, a frame has none).
        return YuguError(category: .server, code: 0, message: message, retryable: false, rawBody: raw)
    }

    /// Converts any error into a `YuguError`. `URLError` codes map to local codes.
    public static func wrap(_ error: Error) -> YuguError {
        if let y = error as? YuguError { return y }
        if error is JSONParseError { return local(ErrorCodes.protocolError, String(describing: error), cause: error) }
        if error is CancellationError { return local(ErrorCodes.cancelled, cause: error) }
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain {
            return fromURLErrorCode(ns.code, error: error)
        }
        return local(ErrorCodes.networkError, String(describing: error), cause: error)
    }

    static func fromURLErrorCode(_ raw: Int, error: Error) -> YuguError {
        switch raw {
        case URLError.timedOut.rawValue:
            return local(ErrorCodes.timeout, "request timed out", cause: error)
        case URLError.cancelled.rawValue:
            return local(ErrorCodes.cancelled, cause: error)
        case URLError.badURL.rawValue, URLError.unsupportedURL.rawValue, -1022:
            // -1022: App Transport Security blocked a cleartext URL.
            return local(ErrorCodes.invalidArgument, "URL not supported (NSURLErrorDomain \(raw))", cause: error)
        case -1200, -1201, -1202, -1203, -1204, -1205, -1206:
            return local(ErrorCodes.tlsError, "TLS failure (NSURLErrorDomain \(raw))", cause: error)
        default:
            return local(ErrorCodes.networkError, "network error (NSURLErrorDomain \(raw))", cause: error)
        }
    }

    // MARK: Internal

    static func build(_ e: ErrorTableEntry, message: String?, httpStatus: Int) -> YuguError {
        YuguError(
            category: e.category, code: e.code, httpStatus: httpStatus,
            message: (message?.isEmpty == false ? message! : e.message), retryable: e.retryable, name: e.name)
    }

    /// Maps a parsed error body. `json` is nil when the body is not JSON.
    static func fromBody(status: Int, json: JSONValue?, rawBody: String?, retryAfterMs: Int?) -> YuguError {
        var code = 0
        var message: String?
        if let o = json?.objectValue {
            if let c = o["code"]?.intValue, c != 0 {
                code = c
            } else if let c = o["errId"]?.intValue, c != 0 {
                code = c
            }
            if let m = o["message"]?.stringValue, !m.isEmpty {
                message = m
            } else if let m = o["error"]?.stringValue, !m.isEmpty {
                message = m
            }
            if let detail = o["detail"] {
                switch detail {
                case .string(let s):
                    if message == nil { message = s }
                    if code == 0, let c = bracketCode(s) { code = c }
                case .array(let items):
                    let msgs = items.compactMap { $0["msg"]?.stringValue ?? $0.stringValue }
                    if message == nil { message = msgs.isEmpty ? detail.jsonText : msgs.joined(separator: "; ") }
                case .object(let d):
                    if code == 0, let c = d["code"]?.intValue { code = c }
                    if message == nil { message = d["message"]?.stringValue ?? detail.jsonText }
                default:
                    break
                }
            }
        }
        var category: ErrorCategory
        var retryable: Bool
        var name: String?
        if code != 0, let e = ErrorTable.errors[code] ?? ErrorTable.local[code] {
            category = e.category
            retryable = e.retryable
            name = e.name
            if message == nil { message = e.message }
        } else {
            category = YuguErrors.category(forHTTPStatus: status)
            retryable = ErrorTable.retryableHttp.contains(status)
        }
        if (2001...2003).contains(code) {
            category = .auth
            retryable = false
        }
        return YuguError(
            category: category, code: code, httpStatus: status,
            message: message ?? (status > 0 ? "HTTP \(status)" : "error"), retryable: retryable, name: name,
            rawBody: rawBody, retryAfterMs: retryAfterMs)
    }

    /// `[2001] message` of the engine compatibility layer.
    static func bracketCode(_ s: String) -> Int? {
        let t = s.trimmingCharacters(in: .whitespaces)
        guard t.hasPrefix("["), let close = t.firstIndex(of: "]") else { return nil }
        let inner = t[t.index(after: t.startIndex)..<close]
        guard (1...6).contains(inner.count), inner.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return Int(inner)
    }

    /// `Retry-After` as delay seconds or an HTTP date.
    static func parseRetryAfter(_ value: String, now: Date = Date()) -> Int? {
        let v = value.trimmingCharacters(in: .whitespaces)
        if let secs = Double(v), secs >= 0 { return Int((secs * 1000).rounded()) }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        if let d = f.date(from: v) { return max(0, Int((d.timeIntervalSince(now) * 1000).rounded())) }
        return nil
    }

    /// First 4 KB of a body as text.
    static func truncatedText(_ data: Data) -> String {
        if data.count <= rawBodyLimit { return String(decoding: data, as: UTF8.self) }
        var s = String(decoding: data.prefix(rawBodyLimit), as: UTF8.self)
        while s.hasSuffix("\u{FFFD}") { s.removeLast() }
        return s
    }
}
