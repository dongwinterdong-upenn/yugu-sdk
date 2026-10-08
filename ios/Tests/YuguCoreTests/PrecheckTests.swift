import XCTest
@testable import YuguCore

/// Requirement C-04: local audio checks before upload.
final class PrecheckTests: XCTestCase {
    private func codes(_ d: Data) -> [Int] { AudioPrecheck.check(d).issues.map { $0.code } }

    private func wav(rate: Int = 16000, channels: Int = 1, bits: Int = 16, format: Int = 1, data: Data) -> Data {
        var h = Data()
        func s(_ t: String) { h.append(contentsOf: Array(t.utf8)) }
        func u32(_ v: Int) { var x = UInt32(v).littleEndian; withUnsafeBytes(of: &x) { h.append(contentsOf: $0) } }
        func u16(_ v: Int) { var x = UInt16(v).littleEndian; withUnsafeBytes(of: &x) { h.append(contentsOf: $0) } }
        s("RIFF"); u32(36 + data.count); s("WAVE"); s("fmt "); u32(16); u16(format); u16(channels); u32(rate)
        u32(rate * channels * bits / 8); u16(channels * bits / 8); u16(bits); s("data"); u32(data.count)
        return h + data
    }

    func testFixtures() {
        XCTAssertEqual(codes(Fixture.data("audio/en_apple.wav")), [])
        XCTAssertEqual(codes(Fixture.data("audio/zh_short.wav")), [], "LIST chunk before data")
        XCTAssertEqual(codes(Fixture.data("audio/low_volume.wav")), [90104])
        XCTAssertEqual(codes(Fixture.data("audio/silent.wav")), [90103])
        let r = AudioPrecheck.check(Fixture.data("audio/zh_short.wav"))
        XCTAssertEqual(r.durationSeconds ?? 0, 1.92, accuracy: 0.001)
        XCTAssertEqual(r.peak, 31086)
        XCTAssertEqual(r.rms ?? 0, 4343.4, accuracy: 0.5)
        XCTAssertEqual(r.rmsDbfs ?? 0, -17.6, accuracy: 0.1)
        for name in ["en_abc.wav", "en_fox.wav", "zh_para.wav", "g9_zh_selfintro.wav"] {
            XCTAssertEqual(codes(Fixture.data("audio/" + name)), [], name)
        }
    }

    func testWAVParsing() throws {
        let info = try XCTUnwrap(YuguWAV.parse(Fixture.data("audio/zh_short.wav")))
        XCTAssertEqual(info.sampleRate, 16000)
        XCTAssertEqual(info.channels, 1)
        XCTAssertEqual(info.bitsPerSample, 16)
        XCTAssertTrue(info.isIntegerPCM)
        XCTAssertEqual(info.dataOffset + info.dataLength, Fixture.data("audio/zh_short.wav").count)
        XCTAssertNil(YuguWAV.parse(Data("RIFF....WAVX".utf8)))
        XCTAssertNil(YuguWAV.parse(Data("RIFF\0\0\0\0WAVEdata\0\0\0\0".utf8)), "data before fmt")
        let wrapped = YuguWAV.wrap(pcm: Data(count: 32000))
        XCTAssertEqual(wrapped.count, 32044)
        XCTAssertEqual(YuguWAV.parse(wrapped)?.durationSeconds, 1.0)
        // A truncated data chunk is clamped to the bytes present.
        var cut = wav(data: Data(count: 1000))
        cut.removeLast(100)
        XCTAssertEqual(YuguWAV.parse(cut)?.dataLength, 900)
    }

    func testSyntheticCases() {
        XCTAssertEqual(codes(wav(data: tonePCM(seconds: 0.5))), [90101])
        XCTAssertEqual(codes(wav(data: tonePCM(seconds: 1.0))), [])
        XCTAssertEqual(codes(wav(rate: 8000, data: tonePCM(seconds: 2, sampleRate: 8000))), [90105])
        XCTAssertEqual(codes(wav(bits: 8, data: Data(repeating: 128, count: 32000))), [90105])
        XCTAssertEqual(codes(wav(bits: 32, format: 3, data: Data(count: 128_000))), [90105])
        XCTAssertEqual(codes(wav(data: tonePCM(seconds: 1.5, amplitude: 150))), [90104])
        XCTAssertEqual(codes(wav(data: tonePCM(seconds: 1.5, amplitude: 250))), [90104])
        // Peak at least 200 is not silent even with a low RMS.
        var spike = Data(count: 48000)
        spike[100] = 0xF4
        spike[101] = 0x01
        XCTAssertEqual(codes(wav(data: spike)), [90104])
        // Over 300 s (9.6 MB of 16 kHz mono). Uploads up to 50 MB pass the size check.
        XCTAssertEqual(codes(wav(data: Data(repeating: 0x10, count: 16000 * 2 * 301))), [90102])
        XCTAssertEqual(codes(wav(rate: 48000, channels: 2, data: Data(repeating: 0x10, count: 11 * 1024 * 1024))), [])
        // Not WAV: only the size check, limit 50 MB.
        XCTAssertEqual(codes(Data([0x49, 0x44, 0x33] + [UInt8](repeating: 1, count: 100))), [])
        XCTAssertEqual(codes(Data(count: 11 * 1024 * 1024)), [])
        XCTAssertEqual(codes(Data(count: 51 * 1024 * 1024)), [90102])
        XCTAssertEqual(codes(wav(rate: 8000, data: Data(repeating: 0x10, count: 8000 * 2 * 301))), [90105, 90102])
        XCTAssertEqual(AudioPrecheck.maxUploadBytes, 50 * 1024 * 1024)
        XCTAssertEqual(AudioPrecheck.maxStreamBytes, 10 * 1024 * 1024)
    }

    func testStreamStats() {
        var stats = PCMStats()
        let tone = tonePCM(seconds: 0.5)
        // Odd chunk sizes split samples across chunks.
        stats.add(tone.prefix(101))
        stats.add(tone.dropFirst(101))
        XCTAssertEqual(stats.samples, 8000)
        XCTAssertEqual(AudioPrecheck.check(stats: stats).issues.map { $0.code }, [90101])
        XCTAssertEqual(AudioPrecheck.check(stats: PCMStats()).issues.map { $0.code }, [90101])
        // A streaming round longer than 300 s.
        var long = PCMStats()
        long.add(Data(repeating: 0x10, count: 16000 * 2 * 301))
        XCTAssertEqual(AudioPrecheck.check(stats: long).issues.map { $0.code }, [90102])
    }

    func testModes() throws {
        let silent = AudioPrecheck.check(Fixture.data("audio/silent.wav"))
        XCTAssertEqual(try AudioPrecheck.apply(silent, mode: .off), [])
        XCTAssertEqual(try AudioPrecheck.apply(silent, mode: .warn).map { $0.code }, [90103])
        XCTAssertThrowsError(try AudioPrecheck.apply(silent, mode: .reject)) {
            XCTAssertEqual(($0 as? YuguError)?.code, 90103)
            XCTAssertEqual(($0 as? YuguError)?.category, .audio)
        }
        let low = AudioPrecheck.check(Fixture.data("audio/low_volume.wav"))
        XCTAssertEqual(try AudioPrecheck.apply(low, mode: .reject).map { $0.code }, [90104], "low volume never rejects")
    }

    /// REJECT fails before upload; WARN uploads and reports localWarnings; OFF does nothing.
    func testClientModes() throws {
        let t = makeTestClient { $0.audioPrecheck = .reject }
        var got: Result<EvalResult, YuguError>?
        let config = EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好")
        t.client.evaluate(audio: .data(Fixture.data("audio/silent.wav")), config: config) { got = $0 }
        t.executor.runUntilIdle()
        guard case .failure(let e)? = got else { return XCTFail() }
        XCTAssertEqual(e.code, 90103)
        XCTAssertTrue(e.isAudioQuality)
        XCTAssertEqual(t.http.requestCount, 0)

        let w = makeTestClient()
        w.http.defaultReply = .status(200, Fixture.text("platform/native_evaluate_sentence_zh.json"), [:])
        var r: Result<EvalResult, YuguError>?
        w.client.evaluate(audio: .data(Fixture.data("audio/low_volume.wav")), config: config) { r = $0 }
        w.executor.runUntilIdle()
        XCTAssertEqual(try XCTUnwrap(r).get().localWarnings.map { $0.code }, [90104])
        XCTAssertEqual(w.http.requestCount, 1)
        XCTAssertTrue(w.logger.messages(.warn).contains { $0.contains("audio precheck") })

        var o: Result<EvalResult, YuguError>?
        w.client.evaluate(audio: .data(Fixture.data("audio/silent.wav")), config: config, options: RequestOptions(audioPrecheck: .off)) { o = $0 }
        w.executor.runUntilIdle()
        XCTAssertEqual(try XCTUnwrap(o).get().localWarnings, [])

        var c: Result<EvalResult, YuguError>?
        w.client.evaluateCompat(coreType: .sentEvalCn, audio: .data(Fixture.data("audio/low_volume.wav")), params: CompatParams(refText: "x")) { c = $0 }
        w.executor.runUntilIdle()
        XCTAssertEqual(try XCTUnwrap(c).get().localWarnings.map { $0.code }, [90104])
    }

    func testFileInput() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("yugu-precheck-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("my take.wav")
        try Fixture.data("audio/en_apple.wav").write(to: url)
        let loaded = try AudioInput.file(url).load()
        XCTAssertEqual(loaded.filename, "my take.wav")
        XCTAssertEqual(loaded.contentType, "audio/wav")
        XCTAssertEqual(loaded.kind, .wav)
        let mp3 = try AudioInput.data(Data([0x49, 0x44, 0x33, 3, 0]), filename: "a.mp3").load()
        XCTAssertEqual(mp3.contentType, "audio/mpeg")
        XCTAssertEqual(mp3.kind, .other)
        let custom = try AudioInput.data(Data([1, 2]), contentType: "audio/x-custom").load()
        XCTAssertEqual(custom.contentType, "audio/x-custom")
        XCTAssertThrowsError(try AudioInput.pcm16(Data([0, 0]), sampleRate: 0).load())
        XCTAssertEqual(try AudioInput.pcm16(Data(count: 64), sampleRate: 8000, channels: 2).load().data.count, 108)
    }
}
