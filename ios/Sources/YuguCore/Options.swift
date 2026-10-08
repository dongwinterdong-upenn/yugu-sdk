import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Local audio precheck mode (DESIGN 2.9, requirement C-04).
public enum AudioPrecheckMode: String, Sendable, CaseIterable {
    /// No precheck.
    case off = "OFF"
    /// Problems become local warnings in `localWarnings`. Default.
    case warn = "WARN"
    /// Too short, too long, silent and unsupported audio fail before upload; low volume stays a
    /// warning.
    case reject = "REJECT"
}

/// Client options (DESIGN 2.1). Defaults are identical in every Yugu SDK.
public struct YuguClientOptions {
    public static let defaultBaseUrl = "https://open.shengzhiai.com"
    public static let defaultWsBaseUrl = "wss://open.shengzhiai.com"

    /// REST base URL.
    public var baseUrl: String
    /// WebSocket base URL. `nil` derives it from `baseUrl` (`https` becomes `wss`, `http` becomes
    /// `ws`), so the default is `wss://open.shengzhiai.com`.
    public var wsBaseUrl: String?
    public var auth: YuguAuth
    /// TCP and TLS connect timeout of a REST attempt (until the request body starts to flow), and
    /// the handshake timeout of a WebSocket connection (until the server's `started` frame).
    public var connectTimeoutMs: Int = 10_000
    /// Timeout of one REST attempt.
    public var readTimeoutMs: Int = 120_000
    /// Deadline of one logical REST call including retries and waits.
    public var totalTimeoutMs: Int = 300_000
    public var retry: RetryPolicy = .default
    /// Generate an idempotency key for every write call that has none. When `false` and the
    /// caller gives no key, write calls are not retried and sessions do not reconnect.
    public var autoIdempotencyKey: Bool = true
    public var logLevel: YuguLogLevel = .warn
    /// `nil` uses `ConsoleLogger`.
    public var logger: YuguLogger?
    public var eventListener: YuguEventListener?
    public var audioPrecheck: AudioPrecheckMode = .warn
    /// When the platform reports warning 1001 (no valid audio), report an AUDIO error instead of
    /// a result.
    public var strictAudio: Bool = false
    public var userAgent: String = YuguSDKInfo.userAgent

    /// Streaming defaults.
    public var reconnect: ReconnectPolicy = .default
    public var audioBufferPolicy: AudioBufferPolicy = .replay
    /// Protocol ping interval of WebSocket sessions.
    public var pingIntervalMs: Int = 15_000
    /// A missing pong for this long counts as a transport failure.
    public var pongTimeoutMs: Int = 30_000
    /// Wait for the final result after `end()`.
    public var resultTimeoutMs: Int = 300_000

    /// Queue of completion handlers and session listeners. Callbacks of one client never run at
    /// the same time. The `async` API does not use it.
    public var callbackQueue: DispatchQueue = .main
    /// Configuration of the default URLSession transports, for proxies and similar settings.
    public var urlSessionConfiguration: URLSessionConfiguration?
    /// Replaces the HTTP layer, for example for certificate pinning or tests.
    public var httpTransport: YuguHTTPTransport?
    /// Replaces the WebSocket layer. Required on Linux, whose URLSession has no WebSocket support.
    public var webSocketTransportFactory: YuguWebSocketTransportFactory?

    public init(
        auth: YuguAuth,
        baseUrl: String = YuguClientOptions.defaultBaseUrl,
        wsBaseUrl: String? = nil
    ) {
        self.auth = auth
        self.baseUrl = baseUrl
        self.wsBaseUrl = wsBaseUrl
    }

    /// The WebSocket base URL in effect.
    public var effectiveWsBaseUrl: String {
        if let ws = wsBaseUrl, !ws.isEmpty { return YuguClientOptions.trimSlash(ws) }
        let b = YuguClientOptions.trimSlash(baseUrl)
        if b.lowercased().hasPrefix("https://") { return "wss://" + b.dropFirst("https://".count) }
        if b.lowercased().hasPrefix("http://") { return "ws://" + b.dropFirst("http://".count) }
        return b
    }

    static func trimSlash(_ s: String) -> String {
        var t = s.trimmingCharacters(in: .whitespaces)
        while t.hasSuffix("/") { t.removeLast() }
        return t
    }
}

/// Options of one REST call (DESIGN 2.1).
public struct RequestOptions {
    /// Caller idempotency key, 1 to 200 printable ASCII characters. Reuse it to repeat a call
    /// without double billing.
    public var idempotencyKey: String?
    /// Deadline of this call including retries, replaces `totalTimeoutMs`.
    public var timeoutMs: Int?
    /// Timeout of each attempt, replaces `readTimeoutMs`.
    public var readTimeoutMs: Int?
    /// Retry policy of this call.
    public var retry: RetryPolicy?
    public var cancellationToken: YuguCancellationToken?
    /// Audio precheck of this call.
    public var audioPrecheck: AudioPrecheckMode?

    public init(
        idempotencyKey: String? = nil,
        timeoutMs: Int? = nil,
        readTimeoutMs: Int? = nil,
        retry: RetryPolicy? = nil,
        cancellationToken: YuguCancellationToken? = nil,
        audioPrecheck: AudioPrecheckMode? = nil
    ) {
        self.idempotencyKey = idempotencyKey
        self.timeoutMs = timeoutMs
        self.readTimeoutMs = readTimeoutMs
        self.retry = retry
        self.cancellationToken = cancellationToken
        self.audioPrecheck = audioPrecheck
    }
}

/// Options of one streaming session. `nil` fields use the client options.
public struct StreamOptions {
    public var idempotencyKey: String?
    public var reconnect: ReconnectPolicy?
    public var audioBufferPolicy: AudioBufferPolicy?
    public var connectTimeoutMs: Int?
    public var resultTimeoutMs: Int?
    public var pingIntervalMs: Int?
    public var pongTimeoutMs: Int?
    public var audioPrecheck: AudioPrecheckMode?

    public init(
        idempotencyKey: String? = nil,
        reconnect: ReconnectPolicy? = nil,
        audioBufferPolicy: AudioBufferPolicy? = nil,
        connectTimeoutMs: Int? = nil,
        resultTimeoutMs: Int? = nil,
        pingIntervalMs: Int? = nil,
        pongTimeoutMs: Int? = nil,
        audioPrecheck: AudioPrecheckMode? = nil
    ) {
        self.idempotencyKey = idempotencyKey
        self.reconnect = reconnect
        self.audioBufferPolicy = audioBufferPolicy
        self.connectTimeoutMs = connectTimeoutMs
        self.resultTimeoutMs = resultTimeoutMs
        self.pingIntervalMs = pingIntervalMs
        self.pongTimeoutMs = pongTimeoutMs
        self.audioPrecheck = audioPrecheck
    }
}
