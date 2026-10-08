import Combine
import Foundation
import YuguSDK

/// Credentials and base URL from Info.plist, which takes them from Config.xcconfig.
struct DemoConfig {
    let baseUrl: String
    let auth: YuguAuth
    let isConfigured: Bool

    static func load() -> DemoConfig {
        let info = Bundle.main.infoDictionary ?? [:]
        func value(_ key: String) -> String {
            (info[key] as? String ?? "").trimmingCharacters(in: .whitespaces)
        }
        let base = value("YuguBaseURL")
        let token = value("YuguToken")
        let key = value("YuguAppKey")
        let secret = value("YuguSecretKey")
        let auth: YuguAuth = token.isEmpty ? .appKey(key, secretKey: secret) : .token(token)
        return DemoConfig(
            baseUrl: base.isEmpty ? YuguClientOptions.defaultBaseUrl : base,
            auth: auth,
            isConfigured: !token.isEmpty || (!key.isEmpty && !secret.isEmpty))
    }
}

/// Create, use, release: one client and one recorder for the life of the screen.
final class DemoModel: ObservableObject {
    enum Mode: String, CaseIterable, Identifiable {
        case whole = "整段评测"
        case stream = "实时评测"
        var id: String { rawValue }
    }

    @Published var referenceText = "今天天气很好"
    @Published var coreType = "sentence"
    @Published var language = "zh-CN"
    @Published var mode: Mode = .whole
    @Published var isRecording = false
    @Published var isBusy = false
    @Published var status = "就绪"
    @Published var sessionState = "-"
    @Published var result: EvalResult?
    @Published var errorText: String?
    @Published var events: [String] = []

    let config = DemoConfig.load()
    private let client: YuguClient
    private let recorder = YuguRecorder()
    private let recorderHandlers = YuguRecorderHandlers()
    private var session: YuguStreamSession?

    init() {
        var options = YuguClientOptions(auth: config.auth, baseUrl: config.baseUrl)
        options.logLevel = .info
        client = YuguClient(options: options)
        recorderHandlers.onFrame = { [weak self] frame in
            // Streaming: forward each 640-byte frame. sendAudio is thread-safe.
            try? self?.session?.sendAudio(frame)
        }
        recorderHandlers.onStateChanged = { [weak self] _, to in
            self?.isRecording = to == .recording
        }
        recorderHandlers.onError = { [weak self] error in
            self?.show(error)
        }
        recorder.setListener(recorderHandlers)
    }

    deinit {
        // Release: microphone, sessions and network resources. Both calls are idempotent.
        recorder.release()
        client.close()
    }

    private var evaluateConfig: EvaluateConfig {
        EvaluateConfig(
            coreType: CoreType(coreType), referenceText: referenceText, language: language,
            includeReport: true, includeAsrText: true)
    }

    // MARK: Actions

    func toggleRecording() {
        if isRecording {
            mode == .whole ? stopAndEvaluate() : stopStreaming()
            return
        }
        YuguRecorder.requestPermission { [weak self] granted in
            guard let self = self else { return }
            guard granted else {
                self.errorText = "没有麦克风权限，请在系统设置中允许"
                return
            }
            self.mode == .whole ? self.startRecording() : self.startStreaming()
        }
    }

    func evaluateSample() {
        guard let url = Bundle.main.url(forResource: "sample_zh", withExtension: "wav") else { return }
        referenceText = "今天天气很好"
        language = "zh-CN"
        evaluate(.file(url))
    }

    private func startRecording() {
        reset()
        do {
            try recorder.start()
            status = "录音中"
        } catch {
            show(error)
        }
    }

    private func stopAndEvaluate() {
        guard let recording = recorder.stop() else { return }
        status = String(format: "已录 %.1f 秒，评测中", recording.durationSeconds)
        evaluate(recording.audioInput)
    }

    private func evaluate(_ audio: AudioInput) {
        isBusy = true
        errorText = nil
        client.evaluate(audio: audio, config: evaluateConfig) { [weak self] outcome in
            guard let self = self else { return }
            self.isBusy = false
            switch outcome {
            case .success(let r):
                self.result = r
                self.status = "完成"
            case .failure(let e):
                self.show(e)
            }
        }
    }

    private func startStreaming() {
        reset()
        let listener = YuguStreamHandlers()
        listener.onStateChanged = { [weak self] _, to in self?.sessionState = to.rawValue }
        listener.onStarted = { [weak self] in self?.log("服务端已就绪") }
        listener.onReconnecting = { [weak self] attempt, delay, cause in
            self?.log("第 \(attempt) 次重连，\(delay) 毫秒后：\(cause.message)")
        }
        listener.onReconnected = { [weak self] attempt, dropped in
            self?.log("第 \(attempt) 次重连成功，丢弃 \(dropped) 字节")
        }
        listener.onWarning = { [weak self] w in self?.log("本地预检：\(w.message)") }
        listener.onResult = { [weak self] r in
            self?.result = r
            self?.status = "完成"
        }
        listener.onError = { [weak self] e in self?.show(e) }
        listener.onClosed = { [weak self] _, _ in
            self?.session = nil
            self?.isBusy = false
        }
        do {
            let s = try client.streamEvaluate(config: evaluateConfig, listener: listener)
            session = s
            try s.start()
            try recorder.start()
            status = "实时评测中"
        } catch {
            session?.cancel()
            show(error)
        }
    }

    private func stopStreaming() {
        recorder.stop()
        isBusy = true
        status = "等待结果"
        do {
            try session?.end()
        } catch {
            show(error)
        }
    }

    func cancel() {
        session?.cancel()
        recorder.stop()
        status = "已取消"
        isBusy = false
    }

    // MARK: Helpers

    private func reset() {
        result = nil
        errorText = nil
        events = []
        sessionState = "-"
    }

    private func log(_ s: String) {
        events.append(s)
    }

    private func show(_ error: Error) {
        let e = YuguErrors.wrap(error)
        var text = "\(e.category.rawValue) \(e.code)：\(e.message)"
        if e.retryable { text += "，可用同一幂等键重试" }
        errorText = text
        status = "失败"
        isBusy = false
    }
}
