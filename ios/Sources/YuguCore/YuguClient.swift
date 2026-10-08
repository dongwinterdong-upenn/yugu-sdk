import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Client of the Yugu speech evaluation platform.
///
/// ```swift
/// let client = YuguClient(options: YuguClientOptions(auth: .appKey("<appKey>", secretKey: "<secretKey>")))
/// let result = try await client.evaluate(
///     audio: .file(wavURL),
///     config: EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好", language: "zh-CN"))
/// print(result.overall ?? 0)
/// client.close()
/// ```
///
/// Write calls (`evaluate`, `evaluateCompat`, `tts`) carry an `Idempotency-Key`; retries and
/// reconnects reuse it, so the platform bills a repeated call once. All methods are thread-safe.
public final class YuguClient {
    public let options: YuguClientOptions
    let core: ClientCore

    public init(options: YuguClientOptions) {
        self.options = options
        core = ClientCore(
            options: options, executor: DispatchExecutor(label: "yugu.client"),
            sink: QueueCallbackSink(target: options.callbackQueue), random: SystemRandomSource(), wallClock: Date.init)
    }

    /// Test seam: executor, callback sink, random source and wall clock.
    init(options: YuguClientOptions, executor: YuguExecutor, sink: CallbackSink, random: YuguRandom, wallClock: @escaping () -> Date = Date.init) {
        self.options = options
        core = ClientCore(options: options, executor: executor, sink: sink, random: random, wallClock: wallClock)
    }

    /// True after `close()`.
    public var isClosed: Bool { core.isClosed }

    /// Cancels open sessions and calls and releases the client's resources. Idempotent. Calls
    /// made afterwards fail with CLIENT_CLOSED (90004).
    public func close() {
        guard let (calls, sessions) = core.markClosed() else { return }
        for c in calls {
            c.abort(YuguErrors.local(ErrorCodes.clientClosed, "client closed"))
        }
        for s in sessions {
            s.cancelByClient()
        }
        core.log.info("client", "closed, \(calls.count) call(s) and \(sessions.count) session(s) cancelled")
        if core.ownsHTTP {
            let http = core.http
            core.executor.async { http.close() }
        }
    }

    // MARK: - evaluate

    /// Native evaluation `POST /api/v1/evaluate` with completion handler.
    ///
    /// - Parameters:
    ///   - audio: WAV or other audio bytes, a file, or raw PCM.
    ///   - config: the `config` part; its exact JSON text is signed.
    ///   - image: picture for `open` tasks with `taskType` `picture`.
    @discardableResult
    public func evaluate(
        audio: AudioInput, config: EvaluateConfig, image: Data? = nil, options: RequestOptions = RequestOptions(),
        completion: @escaping (Result<EvalResult, YuguError>) -> Void
    ) -> YuguCall {
        evaluate(audio: audio, config: config, image: image, options: options, sink: core.sink, completion: completion)
    }

    /// Native evaluation `POST /api/v1/evaluate`.
    public func evaluate(audio: AudioInput, config: EvaluateConfig, image: Data? = nil, options: RequestOptions = RequestOptions()) async throws -> EvalResult {
        try await bridge(options) { opts, sink, done in
            self.evaluate(audio: audio, config: config, image: image, options: opts, sink: sink, completion: done)
        }
    }

    func evaluate(
        audio: AudioInput, config: EvaluateConfig, image: Data?, options: RequestOptions, sink: CallbackSink,
        completion: @escaping (Result<EvalResult, YuguError>) -> Void
    ) -> YuguCall {
        let op = "evaluate"
        return start(op, options: options, sink: sink, completion: completion) {
            let key = try self.resolveKey(options.idempotencyKey, isWrite: true)
            let configText = try config.jsonText()
            let loaded = try audio.load()
            let warnings = try self.precheck(loaded, mode: options.audioPrecheck ?? self.options.audioPrecheck, op: op)
            let body = self.multipart { mp in
                mp.addJSONField(name: "config", json: configText)
                mp.addFile(name: "audio", filename: loaded.filename, contentType: loaded.contentType, data: loaded.data)
                if let img = image {
                    let type = AudioInput.imageType(img)
                    mp.addFile(name: "image", filename: type == "image/png" ? "image.png" : "image.jpg", contentType: type, data: img)
                }
            }
            let strict = self.options.strictAudio
            let plan = CallPlan<EvalResult>(
                op: op, method: "POST", path: "/api/v1/evaluate", url: try self.core.url(path: "/api/v1/evaluate"),
                body: body.data, contentType: body.contentType, signParams: ["config": configText], isWrite: true,
                parse: { resp, info in YuguClient.parseEvaluation(resp, info, warnings: warnings, strictAudio: strict) })
            return (plan, key)
        }
    }

    // MARK: - evaluateCompat

    /// Engine compatible evaluation `POST /{coreType}` with completion handler. The form fields
    /// that are sent are the fields that are signed.
    @discardableResult
    public func evaluateCompat(
        coreType: CompatCoreType, audio: AudioInput, params: CompatParams, options: RequestOptions = RequestOptions(),
        completion: @escaping (Result<EvalResult, YuguError>) -> Void
    ) -> YuguCall {
        evaluateCompat(coreType: coreType, audio: audio, params: params, options: options, sink: core.sink, completion: completion)
    }

    /// Engine compatible evaluation `POST /{coreType}`.
    public func evaluateCompat(coreType: CompatCoreType, audio: AudioInput, params: CompatParams, options: RequestOptions = RequestOptions()) async throws -> EvalResult {
        try await bridge(options) { opts, sink, done in
            self.evaluateCompat(coreType: coreType, audio: audio, params: params, options: opts, sink: sink, completion: done)
        }
    }

    func evaluateCompat(
        coreType: CompatCoreType, audio: AudioInput, params: CompatParams, options: RequestOptions, sink: CallbackSink,
        completion: @escaping (Result<EvalResult, YuguError>) -> Void
    ) -> YuguCall {
        let op = "evaluateCompat"
        return start(op, options: options, sink: sink, completion: completion) {
            let key = try self.resolveKey(options.idempotencyKey, isWrite: true)
            try params.validate(coreType: coreType)
            let loaded = try audio.load()
            let warnings = try self.precheck(loaded, mode: options.audioPrecheck ?? self.options.audioPrecheck, op: op)
            let fields = params.formFields(coreType: coreType)
            let body = self.multipart { mp in
                for (k, v) in fields { mp.addField(name: k, value: v) }
                mp.addFile(name: "audio", filename: loaded.filename, contentType: loaded.contentType, data: loaded.data)
            }
            var sign: [String: String] = [:]
            for (k, v) in fields { sign[k] = v }
            let path = "/" + coreType.rawValue
            let strict = self.options.strictAudio
            let plan = CallPlan<EvalResult>(
                op: op, method: "POST", path: path, url: try self.core.url(path: path), body: body.data,
                contentType: body.contentType, signParams: sign, isWrite: true,
                parse: { resp, info in YuguClient.parseEvaluation(resp, info, warnings: warnings, strictAudio: strict) })
            return (plan, key)
        }
    }

    // MARK: - tts

    /// Text to speech `POST /api/v1/tts/generate` with completion handler. The top-level scalar
    /// members of the JSON body are signed with the text that is sent.
    @discardableResult
    public func tts(_ request: TTSRequest, options: RequestOptions = RequestOptions(), completion: @escaping (Result<TTSResult, YuguError>) -> Void) -> YuguCall {
        tts(request, options: options, sink: core.sink, completion: completion)
    }

    /// Text to speech `POST /api/v1/tts/generate`.
    public func tts(_ request: TTSRequest, options: RequestOptions = RequestOptions()) async throws -> TTSResult {
        try await bridge(options) { opts, sink, done in self.tts(request, options: opts, sink: sink, completion: done) }
    }

    func tts(_ request: TTSRequest, options: RequestOptions, sink: CallbackSink, completion: @escaping (Result<TTSResult, YuguError>) -> Void) -> YuguCall {
        let op = "tts"
        return start(op, options: options, sink: sink, completion: completion) {
            let key = try self.resolveKey(options.idempotencyKey, isWrite: true)
            try request.validate()
            let json = request.jsonObject()
            let base = self.core.baseUrl
            let plan = CallPlan<TTSResult>(
                op: op, method: "POST", path: "/api/v1/tts/generate", url: try self.core.url(path: "/api/v1/tts/generate"),
                body: Data(json.text.utf8), contentType: "application/json; charset=utf-8", signParams: json.signableScalars,
                isWrite: true,
                parse: { resp, info in
                    YuguClient.parseEnvelope(resp).map { j in
                        var r = ResultParser.ttsResult(j, baseUrl: base)
                        r.idempotencyKey = info.idempotencyKey
                        r.replayed = info.replayed
                        r.attempts = info.attempts
                        return r
                    }
                })
            return (plan, key)
        }
    }

    // MARK: - getReport

    /// Report of an evaluation `GET /api/v1/report/{recordId}` with completion handler. Retried
    /// without idempotency key, a GET is idempotent.
    @discardableResult
    public func getReport(recordId: String, options: RequestOptions = RequestOptions(), completion: @escaping (Result<ReportResult, YuguError>) -> Void) -> YuguCall {
        getReport(recordId: recordId, options: options, sink: core.sink, completion: completion)
    }

    /// Report of an evaluation `GET /api/v1/report/{recordId}`.
    public func getReport(recordId: String, options: RequestOptions = RequestOptions()) async throws -> ReportResult {
        try await bridge(options) { opts, sink, done in self.getReport(recordId: recordId, options: opts, sink: sink, completion: done) }
    }

    func getReport(recordId: String, options: RequestOptions, sink: CallbackSink, completion: @escaping (Result<ReportResult, YuguError>) -> Void) -> YuguCall {
        let op = "getReport"
        return start(op, options: options, sink: sink, completion: completion) {
            let key = try self.resolveKey(options.idempotencyKey, isWrite: false)
            let id = recordId.trimmingCharacters(in: .whitespaces)
            if id.isEmpty { throw YuguErrors.local(ErrorCodes.invalidArgument, "recordId is empty") }
            let path = "/api/v1/report/" + RequestSigner.percentEncode(id)
            let plan = CallPlan<ReportResult>(
                op: op, method: "GET", path: path, url: try self.core.url(path: path), body: nil, contentType: nil,
                signParams: [:], isWrite: false,
                parse: { resp, info in
                    YuguClient.parseEnvelope(resp).map { j in
                        var r = ReportResult(recordId: id, data: j["data"] ?? .null, raw: j)
                        r.attempts = info.attempts
                        return r
                    }
                })
            return (plan, key)
        }
    }

    // MARK: - Streaming

    /// Native streaming evaluation over `WS /api/v1/ws/evaluate`. Call `start()` on the session.
    public func streamEvaluate(config: EvaluateConfig, options: StreamOptions = StreamOptions(), listener: YuguStreamListener) throws -> YuguStreamSession {
        if core.isClosed { throw YuguErrors.local(ErrorCodes.clientClosed) }
        try config.validate()
        let key = try resolveStreamKey(options.idempotencyKey)
        return YuguStreamSession(core: core, kind: .native(config), idempotencyKey: key, settings: SessionSettings(options, self.options), listener: listener)
    }

    /// Engine compatible streaming evaluation over `WS /{coreType}`. Call `start()` on the session.
    public func streamEvaluateCompat(
        coreType: CompatCoreType, params: CompatParams, options: StreamOptions = StreamOptions(), listener: YuguStreamListener
    ) throws -> YuguStreamSession {
        if core.isClosed { throw YuguErrors.local(ErrorCodes.clientClosed) }
        try params.validate(coreType: coreType)
        let key = try resolveStreamKey(options.idempotencyKey)
        return YuguStreamSession(
            core: core, kind: .compat(coreType, params), idempotencyKey: key, settings: SessionSettings(options, self.options), listener: listener)
    }

    // MARK: - Internals

    private func resolveKey(_ provided: String?, isWrite: Bool) throws -> String? {
        if let k = provided {
            guard IdempotencyKey.isValid(k) else {
                throw YuguErrors.local(ErrorCodes.invalidArgument, "idempotencyKey must be 1 to 200 printable ASCII characters without spaces")
            }
            return k
        }
        return isWrite && options.autoIdempotencyKey ? IdempotencyKey.generate() : nil
    }

    private func resolveStreamKey(_ provided: String?) throws -> String? {
        try resolveKey(provided, isWrite: true)
    }

    private func precheck(_ audio: AudioInput.Loaded, mode: AudioPrecheckMode, op: String) throws -> [YuguWarning] {
        guard mode != .off else { return [] }
        let report = audio.kind == .wav ? AudioPrecheck.check(audio.data) : AudioPrecheck.sizeOnly(audio.data.count)
        let warnings = try AudioPrecheck.apply(report, mode: mode)
        for w in warnings { core.log.warn(op, "audio precheck: \(w)") }
        return warnings
    }

    private func multipart(_ fill: (inout MultipartBuilder) -> Void) -> (data: Data, contentType: String) {
        var mp = MultipartBuilder(boundary: MultipartBuilder.makeBoundary(core.random))
        fill(&mp)
        var tries = 0
        while !mp.boundaryIsSafe && tries < 8 {
            var again = MultipartBuilder(boundary: MultipartBuilder.makeBoundary(core.random))
            fill(&again)
            mp = again
            tries += 1
        }
        return (mp.build(), mp.contentType)
    }

    private func start<T>(
        _ op: String, options: RequestOptions, sink: CallbackSink, completion: @escaping (Result<T, YuguError>) -> Void,
        prepare: () throws -> (CallPlan<T>, String?)
    ) -> YuguCall {
        do {
            if core.isClosed { throw YuguErrors.local(ErrorCodes.clientClosed) }
            let (plan, key) = try prepare()
            let call = RestCall(core: core, plan: plan, key: key, options: options, sink: sink, completion: completion)
            call.start()
            return YuguCall(operation: op, idempotencyKey: key) { [weak call] in call?.cancel() }
        } catch {
            let e = YuguErrors.wrap(error)
            core.log.warn(op, "rejected before sending: \(e)")
            sink.deliver { completion(.failure(e)) }
            return YuguCall(operation: op, idempotencyKey: nil) {}
        }
    }

    /// Runs a completion based call under Swift concurrency. Task cancellation cancels the call.
    private func bridge<T>(
        _ options: RequestOptions,
        _ run: @escaping (RequestOptions, CallbackSink, @escaping (Result<T, YuguError>) -> Void) -> YuguCall
    ) async throws -> T {
        let token = YuguCancellationToken()
        var opts = options
        let user = options.cancellationToken
        let registration = user?.onCancel { token.cancel() }
        defer { user?.removeHandler(registration ?? nil) }
        opts.cancellationToken = token
        let sink = InlineCallbackSink()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<T, Error>) in
                _ = run(opts, sink) { result in cont.resume(with: result) }
            }
        } onCancel: {
            token.cancel()
        }
    }

    static func parseEvaluation(_ resp: YuguHTTPResponse, _ info: CallInfo, warnings: [YuguWarning], strictAudio: Bool) -> Result<EvalResult, YuguError> {
        let json: JSONValue
        do {
            json = try JSONValue.parse(resp.body)
        } catch {
            return .failure(YuguErrors.local(ErrorCodes.protocolError, "evaluation response is not JSON", cause: error))
        }
        guard case .object = json else {
            return .failure(YuguErrors.local(ErrorCodes.protocolError, "evaluation response is not a JSON object"))
        }
        if json["result"] == nil && json["recordId"] == nil {
            let code = json["code"]?.intValue ?? json["errId"]?.intValue ?? 0
            if code != 0 || json["detail"] != nil {
                return .failure(YuguErrors.fromBody(status: resp.statusCode, json: json, rawBody: YuguErrors.truncatedText(resp.body), retryAfterMs: nil))
            }
            return .failure(YuguErrors.local(ErrorCodes.protocolError, "evaluation response has no result"))
        }
        var r = ResultParser.evalResult(json)
        r.localWarnings = warnings
        r.idempotencyKey = info.idempotencyKey
        r.replayed = info.replayed
        r.attempts = info.attempts
        if strictAudio && r.hasNoValidAudio {
            return .failure(YuguErrors.fromWarning(WarningCode.noValidAudio.rawValue).with(recordId: r.recordId))
        }
        return .success(r)
    }

    /// `{code:0, data:{...}}` bodies of TTS and report.
    static func parseEnvelope(_ resp: YuguHTTPResponse) -> Result<JSONValue, YuguError> {
        let json: JSONValue
        do {
            json = try JSONValue.parse(resp.body)
        } catch {
            return .failure(YuguErrors.local(ErrorCodes.protocolError, "response is not JSON", cause: error))
        }
        let code = json["code"]?.intValue ?? 0
        if code != 0 {
            return .failure(YuguErrors.fromBody(status: resp.statusCode, json: json, rawBody: YuguErrors.truncatedText(resp.body), retryAfterMs: nil))
        }
        if json["data"] == nil || json["data"]?.isNull == true {
            return .failure(YuguErrors.local(ErrorCodes.protocolError, "response has no data"))
        }
        return .success(json)
    }
}

extension AudioInput {
    static func imageType(_ d: Data) -> String {
        let b = [UInt8](d.prefix(4))
        if b.count >= 4, b[0] == 0x89, b[1] == 0x50, b[2] == 0x4E, b[3] == 0x47 { return "image/png" }
        return "image/jpeg"
    }
}
