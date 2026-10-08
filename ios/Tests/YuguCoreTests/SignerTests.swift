import XCTest
@testable import YuguCore

/// Random source that returns fixed bytes, for exact nonce values.
final class FixedBytesRandom: YuguRandom {
    let bytes: [UInt8]
    init(_ bytes: [UInt8]) { self.bytes = bytes }
    func nextUnit() -> Double { 0.5 }
    func nextBytes(_ count: Int) -> [UInt8] { Array(bytes.prefix(count)) }
}

final class SignerTests: XCTestCase {
    /// Every case of spec/fixtures/sign/vectors.json: payload and signature.
    func testAllSharedVectors() throws {
        let vectors = Fixture.json("sign/vectors.json")
        let cases = try XCTUnwrap(vectors["cases"]?.arrayValue)
        XCTAssertEqual(cases.count, 7)
        for c in cases {
            let name = c["name"]?.stringValue ?? "?"
            var params: [String: String?] = [:]
            for (k, v) in c["params"]?.objectValue ?? [:] {
                params[k] = v.isNull ? nil : v.stringValue
            }
            let secret = try XCTUnwrap(c["secret"]?.stringValue)
            XCTAssertEqual(YuguSigner.buildPayload(params), c["payload"]?.stringValue, "payload of \(name)")
            XCTAssertEqual(YuguSigner.sign(params: params, secretKey: secret), c["signature"]?.stringValue, "signature of \(name)")
            XCTAssertEqual(YuguSigner.sign(payload: c["payload"]!.stringValue!, secretKey: secret), c["signature"]?.stringValue, name)
        }
    }

    func testContractVector() {
        let sig = YuguSigner.sign(params: ["coreType": "sent.eval.cn", "language": "zh-CN", "refText": "北京你好"], secretKey: "test_secret_key_123")
        XCTAssertEqual(sig, "A+6uVB/D7khxQEt8tzgCNjMUC1QtQQd1UF+NCYVYZqE=")
    }

    func testSHA256KnownAnswers() {
        XCTAssertEqual(SHA256.hash([]).hexString, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(SHA256.hash(Array("abc".utf8)).hexString, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(
            SHA256.hash(Array("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".utf8)).hexString,
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
        // 64 and 65 byte messages cross the padding boundary.
        XCTAssertEqual(SHA256.hash([UInt8](repeating: 0x61, count: 64)).hexString, "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb")
        XCTAssertEqual(SHA256.hash([UInt8](repeating: 0x61, count: 55)).hexString, "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318")
    }

    /// RFC 4231 test cases 1, 2 and 6.
    func testHMACRFC4231() {
        XCTAssertEqual(
            HMACSHA256.mac(key: [UInt8](repeating: 0x0b, count: 20), message: Array("Hi There".utf8)).hexString,
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
        XCTAssertEqual(
            HMACSHA256.mac(key: Array("Jefe".utf8), message: Array("what do ya want for nothing?".utf8)).hexString,
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
        XCTAssertEqual(
            HMACSHA256.mac(key: [UInt8](repeating: 0xaa, count: 131), message: Array("Test Using Larger Than Block-Size Key - Hash Key First".utf8)).hexString,
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54")
    }

    func testDropsNilAndEmptyAndSortsByUTF16() {
        let p = YuguSigner.buildPayload(["b": "2", "a": "1", "_": "u", "B": "upper", "e": "", "n": nil])
        XCTAssertEqual(p, "B=upper&_=u&a=1&b=2")
        XCTAssertEqual(YuguSigner.buildPayload([String: String]()), "")
    }

    /// The config text the SDK writes for this config is the text of the shared vector.
    func testNativeConfigPartVectorFromEvaluateConfig() throws {
        let vectors = Fixture.json("sign/vectors.json")
        let vector = try XCTUnwrap(vectors["cases"]?.arrayValue?.first { $0["name"]?.stringValue == "native_config_part" })
        let config = EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好", language: "zh-CN", slack: 0.2, scale: 100, includeReport: false)
        let text = try config.jsonText()
        XCTAssertEqual(text, vector["params"]?["config"]?.stringValue)
        XCTAssertEqual(YuguSigner.sign(params: ["config": text], secretKey: "test_secret_key_123"), vector["signature"]?.stringValue)
    }

    /// The signed TTS set equals the vector: top-level non-null scalars as sent.
    func testTTSVectorFromRequest() throws {
        let vectors = Fixture.json("sign/vectors.json")
        let vector = try XCTUnwrap(vectors["cases"]?.arrayValue?.first { $0["name"]?.stringValue == "tts_json_body" })
        let req = TTSRequest(text: "你好世界", language: "zh-CN", voice: "xiaoyan", format: "mp3", speed: 50, pitch: 50, volume: 50, style: nil)
        let signed = req.jsonObject().signableScalars
        XCTAssertEqual(YuguSigner.buildPayload(signed), vector["payload"]?.stringValue)
        XCTAssertEqual(YuguSigner.sign(params: signed, secretKey: "test_secret_key_123"), vector["signature"]?.stringValue)
        XCTAssertEqual(req.jsonObject().text, #"{"text":"你好世界","language":"zh-CN","voice":"xiaoyan","format":"mp3","speed":50,"pitch":50,"volume":50}"#)
    }

    /// Handshake query: appKey, timestamp, nonce, idempotencyKey signed, signature last.
    func testWebSocketQueryVector() throws {
        let signer = RequestSigner(
            auth: .appKey("ak_test", secretKey: "test_secret_key_123"),
            random: FixedBytesRandom([0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef]),
            wallClock: { Date(timeIntervalSince1970: 1_791_447_600) })
        let items = signer.webSocketQuery([("idempotencyKey", "3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c")])
        XCTAssertEqual(items.map { $0.0 }, ["appKey", "timestamp", "nonce", "idempotencyKey", "signature"])
        XCTAssertEqual(items.last?.1, "/nQKY6YLDY1o9QsZphE5etDBKxhUMyIjgqFEOlOFx8w=")
        // `+`, `/` and `=` of the signature are percent-encoded.
        let q = RequestSigner.encodeQuery(items)
        XCTAssertTrue(q.hasSuffix("signature=%2FnQKY6YLDY1o9QsZphE5etDBKxhUMyIjgqFEOlOFx8w%3D"), q)
    }

    func testTokenAuthHeadersAndQuery() {
        let signer = RequestSigner(auth: .token("jwt-1"), random: SeededRandom(seed: 1), wallClock: Date.init)
        XCTAssertEqual(signer.headers(signing: ["a": "b"]).map { "\($0.0)=\($0.1)" }, ["Authorization=Bearer jwt-1"])
        XCTAssertEqual(signer.webSocketQuery([("idempotencyKey", "k")]).map { $0.0 }, ["idempotencyKey", "token"])
    }

    func testRESTHeadersAreFreshPerCall() {
        let signer = RequestSigner(auth: .appKey("ak", secretKey: "sk"), random: SeededRandom(seed: 7), wallClock: { Date(timeIntervalSince1970: 1_000) })
        let a = Dictionary(uniqueKeysWithValues: signer.headers(signing: ["config": "{}"]))
        let b = Dictionary(uniqueKeysWithValues: signer.headers(signing: ["config": "{}"]))
        XCTAssertEqual(a["X-Timestamp"], "1000")
        XCTAssertEqual(a["X-Nonce"]?.count, 16)
        XCTAssertNotEqual(a["X-Nonce"], b["X-Nonce"])
        XCTAssertEqual(a["X-Signature"], YuguSigner.sign(params: ["config": "{}"], secretKey: "sk"))
    }

    func testPercentEncoding() {
        XCTAssertEqual(RequestSigner.percentEncode("a+b/c=d e~_.-中"), "a%2Bb%2Fc%3Dd%20e~_.-%E4%B8%AD")
    }

    /// v1 defect: integral doubles were signed as `1.0` while the JSON said `1`. v2 signs the
    /// exact text it sends, and writes integral doubles as integers, which is also what a server
    /// gets from `String(value)` in JavaScript.
    func testIntegralDoublesAreSignedAsSent() throws {
        let config = EvaluateConfig(coreType: .sentence, referenceText: "x", slack: 1.0, precision: 1.0, toneWeight: 0.5)
        let text = try config.jsonText()
        XCTAssertEqual(text, #"{"coreType":"sentence","referenceText":"x","slack":1,"precision":1,"toneWeight":0.5}"#)
        let parsed = try JSONValue.parse(text)
        var flat: [String: String] = [:]
        for (k, v) in parsed.objectValue! { flat[k] = v.scalarText }
        XCTAssertEqual(flat["slack"], "1")
        XCTAssertEqual(flat["precision"], "1")
        XCTAssertEqual(flat["toneWeight"], "0.5")
    }
}
