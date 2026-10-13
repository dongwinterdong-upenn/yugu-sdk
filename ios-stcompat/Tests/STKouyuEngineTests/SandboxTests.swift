// Copyright 2026 优谷雅言 open.shengzhiai.com
// SPDX-License-Identifier: Apache-2.0
//
// End-to-end cases against the real platform with sandbox keys (acceptance A-05-2), through the
// public Objective-C API the way a Shengtong app calls it: initEngine with the keys and the server
// address, startEngine in file mode, the JSON of the result block. Run by ci/ios-stcompat-macos.sh;
// the Linux CI runs the same cases through the C core (core-tests/integration/run_sandbox.py).
//
// Runs only when YUGU_SANDBOX_APPKEY and YUGU_SANDBOX_SECRET are set (YUGU_SANDBOX_BASE defaults to
// https://open.shengzhiai.com); otherwise it is skipped, so push builds never call the platform.
// One test method runs the five cases in sequence, so `swift test --parallel` never runs two
// sandbox evaluations at once: two evaluations, two error paths the platform answers without
// evaluating, then (2.1.1) a paragraph streamed in real time over the compat WebSocket, the default
// for recordings, which is where NSURLSessionWebSocketTask meets the platform's permessage-deflate.
// One retry per call, non-retryable errors and no autoRetry keep a run at 8 platform calls or
// fewer. The cases run at log level INFO and print the timing lines, which carry the tokenId,
// times and sizes only. Key values are never printed: messages carry errId, error and recordId
// only, never the envelope (it contains applicationId), and applicationId is compared without
// XCTAssertEqual so a failure cannot print the appKey.

import Foundation
import STKouyuEngine
import STKouyuEngine.YuguCompat
import XCTest

final class SandboxTests: XCTestCase {
    private struct Sandbox {
        let appKey: String
        let secret: String
        let base: String
        let spec: String
    }

    private func sandbox() throws -> Sandbox? {
        let env = ProcessInfo.processInfo.environment
        func value(_ name: String) -> String? {
            guard let v = env[name]?.trimmingCharacters(in: .whitespacesAndNewlines), !v.isEmpty else { return nil }
            return v
        }
        guard let appKey = value("YUGU_SANDBOX_APPKEY"), let secret = value("YUGU_SANDBOX_SECRET") else {
            throw XCTSkip("YUGU_SANDBOX_APPKEY and YUGU_SANDBOX_SECRET are not set: the sandbox cases call the real platform and are skipped")
        }
        let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let spec = value("YUGU_SPEC_DIR") ?? here.appendingPathComponent("../../../spec").standardized.path
        guard FileManager.default.fileExists(atPath: spec + "/fixtures/audio/zh_para.wav") else {
            XCTFail("the sandbox keys are set but spec/fixtures/audio was not found: set YUGU_SPEC_DIR")
            return nil
        }
        return Sandbox(appKey: appKey, secret: secret, base: value("YUGU_SANDBOX_BASE") ?? "https://open.shengzhiai.com",
                       spec: spec)
    }

    private func json(_ s: String?) -> [String: Any] {
        guard let s = s, let d = s.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return [:] }
        return o
    }

    private func describe(_ r: [String: Any]) -> String {
        return "errId=\(r["errId"] ?? "-") error=\(r["error"] ?? "-") recordId=\(r["recordId"] ?? "-")"
    }

    private func authCodes(_ spec: String) -> Set<Int> {
        guard let d = try? Data(contentsOf: URL(fileURLWithPath: spec + "/errors.json")),
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let errors = o["errors"] as? [[String: Any]] else { return [] }
        return Set(errors.compactMap { e -> Int? in
            (e["category"] as? String) == "AUTH" ? (e["code"] as? NSNumber)?.intValue : nil
        })
    }

    private func makeEngine(_ sb: Sandbox, appKey: String? = nil, secret: String? = nil) -> KYTestEngine? {
        let engine = KYTestEngine()
        let cfg = KYStartEngineConfig()
        cfg.appKey = appKey ?? sb.appKey
        cfg.secretKey = secret ?? sb.secret
        cfg.server = sb.base
        var ok = false
        let done = expectation(description: "initEngine")
        engine.initEngine(KY_CloudEngine, startEngineConfig: cfg) { success, _ in
            ok = success
            done.fulfill()
        }
        wait(for: [done], timeout: 10)
        XCTAssertTrue(ok, "initEngine failed")
        return ok ? engine : nil
    }

    private func assertError(_ r: [String: Any], appKey: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertNil(r["result"], "a result where an error was expected: " + describe(r), file: file, line: line)
        XCTAssertNotNil(r["errId"] as? NSNumber, "errId", file: file, line: line)
        XCTAssertFalse((r["error"] as? String ?? "").isEmpty, "error message", file: file, line: line)
        XCTAssertEqual(r["eof"] as? Int, 1, file: file, line: line)
        XCTAssertTrue((r["applicationId"] as? String) == appKey, "applicationId is not the appKey that was sent",
                      file: file, line: line)
    }

    private func evaluate(_ engine: KYTestEngine, coreType: String, refText: String, audio: String) -> [String: Any] {
        let done = expectation(description: coreType)
        var out: [String: Any] = [:]
        let config = KYTestConfig()
        config.coreTypeNS = coreType
        config.refText = refText
        config.audioPath = audio
        config.autoRetry = false
        let token = engine.start(with: config, result: { r in
            out = self.json(r)
            done.fulfill()
        }) { _, _ in }
        wait(for: [done], timeout: 400)
        XCTAssertEqual(out["tokenId"] as? String, token)
        return out
    }

    private func assertSuccess(_ r: [String: Any], _ sb: Sandbox, refText: String,
                               file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertNil(r["errId"], "platform answered with an error: " + describe(r), file: file, line: line)
        XCTAssertEqual((r["tokenId"] as? String)?.count, 32, "tokenId", file: file, line: line)
        XCTAssertFalse((r["recordId"] as? String ?? "").isEmpty, "recordId", file: file, line: line)
        XCTAssertTrue((r["applicationId"] as? String) == sb.appKey, "applicationId is not the appKey", file: file, line: line)
        XCTAssertEqual(r["refText"] as? String, refText, file: file, line: line)
        XCTAssertEqual(r["eof"] as? Int, 1, file: file, line: line)
        XCTAssertNotNil(r["dtLastResponse"] as? String, file: file, line: line)
        XCTAssertNotNil(r["result"] as? [String: Any], "result", file: file, line: line)
    }

    /// The PCM of a WAV fixture: the bytes of its data chunk.
    private func pcm(_ path: String) -> Data? {
        guard let wav = try? Data(contentsOf: URL(fileURLWithPath: path)), wav.count > 12 else { return nil }
        let b = [UInt8](wav)
        var pos = 12
        while pos + 8 <= b.count {
            let size = Int(b[pos + 4]) | Int(b[pos + 5]) << 8 | Int(b[pos + 6]) << 16 | Int(b[pos + 7]) << 24
            if b[pos] == 0x64, b[pos + 1] == 0x61, b[pos + 2] == 0x74, b[pos + 3] == 0x61 { // "data"
                return Data(b[(pos + 8)..<min(b.count, pos + 8 + size)])
            }
            pos += 8 + size + (size & 1)
        }
        return nil
    }

    /// A stream session fed in real time, 100 ms of audio every 100 ms, then stopEngine.
    private func stream(_ engine: KYTestEngine, coreType: String, refText: String, pcm: Data) -> [String: Any] {
        let done = expectation(description: coreType + " streamed")
        var out: [String: Any] = [:]
        let config = KYTestConfig()
        config.coreTypeNS = coreType
        config.refText = refText
        config.isStream = true
        config.autoRetry = false
        let token = engine.start(with: config, result: { r in
            let j = self.json(r)
            if (j["eof"] as? Int ?? 1) != 0 {
                out = j
                done.fulfill()
            }
        }) { _, _ in }
        var bytes = [UInt8](pcm)
        var offset = 0
        while offset < bytes.count {
            let n = min(3200, bytes.count - offset)
            bytes.withUnsafeMutableBytes { buf in
                engine.feedAudioData(buf.baseAddress! + offset, audioLength: Int32(n))
            }
            offset += n
            RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.1))
        }
        engine.stop()
        wait(for: [done], timeout: 400)
        XCTAssertEqual(out["tokenId"] as? String, token)
        return out
    }

    func testSandboxEndToEnd() throws {
        guard let sb = try sandbox() else { return }
        let retries = YuguCompat.maxRetries
        YuguCompat.maxRetries = 1
        // 2.1.1: INFO, so that the platform adds its timing and every evaluation logs one timing line
        let lock = NSLock()
        var timingLines: [String] = []
        YuguCompat.setLogLevel(.info)
        YuguCompat.setLogHandler { _, message in
            guard message.hasPrefix("timing tokenId=") else { return }
            lock.lock()
            timingLines.append(message)
            lock.unlock()
        }
        defer {
            YuguCompat.maxRetries = retries
            YuguCompat.setLogHandler(nil)
            YuguCompat.resetLogLevel()
            lock.lock()
            for line in timingLines { print("sandbox " + line) }
            lock.unlock()
        }
        let short = sb.spec + "/fixtures/audio/zh_short.wav"

        // 1. sent.eval.cn, zh_short.wav, refText 今天天气很好: no errId, numeric result.overall
        if let engine = makeEngine(sb) {
            let r = evaluate(engine, coreType: "sent.eval.cn", refText: "今天天气很好", audio: short)
            assertSuccess(r, sb, refText: "今天天气很好")
            let overall = (r["result"] as? [String: Any])?["overall"] as? NSNumber
            XCTAssertNotNil(overall, "result.overall is not numeric: " + describe(r))
            print("sandbox sent.eval.cn: overall=\(overall?.stringValue ?? "-") recordId=\(r["recordId"] ?? "-")")
            engine.delete()
        }

        // 2. para.eval.cn, zh_para.wav and its text: details[] carry overall the way Shengtong's do
        if let engine = makeEngine(sb) {
            let text = "今天天气很好。我们一起去公园散步。"
            let r = evaluate(engine, coreType: "para.eval.cn", refText: text, audio: sb.spec + "/fixtures/audio/zh_para.wav")
            assertSuccess(r, sb, refText: text)
            let sentences = (r["result"] as? [String: Any])?["sentences"] as? [[String: Any]] ?? []
            XCTAssertFalse(sentences.isEmpty, "no sentences in the paragraph result: " + describe(r))
            var details = 0
            for s in sentences {
                for item in s["details"] as? [[String: Any]] ?? [] {
                    // the way the public Shengtong sample reads a word score
                    let overall = item["overall"] as? NSNumber
                    XCTAssertNotNil(overall, "details item \(item["word"] ?? "-") without overall")
                    if let o = overall, let so = (item["scores"] as? [String: Any])?["overall"] as? NSNumber {
                        XCTAssertEqual(o, so, "details overall is not scores.overall")
                    }
                    details += 1
                }
            }
            XCTAssertGreaterThan(details, 0, "no details in the paragraph result")
            print("sandbox para.eval.cn: sentences=\(sentences.count) details=\(details) recordId=\(r["recordId"] ?? "-")")
            engine.delete()
        }

        // 3. an appKey the platform does not know: an authentication errId, no evaluation
        let unknown = "no-such-app-key-\(UInt64(Date().timeIntervalSince1970 * 1000))"
        if let engine = makeEngine(sb, appKey: unknown, secret: "not-a-secret") {
            let r = evaluate(engine, coreType: "sent.eval.cn", refText: "今天天气很好", audio: short)
            assertError(r, appKey: unknown)
            let errId = (r["errId"] as? NSNumber)?.intValue ?? 0
            XCTAssertTrue(authCodes(sb.spec).contains(errId), "errId \(errId) is not an authentication code: " + describe(r))
            print("sandbox unknown appKey: errId=\(errId) error=\(r["error"] ?? "-")")
            engine.delete()
        }

        // 4. pinyin without refPinyin: the platform's 400 40001 as errId 40001, no evaluation
        if let engine = makeEngine(sb) {
            let r = evaluate(engine, coreType: "pinyin", refText: "重庆", audio: short)
            assertError(r, appKey: sb.appKey)
            XCTAssertEqual((r["errId"] as? NSNumber)?.intValue, 40001, describe(r))
            XCTAssertTrue((r["error"] as? String ?? "").contains("refPinyin"), describe(r))
            print("sandbox pinyin without refPinyin: " + describe(r))
            engine.delete()
        }

        // 5. 2.1.1: para.eval.cn streamed in real time over the compat WebSocket, the default for
        // recordings and feeds: the final arrives whole and without the platform's timing object
        if let engine = makeEngine(sb), let audio = pcm(sb.spec + "/fixtures/audio/zh_para.wav") {
            let text = "今天天气很好。我们一起去公园散步。"
            XCTAssertTrue(YuguCompat.streamUpload)
            let r = stream(engine, coreType: "para.eval.cn", refText: text, pcm: audio)
            assertSuccess(r, sb, refText: text)
            XCTAssertNil(r["timing"], "the platform timing object reached the app")
            let sentences = (r["result"] as? [String: Any])?["sentences"] as? [[String: Any]] ?? []
            XCTAssertFalse(sentences.isEmpty, "no sentences in the streamed paragraph: " + describe(r))
            engine.delete()
            RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.5)) // the timing line follows the callback
            lock.lock()
            // 2.2.0: recordId of the result right after tokenId
            let line = timingLines.first {
                $0.hasPrefix("timing tokenId=\(r["tokenId"] as? String ?? "-") recordId=\(r["recordId"] as? String ?? "-") mode=")
            }
            lock.unlock()
            XCTAssertTrue(line?.contains(" mode=ws audio=") ?? false, "streamed: " + (line ?? "no timing line"))
            XCTAssertTrue(line?.contains(" server.total=") ?? false, "the platform timing: " + (line ?? "-"))
            print("sandbox streamed para.eval.cn: recordId=\(r["recordId"] ?? "-")")
        }
    }
}
