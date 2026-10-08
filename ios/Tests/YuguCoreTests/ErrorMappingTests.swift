import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import YuguCore

/// Acceptance 6.6: every error and warning code maps to its category and retryable flag.
final class ErrorMappingTests: XCTestCase {
    let spec = Fixture.json("errors.json")

    private func expectedException(_ category: String) -> String {
        switch category {
        case "NETWORK": return "NetworkException"
        case "TIMEOUT": return "RequestTimeoutException"
        case "AUTH": return "AuthException"
        case "PERMISSION": return "PermissionException"
        case "INVALID_PARAM": return "InvalidParameterException"
        case "NOT_FOUND": return "NotFoundException"
        case "CONFLICT": return "ConflictException"
        case "RATE_LIMIT": return "RateLimitException"
        case "QUOTA": return "QuotaExceededException"
        case "SERVER", "UPSTREAM": return "ServerException"
        case "AUDIO": return "AudioQualityException"
        case "STATE": return "IllegalSessionStateException"
        case "CANCELLED": return "RequestCancelledException"
        case "PROTOCOL": return "ProtocolViolationException"
        default: return "YuguException"
        }
    }

    func testEveryServerErrorCode() throws {
        let errors = try XCTUnwrap(spec["errors"]?.arrayValue)
        XCTAssertEqual(errors.count, 36)
        for e in errors {
            let code = e["code"]!.intValue!
            let category = ErrorCategory(rawValue: e["category"]!.stringValue!)
            let retryable = e["retryable"]!.boolValue!
            let http = e["http"]!.intValue!
            // fromCode
            let a = YuguErrors.fromCode(code)
            XCTAssertEqual(a.category, category, "category of \(code)")
            XCTAssertEqual(a.retryable, retryable, "retryable of \(code)")
            XCTAssertEqual(a.code, code)
            XCTAssertEqual(a.name, e["name"]?.stringValue)
            XCTAssertEqual(a.exceptionName, expectedException(e["category"]!.stringValue!), "\(code)")
            XCTAssertEqual(YuguErrors.isRetryable(a), retryable)
            // As a real platform error body with its HTTP status.
            let body = Data(#"{"code":\#(code),"message":"m\#(code)","timestamp":1}"#.utf8)
            let b = YuguErrors.fromHTTP(status: http, body: body, headers: [:])
            XCTAssertEqual(b.category, category, "HTTP category of \(code)")
            XCTAssertEqual(b.retryable, retryable, "HTTP retryable of \(code)")
            XCTAssertEqual(b.code, code)
            XCTAssertEqual(b.httpStatus, http)
            XCTAssertEqual(b.message, "m\(code)")
            XCTAssertEqual(b.rawBody, String(decoding: body, as: UTF8.self))
            // The business code wins over the HTTP status: same answer with HTTP 200 or 500.
            for status in [200, 500] {
                let c = YuguErrors.fromHTTP(status: status, body: body, headers: [:])
                XCTAssertEqual(c.category, category, "code \(code) with HTTP \(status)")
                XCTAssertEqual(c.retryable, retryable, "code \(code) with HTTP \(status)")
            }
        }
    }

    func testEveryWarningCode() throws {
        let warnings = try XCTUnwrap(spec["warnings"]?.arrayValue)
        XCTAssertEqual(warnings.count, 6)
        for w in warnings {
            let code = w["code"]!.intValue!
            let e = YuguErrors.fromWarning(code)
            XCTAssertEqual(e.category, .audio, "\(code)")
            XCTAssertTrue(e.isAudioQuality)
            XCTAssertEqual(e.exceptionName, "AudioQualityException")
            XCTAssertEqual(e.retryable, w["retryable"]!.boolValue!, "\(code)")
            XCTAssertEqual(e.name, w["name"]?.stringValue)
            XCTAssertNotNil(WarningCode(rawValue: code))
            // Parsed from a result, the platform message is kept.
            let parsed = ResultParser.parseWarnings([.number(Double(code))])
            XCTAssertEqual(parsed, [YuguWarning(code: code, message: w["message"]!.stringValue!)])
        }
        // Codes that exist only as warnings also resolve through fromCode.
        XCTAssertEqual(YuguErrors.fromCode(1001).category, .audio)
        XCTAssertEqual(YuguErrors.fromCode(1009).retryable, true)
    }

    /// 1004 and 1005 are both business errors and warnings in errors.json.
    func testCodeCollisionBetweenErrorsAndWarnings() {
        XCTAssertEqual(YuguErrors.fromCode(1004).category, .permission)
        XCTAssertEqual(YuguErrors.fromCode(1004).name, "USER_DISABLED")
        XCTAssertEqual(YuguErrors.fromWarning(1004).category, .audio)
        XCTAssertEqual(YuguErrors.fromWarning(1004).name, "AUDIO_NOISY")
        XCTAssertEqual(YuguErrors.fromWarning(1005).name, "AUDIO_INCOMPLETE")
        XCTAssertEqual(YuguErrors.fromHTTP(status: 400, body: Data(#"{"code":1005,"message":"locked"}"#.utf8)).category, .permission)
    }

    func testEveryLocalCode() throws {
        let locals = try XCTUnwrap(spec["local"]?.arrayValue)
        XCTAssertEqual(locals.count, 19)
        for l in locals {
            let code = l["code"]!.intValue!
            for e in [YuguErrors.local(code), YuguErrors.fromCode(code)] {
                XCTAssertEqual(e.category, ErrorCategory(rawValue: l["category"]!.stringValue!), "\(code)")
                XCTAssertEqual(e.retryable, [90001, 90002, 90007].contains(code), "\(code)")
                XCTAssertEqual(e.retryable, l["retryable"]!.boolValue!)
                XCTAssertEqual(e.httpStatus, 0)
            }
        }
    }

    func testHttpFallbackWithoutBusinessCode() throws {
        let fallback = try XCTUnwrap(spec["httpFallback"]?.objectValue)
        let retryableHttp = Set((spec["retryableHttp"]?.arrayValue ?? []).compactMap { $0.intValue })
        XCTAssertEqual(retryableHttp, [408, 425, 429, 500, 502, 503, 504])
        for (status, category) in fallback {
            let s = Int(status)!
            for body in [nil, Data("<html>bad gateway</html>".utf8), Data(#"{"message":"x"}"#.utf8), Data(#"{"code":0}"#.utf8)] {
                let e = YuguErrors.fromHTTP(status: s, body: body)
                XCTAssertEqual(e.category.rawValue, category.stringValue, "HTTP \(s)")
                XCTAssertEqual(e.retryable, retryableHttp.contains(s), "HTTP \(s)")
                XCTAssertEqual(e.code, 0)
            }
        }
        // Statuses missing from httpFallback map by class (DESIGN 2.4).
        for (status, category, retryable) in [
            (418, ErrorCategory.invalidParam, false), (451, .invalidParam, false), (505, .server, false), (599, .server, false),
            (0, .unknown, false), (302, .unknown, false), (600, .unknown, false),
        ] {
            let e = YuguErrors.fromHTTP(status: status, body: nil)
            XCTAssertEqual(e.category, category, "HTTP \(status)")
            XCTAssertEqual(e.retryable, retryable, "HTTP \(status)")
        }
    }

    func testUnknownBusinessCodeFallsBackToHttp() {
        let e = YuguErrors.fromHTTP(status: 503, body: Data(#"{"code":77777,"message":"new"}"#.utf8))
        XCTAssertEqual(e.code, 77777)
        XCTAssertEqual(e.category, .upstream)
        XCTAssertTrue(e.retryable)
        let f = YuguErrors.fromHTTP(status: 400, body: Data(#"{"code":77777}"#.utf8))
        XCTAssertEqual(f.category, .invalidParam)
        XCTAssertFalse(f.retryable)
        XCTAssertEqual(YuguErrors.fromCode(77777).category, .unknown)
    }

    func testFastAPIDetailShapes() {
        let a = YuguErrors.fromHTTP(status: 401, body: Data(#"{"detail":"[2001] missing appKey, timestamp or signature"}"#.utf8))
        XCTAssertEqual(a.code, 2001)
        XCTAssertEqual(a.category, .auth)
        XCTAssertFalse(a.retryable)
        let b = YuguErrors.fromHTTP(status: 500, body: Data(#"{"detail":"[2003] app key not found"}"#.utf8))
        XCTAssertEqual(b.category, .auth)
        XCTAssertEqual(b.code, 2003)
        let c = YuguErrors.fromHTTP(status: 422, body: Data(#"{"detail":[{"loc":["body","text"],"msg":"field required","type":"value_error"}]}"#.utf8))
        XCTAssertEqual(c.category, .invalidParam)
        XCTAssertEqual(c.message, "field required")
        let d = YuguErrors.fromHTTP(status: 502, body: Data(#"{"detail":"upstream timeout"}"#.utf8))
        XCTAssertEqual(d.category, .upstream)
        XCTAssertTrue(d.retryable)
        XCTAssertEqual(d.message, "upstream timeout")
        let e = YuguErrors.fromHTTP(status: 400, body: Data(#"{"errId":60003,"error":"coreType not supported"}"#.utf8))
        XCTAssertEqual(e.code, 60003)
        XCTAssertEqual(e.message, "coreType not supported")
        XCTAssertEqual(YuguErrors.bracketCode("[12x] a"), nil)
        XCTAssertEqual(YuguErrors.bracketCode("  [40001] a"), 40001)
    }

    func testRealErrorFixtures() {
        for (name, status, code, category) in [
            ("platform/error_native_bad_signature.json", 401, 2003, ErrorCategory.auth),
            ("platform/error_compat_pinyin_missing_refpinyin.json", 400, 40001, ErrorCategory.invalidParam),
        ] {
            let f = Fixture.json(name)
            XCTAssertEqual(f["status"]?.intValue, status)
            var headers: [String: String] = [:]
            for (k, v) in f["headers"]?.objectValue ?? [:] { headers[k] = v.stringValue }
            let e = YuguErrors.fromHTTP(status: status, body: Data(f["body"]!.stringValue!.utf8), headers: headers)
            XCTAssertEqual(e.code, code, name)
            XCTAssertEqual(e.category, category, name)
            XCTAssertFalse(e.retryable, name)
            XCTAssertFalse(e.message.isEmpty)
        }
    }

    func testWebSocketErrorFrames() {
        let upstream = YuguErrors.fromErrorFrame(["event": "error", "code": 50200, "message": "mock upstream error"])
        XCTAssertEqual(upstream.category, .upstream)
        XCTAssertTrue(upstream.retryable)
        XCTAssertEqual(upstream.message, "mock upstream error")
        XCTAssertTrue(YuguErrors.fromErrorFrame(["event": "error", "code": 40901, "message": "busy"]).retryable)
        XCTAssertTrue(YuguErrors.fromErrorFrame(["event": "error", "code": 42901, "message": "concurrency"]).retryable)
        XCTAssertFalse(YuguErrors.fromErrorFrame(["event": "error", "code": 40903, "message": "reused"]).retryable)
        let noCode = YuguErrors.fromErrorFrame(["event": "error", "message": "no audio"])
        XCTAssertFalse(noCode.retryable)
        XCTAssertEqual(noCode.code, 0)
        // The real native capture contains an error frame without code.
        let frames = Fixture.json("platform/ws_native_sentence_frames.json").arrayValue ?? []
        let err = frames.compactMap { $0["frame"] }.first { $0["event"]?.stringValue == "error" }
        XCTAssertEqual(err.map { YuguErrors.fromErrorFrame($0).retryable }, false)
    }

    func testRetryAfterParsing() {
        XCTAssertEqual(YuguErrors.parseRetryAfter("30"), 30000)
        XCTAssertEqual(YuguErrors.parseRetryAfter(" 1.5 "), 1500)
        let now = Date(timeIntervalSince1970: 1_791_447_600)
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        XCTAssertEqual(YuguErrors.parseRetryAfter(f.string(from: now.addingTimeInterval(5)), now: now), 5000)
        XCTAssertNil(YuguErrors.parseRetryAfter("soon"))
        let e = YuguErrors.fromHTTP(status: 429, body: Data(#"{"code":42900,"message":"排队超时"}"#.utf8), headers: ["Retry-After": "30"])
        XCTAssertEqual(e.retryAfterMs, 30000)
        XCTAssertTrue(e.isRateLimited)
    }

    func testRawBodyIsCutAt4KB() {
        let big = Data(repeating: UInt8(ascii: "a"), count: 10_000)
        XCTAssertEqual(YuguErrors.fromHTTP(status: 500, body: big).rawBody?.utf8.count, 4096)
        // A multi-byte character cut in half is dropped.
        var cjk = Data(repeating: UInt8(ascii: "b"), count: 4095)
        cjk.append(Data("中".utf8))
        let s = YuguErrors.truncatedText(cjk)
        XCTAssertEqual(s.utf8.count, 4095)
    }

    func testWrapMapsURLErrors() {
        XCTAssertEqual(YuguErrors.wrap(URLError(.timedOut)).code, 90002)
        XCTAssertTrue(YuguErrors.wrap(URLError(.timedOut)).retryable)
        XCTAssertEqual(YuguErrors.wrap(URLError(.cannotConnectToHost)).code, 90001)
        XCTAssertEqual(YuguErrors.wrap(URLError(.networkConnectionLost)).code, 90001)
        XCTAssertTrue(YuguErrors.isRetryable(URLError(.notConnectedToInternet)))
        XCTAssertEqual(YuguErrors.wrap(URLError(.cancelled)).code, 90003)
        XCTAssertEqual(YuguErrors.wrap(URLError(.unsupportedURL)).code, 90010)
        XCTAssertFalse(YuguErrors.isRetryable(URLError(.badURL)))
        XCTAssertEqual(YuguErrors.wrap(URLError(.serverCertificateUntrusted)).code, 90011)
        XCTAssertFalse(YuguErrors.wrap(URLError(.secureConnectionFailed)).retryable)
        XCTAssertEqual(YuguErrors.wrap(CancellationError()).code, 90003)
        XCTAssertEqual(YuguErrors.wrap(JSONParseError(message: "x", offset: 0)).code, 90005)
        struct Other: Error {}
        XCTAssertEqual(YuguErrors.wrap(Other()).code, 90001)
        let y = YuguErrors.local(90009)
        XCTAssertEqual(YuguErrors.wrap(y).code, 90009)
    }

    func testDebugDescriptionMasksSecretsInCause() {
        let cause = NSError(domain: NSURLErrorDomain, code: -1005, userInfo: ["NSErrorFailingURLStringKey": "wss://h/x?appKey=ak12345&signature=AbCdEf%2B&token=jwtvalue1"])
        let e = YuguErrors.wrap(cause)
        let d = e.debugDescription
        XCTAssertTrue(d.contains("cause="))
        XCTAssertFalse(d.contains("AbCdEf"), d)
        XCTAssertFalse(d.contains("jwtvalue1"), d)
        XCTAssertFalse(d.contains("ak12345"), d)
    }

    func testConvenienceFlagsAndDescriptions() {
        let e = YuguErrors.fromHTTP(status: 503, body: Data(#"{"code":50200,"message":"x"}"#.utf8)).with(idempotencyKey: "k1", attempts: 3)
        XCTAssertTrue(e.isServerError)
        XCTAssertFalse(e.isNetworkError || e.isTimeout || e.isAuthError || e.isPermissionDenied || e.isInvalidParameter)
        XCTAssertFalse(e.isNotFound || e.isConflict || e.isRateLimited || e.isQuotaExceeded || e.isAudioQuality)
        XCTAssertFalse(e.isIllegalState || e.isCancelled || e.isProtocolViolation)
        XCTAssertEqual(e.summary, "HTTP 503 code=50200")
        XCTAssertTrue(e.description.contains("UPSTREAM code=50200 http=503 retryable=true attempts=3"))
        XCTAssertTrue(e.debugDescription.contains("idempotencyKey=k1"))
        XCTAssertEqual(e.errorDescription, e.description)
        XCTAssertEqual(YuguErrors.local(90002).summary, "TIMEOUT code=90002")
        XCTAssertEqual(YuguErrors.local(12345).category, .unknown)
        XCTAssertEqual(YuguErrors.fromWarning(4242).category, .audio)
    }

    /// The generated ErrorTable.swift equals spec/errors.json (catches a stale generated file).
    func testGeneratedTableMatchesSpec() {
        func check(_ list: [JSONValue], _ table: [Int: ErrorTableEntry]) {
            XCTAssertEqual(list.count, table.count)
            for e in list {
                let t = table[e["code"]!.intValue!]
                XCTAssertEqual(t?.name, e["name"]?.stringValue)
                XCTAssertEqual(t?.category.rawValue, e["category"]?.stringValue)
                XCTAssertEqual(t?.retryable, e["retryable"]?.boolValue)
                XCTAssertEqual(t?.message, e["message"]?.stringValue)
            }
        }
        check(spec["errors"]!.arrayValue!, ErrorTable.errors)
        check(spec["warnings"]!.arrayValue!, ErrorTable.warnings)
        check(spec["local"]!.arrayValue!, ErrorTable.local)
        XCTAssertEqual(ErrorTable.httpFallback.count, spec["httpFallback"]?.objectValue?.count)
        XCTAssertEqual(Set(ErrorCategory.allCases.map { $0.rawValue }), Set(spec["categories"]!.arrayValue!.compactMap { $0.stringValue }))
    }
}
