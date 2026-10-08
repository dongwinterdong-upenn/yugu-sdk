import Foundation

/// Audio quality warning codes of evaluation results (CONTRACT 4).
public enum WarningCode: Int, CaseIterable, Sendable {
    case noValidAudio = 1001
    case volumeTooLow = 1002
    case volumeTooHigh = 1003
    case audioNoisy = 1004
    case audioIncomplete = 1005
    case scorerDegraded = 1009
}

/// A warning: from the platform (`warnings`, codes 1001 to 1009) or from the local precheck
/// (`localWarnings`, codes 90101 to 90105). Warnings are not errors; the score is still returned.
public struct YuguWarning: Equatable, Sendable, CustomStringConvertible {
    public let code: Int
    public let message: String

    public init(code: Int, message: String) {
        self.code = code
        self.message = message
    }

    /// The platform warning code, nil for local warnings and unknown codes.
    public var warningCode: WarningCode? { WarningCode(rawValue: code) }
    /// True for local precheck warnings (`901xx`).
    public var isLocal: Bool { code >= 90000 }
    public var description: String { "\(code) \(message)" }
}

/// Time span in units of 10 ms.
public struct Span: Equatable, Sendable {
    public let start: Int?
    public let end: Int?
}

public struct PhonemeScore: Equatable, Sendable {
    public let phoneme: String?
    public let pronunciation: Double?
    public let span: Span?
}

/// Score of one word or character.
public struct WordScore: Equatable, Sendable {
    public let word: String?
    public let pinyin: String?
    public let symbolPinyin: String?
    public let tone: String?
    /// `correct`, `mispronounced`, `skipped` or `inserted`.
    public let readStatus: String?
    public let overall: Double?
    public let pronunciation: Double?
    public let toneScore: Double?
    public let span: Span?
    public let phonemes: [PhonemeScore]
    public let raw: JSONValue
}

/// Score of one sentence of a passage or sentence task.
public struct SentenceScore: Equatable, Sendable {
    public let text: String?
    public let index: Int?
    public let overall: Double?
    public let pronunciation: Double?
    public let fluency: Double?
    public let integrity: Double?
    public let span: Span?
    /// Per-word scores of this sentence, `details` (passage with `paragraphNeedWordScore` 1).
    public let details: [WordScore]
    public let raw: JSONValue
}

/// One word boundary of a `connected` task.
public struct LinkingBoundary: Equatable, Sendable {
    /// The two words around the boundary.
    public let between: [String]
    /// Expected phenomena, for example `linking`, `elision`, `reduction`.
    public let tags: [String]
    /// How well the phenomena were realized, 0 to 1.
    public let realized: Double?
    public let startMs: Double?
    public let endMs: Double?
    public let gapMs: Double?
    public let continuity: Double?
    public let covered: Bool?
    public let raw: JSONValue
}

/// Scores of a `connected` (linking) task. The total is `result.connected_overall`; the task has
/// no `result.overall`.
public struct ConnectedScores: Equatable, Sendable {
    /// `result.connected_overall`.
    public let overall: Double?
    public let linking: Double?
    public let rhythm: Double?
    public let elision: Double?
    public let reduction: Double?
    /// `result.n_boundaries`.
    public let boundaryCount: Int?
    public let boundaries: [LinkingBoundary]
    /// `result.report.summary`.
    public let summary: String?
    /// `result.report.suggestions`.
    public let suggestions: [String]
    public let raw: JSONValue
}

/// `result.content` of an `open` task.
public struct OpenContentScores: Equatable, Sendable {
    public let overall: Double?
    public let relevance: Double?
    public let coherence: Double?
    public let taskAchievement: Double?
    public let raw: JSONValue
}

/// `result.languageUse` of an `open` task.
public struct OpenLanguageUseScores: Equatable, Sendable {
    public let overall: Double?
    public let grammar: Double?
    public let vocabulary: Double?
    public let raw: JSONValue
}

/// `result.delivery` of an `open` task.
public struct OpenDeliveryScores: Equatable, Sendable {
    public let overall: Double?
    public let fluency: Double?
    public let pronunciation: Double?
    /// `speech_rate`, units per second.
    public let speechRate: Double?
    /// `speech_rate_label`, for example `233.8 字/分`.
    public let speechRateLabel: String?
    /// `n_pauses`.
    public let pauseCount: Int?
    /// `longest_pause_s`.
    public let longestPauseSeconds: Double?
    public let raw: JSONValue
}

/// `result.feedback` of an `open` task.
public struct OpenFeedback: Equatable, Sendable {
    public let strengths: String?
    public let weaknesses: String?
    public let suggestions: [String]
    public let raw: JSONValue
}

/// `result.openTaskAudit`: prompt echo caps the total at 60, low content at 70.
public struct OpenTaskAudit: Equatable, Sendable {
    public let promptEcho: Bool?
    public let lowContent: Bool?
    public let offTopic: Bool?
    public let lowYield: Bool?
    public let capApplied: Bool?
    public let raw: JSONValue
}

/// Scores of an `open` (free speaking) task.
public struct OpenTaskScores: Equatable, Sendable {
    /// `result.overall`.
    public let overall: Double?
    public let taskType: String?
    /// Recognized text, `result.transcript`.
    public let transcript: String?
    public let hasSpeech: Bool?
    /// `result.duration_s`.
    public let durationSeconds: Double?
    public let content: OpenContentScores?
    public let languageUse: OpenLanguageUseScores?
    public let delivery: OpenDeliveryScores?
    public let feedback: OpenFeedback?
    public let audit: OpenTaskAudit?
    public let rubricVersion: String?
    public let raw: JSONValue
}

/// Dimension scores of `result`.
public struct EvalDimensions: Equatable, Sendable {
    public let integrity: Double?
    /// `result.accuracy`, else `result.pronunciation`.
    public let accuracy: Double?
    public let pronunciation: Double?
    public let fluency: Double?
    /// Tone score, Chinese tasks; 0 means no tone data.
    public let tone: Double?
    public let rhythm: Double?
    /// `result.reading_skill`, else `yuguScores.reading_skill.overall`.
    public let readingSkill: Double?
    /// `result.emotion`, else `yuguScores.emotion`.
    public let emotion: Double?
    /// Speaking rate, characters or words per minute.
    public let speed: Double?
}

/// The structured five-dimension scores `result.yuguScores`.
public struct YuguScores: Equatable, Sendable {
    public let overall: Double?
    public let integrity: Double?
    public let accuracy: Double?
    public let fluency: Double?
    public let readingSkill: Double?
    public let emotion: Double?
    public let prosody: Double?
    public let tone: Double?
    public let speed: Double?
    public let durationSeconds: Double?
    /// The whole object, sub-scores such as `accuracy.tone` or `fluency.speed_score` included.
    public let raw: JSONValue
}

public struct AsrAlignment: Equatable, Sendable {
    public let char: String?
    public let index: Int?
    public let readStatus: String?
    public let startTime: Int?
    public let endTime: Int?
    public let asrPinyin: String?
    public let asrTone: Int?
    public let gopScore: Double?
}

public struct AsrText: Equatable, Sendable {
    public let text: String?
    public let alignment: [AsrAlignment]
}

public struct EvalReport: Equatable, Sendable {
    public let summary: String?
    public let suggestions: [String]
    /// `report.dimensions`, text per dimension.
    public let dimensions: JSONValue?
    /// `report.dimensionScores`, `null` means the dimension is not scored (not zero).
    public let dimensionScores: JSONValue?
    public let rubricVersion: String?
    public let raw: JSONValue
}

public struct StandardAudio: Equatable, Sendable {
    public let url: String?
    public let format: String?
    public let duration: String?
}

/// Result of an evaluation: REST `evaluate`, `evaluateCompat` and both streaming sessions.
public struct EvalResult: Sendable {
    public let recordId: String?
    public let eof: Int?
    /// `result._coreType`, for example `sentence` or `sent.eval.cn`.
    public let coreType: String?
    /// `result._language`, `zh` or `en`.
    public let language: String?
    /// Total score: `result.overall`, for `connected` tasks `result.connected_overall`.
    public let overall: Double?
    public let dimensions: EvalDimensions
    public let yuguScores: YuguScores?
    /// Linking scores, `connected` tasks only.
    public let connected: ConnectedScores?
    /// Content, language use and delivery scores, `open` tasks only.
    public let openTask: OpenTaskScores?
    /// The `result` object of the response.
    public let resultJSON: JSONValue
    /// Audio duration in seconds.
    public let durationSeconds: Double?
    public let words: [WordScore]
    public let sentences: [SentenceScore]
    public let report: EvalReport?
    public let asrText: AsrText?
    public let standardAudio: StandardAudio?
    /// Platform audio quality warnings, top-level `warnings` and `result.warning` merged.
    public let warnings: [YuguWarning]
    /// Local precheck warnings.
    public internal(set) var localWarnings: [YuguWarning] = []
    /// Idempotency key of the call.
    public internal(set) var idempotencyKey: String?
    /// True when the platform replayed the stored result of an earlier call with the same key.
    public internal(set) var replayed: Bool = false
    /// Attempts made (REST) or connections made (streaming).
    public internal(set) var attempts: Int = 1
    /// The whole response.
    public let raw: JSONValue

    /// True when warning 1001 (no valid audio) is present; the score is not reliable.
    public var hasNoValidAudio: Bool { warnings.contains { $0.code == WarningCode.noValidAudio.rawValue } }
}

/// Progress frame of a compatible streaming session with `realtimeFeedback`.
public struct StreamPartial: Sendable {
    /// Audio bytes received by the server so far.
    public let bytes: Int?
    public let raw: JSONValue
}

/// Result of `tts`.
public struct TTSResult: Sendable {
    /// `audioUrl` as returned.
    public let audioUrl: String?
    /// Absolute URL: a path starting with `/audio/` becomes `<baseUrl>/tts/audio/...`.
    public let absoluteAudioUrl: String?
    /// Duration in seconds.
    public let duration: Double?
    public let format: String?
    public let warnings: [YuguWarning]
    public internal(set) var idempotencyKey: String?
    public internal(set) var replayed: Bool = false
    public internal(set) var attempts: Int = 1
    public let raw: JSONValue
}

/// Result of `getReport`.
public struct ReportResult: Sendable {
    public let recordId: String
    /// `data` of the response.
    public let data: JSONValue
    public internal(set) var attempts: Int = 1
    public let raw: JSONValue
}

// MARK: - Parsing

/// Builds result models from platform JSON. Missing fields stay `nil`; unknown fields stay in `raw`.
public enum ResultParser {
    /// Parses an evaluation result: REST body, native `result` frame or compatible final frame.
    public static func evalResult(_ json: JSONValue) -> EvalResult {
        let r = json["result"] ?? .null
        let ys = r["yuguScores"]
        let yugu: YuguScores? = ys.flatMap { y in
            guard case .object = y else { return nil }
            return YuguScores(
                overall: y["overall"]?.doubleValue,
                integrity: y["integrity"]?.doubleValue,
                accuracy: nested(y["accuracy"]),
                fluency: nested(y["fluency"]),
                readingSkill: nested(y["reading_skill"]),
                emotion: nested(y["emotion"]),
                prosody: nested(y["prosody"]),
                tone: nested(y["tone"]),
                speed: y["speed"]?.doubleValue,
                durationSeconds: y["duration_s"]?.doubleValue,
                raw: y)
        }
        let dims = EvalDimensions(
            integrity: r["integrity"]?.doubleValue,
            accuracy: r["accuracy"]?.doubleValue ?? r["pronunciation"]?.doubleValue,
            pronunciation: r["pronunciation"]?.doubleValue,
            fluency: r["fluency"]?.doubleValue,
            tone: r["tone"]?.doubleValue,
            rhythm: r["rhythm"]?.doubleValue,
            readingSkill: r["reading_skill"]?.doubleValue ?? yugu?.readingSkill,
            emotion: r["emotion"]?.doubleValue ?? yugu?.emotion,
            speed: r["speed"]?.doubleValue)
        let words = (r["words"]?.arrayValue ?? []).map(word)
        let sentences = (r["sentences"]?.arrayValue ?? []).map(sentence)
        var warnings = parseWarnings(json["warnings"])
        for w in parseWarnings(r["warning"]) where !warnings.contains(where: { $0.code == w.code }) {
            warnings.append(w)
        }
        let recordId: String? = json["recordId"].flatMap { v in
            if let s = v.stringValue { return s }
            return v.scalarText
        }
        let coreType = r["_coreType"]?.stringValue
        let connectedScores = (r["connected_overall"] != nil || coreType == "connected") ? connected(r) : nil
        let open = (coreType == "open" || r["content"]?.objectValue != nil || r["delivery"]?.objectValue != nil) ? openTask(r) : nil
        return EvalResult(
            recordId: recordId,
            eof: json["eof"]?.intValue,
            coreType: coreType,
            language: r["_language"]?.stringValue ?? r["language"]?.stringValue,
            overall: r["overall"]?.doubleValue ?? r["connected_overall"]?.doubleValue,
            dimensions: dims,
            yuguScores: yugu,
            connected: connectedScores,
            openTask: open,
            resultJSON: r,
            durationSeconds: r["numeric_duration"]?.doubleValue ?? r["duration"]?.doubleValue ?? r["duration_s"]?.doubleValue,
            words: words,
            sentences: sentences,
            report: json["report"].flatMap(report),
            asrText: json["asrText"].flatMap(asrText),
            standardAudio: json["standardAudio"].flatMap(standardAudio),
            warnings: warnings,
            raw: json)
    }

    /// Warnings as integers `[1002]` or objects `[{"code":1002,"message":"..."}]`.
    public static func parseWarnings(_ v: JSONValue?) -> [YuguWarning] {
        guard let items = v?.arrayValue else { return [] }
        return items.compactMap { item in
            if let code = item.intValue {
                return YuguWarning(code: code, message: ErrorTable.warnings[code]?.message ?? "warning \(code)")
            }
            if let code = item["code"]?.intValue {
                let m = item["message"]?.stringValue ?? ErrorTable.warnings[code]?.message ?? "warning \(code)"
                return YuguWarning(code: code, message: m)
            }
            return nil
        }
    }

    static func nested(_ v: JSONValue?) -> Double? {
        guard let v = v else { return nil }
        if let d = v.doubleValue { return d }
        return v["overall"]?.doubleValue
    }

    static func span(_ v: JSONValue?) -> Span? {
        guard let v = v, case .object = v else { return nil }
        return Span(start: v["start"]?.intValue, end: v["end"]?.intValue)
    }

    static func word(_ w: JSONValue) -> WordScore {
        let s = w["scores"]
        return WordScore(
            word: w["word"]?.stringValue,
            pinyin: w["pinyin"]?.stringValue,
            symbolPinyin: w["symbolpinyin"]?.stringValue,
            tone: w["tone"]?.stringValue,
            readStatus: w["read_status"]?.stringValue ?? w["readStatus"]?.stringValue,
            overall: s?["overall"]?.doubleValue,
            pronunciation: s?["pronunciation"]?.doubleValue,
            toneScore: s?["tone"]?.doubleValue,
            span: span(w["span"]),
            phonemes: (w["phonemes"]?.arrayValue ?? []).map {
                PhonemeScore(phoneme: $0["phoneme"]?.stringValue, pronunciation: $0["pronunciation"]?.doubleValue, span: span($0["span"]))
            },
            raw: w)
    }

    static func sentence(_ s: JSONValue) -> SentenceScore {
        let sc = s["scores"]
        return SentenceScore(
            text: s["sentence"]?.stringValue ?? s["text"]?.stringValue,
            index: s["index"]?.intValue,
            overall: s["overall"]?.doubleValue ?? sc?["overall"]?.doubleValue,
            pronunciation: sc?["pronunciation"]?.doubleValue,
            fluency: sc?["fluency"]?.doubleValue,
            integrity: sc?["integrity"]?.doubleValue,
            span: span(s["span"]),
            details: (s["details"]?.arrayValue ?? []).map(word),
            raw: s)
    }

    static func strings(_ v: JSONValue?) -> [String] {
        (v?.arrayValue ?? []).compactMap { $0.stringValue }
    }

    static func connected(_ r: JSONValue) -> ConnectedScores {
        let rep = r["report"]
        return ConnectedScores(
            overall: r["connected_overall"]?.doubleValue,
            linking: r["linking"]?.doubleValue,
            rhythm: r["rhythm"]?.doubleValue,
            elision: r["elision"]?.doubleValue,
            reduction: r["reduction"]?.doubleValue,
            boundaryCount: r["n_boundaries"]?.intValue,
            boundaries: (r["boundaries"]?.arrayValue ?? []).map { b in
                LinkingBoundary(
                    between: strings(b["between"]), tags: strings(b["tags"]), realized: b["realized"]?.doubleValue,
                    startMs: b["start_ms"]?.doubleValue, endMs: b["end_ms"]?.doubleValue, gapMs: b["gap_ms"]?.doubleValue,
                    continuity: b["continuity"]?.doubleValue, covered: b["covered"]?.boolValue, raw: b)
            },
            summary: rep?["summary"]?.stringValue,
            suggestions: strings(rep?["suggestions"]),
            raw: r)
    }

    static func openTask(_ r: JSONValue) -> OpenTaskScores {
        func obj(_ key: String) -> JSONValue? {
            guard let v = r[key], case .object = v else { return nil }
            return v
        }
        return OpenTaskScores(
            overall: r["overall"]?.doubleValue,
            taskType: r["taskType"]?.stringValue,
            transcript: r["transcript"]?.stringValue,
            hasSpeech: r["hasSpeech"]?.boolValue,
            durationSeconds: r["duration_s"]?.doubleValue,
            content: obj("content").map {
                OpenContentScores(
                    overall: $0["overall"]?.doubleValue, relevance: $0["relevance"]?.doubleValue,
                    coherence: $0["coherence"]?.doubleValue, taskAchievement: $0["task_achievement"]?.doubleValue, raw: $0)
            },
            languageUse: obj("languageUse").map {
                OpenLanguageUseScores(overall: $0["overall"]?.doubleValue, grammar: $0["grammar"]?.doubleValue, vocabulary: $0["vocabulary"]?.doubleValue, raw: $0)
            },
            delivery: obj("delivery").map {
                OpenDeliveryScores(
                    overall: $0["overall"]?.doubleValue, fluency: $0["fluency"]?.doubleValue,
                    pronunciation: $0["pronunciation"]?.doubleValue, speechRate: $0["speech_rate"]?.doubleValue,
                    speechRateLabel: $0["speech_rate_label"]?.stringValue, pauseCount: $0["n_pauses"]?.intValue,
                    longestPauseSeconds: $0["longest_pause_s"]?.doubleValue, raw: $0)
            },
            feedback: obj("feedback").map {
                OpenFeedback(strengths: $0["strengths"]?.scalarText, weaknesses: $0["weaknesses"]?.scalarText, suggestions: strings($0["suggestions"]), raw: $0)
            },
            audit: obj("openTaskAudit").map {
                OpenTaskAudit(
                    promptEcho: $0["promptEcho"]?.boolValue, lowContent: $0["lowContent"]?.boolValue,
                    offTopic: $0["offTopic"]?.boolValue, lowYield: $0["lowYield"]?.boolValue,
                    capApplied: $0["capApplied"]?.boolValue, raw: $0)
            },
            rubricVersion: r["rubricVersion"]?.stringValue,
            raw: r)
    }

    static func report(_ v: JSONValue) -> EvalReport? {
        guard case .object = v else { return nil }
        return EvalReport(
            summary: v["summary"]?.stringValue,
            suggestions: (v["suggestions"]?.arrayValue ?? []).compactMap { $0.stringValue },
            dimensions: v["dimensions"],
            dimensionScores: v["dimensionScores"],
            rubricVersion: v["rubricVersion"]?.stringValue,
            raw: v)
    }

    static func asrText(_ v: JSONValue) -> AsrText? {
        guard case .object = v else { return nil }
        return AsrText(
            text: v["text"]?.stringValue,
            alignment: (v["alignment"]?.arrayValue ?? []).map {
                AsrAlignment(
                    char: $0["char"]?.stringValue,
                    index: $0["index"]?.intValue,
                    readStatus: $0["read_status"]?.stringValue,
                    startTime: $0["start_time"]?.intValue,
                    endTime: $0["end_time"]?.intValue,
                    asrPinyin: $0["asr_pinyin"]?.stringValue,
                    asrTone: $0["asr_tone"]?.intValue,
                    gopScore: $0["gop_score"]?.doubleValue)
            })
    }

    static func standardAudio(_ v: JSONValue) -> StandardAudio? {
        guard case .object = v else { return nil }
        return StandardAudio(url: v["url"]?.stringValue, format: v["format"]?.stringValue, duration: v["duration"]?.scalarText)
    }

    /// Parses `{code:0, data:{audioUrl, duration, format, warnings}}` of TTS.
    static func ttsResult(_ json: JSONValue, baseUrl: String) -> TTSResult {
        let d = json["data"] ?? .null
        let url = d["audioUrl"]?.stringValue
        var absolute: String?
        if let u = url {
            if u.hasPrefix("http://") || u.hasPrefix("https://") {
                absolute = u
            } else if u.hasPrefix("/audio/") {
                absolute = baseUrl + "/tts" + u
            } else if u.hasPrefix("/") {
                absolute = baseUrl + u
            } else {
                absolute = baseUrl + "/" + u
            }
        }
        return TTSResult(
            audioUrl: url, absoluteAudioUrl: absolute, duration: d["duration"]?.doubleValue,
            format: d["format"]?.stringValue, warnings: parseWarnings(d["warnings"]), raw: json)
    }
}
