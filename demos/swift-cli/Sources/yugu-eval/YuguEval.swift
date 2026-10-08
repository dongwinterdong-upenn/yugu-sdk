import Foundation
import YuguCore

/// yugu-eval [options] FILE.wav
///
///   --base-url URL         platform base, default https://open.shengzhiai.com (env YUGU_BASE_URL)
///   --app-key KEY          API key (env YUGU_APP_KEY)
///   --secret-key SECRET    API secret (env YUGU_SECRET_KEY)
///   --token JWT            user token instead of the key pair (env YUGU_TOKEN)
///   --text TEXT            reference text, required
///   --core-type TYPE       word, sentence (default), passage, connected, open, alpha, pinyin
///   --language LANG        en-US, en-GB or zh-CN
///   --compat CORETYPE      engine compatible REST, for example sent.eval.cn
///   --stream               native WebSocket streaming instead of REST
///   --idempotency-key KEY  reuse a key to repeat a call without double billing
///   --precheck MODE        off, warn (default) or reject
///   --json                 print the raw platform JSON
@main
struct YuguEval {
    static func main() async {
        let env = ProcessInfo.processInfo.environment
        var o = Options()
        o.baseUrl = env["YUGU_BASE_URL"] ?? YuguClientOptions.defaultBaseUrl
        o.appKey = env["YUGU_APP_KEY"]
        o.secretKey = env["YUGU_SECRET_KEY"]
        o.token = env["YUGU_TOKEN"]
        do {
            try o.parse(Array(CommandLine.arguments.dropFirst()))
        } catch {
            fail(2, "\(error)\n\n\(Options.usage)")
        }
        guard let file = o.file else { fail(2, Options.usage) }
        guard let text = o.text, !text.isEmpty else { fail(2, "--text is required\n\n\(Options.usage)") }
        let auth: YuguAuth
        if let t = o.token, !t.isEmpty {
            auth = .token(t)
        } else if let k = o.appKey, let s = o.secretKey, !k.isEmpty, !s.isEmpty {
            auth = .appKey(k, secretKey: s)
        } else {
            fail(2, "give --app-key and --secret-key, or --token")
        }

        var options = YuguClientOptions(auth: auth, baseUrl: o.baseUrl)
        options.audioPrecheck = o.precheck
        options.callbackQueue = DispatchQueue(label: "yugu-eval")
        let client = YuguClient(options: options)
        defer { client.close() }

        let url = URL(fileURLWithPath: file)
        let config = EvaluateConfig(coreType: CoreType(o.coreType), referenceText: text, language: o.language)
        do {
            let result: EvalResult
            if let compat = o.compat {
                result = try await client.evaluateCompat(
                    coreType: CompatCoreType(compat), audio: .file(url), params: CompatParams(refText: text, language: o.language),
                    options: RequestOptions(idempotencyKey: o.idempotencyKey))
            } else if o.stream {
                result = try await stream(client, file: url, config: config, key: o.idempotencyKey)
            } else {
                result = try await client.evaluate(audio: .file(url), config: config, options: RequestOptions(idempotencyKey: o.idempotencyKey))
            }
            if o.json {
                print(result.raw.jsonText)
            } else {
                printSummary(result)
            }
            exit(0)
        } catch let e as YuguError {
            fail(1, "evaluation failed: \(e.debugDescription)")
        } catch {
            fail(1, "evaluation failed: \(error)")
        }
    }

    static func stream(_ client: YuguClient, file: URL, config: EvaluateConfig, key: String?) async throws -> EvalResult {
        let wav = try Data(contentsOf: file)
        guard let info = YuguWAV.parse(wav), info.sampleRate == 16000, info.channels == 1, info.bitsPerSample == 16 else {
            throw YuguErrors.local(ErrorCodes.audioFormatUnsupported, "streaming needs a 16 kHz mono 16-bit WAV")
        }
        let pcm = wav.subdata(in: info.dataOffset..<(info.dataOffset + info.dataLength))
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<EvalResult, Error>) in
            let handlers = YuguStreamHandlers()
            var outcome: Result<EvalResult, Error>?
            handlers.onReconnecting = { attempt, delay, cause in
                FileHandle.standardError.write(Data("reconnecting \(attempt) in \(delay) ms: \(cause.message)\n".utf8))
            }
            handlers.onResult = { outcome = .success($0) }
            handlers.onError = { outcome = .failure($0) }
            handlers.onClosed = { _, _ in
                cont.resume(with: outcome ?? .failure(YuguErrors.local(ErrorCodes.cancelled)))
            }
            do {
                let session = try client.streamEvaluate(config: config, options: StreamOptions(idempotencyKey: key), listener: handlers)
                try session.start()
                var offset = 0
                while offset < pcm.count {
                    let end = min(offset + 640, pcm.count)
                    try session.sendAudio(pcm.subdata(in: offset..<end))
                    offset = end
                }
                try session.end()
            } catch {
                cont.resume(throwing: error)
            }
        }
    }

    static func printSummary(_ r: EvalResult) {
        func f(_ v: Double?) -> String { v.map { JSONWriter.formatNumber($0) ?? "-" } ?? "-" }
        print("recordId        \(r.recordId ?? "-")")
        print("coreType        \(r.coreType ?? "-")")
        print("overall         \(f(r.overall))")
        let d = r.dimensions
        print("integrity       \(f(d.integrity))   accuracy \(f(d.accuracy))   fluency \(f(d.fluency))")
        print("tone            \(f(d.tone))   rhythm \(f(d.rhythm))   speed \(f(d.speed))")
        if let c = r.connected {
            print("linking         \(f(c.linking))   elision \(f(c.elision))   reduction \(f(c.reduction))")
        }
        if let open = r.openTask {
            print("content         \(f(open.content?.overall))   languageUse \(f(open.languageUse?.overall))   delivery \(f(open.delivery?.overall))")
        }
        if !r.words.isEmpty {
            print("words           " + r.words.map { "\($0.word ?? "?") \(f($0.overall))" }.joined(separator: " | "))
        }
        if !r.warnings.isEmpty { print("warnings        " + r.warnings.map { $0.description }.joined(separator: "; ")) }
        if !r.localWarnings.isEmpty { print("local warnings  " + r.localWarnings.map { $0.description }.joined(separator: "; ")) }
        print("attempts        \(r.attempts)   replayed \(r.replayed)   idempotencyKey \(r.idempotencyKey ?? "-")")
    }

    static func fail(_ code: Int32, _ message: String) -> Never {
        FileHandle.standardError.write(Data((message + "\n").utf8))
        exit(code)
    }
}

struct Options {
    var baseUrl = YuguClientOptions.defaultBaseUrl
    var appKey: String?
    var secretKey: String?
    var token: String?
    var text: String?
    var coreType = "sentence"
    var language: String?
    var compat: String?
    var stream = false
    var idempotencyKey: String?
    var precheck = AudioPrecheckMode.warn
    var json = false
    var file: String?

    static let usage = """
        usage: yugu-eval [--base-url URL] (--app-key KEY --secret-key SECRET | --token JWT)
                         --text TEXT [--core-type TYPE] [--language LANG] [--compat CORETYPE]
                         [--stream] [--idempotency-key KEY] [--precheck off|warn|reject] [--json] FILE.wav
        """

    struct UsageError: Error, CustomStringConvertible {
        let description: String
    }

    mutating func parse(_ args: [String]) throws {
        var i = 0
        func value() throws -> String {
            i += 1
            guard i < args.count else { throw UsageError(description: "\(args[i - 1]) needs a value") }
            return args[i]
        }
        while i < args.count {
            let a = args[i]
            switch a {
            case "--base-url": baseUrl = try value()
            case "--app-key": appKey = try value()
            case "--secret-key": secretKey = try value()
            case "--token": token = try value()
            case "--text": text = try value()
            case "--core-type": coreType = try value()
            case "--language": language = try value()
            case "--compat": compat = try value()
            case "--stream": stream = true
            case "--idempotency-key": idempotencyKey = try value()
            case "--precheck":
                let v = try value()
                guard let m = AudioPrecheckMode(rawValue: v.uppercased()) else { throw UsageError(description: "unknown precheck mode \(v)") }
                precheck = m
            case "--json": json = true
            case "-h", "--help": throw UsageError(description: "help")
            default:
                if a.hasPrefix("--") { throw UsageError(description: "unknown option \(a)") }
                file = a
            }
            i += 1
        }
    }
}
