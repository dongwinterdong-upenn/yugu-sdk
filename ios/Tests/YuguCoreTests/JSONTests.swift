import XCTest
@testable import YuguCore

final class JSONTests: XCTestCase {
    func testNumberFormatting() {
        let cases: [(Double, String)] = [
            (0, "0"), (-0.0, "0"), (1, "1"), (1.0, "1"), (-3, "-3"), (100, "100"), (0.2, "0.2"), (0.1 + 0.2, "0.30000000000000004"),
            (123.5, "123.5"), (-0.5, "-0.5"), (1e-7, "0.0000001"), (1.5e-10, "0.00000000015"), (1e16, "10000000000000000"),
            (1.2345678901234568e20, "123456789012345680000"), (9007199254740992, "9007199254740992"), (2.5e-3, "0.0025"),
        ]
        for (v, s) in cases {
            XCTAssertEqual(JSONWriter.formatNumber(v), s, "\(v)")
        }
        XCTAssertNil(JSONWriter.formatNumber(.nan))
        XCTAssertNil(JSONWriter.formatNumber(.infinity))
        // Never an exponent.
        for v in [1e-300, Double.leastNonzeroMagnitude, 1e300, 3.14159e25, -2.2e-8] {
            let s = JSONWriter.formatNumber(v)!
            XCTAssertFalse(s.contains("e") || s.contains("E"), s)
            XCTAssertEqual(Double(s), v, s)
        }
    }

    func testQuoteEscapesOnlyWhatJSONRequires() {
        XCTAssertEqual(JSONWriter.quote("a\"b\\c\n\r\t\u{08}\u{0C}\u{01}/中文😀"), "\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0001/中文😀\"")
    }

    func testParseScalarsAndStructures() throws {
        let v = try JSONValue.parse(#"{"a":1,"b":[true,false,null,"x"],"c":{"d":-1.5e2},"e":"\u4e2d\ud83d\ude00\n"}"#)
        XCTAssertEqual(v["a"]?.intValue, 1)
        XCTAssertEqual(v["b"]?[0]?.boolValue, true)
        XCTAssertEqual(v["b"]?[1]?.boolValue, false)
        XCTAssertEqual(v["b"]?[2]?.isNull, true)
        XCTAssertEqual(v["b"]?[3]?.stringValue, "x")
        XCTAssertNil(v["b"]?[4])
        XCTAssertEqual(v["c"]?["d"]?.doubleValue, -150)
        XCTAssertEqual(v["e"]?.stringValue, "中😀\n")
        XCTAssertNil(v["missing"])
        XCTAssertNil(v["a"]?["x"])
    }

    func testParseErrors() {
        for bad in ["", "{", "[1,]", "{\"a\" 1}", "01", "1.", "-", "tru", "\"abc", "{\"a\":1} x", "\"\u{01}\"", "[\"\\x\"]", "1e", "{1:2}"] {
            XCTAssertThrowsError(try JSONValue.parse(bad), bad)
        }
        let deep = String(repeating: "[", count: 600) + String(repeating: "]", count: 600)
        XCTAssertThrowsError(try JSONValue.parse(deep))
    }

    func testLoneSurrogatesBecomeReplacement() throws {
        XCTAssertEqual(try JSONValue.parse(#""\ud800x""#).stringValue, "\u{FFFD}x")
        XCTAssertEqual(try JSONValue.parse(#""\udc00""#).stringValue, "\u{FFFD}")
        XCTAssertEqual(try JSONValue.parse(#""\ud800\u0041""#).stringValue, "\u{FFFD}A")
    }

    func testByteOrderMarkAccepted() throws {
        var d = Data([0xEF, 0xBB, 0xBF])
        d.append(Data(#"{"a":2}"#.utf8))
        XCTAssertEqual(try JSONValue.parse(d)["a"]?.intValue, 2)
    }

    func testAccessorsConvertNumericStrings() {
        let v: JSONValue = ["s": "12", "d": "1.5", "x": "abc", "n": 3.5]
        XCTAssertEqual(v["s"]?.intValue, 12)
        XCTAssertEqual(v["d"]?.doubleValue, 1.5)
        XCTAssertNil(v["x"]?.doubleValue)
        XCTAssertNil(v["n"]?.intValue)
        XCTAssertEqual(v["n"]?.scalarText, "3.5")
        XCTAssertNil(JSONValue.array([]).scalarText)
        XCTAssertEqual(JSONValue.bool(true).scalarText, "true")
        XCTAssertEqual(v.objectValue?.count, 4)
        XCTAssertEqual(JSONValue.array([1, 2]).arrayValue?.count, 2)
    }

    func testRoundTripOfEveryFixture() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Fixture.dir.appendingPathComponent("platform").path)
        XCTAssertGreaterThanOrEqual(names.filter { $0.hasSuffix(".json") }.count, 18)
        for name in names where name.hasSuffix(".json") {
            let v = Fixture.json("platform/" + name)
            let again = try JSONValue.parse(v.jsonText)
            XCTAssertEqual(again, v, name)
            XCTAssertEqual(v.description, v.jsonText)
        }
    }

    func testObjectBuilderOrderAndReplace() {
        var b = JSONObjectBuilder()
        b.set("z", 1)
        b.set("a", "x")
        b.set("z", 2)
        b.set("skip", nil as String?)
        b.merge(["m": true, "a": "ignored"])
        XCTAssertEqual(b.text, #"{"z":2,"a":"x","m":true}"#)
        XCTAssertEqual(b.signableScalars, ["z": "2", "a": "x", "m": "true"])
    }

    func testDecodeIntoDecodable() throws {
        struct T: Decodable { let a: Int; let b: String }
        let v: JSONValue = ["a": 3, "b": "y"]
        let t = try v.decode(T.self)
        XCTAssertEqual(t.a, 3)
        XCTAssertEqual(t.b, "y")
    }
}
