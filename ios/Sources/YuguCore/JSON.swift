import Foundation

/// A parsed JSON value.
///
/// The SDK parses responses with its own RFC 8259 parser and writes request JSON with its own
/// writer, so parsing and number formatting behave identically on every Apple platform version
/// and on Linux.
public enum JSONValue: Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])
}

// MARK: - Accessors

extension JSONValue {
    /// Member of an object, `nil` for other values or a missing key.
    public subscript(key: String) -> JSONValue? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    /// Element of an array, `nil` for other values or an index out of range.
    public subscript(index: Int) -> JSONValue? {
        if case .array(let a) = self, index >= 0, index < a.count { return a[index] }
        return nil
    }

    public var isNull: Bool {
        if case .null = self { return true }
        return false
    }

    /// The string of a `.string` value.
    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    /// The number of a `.number` value, or a `.string` that holds a decimal number.
    public var doubleValue: Double? {
        switch self {
        case .number(let d): return d
        case .string(let s): return Double(s.trimmingCharacters(in: .whitespaces))
        default: return nil
        }
    }

    /// An integral number, or a `.string` that holds one.
    public var intValue: Int? {
        switch self {
        case .number(let d):
            guard d.isFinite, d.rounded(.towardZero) == d, abs(d) < 9.0e18 else { return nil }
            return Int(d)
        case .string(let s): return Int(s.trimmingCharacters(in: .whitespaces))
        default: return nil
        }
    }

    public var boolValue: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    public var arrayValue: [JSONValue]? {
        if case .array(let a) = self { return a }
        return nil
    }

    public var objectValue: [String: JSONValue]? {
        if case .object(let o) = self { return o }
        return nil
    }

    /// Text of a scalar as used in signatures and form fields: strings unchanged, numbers in
    /// plain decimal form, booleans `true` or `false`. `nil` for null, arrays and objects.
    public var scalarText: String? {
        switch self {
        case .string(let s): return s
        case .number(let d): return JSONWriter.formatNumber(d)
        case .bool(let b): return b ? "true" : "false"
        default: return nil
        }
    }

    /// Compact JSON text with object keys sorted, numbers in plain decimal form.
    public var jsonText: String {
        var out = ""
        JSONWriter.write(self, into: &out)
        return out
    }

    /// Decodes this value into a `Decodable` type with `JSONDecoder`.
    public func decode<T: Decodable>(_ type: T.Type, decoder: JSONDecoder = JSONDecoder()) throws -> T {
        try decoder.decode(T.self, from: Data(jsonText.utf8))
    }

    /// Parses UTF-8 JSON text.
    public static func parse(_ data: Data) throws -> JSONValue {
        var parser = JSONParser(bytes: [UInt8](data))
        return try parser.parseDocument()
    }

    /// Parses JSON text.
    public static func parse(_ text: String) throws -> JSONValue {
        var parser = JSONParser(bytes: Array(text.utf8))
        return try parser.parseDocument()
    }
}

extension JSONValue: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByFloatLiteral,
    ExpressibleByBooleanLiteral, ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral
{
    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(floatLiteral value: Double) { self = .number(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
    public init(dictionaryLiteral elements: (String, JSONValue)...) {
        var o: [String: JSONValue] = [:]
        for (k, v) in elements { o[k] = v }
        self = .object(o)
    }
}

extension JSONValue: CustomStringConvertible {
    public var description: String { jsonText }
}

// MARK: - Parser

/// Error thrown by `JSONValue.parse`.
public struct JSONParseError: Error, CustomStringConvertible {
    public let message: String
    public let offset: Int
    public var description: String { "JSON parse error at byte \(offset): \(message)" }
}

struct JSONParser {
    private let bytes: [UInt8]
    private var i = 0
    private var depth = 0
    private static let maxDepth = 512

    init(bytes: [UInt8]) {
        self.bytes = bytes
    }

    mutating func parseDocument() throws -> JSONValue {
        // Tolerate a UTF-8 byte order mark.
        if bytes.count >= 3, bytes[0] == 0xEF, bytes[1] == 0xBB, bytes[2] == 0xBF { i = 3 }
        let v = try parseValue()
        skipWhitespace()
        if i != bytes.count { throw fail("trailing characters") }
        return v
    }

    private func fail(_ message: String) -> JSONParseError {
        JSONParseError(message: message, offset: i)
    }

    private mutating func skipWhitespace() {
        while i < bytes.count {
            switch bytes[i] {
            case 0x20, 0x09, 0x0A, 0x0D: i += 1
            default: return
            }
        }
    }

    private mutating func parseValue() throws -> JSONValue {
        skipWhitespace()
        guard i < bytes.count else { throw fail("unexpected end of input") }
        switch bytes[i] {
        case UInt8(ascii: "{"): return try parseObject()
        case UInt8(ascii: "["): return try parseArray()
        case UInt8(ascii: "\""): return .string(try parseString())
        case UInt8(ascii: "t"): try expectLiteral("true"); return .bool(true)
        case UInt8(ascii: "f"): try expectLiteral("false"); return .bool(false)
        case UInt8(ascii: "n"): try expectLiteral("null"); return .null
        case UInt8(ascii: "-"), UInt8(ascii: "0")...UInt8(ascii: "9"): return .number(try parseNumber())
        default: throw fail("unexpected character")
        }
    }

    private mutating func expectLiteral(_ word: String) throws {
        let w = Array(word.utf8)
        guard i + w.count <= bytes.count, Array(bytes[i..<(i + w.count)]) == w else { throw fail("invalid literal") }
        i += w.count
    }

    private mutating func parseObject() throws -> JSONValue {
        depth += 1
        if depth > JSONParser.maxDepth { throw fail("nesting too deep") }
        defer { depth -= 1 }
        i += 1
        var out: [String: JSONValue] = [:]
        skipWhitespace()
        if i < bytes.count, bytes[i] == UInt8(ascii: "}") { i += 1; return .object(out) }
        while true {
            skipWhitespace()
            guard i < bytes.count, bytes[i] == UInt8(ascii: "\"") else { throw fail("expected object key") }
            let key = try parseString()
            skipWhitespace()
            guard i < bytes.count, bytes[i] == UInt8(ascii: ":") else { throw fail("expected ':'") }
            i += 1
            out[key] = try parseValue()
            skipWhitespace()
            guard i < bytes.count else { throw fail("unterminated object") }
            if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
            if bytes[i] == UInt8(ascii: "}") { i += 1; return .object(out) }
            throw fail("expected ',' or '}'")
        }
    }

    private mutating func parseArray() throws -> JSONValue {
        depth += 1
        if depth > JSONParser.maxDepth { throw fail("nesting too deep") }
        defer { depth -= 1 }
        i += 1
        var out: [JSONValue] = []
        skipWhitespace()
        if i < bytes.count, bytes[i] == UInt8(ascii: "]") { i += 1; return .array(out) }
        while true {
            out.append(try parseValue())
            skipWhitespace()
            guard i < bytes.count else { throw fail("unterminated array") }
            if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
            if bytes[i] == UInt8(ascii: "]") { i += 1; return .array(out) }
            throw fail("expected ',' or ']'")
        }
    }

    private mutating func parseHex4() throws -> UInt32 {
        guard i + 4 <= bytes.count else { throw fail("truncated \\u escape") }
        var v: UInt32 = 0
        for _ in 0..<4 {
            let c = bytes[i]
            let d: UInt32
            switch c {
            case UInt8(ascii: "0")...UInt8(ascii: "9"): d = UInt32(c - UInt8(ascii: "0"))
            case UInt8(ascii: "a")...UInt8(ascii: "f"): d = UInt32(c - UInt8(ascii: "a") + 10)
            case UInt8(ascii: "A")...UInt8(ascii: "F"): d = UInt32(c - UInt8(ascii: "A") + 10)
            default: throw fail("invalid hex digit")
            }
            v = v << 4 | d
            i += 1
        }
        return v
    }

    private static func appendUTF8(_ scalar: Unicode.Scalar, to out: inout [UInt8]) {
        out.append(contentsOf: Array(String(Character(scalar)).utf8))
    }

    private mutating func parseString() throws -> String {
        i += 1 // opening quote
        var out: [UInt8] = []
        while true {
            guard i < bytes.count else { throw fail("unterminated string") }
            let c = bytes[i]
            if c == UInt8(ascii: "\"") {
                i += 1
                return String(decoding: out, as: UTF8.self)
            }
            if c == UInt8(ascii: "\\") {
                i += 1
                guard i < bytes.count else { throw fail("unterminated escape") }
                let e = bytes[i]
                i += 1
                switch e {
                case UInt8(ascii: "\""): out.append(UInt8(ascii: "\""))
                case UInt8(ascii: "\\"): out.append(UInt8(ascii: "\\"))
                case UInt8(ascii: "/"): out.append(UInt8(ascii: "/"))
                case UInt8(ascii: "b"): out.append(0x08)
                case UInt8(ascii: "f"): out.append(0x0C)
                case UInt8(ascii: "n"): out.append(0x0A)
                case UInt8(ascii: "r"): out.append(0x0D)
                case UInt8(ascii: "t"): out.append(0x09)
                case UInt8(ascii: "u"):
                    var code = try parseHex4()
                    if code >= 0xD800 && code <= 0xDBFF {
                        // High surrogate, expect a low surrogate escape.
                        if i + 6 <= bytes.count, bytes[i] == UInt8(ascii: "\\"), bytes[i + 1] == UInt8(ascii: "u") {
                            let save = i
                            i += 2
                            let low = try parseHex4()
                            if low >= 0xDC00 && low <= 0xDFFF {
                                code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
                            } else {
                                i = save
                                code = 0xFFFD
                            }
                        } else {
                            code = 0xFFFD
                        }
                    } else if code >= 0xDC00 && code <= 0xDFFF {
                        code = 0xFFFD
                    }
                    JSONParser.appendUTF8(Unicode.Scalar(code) ?? "\u{FFFD}", to: &out)
                default:
                    throw fail("invalid escape")
                }
                continue
            }
            if c < 0x20 { throw fail("control character in string") }
            out.append(c)
            i += 1
        }
    }

    private mutating func parseNumber() throws -> Double {
        let start = i
        if bytes[i] == UInt8(ascii: "-") { i += 1 }
        guard i < bytes.count else { throw fail("invalid number") }
        if bytes[i] == UInt8(ascii: "0") {
            i += 1
        } else if bytes[i] >= UInt8(ascii: "1") && bytes[i] <= UInt8(ascii: "9") {
            while i < bytes.count, bytes[i] >= UInt8(ascii: "0"), bytes[i] <= UInt8(ascii: "9") { i += 1 }
        } else {
            throw fail("invalid number")
        }
        if i < bytes.count, bytes[i] == UInt8(ascii: ".") {
            i += 1
            let fracStart = i
            while i < bytes.count, bytes[i] >= UInt8(ascii: "0"), bytes[i] <= UInt8(ascii: "9") { i += 1 }
            if i == fracStart { throw fail("invalid fraction") }
        }
        if i < bytes.count, bytes[i] == UInt8(ascii: "e") || bytes[i] == UInt8(ascii: "E") {
            i += 1
            if i < bytes.count, bytes[i] == UInt8(ascii: "+") || bytes[i] == UInt8(ascii: "-") { i += 1 }
            let expStart = i
            while i < bytes.count, bytes[i] >= UInt8(ascii: "0"), bytes[i] <= UInt8(ascii: "9") { i += 1 }
            if i == expStart { throw fail("invalid exponent") }
        }
        let text = String(decoding: bytes[start..<i], as: UTF8.self)
        guard let d = Double(text) else { throw fail("invalid number") }
        return d
    }
}

// MARK: - Writer

/// Deterministic JSON writer. Numbers are written in plain decimal form without exponent, and
/// integral values without a fractional part, so the text equals what a server computes with
/// `String(value)` in JavaScript or `asText()` in Jackson for the same value (DESIGN 5.2).
public enum JSONWriter {
    /// Plain decimal text of a finite number, `nil` for NaN and infinity.
    ///
    /// `1.0` becomes `1`, `0.2` stays `0.2`, `1e-7` becomes `0.0000001`.
    public static func formatNumber(_ value: Double) -> String? {
        guard value.isFinite else { return nil }
        if value == 0 { return "0" }
        if value.rounded(.towardZero) == value && abs(value) < 9_007_199_254_740_992 {
            return String(Int64(value))
        }
        let s = value.description
        let negative = s.hasPrefix("-")
        var body = negative ? String(s.dropFirst()) : s
        var exponent = 0
        if let e = body.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            exponent = Int(body[body.index(after: e)...]) ?? 0
            body = String(body[..<e])
        }
        let intPart: Substring
        let fracPart: Substring
        if let dot = body.firstIndex(of: ".") {
            intPart = body[..<dot]
            fracPart = body[body.index(after: dot)...]
        } else {
            intPart = Substring(body)
            fracPart = ""
        }
        var digits = Array(intPart) + Array(fracPart)
        var point = intPart.count + exponent
        while digits.count > 1, digits.first == "0" {
            digits.removeFirst()
            point -= 1
        }
        var text: String
        if point <= 0 {
            text = "0." + String(repeating: "0", count: -point) + String(digits)
        } else if point >= digits.count {
            text = String(digits) + String(repeating: "0", count: point - digits.count)
        } else {
            text = String(digits[0..<point]) + "." + String(digits[point...])
        }
        if text.contains(".") {
            while text.hasSuffix("0") { text.removeLast() }
            if text.hasSuffix(".") { text.removeLast() }
        }
        return (negative ? "-" : "") + text
    }

    /// JSON string literal with the mandatory escapes only. Non-ASCII text stays UTF-8 and `/`
    /// is not escaped.
    public static func quote(_ s: String) -> String {
        var out = "\""
        out.reserveCapacity(s.utf8.count + 2)
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if scalar.value < 0x20 {
                    let hex = String(scalar.value, radix: 16)
                    out += "\\u" + String(repeating: "0", count: 4 - hex.count) + hex
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
        return out
    }

    static func write(_ value: JSONValue, into out: inout String) {
        switch value {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .number(let d): out += formatNumber(d) ?? "null"
        case .string(let s): out += quote(s)
        case .array(let a):
            out += "["
            for (n, v) in a.enumerated() {
                if n > 0 { out += "," }
                write(v, into: &out)
            }
            out += "]"
        case .object(let o):
            out += "{"
            for (n, k) in o.keys.sorted().enumerated() {
                if n > 0 { out += "," }
                out += quote(k)
                out += ":"
                write(o[k]!, into: &out)
            }
            out += "}"
        }
    }

    /// Returns `true` when the value and everything inside it can be written (no NaN or infinity).
    static func isWritable(_ value: JSONValue) -> Bool {
        switch value {
        case .number(let d): return d.isFinite
        case .array(let a): return a.allSatisfy(isWritable)
        case .object(let o): return o.values.allSatisfy(isWritable)
        default: return true
        }
    }
}

/// Builds a JSON object with keys in insertion order. Used for every request body so that the
/// exact text that is sent is also the text that is signed.
struct JSONObjectBuilder {
    private(set) var entries: [(key: String, value: JSONValue)] = []

    /// Adds a member. `nil` values are skipped. A key that is already present is replaced in place.
    mutating func set(_ key: String, _ value: JSONValue?) {
        guard let value = value else { return }
        if let idx = entries.firstIndex(where: { $0.key == key }) {
            entries[idx].value = value
        } else {
            entries.append((key, value))
        }
    }

    mutating func set(_ key: String, _ value: String?) { set(key, value.map { JSONValue.string($0) }) }
    mutating func set(_ key: String, _ value: Int?) { set(key, value.map { JSONValue.number(Double($0)) }) }
    mutating func set(_ key: String, _ value: Double?) { set(key, value.map { JSONValue.number($0) }) }
    mutating func set(_ key: String, _ value: Bool?) { set(key, value.map { JSONValue.bool($0) }) }

    /// Adds members of `extra` in sorted key order, skipping keys already present.
    mutating func merge(_ extra: [String: JSONValue]) {
        for k in extra.keys.sorted() where !entries.contains(where: { $0.key == k }) {
            entries.append((k, extra[k]!))
        }
    }

    /// `true` when every number is finite.
    var isWritable: Bool { entries.allSatisfy { JSONWriter.isWritable($0.value) } }

    var text: String {
        var out = "{"
        for (n, e) in entries.enumerated() {
            if n > 0 { out += "," }
            out += JSONWriter.quote(e.key)
            out += ":"
            JSONWriter.write(e.value, into: &out)
        }
        out += "}"
        return out
    }

    /// Top-level non-null scalar members as signature parameters (DESIGN 5.2, JSON-body calls).
    var signableScalars: [String: String] {
        var out: [String: String] = [:]
        for e in entries {
            if let t = e.value.scalarText { out[e.key] = t }
        }
        return out
    }
}
