import Foundation

/// Log levels (DESIGN 2.7). Default WARN.
public enum YuguLogLevel: Int, Comparable, Sendable, CaseIterable {
    case off = 0
    case error = 1
    case warn = 2
    case info = 3
    case debug = 4

    public static func < (a: YuguLogLevel, b: YuguLogLevel) -> Bool { a.rawValue < b.rawValue }

    public var label: String {
        switch self {
        case .off: return "OFF"
        case .error: return "ERROR"
        case .warn: return "WARN"
        case .info: return "INFO"
        case .debug: return "DEBUG"
        }
    }
}

/// Log sink. Messages arrive already redacted: no secretKey, signature, token or audio bytes, and
/// the appKey only as its first 4 characters followed by `***`.
public protocol YuguLogger: AnyObject {
    func log(level: YuguLogLevel, tag: String, message: String, error: Error?)
}

/// Default logger, writes through `NSLog` (the Xcode console on Apple platforms, stderr on Linux).
public final class ConsoleLogger: YuguLogger {
    public init() {}

    public func log(level: YuguLogLevel, tag: String, message: String, error: Error?) {
        var line = "[Yugu] \(level.label) \(tag): \(message)"
        if let error = error { line += " | \(error)" }
        NSLog("%@", line)
    }
}

/// Removes secrets from log text.
struct Redactor {
    var secrets: [String] = []
    var appKey: String?

    init(auth: YuguAuth) {
        switch auth {
        case .token(let t): secrets = [t]
        case .appKey(let k, let s):
            secrets = [s]
            appKey = k
        }
    }

    static func mask(appKey: String) -> String {
        String(appKey.prefix(4)) + "***"
    }

    func redact(_ text: String) -> String {
        var out = text
        for s in secrets where s.count >= 4 {
            out = out.replacingOccurrences(of: s, with: "***")
        }
        for name in ["signature", "token", "X-Signature", "secretKey"] {
            out = Redactor.maskParameter(out, name: name)
        }
        if let k = appKey, !k.isEmpty {
            out = out.replacingOccurrences(of: k, with: Redactor.mask(appKey: k))
        }
        return out
    }

    /// Replaces the value after `name=` or `name: ` up to the next separator with `***`.
    static func maskParameter(_ text: String, name: String) -> String {
        var out = ""
        var rest = Substring(text)
        while let r = rest.range(of: name) {
            out += rest[..<r.upperBound]
            rest = rest[r.upperBound...]
            var sep = ""
            if rest.hasPrefix("=") {
                sep = "="
            } else if rest.hasPrefix(": ") {
                sep = ": "
            } else if rest.hasPrefix(":") {
                sep = ":"
            }
            guard !sep.isEmpty else { continue }
            out += sep
            rest = rest.dropFirst(sep.count)
            let end = rest.firstIndex(where: { $0 == "&" || $0 == " " || $0 == "\n" || $0 == "," || $0 == "\"" || $0 == "'" }) ?? rest.endIndex
            if end != rest.startIndex { out += "***" }
            rest = rest[end...]
        }
        out += rest
        return out
    }
}

/// Error passed to loggers in place of the original, whose text is redacted.
struct RedactedError: Error, CustomStringConvertible {
    let description: String
}

/// Level filter plus redaction in front of the caller's logger.
final class LogSink {
    let level: YuguLogLevel
    private let logger: YuguLogger
    private let redactor: Redactor

    init(level: YuguLogLevel, logger: YuguLogger, redactor: Redactor) {
        self.level = level
        self.logger = logger
        self.redactor = redactor
    }

    func enabled(_ l: YuguLogLevel) -> Bool {
        l != .off && l <= level
    }

    func log(_ l: YuguLogLevel, _ tag: String, _ message: @autoclosure () -> String, _ error: Error? = nil) {
        guard enabled(l) else { return }
        let redactedError: Error? = error.map { RedactedError(description: redactor.redact(String(describing: $0))) }
        logger.log(level: l, tag: tag, message: redactor.redact(message()), error: redactedError)
    }

    func debug(_ tag: String, _ m: @autoclosure () -> String) { log(.debug, tag, m()) }
    func info(_ tag: String, _ m: @autoclosure () -> String) { log(.info, tag, m()) }
    func warn(_ tag: String, _ m: @autoclosure () -> String, _ e: Error? = nil) { log(.warn, tag, m(), e) }
    func error(_ tag: String, _ m: @autoclosure () -> String, _ e: Error? = nil) { log(.error, tag, m(), e) }
}

/// Metrics hooks (DESIGN 2.7, requirement C-01). Every method is optional. Methods run on an SDK
/// queue and must return quickly.
public protocol YuguEventListener: AnyObject {
    /// One REST attempt starts. `attempt` is 1 for the first try.
    func onRequestStart(op: String, method: String, path: String, attempt: Int)
    /// A logical REST call ends. `latencyMs` covers all attempts and waits.
    func onRequestEnd(op: String, httpStatus: Int, latencyMs: Int, attempts: Int, error: YuguError?)
    /// A retry is scheduled. `attempt` is the retry number, 1 for the first retry.
    func onRetry(op: String, attempt: Int, delayMs: Int, error: YuguError)
    /// A streaming session changes state.
    func onSessionStateChanged(sessionId: String, from: SessionState, to: SessionState)
    /// A reconnect attempt of a streaming session ends.
    func onReconnect(sessionId: String, attempt: Int, succeeded: Bool)
}

extension YuguEventListener {
    public func onRequestStart(op: String, method: String, path: String, attempt: Int) {}
    public func onRequestEnd(op: String, httpStatus: Int, latencyMs: Int, attempts: Int, error: YuguError?) {}
    public func onRetry(op: String, attempt: Int, delayMs: Int, error: YuguError) {}
    public func onSessionStateChanged(sessionId: String, from: SessionState, to: SessionState) {}
    public func onReconnect(sessionId: String, attempt: Int, succeeded: Bool) {}
}
