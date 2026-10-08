// Copyright 2026 优谷雅言 open.shengzhiai.com
// SPDX-License-Identifier: Apache-2.0
//
// XCTest for macOS (swift test) and an iOS Simulator (xcodebuild test), both run by
// ci/ios-stcompat-macos.sh. Not executed on the Linux CI.
//
// The first group only has to compile: it uses the Shengtong API from Swift exactly as an app
// written against STKouyuEngine.framework does. The Objective-C group calls the checks of the
// STKouyuEngineObjCSupport target, which is compiled with the three Objective-C import forms.
// The engine group runs against the mock platform when YUGU_MOCK_BASE_URL (http://127.0.0.1:PORT)
// and YUGU_SPEC_DIR (path of spec/) are set.

import Foundation
import STKouyuEngine
import STKouyuEngine.YuguCompat
import STKouyuEngineObjCSupport
import XCTest

final class SwiftSurfaceTests: XCTestCase {
    func testConfigDefaultsAndTypes() {
        let start = KYStartEngineConfig()
        XCTAssertEqual(start.seek, 60)
        XCTAssertEqual(start.connectTimeout, 20)
        XCTAssertEqual(start.serverTimeout, 60)
        XCTAssertTrue(start.isOutputLog)
        XCTAssertTrue(start.enable)
        start.appKey = "ak"
        start.secretKey = "sk"
        start.server = KY_CloudServer_Release
        start.vadEnable = true
        start.logLevel = CGFloat(KYLOG_DEBUG)

        let config = KYTestConfig()
        config.coreType = KYTestType_Sentence
        config.coreTypeNS = "sent.eval.cn"
        config.refText = "今天天气很好"
        config.phonemeOption = KYPhonemeOption_KK
        config.ageGroup = KYAgeGroupSupportOption_Middle
        config.mode = KYModeType_Home
        config.qType = KYQType_SituationalReply
        config.compress = KYCompress_Raw
        config.scale = 100
        config.customParams = ["language": "zh-CN"]
        config.errIds = ["20009", 40001]
        XCTAssertTrue(config.phoneme_output)
        XCTAssertEqual(config.durationInterval, 100)
        XCTAssertEqual(config.audioType, "wav")
        XCTAssertEqual(KYEngineCloud, "cloud")
        XCTAssertEqual(KYEngineNative, "native")

        let temp = KYTestConfigTemp()
        temp.coreType = KYTestType_Word_Cn
        temp.coreTypeNS = "word.eval.cn"
        temp.coreProvideType = KYEngineCloud
        XCTAssertEqual(KY_CloudEngine.rawValue, 0)
        XCTAssertEqual(KY_MultiEngine.rawValue, 2)
        XCTAssertEqual(KYTestType_Wordspell.rawValue, 22)
        XCTAssertEqual(KYCompress_Speex.rawValue, 1)
        XCTAssertEqual(STKouyuEngineVersionNumber, 2.0)
    }

    func testEngineSelectorsCompile() {
        let engine = KYTestEngine.sharedInstance()!
        XCTAssertFalse(engine.getStatus())
        XCTAssertNotNil(engine.getLastRecordPath())
        XCTAssertTrue(engine.updateProvision())
        XCTAssertTrue(engine.updateProvision("ak", secretkey: "sk"))
        XCTAssertTrue(engine.updateProvision("p", appkey: "ak", secretkey: "sk"))
        XCTAssertTrue(engine.inquireProvision { _ in })
        XCTAssertTrue(engine.inquireProvision("p") { _ in })
        engine.stopPlay()
        engine.cancel()
    }

    /// Never called: every Objective-C method of KYTestEngine under the name the Swift importer
    /// gives it (the Swift name of stopEngine is stop(), the final block label of finishBlock: is
    /// finish:). A plain function rather than a closure, so that the compiler reports every
    /// statement that does not resolve.
    private static func selectorsResolve(_ engine: KYTestEngine) {
        engine.initEngine(KY_CloudEngine, startEngineConfig: KYStartEngineConfig(), finish: { _, _ in })
        _ = engine.start(with: KYTestConfig(), result: { _ in }, finish: { _, _ in })
        _ = engine.start(with: KYTestConfig(), onStart: {}, onStartFail: { _ in }, onPause: {},
                         onTick: { _, _ in }, onRecording: { _, _ in }, onRecordEnd: {},
                         onScoreBlock: { _ in }, finish: { _, _ in })
        engine.stop()
        engine.cancel()
        engine.delete()
        engine.playback()
        engine.playback {}
        engine.play(withPath: "/tmp/a.wav")
        engine.play(withPath: "/tmp/a.wav", void: {})
        engine.stopPlay()
        engine.activeAudioSession()
        _ = engine.getStatus()
        _ = engine.getLastRecordPath()
        var pcm = [UInt8](repeating: 0, count: 640)
        engine.feedAudioData(&pcm, audioLength: 640)
        _ = engine.updateProvision("p", appkey: "ak", secretkey: "sk")
        _ = engine.updateProvision("ak", secretkey: "sk")
        _ = engine.updateProvision()
        _ = engine.inquireProvision("p", inquireProvisionBlock: { _ in })
        _ = engine.inquireProvision({ _ in })
    }

    /// Never called: the Swift example of README.md, word for word.
    private static func readmeSwiftExample() {
        let engine = KYTestEngine.sharedInstance()!
        let config = KYStartEngineConfig()
        config.appKey = "优谷雅言 appKey"
        config.secretKey = "优谷雅言 secretKey"
        engine.initEngine(KY_CloudEngine, startEngineConfig: config) { ok, message in print(ok, message ?? "") }

        let test = KYTestConfig()
        test.coreTypeNS = "sent.eval"
        test.refText = "How are you"
        let tokenId = engine.start(with: test, result: { json in print(json ?? "") }) { ok, str in print(ok, str ?? "") }
        engine.stop()
        engine.delete()
        _ = tokenId
    }

    func testYuguCompatSettings() {
        XCTAssertEqual(YuguCompat.sdkVersion, "2.0.0")
        XCTAssertEqual(YuguCompat.defaultBaseURL, "https://open.shengzhiai.com")
        YuguCompat.baseURL = "ftp://nope"
        XCTAssertNil(YuguCompat.baseURL)
        YuguCompat.baseURL = "http://127.0.0.1:1/"
        XCTAssertEqual(YuguCompat.baseURL, "http://127.0.0.1:1")
        XCTAssertEqual(YuguCompat.effectiveBaseURL, "http://127.0.0.1:1")
        YuguCompat.baseURL = nil
        YuguCompat.maxRetries = 9
        XCTAssertEqual(YuguCompat.maxRetries, 5)
        YuguCompat.maxRetries = 2
        YuguCompat.setLogLevel(.debug)
        YuguCompat.resetLogLevel()
    }
}

final class ObjectiveCLayerTests: XCTestCase {
    func testConstantsAndLog() {
        XCTAssertNil(YGSTCheckConstantsAndLog())
    }

    func testDelegateAndProvision() {
        XCTAssertNil(YGSTCheckDelegateAndProvision(5))
    }

    func testSkegnLocalErrorWithoutNetwork() {
        XCTAssertNil(YGSTCheckSkegnLocalError(5))
    }

    func testSkegnAgainstMockPlatform() throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["YUGU_MOCK_BASE_URL"], let spec = env["YUGU_SPEC_DIR"] else {
            throw XCTSkip("YUGU_MOCK_BASE_URL and YUGU_SPEC_DIR are not set")
        }
        XCTAssertNil(YGSTCheckSkegnAgainstMock(base, spec, 30))
    }
}

final class EngineBehaviourTests: XCTestCase, KYTestEngineDelegate {
    private var engine: KYTestEngine!
    private var scores: [String] = []

    override func setUp() {
        super.setUp()
        engine = KYTestEngine()
        engine.delegate = self
        scores = []
    }

    override func tearDown() {
        engine.delete()
        engine.delete() // idempotent
        YuguCompat.baseURL = nil
        super.tearDown()
    }

    func kyTestEngineDidScore(_ str: String!) {
        scores.append(str)
    }

    private func json(_ s: String?) -> [String: Any] {
        guard let s = s, let d = s.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return [:] }
        return o
    }

    private func initEngine(server: String?) {
        let cfg = KYStartEngineConfig()
        cfg.appKey = "mock-app-key"
        cfg.secretKey = "mock-secret-key"
        cfg.server = server
        let done = expectation(description: "init")
        engine.initEngine(KY_CloudEngine, startEngineConfig: cfg) { ok, _ in
            XCTAssertTrue(ok)
            done.fulfill()
        }
        wait(for: [done], timeout: 5)
    }

    func testStartWithoutInitIs60007() {
        let done = expectation(description: "result")
        let config = KYTestConfig()
        config.refText = "hello"
        let token = engine.start(with: config, result: { r in
            let j = self.json(r)
            XCTAssertEqual(j["errId"] as? Int, 60007)
            XCTAssertEqual(j["eof"] as? Int, 1)
            done.fulfill()
        }) { ok, _ in XCTAssertFalse(ok) }
        XCTAssertEqual(token?.count, 32)
        wait(for: [done], timeout: 5)
    }

    func testUnsupportedCoreTypeIs60003WithoutNetwork() {
        initEngine(server: "http://127.0.0.1:9") // nothing listens: a request would fail with 20009
        let done = expectation(description: "result")
        let config = KYTestConfig()
        config.coreType = KYTestType_Open
        config.refText = "x"
        _ = engine.start(with: config, result: { r in
            XCTAssertEqual(self.json(r)["errId"] as? Int, 60003)
            done.fulfill()
        }) { _, _ in }
        wait(for: [done], timeout: 5)
    }

    func testEmptyRefTextIs60006() {
        initEngine(server: nil)
        let done = expectation(description: "result")
        let config = KYTestConfig()
        config.coreTypeNS = "sent.eval"
        _ = engine.start(with: config, result: { r in
            XCTAssertEqual(self.json(r)["errId"] as? Int, 60006)
            done.fulfill()
        }) { _, _ in }
        wait(for: [done], timeout: 5)
    }

    private func mockEnvironment() throws -> (String, String) {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["YUGU_MOCK_BASE_URL"], let spec = env["YUGU_SPEC_DIR"] else {
            throw XCTSkip("YUGU_MOCK_BASE_URL and YUGU_SPEC_DIR are not set")
        }
        return (base, spec)
    }

    func testFileEvaluationAgainstMockPlatform() throws {
        let (base, spec) = try mockEnvironment()
        initEngine(server: base)
        let done = expectation(description: "result")
        let config = KYTestConfig()
        config.coreTypeNS = "sent.eval.cn"
        config.refText = "今天天气很好"
        config.audioPath = spec + "/fixtures/audio/zh_short.wav"
        config.getParam = true
        var token: String?
        token = engine.start(with: config, result: { r in
            let j = self.json(r)
            XCTAssertEqual(j["tokenId"] as? String, token)
            XCTAssertEqual(j["applicationId"] as? String, "mock-app-key")
            XCTAssertEqual(j["eof"] as? Int, 1)
            XCTAssertTrue((j["recordId"] as? String ?? "").hasPrefix("eval_"))
            XCTAssertNotNil(j["result"] as? [String: Any])
            XCTAssertNotNil(j["params"] as? [String: Any])
            done.fulfill()
        }) { ok, str in
            XCTAssertTrue(ok)
            XCTAssertEqual(str, token)
        }
        wait(for: [done], timeout: 30)
        XCTAssertEqual(scores.count, 1)
    }

    func testStreamEvaluationAgainstMockPlatform() throws {
        let (base, spec) = try mockEnvironment()
        initEngine(server: base)
        let wav = try Data(contentsOf: URL(fileURLWithPath: spec + "/fixtures/audio/zh_short.wav"))
        let done = expectation(description: "result")
        let config = KYTestConfig()
        config.coreTypeNS = "sent.eval.cn"
        config.refText = "今天天气很好"
        config.isStream = true
        _ = engine.start(with: config, result: { r in
            XCTAssertNotNil(self.json(r)["result"])
            done.fulfill()
        }) { _, _ in }
        var pcm = [UInt8](wav.subdata(in: 78 ..< wav.count)) // skip RIFF, fmt and LIST chunks of the fixture
        var offset = 0
        while offset < pcm.count {
            let n = min(640, pcm.count - offset)
            pcm.withUnsafeMutableBytes { buf in
                engine.feedAudioData(buf.baseAddress! + offset, audioLength: Int32(n))
            }
            offset += n
        }
        engine.stop()
        wait(for: [done], timeout: 30)
    }

    func testCancelDeliversNothing() throws {
        let (base, spec) = try mockEnvironment()
        initEngine(server: base)
        let config = KYTestConfig()
        config.coreTypeNS = "sent.eval.cn"
        config.refText = "今天天气很好"
        config.audioPath = spec + "/fixtures/audio/zh_short.wav"
        _ = engine.start(with: config, result: { _ in XCTFail("no result after cancelEngine") }) { _, _ in }
        engine.cancel()
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 2))
        XCTAssertTrue(scores.isEmpty)
    }
}
