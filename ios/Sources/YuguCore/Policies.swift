import Foundation

/// Retry policy of REST calls (DESIGN 2.3, requirement A-02).
///
/// ```
/// delay(n) = min(maxDelayMs, initialDelayMs * multiplier^(n-1)) * (1 + U(-jitter, +jitter))   n = 1..maxRetries
/// Retry-After present and respectRetryAfter: delay = max(delay, min(retryAfterMs, maxRetryAfterMs))
/// now + delay > deadline: stop and report the last error
/// ```
/// Defaults give 200 ms and 400 ms, each within 30 percent.
public struct RetryPolicy: Equatable, Sendable {
    /// Retries after the first attempt. 2 means 3 attempts in total.
    public var maxRetries: Int
    public var initialDelayMs: Int
    public var multiplier: Double
    public var maxDelayMs: Int
    /// Relative jitter, 0.3 means plus or minus 30 percent.
    public var jitter: Double
    public var respectRetryAfter: Bool
    public var maxRetryAfterMs: Int

    public init(
        maxRetries: Int = 2,
        initialDelayMs: Int = 200,
        multiplier: Double = 2.0,
        maxDelayMs: Int = 4000,
        jitter: Double = 0.3,
        respectRetryAfter: Bool = true,
        maxRetryAfterMs: Int = 30000
    ) {
        self.maxRetries = maxRetries
        self.initialDelayMs = initialDelayMs
        self.multiplier = multiplier
        self.maxDelayMs = maxDelayMs
        self.jitter = jitter
        self.respectRetryAfter = respectRetryAfter
        self.maxRetryAfterMs = maxRetryAfterMs
    }

    /// 2 retries, 200 ms, times 2, at most 4000 ms, jitter 0.3, Retry-After honoured up to 30 s.
    public static let `default` = RetryPolicy()
    /// No retries.
    public static let disabled = RetryPolicy(maxRetries: 0)

    /// Delay before retry `n` (1-based) without jitter.
    public func baseDelayMs(retry n: Int) -> Double {
        Backoff.base(n: n, initialMs: initialDelayMs, multiplier: multiplier, maxMs: maxDelayMs)
    }

    /// Delay before retry `n` (1-based). `unit` is a uniform random number in `[0, 1)`;
    /// `retryAfterMs` is the server's `Retry-After` in milliseconds when present.
    public func delayMs(retry n: Int, unit: Double, retryAfterMs: Int? = nil) -> Int {
        var delay = Backoff.jittered(base: baseDelayMs(retry: n), jitter: jitter, unit: unit)
        if respectRetryAfter, let ra = retryAfterMs, ra > 0 {
            delay = max(delay, min(ra, maxRetryAfterMs))
        }
        return delay
    }
}

/// Reconnect policy of WebSocket sessions (DESIGN 2.5, requirement A-03).
///
/// Defaults wait about 0.5, 1, 2, 4, 4, 4, 4 and 4 s, 23 s in total, enough to ride out a
/// network drop of 10 s.
public struct ReconnectPolicy: Equatable, Sendable {
    public var enabled: Bool
    /// Consecutive failed reconnect attempts allowed. The count starts again after a
    /// successful reconnect.
    public var maxAttempts: Int
    public var initialDelayMs: Int
    public var multiplier: Double
    public var maxDelayMs: Int
    public var jitter: Double

    public init(
        enabled: Bool = true,
        maxAttempts: Int = 8,
        initialDelayMs: Int = 500,
        multiplier: Double = 2.0,
        maxDelayMs: Int = 4000,
        jitter: Double = 0.3
    ) {
        self.enabled = enabled
        self.maxAttempts = maxAttempts
        self.initialDelayMs = initialDelayMs
        self.multiplier = multiplier
        self.maxDelayMs = maxDelayMs
        self.jitter = jitter
    }

    /// Enabled, 8 consecutive attempts, 500 ms, times 2, at most 4000 ms, jitter 0.3.
    public static let `default` = ReconnectPolicy()
    /// No reconnect.
    public static let disabled = ReconnectPolicy(enabled: false)

    /// Delay before reconnect `n` (1-based); `unit` is uniform in `[0, 1)`.
    public func delayMs(attempt n: Int, unit: Double) -> Int {
        Backoff.jittered(
            base: Backoff.base(n: n, initialMs: initialDelayMs, multiplier: multiplier, maxMs: maxDelayMs),
            jitter: jitter, unit: unit)
    }
}

/// What a streaming session does with audio when the connection breaks (DESIGN 2.5).
public enum AudioBufferPolicy: String, Sendable, CaseIterable {
    /// Keep every byte of the round (at most 10 MB) and replay it into a new server session with
    /// the same parameters and idempotency key. Default.
    case replay = "REPLAY"
    /// No buffer. Audio sent while reconnecting is discarded; the new server session scores only
    /// audio sent after the reconnect.
    case drop = "DROP"
    /// No reconnect. A transport failure ends the session with an error.
    case fail = "FAIL"
}

enum Backoff {
    static func base(n: Int, initialMs: Int, multiplier: Double, maxMs: Int) -> Double {
        let exp = pow(multiplier, Double(max(0, n - 1)))
        return min(Double(maxMs), Double(initialMs) * exp)
    }

    static func jittered(base: Double, jitter: Double, unit: Double) -> Int {
        let u = min(max(unit, 0), 1) * 2 - 1
        let d = base * (1 + jitter * u)
        return max(0, Int(d.rounded()))
    }
}

/// Uniform random source. Injected in tests to make jitter deterministic.
protocol YuguRandom: AnyObject {
    /// Uniform in `[0, 1)`.
    func nextUnit() -> Double
    func nextBytes(_ count: Int) -> [UInt8]
}

extension YuguRandom {
    func hex(byteCount: Int) -> String {
        nextBytes(byteCount).hexString
    }
}

final class SystemRandomSource: YuguRandom {
    private var generator = SystemRandomNumberGenerator()
    private let lock = NSLock()

    func nextUnit() -> Double {
        lock.lock()
        defer { lock.unlock() }
        return Double(generator.next() >> 11) * 0x1.0p-53
    }

    func nextBytes(_ count: Int) -> [UInt8] {
        lock.lock()
        defer { lock.unlock() }
        var out = [UInt8]()
        out.reserveCapacity(count)
        while out.count < count {
            var v = generator.next()
            for _ in 0..<8 where out.count < count {
                out.append(UInt8(truncatingIfNeeded: v))
                v >>= 8
            }
        }
        return out
    }
}

/// SplitMix64, deterministic for a seed.
final class SeededRandom: YuguRandom {
    private var state: UInt64
    private let lock = NSLock()

    init(seed: UInt64) {
        state = seed
    }

    private func next() -> UInt64 {
        state = state &+ 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    func nextUnit() -> Double {
        lock.lock()
        defer { lock.unlock() }
        return Double(next() >> 11) * 0x1.0p-53
    }

    func nextBytes(_ count: Int) -> [UInt8] {
        lock.lock()
        defer { lock.unlock() }
        var out = [UInt8]()
        while out.count < count {
            var v = next()
            for _ in 0..<8 where out.count < count {
                out.append(UInt8(truncatingIfNeeded: v))
                v >>= 8
            }
        }
        return out
    }
}

/// Idempotency keys (DESIGN 2.2, requirement A-01).
public enum IdempotencyKey {
    /// A new key: 32 lowercase hex characters, a UUID v4 without dashes.
    public static func generate() -> String {
        UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "")
    }

    /// Caller keys must match `^[\x21-\x7E]{1,200}$`: 1 to 200 printable ASCII characters, no space.
    public static func isValid(_ key: String) -> Bool {
        let bytes = Array(key.utf8)
        guard !bytes.isEmpty, bytes.count <= 200 else { return false }
        return bytes.allSatisfy { $0 >= 0x21 && $0 <= 0x7E }
    }
}
