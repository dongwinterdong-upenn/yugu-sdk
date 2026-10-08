import XCTest
@testable import YuguCore

/// Minimal multipart parser, same rules as the mock server.
struct ParsedPart {
    let headers: [String: String]
    let name: String?
    let filename: String?
    let body: Data
}

func parseMultipart(_ body: Data, contentType: String) -> [ParsedPart] {
    guard let r = contentType.range(of: "boundary=") else { return [] }
    let boundary = Data(("--" + contentType[r.upperBound...]).utf8)
    var parts: [ParsedPart] = []
    guard var pos = body.range(of: boundary)?.upperBound else { return [] }
    while true {
        if body[pos..<min(pos + 2, body.count)] == Data("--".utf8) { break }
        if body[pos..<pos + 2] == Data("\r\n".utf8) { pos += 2 }
        guard let headerEnd = body.range(of: Data("\r\n\r\n".utf8), in: pos..<body.count) else { break }
        let headerText = String(decoding: body[pos..<headerEnd.lowerBound], as: UTF8.self)
        guard let next = body.range(of: boundary, in: headerEnd.upperBound..<body.count) else { break }
        let data = body.subdata(in: headerEnd.upperBound..<(next.lowerBound - 2))
        var headers: [String: String] = [:]
        for line in headerText.components(separatedBy: "\r\n") {
            if let c = line.firstIndex(of: ":") {
                headers[line[..<c].lowercased()] = line[line.index(after: c)...].trimmingCharacters(in: .whitespaces)
            }
        }
        let cd = headers["content-disposition"] ?? ""
        func param(_ n: String) -> String? {
            guard let r = cd.range(of: n + "=\"") else { return nil }
            let rest = cd[r.upperBound...]
            return rest.firstIndex(of: "\"").map { String(rest[..<$0]) }
        }
        let filename = cd.contains("filename=") ? param("filename") : nil
        parts.append(ParsedPart(headers: headers, name: param("name"), filename: filename, body: data))
        pos = next.upperBound
    }
    return parts
}

final class MultipartTests: XCTestCase {
    func testExactByteLayout() {
        var mp = MultipartBuilder(boundary: "B0UND")
        mp.addJSONField(name: "config", json: #"{"a":1}"#)
        mp.addField(name: "refText", value: "你好")
        mp.addFile(name: "audio", filename: "a.wav", contentType: "audio/wav", data: Data([1, 2, 3]))
        var expected = Data()
        expected.append(Data("--B0UND\r\nContent-Disposition: form-data; name=\"config\"\r\nContent-Type: application/json; charset=utf-8\r\n\r\n{\"a\":1}\r\n".utf8))
        expected.append(Data("--B0UND\r\nContent-Disposition: form-data; name=\"refText\"\r\n\r\n你好\r\n".utf8))
        expected.append(Data("--B0UND\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n".utf8))
        expected.append(Data([1, 2, 3]))
        expected.append(Data("\r\n--B0UND--\r\n".utf8))
        XCTAssertEqual(mp.build(), expected)
        XCTAssertEqual(mp.contentType, "multipart/form-data; boundary=B0UND")
    }

    func testBoundarySafetyAndEscaping() {
        var mp = MultipartBuilder(boundary: "XYZ")
        mp.addFile(name: "audio", filename: "a\"b\r\n.wav", contentType: "audio/wav", data: Data("..--XYZ..".utf8))
        XCTAssertFalse(mp.boundaryIsSafe)
        XCTAssertTrue(String(decoding: mp.build(), as: UTF8.self).contains("filename=\"a%22b%0D%0A.wav\""))
        let b = MultipartBuilder.makeBoundary(SeededRandom(seed: 3))
        XCTAssertTrue(b.hasPrefix("----YuguFormBoundary"))
        XCTAssertEqual(b.count, "----YuguFormBoundary".count + 32)
    }

    /// The evaluate request: config part without filename and with JSON content type, signed
    /// as the exact text; audio part with filename; idempotency and auth headers.
    func testEvaluateRequestLayoutAndSignature() throws {
        let t = makeTestClient()
        t.http.defaultReply = .status(200, Fixture.text("platform/native_evaluate_sentence_zh.json"), ["Idempotency-Replayed": "true"])
        let wav = Fixture.data("audio/zh_short.wav")
        let config = EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好", language: "zh-CN", slack: 0.2, scale: 100, includeReport: false)
        var got: Result<EvalResult, YuguError>?
        t.client.evaluate(audio: .data(wav), config: config) { got = $0 }
        t.executor.runUntilIdle()
        let result = try XCTUnwrap(got).get()
        XCTAssertTrue(result.replayed)
        XCTAssertEqual(result.overall, 93.7)
        let req = try XCTUnwrap(t.http.requests.first)
        XCTAssertEqual(req.method, "POST")
        XCTAssertEqual(req.url.absoluteString, "https://api.example.test/api/v1/evaluate")
        XCTAssertEqual(req.header("User-Agent"), "yugu-ios-sdk/2.0.0")
        let key = try XCTUnwrap(req.header("Idempotency-Key"))
        XCTAssertEqual(key.count, 32)
        XCTAssertEqual(result.idempotencyKey, key)
        let contentType = try XCTUnwrap(req.header("Content-Type"))
        XCTAssertTrue(contentType.hasPrefix("multipart/form-data; boundary=----YuguFormBoundary"))
        let parts = parseMultipart(req.body!, contentType: contentType)
        XCTAssertEqual(parts.map { $0.name }, ["config", "audio"])
        let cfg = parts[0]
        XCTAssertNil(cfg.filename)
        XCTAssertEqual(cfg.headers["content-type"], "application/json; charset=utf-8")
        let configText = String(decoding: cfg.body, as: UTF8.self)
        XCTAssertEqual(configText, #"{"coreType":"sentence","referenceText":"今天天气很好","language":"zh-CN","slack":0.2,"scale":100,"includeReport":false}"#)
        XCTAssertEqual(parts[1].filename, "audio.wav")
        XCTAssertEqual(parts[1].headers["content-type"], "audio/wav")
        XCTAssertEqual(parts[1].body, wav)
        XCTAssertEqual(req.header("X-App-Key"), "ak_test_key")
        XCTAssertEqual(req.header("X-Signature"), YuguSigner.sign(params: ["config": configText], secretKey: "sk_test_secret"))
        XCTAssertNotNil(req.header("X-Timestamp"))
        XCTAssertEqual(req.header("X-Nonce")?.count, 16)
    }

    func testCompatRequestFieldsAreSignedAsSent() throws {
        let t = makeTestClient()
        t.http.defaultReply = .status(200, Fixture.text("platform/compat_sent.eval.cn.json"), [:])
        var got: Result<EvalResult, YuguError>?
        let params = CompatParams(refText: "今天天气很好", language: "zh-CN", refPinyin: "", extraFields: ["paragraph_need_word_score": "1", "empty": ""])
        t.client.evaluateCompat(coreType: .sentEvalCn, audio: .file(Fixture.dir.appendingPathComponent("audio/zh_short.wav")), params: params) { got = $0 }
        t.executor.runUntilIdle()
        XCTAssertEqual(try XCTUnwrap(got).get().coreType, "sent.eval.cn")
        let req = try XCTUnwrap(t.http.requests.first)
        XCTAssertEqual(req.url.path, "/sent.eval.cn")
        let parts = parseMultipart(req.body!, contentType: req.header("Content-Type")!)
        XCTAssertEqual(parts.map { $0.name }, ["coreType", "refText", "language", "paragraph_need_word_score", "audio"])
        XCTAssertTrue(parts.dropLast().allSatisfy { $0.filename == nil && $0.headers["content-type"] == nil })
        XCTAssertEqual(parts.last?.filename, "zh_short.wav")
        var fields: [String: String] = [:]
        for p in parts.dropLast() { fields[p.name!] = String(decoding: p.body, as: UTF8.self) }
        XCTAssertEqual(req.header("X-Signature"), YuguSigner.sign(params: fields, secretKey: "sk_test_secret"))
    }

    func testTTSBodyAndSignature() throws {
        let t = makeTestClient()
        t.http.defaultReply = .status(200, #"{"code":0,"message":"success","data":{"audioUrl":"/audio/t.mp3","duration":"1.5","format":"mp3"}}"#, [:])
        var got: Result<TTSResult, YuguError>?
        t.client.tts(TTSRequest(text: "你好", speed: 50, extra: ["ratio": 0.5])) { got = $0 }
        t.executor.runUntilIdle()
        let r = try XCTUnwrap(got).get()
        XCTAssertEqual(r.absoluteAudioUrl, "https://api.example.test/tts/audio/t.mp3")
        XCTAssertEqual(r.duration, 1.5)
        let req = try XCTUnwrap(t.http.requests.first)
        XCTAssertEqual(req.header("Content-Type"), "application/json; charset=utf-8")
        XCTAssertEqual(String(decoding: req.body!, as: UTF8.self), #"{"text":"你好","speed":50,"ratio":0.5}"#)
        XCTAssertEqual(req.header("X-Signature"), YuguSigner.sign(params: ["text": "你好", "speed": "50", "ratio": "0.5"], secretKey: "sk_test_secret"))
        XCTAssertNotNil(req.header("Idempotency-Key"))
    }

    func testReportRequestSignsEmptySet() throws {
        let t = makeTestClient()
        t.http.defaultReply = .status(200, #"{"code":0,"data":{"recordId":"eval_1","overall":90}}"#, [:])
        var got: Result<ReportResult, YuguError>?
        t.client.getReport(recordId: "eval_1/x") { got = $0 }
        t.executor.runUntilIdle()
        let r = try XCTUnwrap(got).get()
        XCTAssertEqual(r.data["overall"]?.intValue, 90)
        XCTAssertEqual(r.recordId, "eval_1/x")
        let req = try XCTUnwrap(t.http.requests.first)
        XCTAssertEqual(req.method, "GET")
        XCTAssertTrue(req.url.absoluteString.hasSuffix("/api/v1/report/eval_1%2Fx"))
        XCTAssertNil(req.body)
        XCTAssertNil(req.header("Idempotency-Key"))
        XCTAssertEqual(req.header("X-Signature"), YuguSigner.sign(params: [String: String](), secretKey: "sk_test_secret"))
    }

    func testTokenAuthSendsBearer() throws {
        let t = makeTestClient { $0.auth = .token("jwt-abc") }
        t.http.defaultReply = .status(200, #"{"code":0,"data":{}}"#, [:])
        t.client.getReport(recordId: "eval_2") { _ in }
        t.executor.runUntilIdle()
        XCTAssertEqual(t.http.requests.first?.header("Authorization"), "Bearer jwt-abc")
        XCTAssertNil(t.http.requests.first?.header("X-Signature"))
    }

    func testImagePartAndPCMInput() throws {
        let t = makeTestClient()
        t.http.defaultReply = .status(200, Fixture.text("platform/native_open_zh.json"), [:])
        let png = Data([0x89, 0x50, 0x4E, 0x47, 1, 2])
        var got: Result<EvalResult, YuguError>?
        t.client.evaluate(audio: .pcm16(tonePCM(seconds: 1.5)), config: EvaluateConfig(coreType: .open, referenceText: "看图说话", taskType: "picture"), image: png) { got = $0 }
        t.executor.runUntilIdle()
        XCTAssertEqual(try XCTUnwrap(got).get().openTask?.overall, 92)
        let req = try XCTUnwrap(t.http.requests.first)
        let parts = parseMultipart(req.body!, contentType: req.header("Content-Type")!)
        XCTAssertEqual(parts.map { $0.name }, ["config", "audio", "image"])
        XCTAssertEqual(parts[1].body.prefix(4), Data("RIFF".utf8))
        XCTAssertEqual(parts[1].body.count, 44 + 48000)
        XCTAssertEqual(parts[2].filename, "image.png")
        XCTAssertEqual(parts[2].headers["content-type"], "image/png")
    }

    func testContentTypeSniffing() {
        XCTAssertEqual(AudioInput.sniffContentType(Data([0x49, 0x44, 0x33, 4]), filename: "x"), "audio/mpeg")
        XCTAssertEqual(AudioInput.sniffContentType(Data([0xFF, 0xFB, 0x90, 0]), filename: "x"), "audio/mpeg")
        XCTAssertEqual(AudioInput.sniffContentType(Data("OggS....".utf8), filename: "x"), "audio/ogg")
        XCTAssertEqual(AudioInput.sniffContentType(Data("fLaC....".utf8), filename: "x"), "audio/flac")
        XCTAssertEqual(AudioInput.sniffContentType(Data("....ftypM4A ".utf8), filename: "x"), "audio/mp4")
        XCTAssertEqual(AudioInput.sniffContentType(Data([1, 2, 3, 4]), filename: "a.amr"), "audio/amr")
        XCTAssertEqual(AudioInput.sniffContentType(Data([1, 2, 3, 4]), filename: "a.aac"), "audio/aac")
        XCTAssertEqual(AudioInput.sniffContentType(Data([1, 2, 3, 4]), filename: "a.m4a"), "audio/mp4")
        XCTAssertEqual(AudioInput.sniffContentType(Data([1, 2, 3, 4]), filename: "a.bin"), "application/octet-stream")
        XCTAssertEqual(AudioInput.imageType(Data([0xFF, 0xD8, 0xFF, 0xE0])), "image/jpeg")
    }
}
