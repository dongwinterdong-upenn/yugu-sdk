// AVAudioEngine recorder: 16 kHz mono 16-bit PCM, 640-byte frames for streaming, WAV export.
// Apple platforms only. Linux builds of this target contain only the re-export.
#if canImport(AVFoundation)
import AVFoundation
import Foundation
#if canImport(YuguCore)
import YuguCore
#endif

/// Recorder states (DESIGN 2.6).
public enum RecorderState: String, Sendable {
    case idle = "IDLE"
    case recording = "RECORDING"
    case paused = "PAUSED"
    case stopped = "STOPPED"
    case released = "RELEASED"
}

/// Microphone permission.
public enum RecorderPermission: Sendable {
    case granted
    case denied
    case undetermined
}

/// Recorder options.
public struct RecorderOptions {
    /// Bytes per streaming frame. 640 bytes are 20 ms of 16 kHz mono PCM16.
    public var frameBytes: Int = 640
    /// Activate and deactivate the `AVAudioSession` (iOS). Turn off when the app manages it.
    public var manageAudioSession: Bool = true
    #if os(iOS)
    public var category: AVAudioSession.Category = .playAndRecord
    public var mode: AVAudioSession.Mode = .measurement
    public var categoryOptions: AVAudioSession.CategoryOptions = [.defaultToSpeaker, .allowBluetooth]
    #endif
    /// Queue of listener callbacks. Callbacks of one recorder never run at the same time.
    public var callbackQueue: DispatchQueue = .main

    public init() {}
}

/// A finished recording.
public struct YuguRecording: Sendable {
    /// Little-endian 16-bit mono PCM at `sampleRate`.
    public let pcm: Data
    public let sampleRate: Int
    public let channels: Int

    public init(pcm: Data, sampleRate: Int = 16000, channels: Int = 1) {
        self.pcm = pcm
        self.sampleRate = sampleRate
        self.channels = channels
    }

    public var durationSeconds: Double {
        Double(pcm.count) / Double(2 * channels * sampleRate)
    }

    /// The recording as a WAV file.
    public var wavData: Data {
        YuguWAV.wrap(pcm: pcm, sampleRate: sampleRate, channels: channels, bitsPerSample: 16)
    }

    /// Writes the WAV file.
    public func writeWAV(to url: URL) throws {
        try wavData.write(to: url, options: .atomic)
    }

    /// Input for `YuguClient.evaluate`.
    public var audioInput: AudioInput {
        .pcm16(pcm, sampleRate: sampleRate, channels: channels)
    }
}

/// Recorder listener. Every method is optional.
public protocol YuguRecorderListener: AnyObject {
    func recorder(_ recorder: YuguRecorder, didChangeState from: RecorderState, to: RecorderState)
    /// A frame of `frameBytes` bytes; the last frame after `stop()` may be shorter.
    func recorder(_ recorder: YuguRecorder, didCapture frame: Data)
    /// Recording stopped because of an error; the microphone is already released.
    func recorder(_ recorder: YuguRecorder, didFail error: YuguError)
    /// An audio interruption (phone call, Siri) ended. `shouldResume` is the system's hint;
    /// call `resume()` to continue.
    func recorder(_ recorder: YuguRecorder, interruptionEndedShouldResume shouldResume: Bool)
}

extension YuguRecorderListener {
    public func recorder(_ recorder: YuguRecorder, didChangeState from: RecorderState, to: RecorderState) {}
    public func recorder(_ recorder: YuguRecorder, didCapture frame: Data) {}
    public func recorder(_ recorder: YuguRecorder, didFail error: YuguError) {}
    public func recorder(_ recorder: YuguRecorder, interruptionEndedShouldResume shouldResume: Bool) {}
}

/// Closure based recorder listener.
public final class YuguRecorderHandlers: YuguRecorderListener {
    public var onStateChanged: ((RecorderState, RecorderState) -> Void)?
    public var onFrame: ((Data) -> Void)?
    public var onError: ((YuguError) -> Void)?
    public var onInterruptionEnded: ((Bool) -> Void)?

    public init() {}

    public func recorder(_ recorder: YuguRecorder, didChangeState from: RecorderState, to: RecorderState) { onStateChanged?(from, to) }
    public func recorder(_ recorder: YuguRecorder, didCapture frame: Data) { onFrame?(frame) }
    public func recorder(_ recorder: YuguRecorder, didFail error: YuguError) { onError?(error) }
    public func recorder(_ recorder: YuguRecorder, interruptionEndedShouldResume shouldResume: Bool) { onInterruptionEnded?(shouldResume) }
}

/// Microphone recorder (requirements B-05 and C-06).
///
/// ```swift
/// let recorder = YuguRecorder()
/// let handlers = YuguRecorderHandlers()
/// handlers.onFrame = { frame in try? session.sendAudio(frame) }
/// recorder.setListener(handlers)
/// try recorder.start()
/// // ...
/// let recording = recorder.stop()      // microphone released
/// recorder.release()                   // idempotent
/// ```
///
/// The AVAudioEngine is created in `start()` and destroyed in `stop()`, `release()` and every
/// error path, so the microphone is never held after recording ends. Call the methods from one
/// thread, the main thread is recommended.
public final class YuguRecorder {
    public static let sampleRate = 16000
    public static let frameBytes = 640

    private let options: RecorderOptions
    private let targetFormat: AVAudioFormat
    private let control = NSRecursiveLock()
    private let stateLock = NSLock()
    private let processing = DispatchQueue(label: "yugu.recorder.processing")
    private let callbacks: DispatchQueue

    private var currentState: RecorderState = .idle
    private var listener: YuguRecorderListener?
    private var engine: AVAudioEngine?
    private var generation = 0
    private var sessionActive = false
    private var engineObserver: NSObjectProtocol?
    private var sessionObservers: [NSObjectProtocol] = []
    private var last: YuguRecording?

    // Processing queue only.
    private var pcm = Data()
    private var remainder = Data()

    public init(options: RecorderOptions = RecorderOptions()) {
        self.options = options
        // 16 kHz, mono, interleaved Int16 is always a valid format.
        targetFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(YuguRecorder.sampleRate), channels: 1, interleaved: true)!
        callbacks = DispatchQueue(label: "yugu.recorder.callbacks", target: options.callbackQueue)
        observeAudioSession()
    }

    deinit {
        for o in sessionObservers { NotificationCenter.default.removeObserver(o) }
        if let o = engineObserver { NotificationCenter.default.removeObserver(o) }
        if let e = engine {
            e.inputNode.removeTap(onBus: 0)
            e.stop()
        }
        #if os(iOS)
        if sessionActive && options.manageAudioSession {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
        #endif
    }

    // MARK: Public API

    public var state: RecorderState {
        stateLock.lock()
        defer { stateLock.unlock() }
        return currentState
    }

    /// Same as `state`.
    public func getState() -> RecorderState {
        state
    }

    /// The last finished recording.
    public var lastRecording: YuguRecording? {
        control.lock()
        defer { control.unlock() }
        return last
    }

    /// Replaces or removes (`nil`) the listener.
    public func setListener(_ l: YuguRecorderListener?) {
        stateLock.lock()
        listener = l
        stateLock.unlock()
    }

    public static func permissionStatus() -> RecorderPermission {
        #if os(iOS)
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted: return .granted
        case .denied: return .denied
        case .undetermined: return .undetermined
        @unknown default: return .undetermined
        }
        #elseif os(macOS)
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: return .granted
        case .denied, .restricted: return .denied
        case .notDetermined: return .undetermined
        @unknown default: return .undetermined
        }
        #else
        return .granted
        #endif
    }

    /// Asks for microphone permission; the completion runs on the main queue.
    public static func requestPermission(_ completion: @escaping (Bool) -> Void) {
        #if os(iOS)
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            DispatchQueue.main.async { completion(granted) }
        }
        #elseif os(macOS)
        AVCaptureDevice.requestAccess(for: .audio) { granted in
            DispatchQueue.main.async { completion(granted) }
        }
        #else
        DispatchQueue.main.async { completion(true) }
        #endif
    }

    /// Starts a new recording from IDLE or STOPPED.
    ///
    /// Throws RECORDER_PERMISSION_DENIED (90201) without permission, RECORDER_UNAVAILABLE
    /// (90202) when no input is available or the engine cannot start, INVALID_STATE (90009)
    /// while recording or after `release()`.
    public func start() throws {
        control.lock()
        defer { control.unlock() }
        switch state {
        case .released: throw YuguErrors.local(ErrorCodes.invalidState, "recorder released")
        case .recording, .paused: throw YuguErrors.local(ErrorCodes.invalidState, "recorder already recording")
        case .idle, .stopped: break
        }
        guard YuguRecorder.permissionStatus() == .granted else {
            throw YuguErrors.local(ErrorCodes.recorderPermissionDenied, "microphone permission not granted, call YuguRecorder.requestPermission first")
        }
        processing.sync {
            pcm = Data()
            remainder = Data()
        }
        do {
            try activateSession()
            let e = AVAudioEngine()
            engine = e
            try installTap(on: e)
            e.prepare()
            try e.start()
            observeEngine(e)
            setState(.recording)
        } catch {
            teardownEngine()
            deactivateSession()
            if let y = error as? YuguError { throw y }
            throw YuguErrors.local(ErrorCodes.recorderUnavailable, "audio engine did not start: \(error.localizedDescription)", cause: error)
        }
    }

    /// RECORDING to PAUSED. The microphone stays reserved.
    public func pause() throws {
        control.lock()
        defer { control.unlock() }
        guard state == .recording else { throw YuguErrors.local(ErrorCodes.invalidState, "pause() needs RECORDING") }
        engine?.pause()
        setState(.paused)
    }

    /// PAUSED to RECORDING.
    public func resume() throws {
        control.lock()
        defer { control.unlock() }
        guard state == .paused, let e = engine else { throw YuguErrors.local(ErrorCodes.invalidState, "resume() needs PAUSED") }
        do {
            try activateSession()
            try e.start()
            setState(.recording)
        } catch {
            let y = YuguErrors.local(ErrorCodes.recorderError, "audio engine did not resume: \(error.localizedDescription)", cause: error)
            failLocked(y)
            throw y
        }
    }

    /// Stops recording, releases the microphone and returns the recording. Returns the last
    /// recording when not recording.
    @discardableResult
    public func stop() -> YuguRecording? {
        control.lock()
        defer { control.unlock() }
        guard state == .recording || state == .paused else { return last }
        teardownEngine()
        deactivateSession()
        let recording = finishBuffers(flushTail: true)
        setState(.stopped)
        return recording
    }

    /// Stops when needed, releases every resource and drops the listener. Idempotent.
    public func release() {
        control.lock()
        defer { control.unlock() }
        if state == .released { return }
        if state == .recording || state == .paused {
            teardownEngine()
            deactivateSession()
            _ = finishBuffers(flushTail: true)
        }
        for o in sessionObservers { NotificationCenter.default.removeObserver(o) }
        sessionObservers.removeAll()
        processing.sync {
            pcm = Data()
            remainder = Data()
        }
        setState(.released)
        callbacks.async { [weak self] in self?.setListener(nil) }
    }

    // MARK: Engine

    private func installTap(on e: AVAudioEngine) throws {
        let input = e.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
            throw YuguErrors.local(ErrorCodes.recorderUnavailable, "no audio input available")
        }
        guard let converter = AVAudioConverter(from: inputFormat, to: targetFormat) else {
            throw YuguErrors.local(ErrorCodes.recorderError, "cannot convert \(inputFormat) to 16 kHz mono PCM16")
        }
        stateLock.lock()
        generation += 1
        let gen = generation
        stateLock.unlock()
        let target = targetFormat
        input.installTap(onBus: 0, bufferSize: 2048, format: inputFormat) { [weak self] buffer, _ in
            self?.convert(buffer, converter: converter, target: target, generation: gen)
        }
    }

    /// Runs on the audio thread.
    private func convert(_ buffer: AVAudioPCMBuffer, converter: AVAudioConverter, target: AVAudioFormat, generation gen: Int) {
        let ratio = target.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio + 64)
        guard capacity > 0, let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return }
        var supplied = false
        var conversionError: NSError?
        let status = converter.convert(to: out, error: &conversionError) { _, inputStatus in
            if supplied {
                inputStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            inputStatus.pointee = .haveData
            return buffer
        }
        if status == .error {
            let cause = conversionError
            DispatchQueue.global().async { [weak self] in
                self?.fail(YuguErrors.local(ErrorCodes.recorderError, "audio conversion failed", cause: cause), generation: gen)
            }
            return
        }
        guard out.frameLength > 0, let channels = out.int16ChannelData else { return }
        let data = Data(bytes: channels[0], count: Int(out.frameLength) * MemoryLayout<Int16>.size)
        processing.async { [weak self] in self?.append(data, generation: gen) }
    }

    /// Processing queue.
    private func append(_ data: Data, generation gen: Int) {
        stateLock.lock()
        let current = generation == gen && currentState == .recording
        stateLock.unlock()
        guard current else { return }
        pcm.append(data)
        remainder.append(data)
        let size = max(2, options.frameBytes)
        var out: [Data] = []
        while remainder.count >= size {
            out.append(Data(remainder.prefix(size)))
            remainder = Data(remainder.dropFirst(size))
        }
        if !out.isEmpty {
            deliver { r, l in
                for f in out { l.recorder(r, didCapture: f) }
            }
        }
    }

    /// Flushes the tail frame and returns the recording. Control lock held, engine stopped.
    private func finishBuffers(flushTail: Bool) -> YuguRecording {
        let (data, tail): (Data, Data) = processing.sync {
            let t = remainder
            remainder = Data()
            return (pcm, t)
        }
        if flushTail && !tail.isEmpty {
            deliver { r, l in l.recorder(r, didCapture: tail) }
        }
        let recording = YuguRecording(pcm: data, sampleRate: YuguRecorder.sampleRate, channels: 1)
        last = recording
        return recording
    }

    private func teardownEngine() {
        stateLock.lock()
        generation += 1
        stateLock.unlock()
        if let o = engineObserver {
            NotificationCenter.default.removeObserver(o)
            engineObserver = nil
        }
        if let e = engine {
            e.inputNode.removeTap(onBus: 0)
            e.stop()
            e.reset()
        }
        engine = nil
    }

    /// Route changes (headset, Bluetooth) stop the engine and may change the input format:
    /// install a new tap and restart, or fail.
    private func observeEngine(_ e: AVAudioEngine) {
        engineObserver = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: e, queue: nil) { [weak self] _ in
            self?.handleConfigurationChange()
        }
    }

    private func handleConfigurationChange() {
        control.lock()
        defer { control.unlock() }
        guard state == .recording, let e = engine else { return }
        do {
            e.inputNode.removeTap(onBus: 0)
            try installTap(on: e)
            e.prepare()
            try e.start()
        } catch {
            failLocked(YuguErrors.local(ErrorCodes.recorderError, "audio route change: \(error.localizedDescription)", cause: error))
        }
    }

    private func observeAudioSession() {
        #if os(iOS)
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        sessionObservers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: nil) { [weak self] note in
            self?.handleInterruption(note)
        })
        sessionObservers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: nil) { [weak self] _ in
            self?.fail(YuguErrors.local(ErrorCodes.recorderError, "media services were reset"), generation: nil)
        })
        #endif
    }

    #if os(iOS)
    private func handleInterruption(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
            let type = AVAudioSession.InterruptionType(rawValue: raw)
        else { return }
        switch type {
        case .began:
            control.lock()
            defer { control.unlock() }
            if state == .recording {
                engine?.pause()
                setState(.paused)
            }
        case .ended:
            let opts = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map { AVAudioSession.InterruptionOptions(rawValue: $0) } ?? []
            let resume = opts.contains(.shouldResume)
            deliver { r, l in l.recorder(r, interruptionEndedShouldResume: resume) }
        @unknown default:
            break
        }
    }
    #endif

    // MARK: Audio session

    private func activateSession() throws {
        #if os(iOS)
        guard options.manageAudioSession else { return }
        let s = AVAudioSession.sharedInstance()
        do {
            try s.setCategory(options.category, mode: options.mode, options: options.categoryOptions)
            try s.setActive(true, options: [])
            sessionActive = true
        } catch {
            throw YuguErrors.local(ErrorCodes.recorderUnavailable, "audio session not available: \(error.localizedDescription)", cause: error)
        }
        #endif
    }

    private func deactivateSession() {
        #if os(iOS)
        guard options.manageAudioSession, sessionActive else { return }
        sessionActive = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }

    // MARK: Errors and delivery

    private func fail(_ error: YuguError, generation gen: Int?) {
        control.lock()
        defer { control.unlock() }
        if let g = gen {
            stateLock.lock()
            let stale = g != generation
            stateLock.unlock()
            if stale { return }
        }
        failLocked(error)
    }

    /// Error path: microphone released, state STOPPED, listener told. Control lock held.
    private func failLocked(_ error: YuguError) {
        guard state == .recording || state == .paused else { return }
        teardownEngine()
        deactivateSession()
        _ = finishBuffers(flushTail: false)
        setState(.stopped)
        deliver { r, l in l.recorder(r, didFail: error) }
    }

    private func setState(_ next: RecorderState) {
        stateLock.lock()
        let old = currentState
        currentState = next
        stateLock.unlock()
        guard old != next else { return }
        deliver { r, l in l.recorder(r, didChangeState: old, to: next) }
    }

    private func deliver(_ body: @escaping (YuguRecorder, YuguRecorderListener) -> Void) {
        callbacks.async { [weak self] in
            guard let self = self else { return }
            self.stateLock.lock()
            let l = self.listener
            self.stateLock.unlock()
            if let l = l { body(self, l) }
        }
    }
}
#endif
