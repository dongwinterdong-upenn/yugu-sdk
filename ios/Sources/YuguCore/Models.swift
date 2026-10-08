import Foundation

/// Native evaluation type (`config.coreType`). Unknown values pass through unchanged.
public struct CoreType: RawRepresentable, Hashable, Sendable, ExpressibleByStringLiteral, CustomStringConvertible {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public init(_ rawValue: String) { self.rawValue = rawValue }
    public init(stringLiteral value: String) { self.rawValue = value }
    public var description: String { rawValue }

    public static let word = CoreType("word")
    public static let sentence = CoreType("sentence")
    public static let passage = CoreType("passage")
    public static let connected = CoreType("connected")
    public static let open = CoreType("open")
    public static let alpha = CoreType("alpha")
    public static let pinyin = CoreType("pinyin")
    public static let known: [CoreType] = [.word, .sentence, .passage, .connected, .open, .alpha, .pinyin]
}

/// Engine compatible evaluation type, the path of `POST /{coreType}` and `WS /{coreType}`.
public struct CompatCoreType: RawRepresentable, Hashable, Sendable, ExpressibleByStringLiteral, CustomStringConvertible {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public init(_ rawValue: String) { self.rawValue = rawValue }
    public init(stringLiteral value: String) { self.rawValue = value }
    public var description: String { rawValue }

    public static let wordEval = CompatCoreType("word.eval")
    public static let wordEvalPro = CompatCoreType("word.eval.pro")
    public static let sentEval = CompatCoreType("sent.eval")
    public static let sentEvalPro = CompatCoreType("sent.eval.pro")
    public static let paraEval = CompatCoreType("para.eval")
    public static let alphaEval = CompatCoreType("alpha.eval")
    public static let wordEvalCn = CompatCoreType("word.eval.cn")
    public static let sentEvalCn = CompatCoreType("sent.eval.cn")
    public static let paraEvalCn = CompatCoreType("para.eval.cn")
    public static let pinyin = CompatCoreType("pinyin")
    public static let known: [CompatCoreType] = [
        .wordEval, .wordEvalPro, .sentEval, .sentEvalPro, .paraEval, .alphaEval, .wordEvalCn, .sentEvalCn, .paraEvalCn, .pinyin,
    ]

    /// Lowercase letters, digits and dots only, so the value is a safe path segment.
    var isValidPath: Bool {
        !rawValue.isEmpty && rawValue.utf8.allSatisfy {
            ($0 >= UInt8(ascii: "a") && $0 <= UInt8(ascii: "z")) || ($0 >= UInt8(ascii: "0") && $0 <= UInt8(ascii: "9")) || $0 == UInt8(ascii: ".")
        }
    }
}

/// Configuration of a native evaluation, the JSON of the `config` part (REST) or of the start
/// frame (WebSocket). Fields left `nil` are not sent and take the platform defaults.
public struct EvaluateConfig: Equatable {
    public var coreType: CoreType
    /// Reference text, the task prompt for `open`. Required.
    public var referenceText: String
    /// `en-US` (platform default), `en-GB` or `zh-CN`.
    public var language: String?
    /// Strictness in `[-1, 1]`, default 0.
    public var slack: Double?
    /// Score scale in `(0, 100]`, default 100.
    public var scale: Int?
    /// Score precision in `(0, 1]`, default 1.
    public var precision: Double?
    /// Age group: 1 preschool, 2 primary school, 3 older than 12 (default).
    public var agegroup: Int?
    /// Reserved, `[0, 1]`, does not change the total score.
    public var toneWeight: Double?
    /// Word and phoneme level report, default false.
    public var includeReport: Bool?
    /// URL of a standard reading, default false.
    public var includeStandardAudio: Bool?
    /// Recognized text, default false.
    public var includeAsrText: Bool?
    /// Pinyin for polyphonic characters and `pinyin` tasks, for example `chong2 qing4`.
    public var refPinyin: String?
    /// Phoneme level output.
    public var phonemeOutput: Bool?
    /// `open` task type: `picture`, `situational` or `free`.
    public var taskType: String?
    /// `passage`: 1 returns per-word scores.
    public var paragraphNeedWordScore: Int?
    /// Further fields, written after the known ones in key order.
    public var extra: [String: JSONValue]

    public init(
        coreType: CoreType,
        referenceText: String,
        language: String? = nil,
        slack: Double? = nil,
        scale: Int? = nil,
        precision: Double? = nil,
        agegroup: Int? = nil,
        toneWeight: Double? = nil,
        includeReport: Bool? = nil,
        includeStandardAudio: Bool? = nil,
        includeAsrText: Bool? = nil,
        refPinyin: String? = nil,
        phonemeOutput: Bool? = nil,
        taskType: String? = nil,
        paragraphNeedWordScore: Int? = nil,
        extra: [String: JSONValue] = [:]
    ) {
        self.coreType = coreType
        self.referenceText = referenceText
        self.language = language
        self.slack = slack
        self.scale = scale
        self.precision = precision
        self.agegroup = agegroup
        self.toneWeight = toneWeight
        self.includeReport = includeReport
        self.includeStandardAudio = includeStandardAudio
        self.includeAsrText = includeAsrText
        self.refPinyin = refPinyin
        self.phonemeOutput = phonemeOutput
        self.taskType = taskType
        self.paragraphNeedWordScore = paragraphNeedWordScore
        self.extra = extra
    }

    /// Members in the fixed order of the SDK.
    func jsonObject() -> JSONObjectBuilder {
        var b = JSONObjectBuilder()
        b.set("coreType", coreType.rawValue)
        b.set("referenceText", referenceText)
        b.set("language", language)
        b.set("slack", slack)
        b.set("scale", scale)
        b.set("precision", precision)
        b.set("agegroup", agegroup)
        b.set("toneWeight", toneWeight)
        b.set("includeReport", includeReport)
        b.set("includeStandardAudio", includeStandardAudio)
        b.set("includeAsrText", includeAsrText)
        b.set("refPinyin", refPinyin)
        b.set("phonemeOutput", phonemeOutput)
        b.set("taskType", taskType)
        b.set("paragraphNeedWordScore", paragraphNeedWordScore)
        b.merge(extra)
        return b
    }

    /// The exact JSON text that is sent and signed.
    public func jsonText() throws -> String {
        try validate()
        return jsonObject().text
    }

    /// Checks required fields and numbers before any I/O. Throws INVALID_ARGUMENT (90010).
    public func validate() throws {
        if coreType.rawValue.isEmpty { throw YuguErrors.local(ErrorCodes.invalidArgument, "coreType is empty") }
        if referenceText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw YuguErrors.local(ErrorCodes.invalidArgument, "referenceText is empty")
        }
        if !jsonObject().isWritable {
            throw YuguErrors.local(ErrorCodes.invalidArgument, "config contains NaN or infinity")
        }
    }
}

/// Parameters of an engine compatible evaluation (`POST /{coreType}`, `WS /{coreType}`).
public struct CompatParams: Equatable {
    public var refText: String
    public var language: String?
    /// Required for `pinyin`.
    public var refPinyin: String?
    /// WebSocket only: ask for progress frames `{"eof":0,"result":{"bytes":n}}`.
    public var realtimeFeedback: Bool
    /// Further form fields or parameter frame members, sent as text in key order.
    public var extraFields: [String: String]

    public init(refText: String, language: String? = nil, refPinyin: String? = nil, realtimeFeedback: Bool = false, extraFields: [String: String] = [:]) {
        self.refText = refText
        self.language = language
        self.refPinyin = refPinyin
        self.realtimeFeedback = realtimeFeedback
        self.extraFields = extraFields
    }

    func validate(coreType: CompatCoreType) throws {
        if !coreType.isValidPath { throw YuguErrors.local(ErrorCodes.invalidArgument, "invalid compat coreType \(coreType.rawValue)") }
        if refText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw YuguErrors.local(ErrorCodes.invalidArgument, "refText is empty")
        }
        if coreType == .pinyin && (refPinyin ?? "").isEmpty {
            throw YuguErrors.local(ErrorCodes.invalidArgument, "refPinyin is required for pinyin")
        }
    }

    /// Form fields of the REST call, in send order. Empty values are not sent.
    func formFields(coreType: CompatCoreType) -> [(String, String)] {
        var out: [(String, String)] = [("coreType", coreType.rawValue), ("refText", refText)]
        if let l = language, !l.isEmpty { out.append(("language", l)) }
        if let p = refPinyin, !p.isEmpty { out.append(("refPinyin", p)) }
        for k in extraFields.keys.sorted() where !out.contains(where: { $0.0 == k }) {
            if let v = extraFields[k], !v.isEmpty { out.append((k, v)) }
        }
        return out
    }

    /// Parameter frame of the WebSocket session.
    func parameterFrame(idempotencyKey: String?) -> JSONObjectBuilder {
        var b = JSONObjectBuilder()
        b.set("refText", refText)
        b.set("language", language)
        b.set("refPinyin", refPinyin)
        if realtimeFeedback { b.set("realtime_feedback", true) }
        for k in extraFields.keys.sorted() {
            b.set(k, extraFields[k])
        }
        b.set("idempotencyKey", idempotencyKey)
        return b
    }
}

/// Text to speech request (`POST /api/v1/tts/generate`).
public struct TTSRequest: Equatable {
    public var text: String
    /// `zh-CN` or `en-US`.
    public var language: String?
    /// English `female` or `male`, Chinese `xiaoyan` or `xiaofeng`.
    public var voice: String?
    /// `mp3` (default), `wav` or `ogg`.
    public var format: String?
    /// 0 to 100, default 50.
    public var speed: Int?
    public var pitch: Int?
    public var volume: Int?
    public var style: String?
    public var extra: [String: JSONValue]

    public init(
        text: String, language: String? = nil, voice: String? = nil, format: String? = nil, speed: Int? = nil,
        pitch: Int? = nil, volume: Int? = nil, style: String? = nil, extra: [String: JSONValue] = [:]
    ) {
        self.text = text
        self.language = language
        self.voice = voice
        self.format = format
        self.speed = speed
        self.pitch = pitch
        self.volume = volume
        self.style = style
        self.extra = extra
    }

    func jsonObject() -> JSONObjectBuilder {
        var b = JSONObjectBuilder()
        b.set("text", text)
        b.set("language", language)
        b.set("voice", voice)
        b.set("format", format)
        b.set("speed", speed)
        b.set("pitch", pitch)
        b.set("volume", volume)
        b.set("style", style)
        b.merge(extra)
        return b
    }

    func validate() throws {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw YuguErrors.local(ErrorCodes.invalidArgument, "tts text is empty")
        }
        if !jsonObject().isWritable {
            throw YuguErrors.local(ErrorCodes.invalidArgument, "tts request contains NaN or infinity")
        }
    }
}
