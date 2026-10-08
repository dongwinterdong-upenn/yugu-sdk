import Foundation

/// SDK identity.
public enum YuguSDKInfo {
    public static let version = "2.0.0"
    public static let platform = "ios"
    /// Sent as `User-Agent` on every request and WebSocket handshake.
    public static let userAgent = "yugu-ios-sdk/2.0.0"
}

// MARK: - Cancellation

/// Something that can be cancelled. Cancelling twice is harmless.
public protocol YuguCancellable: AnyObject {
    func cancel()
}

/// Cancellation token for `RequestOptions` (DESIGN 2.1). One token may cancel several calls.
public final class YuguCancellationToken: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private var handlers: [Int: () -> Void] = [:]
    private var nextId = 0

    public init() {}

    public var isCancelled: Bool {
        lock.sync { cancelled }
    }

    /// Cancels every call that uses this token. Later calls with the token fail at once.
    public func cancel() {
        let toRun: [() -> Void] = lock.sync {
            if cancelled { return [] }
            cancelled = true
            let h = Array(handlers.values)
            handlers.removeAll()
            return h
        }
        toRun.forEach { $0() }
    }

    /// Registers a handler. Runs it at once and returns nil when already cancelled.
    @discardableResult
    func onCancel(_ handler: @escaping () -> Void) -> Int? {
        let id: Int? = lock.sync {
            if cancelled { return nil }
            nextId += 1
            handlers[nextId] = handler
            return nextId
        }
        if id == nil { handler() }
        return id
    }

    func removeHandler(_ id: Int?) {
        guard let id = id else { return }
        lock.sync { _ = handlers.removeValue(forKey: id) }
    }
}

/// Handle of a REST call started with a completion handler.
public final class YuguCall: YuguCancellable {
    /// Operation name: `evaluate`, `evaluateCompat`, `tts` or `getReport`.
    public let operation: String
    /// Idempotency key used by every attempt of this call, nil for calls without one.
    public let idempotencyKey: String?
    private let onCancel: () -> Void

    init(operation: String, idempotencyKey: String?, onCancel: @escaping () -> Void) {
        self.operation = operation
        self.idempotencyKey = idempotencyKey
        self.onCancel = onCancel
    }

    /// Cancels the call. The completion receives a CANCELLED error (90003) unless it already ran.
    public func cancel() {
        onCancel()
    }
}

extension NSLock {
    @inline(__always)
    func sync<R>(_ body: () throws -> R) rethrows -> R {
        lock()
        defer { unlock() }
        return try body()
    }
}

// MARK: - Execution context

/// Serial execution context with timers. Every state machine of the SDK runs on one executor, so
/// its state needs no locks. Tests inject a virtual executor to control time.
protocol YuguExecutor: AnyObject {
    /// Monotonic milliseconds.
    func nowMs() -> Int64
    func async(_ work: @escaping () -> Void)
    func schedule(afterMs ms: Int, _ work: @escaping () -> Void) -> YuguCancellable
}

final class DispatchExecutor: YuguExecutor {
    let queue: DispatchQueue

    init(label: String) {
        queue = DispatchQueue(label: label)
    }

    func nowMs() -> Int64 {
        Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
    }

    func async(_ work: @escaping () -> Void) {
        let box = UncheckedBox(work)
        queue.async { box.value() }
    }

    func schedule(afterMs ms: Int, _ work: @escaping () -> Void) -> YuguCancellable {
        let box = UncheckedBox(work)
        let item = DispatchWorkItem { box.value() }
        queue.asyncAfter(deadline: .now() + .milliseconds(max(0, ms)), execute: item)
        return WorkItemHandle(item)
    }

    private final class WorkItemHandle: YuguCancellable {
        let item: DispatchWorkItem
        init(_ item: DispatchWorkItem) { self.item = item }
        func cancel() { item.cancel() }
    }
}

/// Where user callbacks run. Callbacks of one client are delivered in order and never at the
/// same time.
protocol CallbackSink: AnyObject {
    func deliver(_ block: @escaping () -> Void)
}

/// Delivers on a private serial queue that targets the caller's queue, so callbacks stay serial
/// even when the caller passes a concurrent queue.
final class QueueCallbackSink: CallbackSink {
    private let queue: DispatchQueue

    init(target: DispatchQueue) {
        queue = DispatchQueue(label: "yugu.callbacks", target: target)
    }

    func deliver(_ block: @escaping () -> Void) {
        let box = UncheckedBox(block)
        queue.async { box.value() }
    }
}

/// Carries a closure into a `@Sendable` context. Safe here because every closure the SDK
/// dispatches runs on one serial queue.
struct UncheckedBox<T>: @unchecked Sendable {
    let value: T
    init(_ value: T) { self.value = value }
}

/// Runs callbacks on the executor itself. Used by the async API and by tests.
final class InlineCallbackSink: CallbackSink {
    func deliver(_ block: @escaping () -> Void) {
        block()
    }
}
