import Foundation

/// Audio given to `evaluate` and `evaluateCompat` (requirement C-06): bytes, a file, or raw
/// 16-bit PCM that the SDK wraps into WAV.
public struct AudioInput {
    enum Source {
        case data(Data)
        case file(URL)
        case pcm(Data, sampleRate: Int, channels: Int)
    }

    let source: Source
    let filename: String?
    let contentType: String?

    /// Encoded audio bytes, WAV or MP3 for example. The type is detected from the bytes when
    /// `contentType` is nil.
    public static func data(_ data: Data, filename: String = "audio.wav", contentType: String? = nil) -> AudioInput {
        AudioInput(source: .data(data), filename: filename, contentType: contentType)
    }

    /// A local audio file.
    public static func file(_ url: URL, contentType: String? = nil) -> AudioInput {
        AudioInput(source: .file(url), filename: nil, contentType: contentType)
    }

    /// Raw little-endian 16-bit PCM, sent as WAV.
    public static func pcm16(_ data: Data, sampleRate: Int = 16000, channels: Int = 1) -> AudioInput {
        AudioInput(source: .pcm(data, sampleRate: sampleRate, channels: channels), filename: "audio.wav", contentType: "audio/wav")
    }

    enum Kind { case wav, other }

    struct Loaded {
        let data: Data
        let filename: String
        let contentType: String
        let kind: Kind
    }

    /// Reads and classifies the audio. Throws INVALID_ARGUMENT (90010) for unreadable or empty audio.
    func load() throws -> Loaded {
        let bytes: Data
        var name = filename ?? "audio.wav"
        switch source {
        case .data(let d):
            bytes = d
        case .file(let url):
            do {
                bytes = try Data(contentsOf: url)
            } catch {
                throw YuguErrors.local(ErrorCodes.invalidArgument, "audio file not readable: \(url.lastPathComponent)", cause: error)
            }
            if !url.lastPathComponent.isEmpty { name = url.lastPathComponent }
        case .pcm(let d, let rate, let ch):
            guard rate > 0, ch > 0 else { throw YuguErrors.local(ErrorCodes.invalidArgument, "invalid PCM format") }
            bytes = YuguWAV.wrap(pcm: d, sampleRate: rate, channels: ch, bitsPerSample: 16)
            if !name.lowercased().hasSuffix(".wav") { name += ".wav" }
        }
        if bytes.isEmpty { throw YuguErrors.local(ErrorCodes.invalidArgument, "audio is empty") }
        let isWav = YuguWAV.isWAV(bytes)
        let type = contentType ?? AudioInput.sniffContentType(bytes, filename: name)
        return Loaded(data: bytes, filename: name, contentType: type, kind: isWav ? .wav : .other)
    }

    static func sniffContentType(_ d: Data, filename: String) -> String {
        if YuguWAV.isWAV(d) { return "audio/wav" }
        let b = [UInt8](d.prefix(12))
        if b.count >= 3, b[0] == 0x49, b[1] == 0x44, b[2] == 0x33 { return "audio/mpeg" } // ID3
        if b.count >= 2, b[0] == 0xFF, b[1] & 0xE0 == 0xE0 { return "audio/mpeg" }
        if b.count >= 4, b[0] == 0x4F, b[1] == 0x67, b[2] == 0x67, b[3] == 0x53 { return "audio/ogg" }
        if b.count >= 4, b[0] == 0x66, b[1] == 0x4C, b[2] == 0x61, b[3] == 0x43 { return "audio/flac" }
        if b.count >= 8, b[4] == 0x66, b[5] == 0x74, b[6] == 0x79, b[7] == 0x70 { return "audio/mp4" }
        switch (filename as NSString).pathExtension.lowercased() {
        case "wav": return "audio/wav"
        case "mp3": return "audio/mpeg"
        case "m4a", "mp4": return "audio/mp4"
        case "aac": return "audio/aac"
        case "ogg", "opus": return "audio/ogg"
        case "amr": return "audio/amr"
        case "flac": return "audio/flac"
        default: return "application/octet-stream"
        }
    }
}

/// WAV helpers.
public enum YuguWAV {
    /// Format of a WAV file.
    public struct Info: Equatable, Sendable {
        /// 1 PCM, 3 IEEE float, 0xFFFE extensible.
        public let audioFormat: Int
        public let channels: Int
        public let sampleRate: Int
        public let bitsPerSample: Int
        public let dataOffset: Int
        public let dataLength: Int

        /// True for integer PCM, also inside WAVE_FORMAT_EXTENSIBLE.
        public let isIntegerPCM: Bool

        public var durationSeconds: Double {
            let frameBytes = channels * max(1, bitsPerSample / 8)
            guard sampleRate > 0, frameBytes > 0 else { return 0 }
            return Double(dataLength / frameBytes) / Double(sampleRate)
        }
    }

    public static func isWAV(_ d: Data) -> Bool {
        guard d.count >= 12 else { return false }
        let b = [UInt8](d.prefix(12))
        return b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 && b[8] == 0x57 && b[9] == 0x41 && b[10] == 0x56 && b[11] == 0x45
    }

    /// Parses the `fmt ` and `data` chunks; nil when the bytes are not a readable WAV file. A data
    /// chunk longer than the file is clamped to the bytes present.
    public static func parse(_ d: Data) -> Info? {
        guard isWAV(d) else { return nil }
        let b = [UInt8](d)
        func u16(_ o: Int) -> Int { Int(b[o]) | Int(b[o + 1]) << 8 }
        func u32(_ o: Int) -> Int { Int(b[o]) | Int(b[o + 1]) << 8 | Int(b[o + 2]) << 16 | Int(b[o + 3]) << 24 }
        var pos = 12
        var fmt: (format: Int, ch: Int, rate: Int, bits: Int, sub: Int)?
        while pos + 8 <= b.count {
            let id = String(decoding: b[pos..<(pos + 4)], as: UTF8.self)
            let size = u32(pos + 4)
            let body = pos + 8
            if id == "fmt " {
                guard body + 16 <= b.count else { return nil }
                var sub = 0
                if size >= 26, body + 26 <= b.count { sub = u16(body + 24) }
                fmt = (u16(body), u16(body + 2), u32(body + 4), u16(body + 14), sub)
            } else if id == "data" {
                guard let f = fmt else { return nil }
                let len = min(size, b.count - body)
                let isInt = f.format == 1 || (f.format == 0xFFFE && f.sub == 1)
                return Info(audioFormat: f.format, channels: f.ch, sampleRate: f.rate, bitsPerSample: f.bits, dataOffset: body, dataLength: max(0, len), isIntegerPCM: isInt)
            }
            pos = body + size + (size & 1)
        }
        return nil
    }

    /// A 44-byte header plus the PCM bytes.
    public static func wrap(pcm: Data, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16) -> Data {
        let byteRate = sampleRate * channels * bitsPerSample / 8
        let blockAlign = channels * bitsPerSample / 8
        var h = Data()
        func s(_ t: String) { h.append(contentsOf: Array(t.utf8)) }
        func u32(_ v: Int) { var x = UInt32(truncatingIfNeeded: v).littleEndian; withUnsafeBytes(of: &x) { h.append(contentsOf: $0) } }
        func u16(_ v: Int) { var x = UInt16(truncatingIfNeeded: v).littleEndian; withUnsafeBytes(of: &x) { h.append(contentsOf: $0) } }
        s("RIFF"); u32(36 + pcm.count); s("WAVE")
        s("fmt "); u32(16); u16(1); u16(channels); u32(sampleRate); u32(byteRate); u16(blockAlign); u16(bitsPerSample)
        s("data"); u32(pcm.count)
        return h + pcm
    }
}

/// Running statistics of 16-bit little-endian PCM.
struct PCMStats {
    private(set) var bytes = 0
    private(set) var samples = 0
    private(set) var peak = 0
    private(set) var sumSquares: Double = 0
    private var carry: UInt8?

    mutating func add(_ d: Data) {
        bytes += d.count
        d.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            var i = 0
            let n = raw.count
            if let c = carry, n > 0 {
                consume(Int16(bitPattern: UInt16(c) | UInt16(raw[0]) << 8))
                carry = nil
                i = 1
            }
            while i + 1 < n {
                consume(Int16(bitPattern: UInt16(raw[i]) | UInt16(raw[i + 1]) << 8))
                i += 2
            }
            if i < n { carry = raw[i] }
        }
    }

    private mutating func consume(_ s: Int16) {
        let v = Int(s)
        let a = abs(v)
        if a > peak { peak = a }
        sumSquares += Double(v * v)
        samples += 1
    }

    var rms: Double { samples == 0 ? 0 : (sumSquares / Double(samples)).squareRoot() }
}

/// Measured properties of audio and the problems found (DESIGN 2.9).
public struct AudioPrecheckReport: Sendable {
    public let durationSeconds: Double?
    public let sizeBytes: Int
    /// Peak absolute 16-bit sample.
    public let peak: Int?
    /// RMS of 16-bit samples.
    public let rms: Double?
    /// RMS relative to full scale, in dB.
    public let rmsDbfs: Double?
    public let issues: [YuguWarning]
}

/// Local audio checks before upload (DESIGN 2.9, requirement C-04).
///
/// | Code  | Condition                                      | REJECT mode |
/// |-------|------------------------------------------------|-------------|
/// | 90101 | shorter than 1.0 s                             | fails       |
/// | 90102 | longer than 300 s, an upload larger than 50 MB, | fails       |
/// |       | or a streaming round larger than 10 MB          |             |
/// | 90103 | silent: peak < 200 and RMS < 30                | fails       |
/// | 90104 | RMS below -45 dBFS                             | warning     |
/// | 90105 | not 16-bit integer PCM or rate below 16000 Hz  | fails       |
///
/// The silence thresholds equal the platform's guard, so audio rejected here would have scored 0.
public enum AudioPrecheck {
    public static let minDurationSeconds = 1.0
    public static let maxDurationSeconds = 300.0
    /// Upload limit of the platform for REST evaluation.
    public static let maxUploadBytes = 50 * 1024 * 1024
    /// Audio of one streaming round.
    public static let maxStreamBytes = 10 * 1024 * 1024
    public static let silentPeak = 200
    public static let silentRms = 30.0
    public static let lowVolumeDbfs = -45.0

    /// Codes that fail the call in REJECT mode.
    public static let rejectingCodes: Set<Int> = [
        ErrorCodes.audioTooShort, ErrorCodes.audioTooLong, ErrorCodes.audioSilent, ErrorCodes.audioFormatUnsupported,
    ]

    /// Checks audio for a REST upload: WAV is analysed, other containers get only the size check.
    public static func check(_ data: Data) -> AudioPrecheckReport {
        guard let info = YuguWAV.parse(data) else {
            return sizeOnly(data.count)
        }
        if !(info.isIntegerPCM && info.bitsPerSample == 16) || info.sampleRate < 16000 {
            var issues = [warning(ErrorCodes.audioFormatUnsupported, "WAV is \(info.bitsPerSample)-bit format \(info.audioFormat) at \(info.sampleRate) Hz, needs 16-bit PCM at 16000 Hz or more")]
            if data.count > maxUploadBytes || info.durationSeconds > maxDurationSeconds {
                issues.append(warning(ErrorCodes.audioTooLong, String(format: "%.1f s, %ld bytes", info.durationSeconds, data.count)))
            }
            return AudioPrecheckReport(durationSeconds: info.durationSeconds, sizeBytes: data.count, peak: nil, rms: nil, rmsDbfs: nil, issues: issues)
        }
        var stats = PCMStats()
        stats.add(data.subdata(in: info.dataOffset..<(info.dataOffset + info.dataLength)))
        return evaluate(stats: stats, duration: info.durationSeconds, size: data.count, limit: maxUploadBytes)
    }

    /// Checks raw 16-bit PCM statistics, used for streaming sessions at `end()`.
    static func check(stats: PCMStats, sampleRate: Int = 16000, channels: Int = 1) -> AudioPrecheckReport {
        let duration = Double(stats.bytes) / Double(2 * max(1, channels) * max(1, sampleRate))
        return evaluate(stats: stats, duration: duration, size: stats.bytes, limit: maxStreamBytes)
    }

    static func sizeOnly(_ size: Int) -> AudioPrecheckReport {
        let issues = size > maxUploadBytes ? [warning(ErrorCodes.audioTooLong, "\(size) bytes, more than 50 MB")] : []
        return AudioPrecheckReport(durationSeconds: nil, sizeBytes: size, peak: nil, rms: nil, rmsDbfs: nil, issues: issues)
    }

    private static func evaluate(stats: PCMStats, duration: Double, size: Int, limit: Int) -> AudioPrecheckReport {
        var issues: [YuguWarning] = []
        if duration < minDurationSeconds {
            issues.append(warning(ErrorCodes.audioTooShort, String(format: "audio is %.2f s, shorter than 1 s", duration)))
        }
        if duration > maxDurationSeconds || size > limit {
            issues.append(warning(ErrorCodes.audioTooLong, String(format: "audio is %.1f s and %ld bytes, limit 300 s and %ld MB", duration, size, limit / (1024 * 1024))))
        }
        let rms = stats.rms
        let dbfs = rms > 0 ? 20 * log10(rms / 32768.0) : -Double.infinity
        if stats.samples > 0 {
            if stats.peak < silentPeak && rms < silentRms {
                issues.append(warning(ErrorCodes.audioSilent, String(format: "audio is silent, peak %ld, RMS %.1f", stats.peak, rms)))
            } else if dbfs < lowVolumeDbfs {
                issues.append(warning(ErrorCodes.audioLowVolume, String(format: "volume is low, RMS %.1f dBFS", dbfs)))
            }
        }
        return AudioPrecheckReport(
            durationSeconds: duration, sizeBytes: size, peak: stats.samples > 0 ? stats.peak : nil,
            rms: stats.samples > 0 ? rms : nil, rmsDbfs: stats.samples > 0 ? dbfs : nil, issues: issues)
    }

    private static func warning(_ code: Int, _ detail: String) -> YuguWarning {
        let base = ErrorTable.local[code]?.message ?? "audio check \(code)"
        return YuguWarning(code: code, message: base + ": " + detail)
    }

    /// Applies a mode: returns the warnings to report, or throws the first rejecting issue.
    static func apply(_ report: AudioPrecheckReport, mode: AudioPrecheckMode) throws -> [YuguWarning] {
        switch mode {
        case .off:
            return []
        case .warn:
            return report.issues
        case .reject:
            if let bad = report.issues.first(where: { rejectingCodes.contains($0.code) }) {
                throw YuguErrors.local(bad.code, bad.message)
            }
            return report.issues
        }
    }
}
