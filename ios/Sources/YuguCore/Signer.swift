import Foundation

/// The platform signature (CONTRACT 0.2, DESIGN 5.2).
///
/// 1. Take the business parameters of the request (form fields, query parameters, the exact text
///    of the `config` part, or the top-level scalars of a JSON body). Files are never signed.
/// 2. Drop parameters whose value is `nil` or empty.
/// 3. Sort by key in UTF-16 code unit order (Java `TreeMap`, JavaScript `sort`).
/// 4. Join as `k1=v1&k2=v2` without URL encoding.
/// 5. `Base64(HMAC-SHA256(payload, secretKey))`, both UTF-8.
public enum YuguSigner {
    /// The text that is signed.
    public static func buildPayload(_ params: [String: String?]) -> String {
        params
            .compactMap { key, value -> (String, String)? in
                guard let value = value, !value.isEmpty else { return nil }
                return (key, value)
            }
            .sorted { $0.0.utf16.lexicographicallyPrecedes($1.0.utf16) }
            .map { "\($0.0)=\($0.1)" }
            .joined(separator: "&")
    }

    /// The text that is signed.
    public static func buildPayload(_ params: [String: String]) -> String {
        buildPayload(params.mapValues { Optional($0) })
    }

    /// Signature of the parameters.
    public static func sign(params: [String: String?], secretKey: String) -> String {
        sign(payload: buildPayload(params), secretKey: secretKey)
    }

    /// Signature of the parameters.
    public static func sign(params: [String: String], secretKey: String) -> String {
        sign(payload: buildPayload(params), secretKey: secretKey)
    }

    /// Signature of an already built payload.
    public static func sign(payload: String, secretKey: String) -> String {
        let mac = HMACSHA256.mac(key: Array(secretKey.utf8), message: Array(payload.utf8))
        return Data(mac).base64EncodedString()
    }

    /// Lowercase hex HMAC-SHA256, used in tests and diagnostics.
    static func hmacHex(payload: String, secretKey: String) -> String {
        HMACSHA256.mac(key: Array(secretKey.utf8), message: Array(payload.utf8)).hexString
    }
}

/// Authentication of every request (CONTRACT 0).
public enum YuguAuth: Sendable, Equatable {
    /// A platform user token (JWT). REST sends `Authorization: Bearer`, WebSocket sends `token`.
    case token(String)
    /// An API key pair. REST sends `X-App-Key`, `X-Timestamp`, `X-Nonce`, `X-Signature`; WebSocket
    /// sends the same values as query parameters.
    case appKey(String, secretKey: String)

    /// The appKey in log form, its first 4 characters followed by `***`.
    var maskedAppKey: String? {
        if case .appKey(let k, _) = self { return Redactor.mask(appKey: k) }
        return nil
    }
}

/// Builds authentication headers and query parameters with fresh timestamp and nonce.
struct RequestSigner {
    let auth: YuguAuth
    let random: YuguRandom
    let wallClock: () -> Date

    func timestamp() -> String {
        String(Int64(wallClock().timeIntervalSince1970))
    }

    func nonce() -> String {
        random.hex(byteCount: 8)
    }

    /// REST headers for the given signed parameters.
    func headers(signing params: [String: String]) -> [(String, String)] {
        switch auth {
        case .token(let jwt):
            return [("Authorization", "Bearer " + jwt)]
        case .appKey(let appKey, let secret):
            return [
                ("X-App-Key", appKey),
                ("X-Timestamp", timestamp()),
                ("X-Nonce", nonce()),
                ("X-Signature", YuguSigner.sign(params: params, secretKey: secret)),
            ]
        }
    }

    /// WebSocket handshake query: every parameter except `signature` is signed.
    func webSocketQuery(_ business: [(String, String)]) -> [(String, String)] {
        switch auth {
        case .token(let jwt):
            return business + [("token", jwt)]
        case .appKey(let appKey, let secret):
            var items: [(String, String)] = [("appKey", appKey), ("timestamp", timestamp()), ("nonce", nonce())]
            items += business
            var params: [String: String] = [:]
            for (k, v) in items { params[k] = v }
            items.append(("signature", YuguSigner.sign(params: params, secretKey: secret)))
            return items
        }
    }

    /// Strict query encoding: only RFC 3986 unreserved characters stay literal, so `+`, `/` and
    /// `=` of a Base64 signature reach the server unchanged.
    static func encodeQuery(_ items: [(String, String)]) -> String {
        items.map { percentEncode($0.0) + "=" + percentEncode($0.1) }.joined(separator: "&")
    }

    static func percentEncode(_ s: String) -> String {
        var out = ""
        for b in s.utf8 {
            switch b {
            case UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "a")...UInt8(ascii: "z"),
                UInt8(ascii: "0")...UInt8(ascii: "9"), UInt8(ascii: "-"), UInt8(ascii: "."),
                UInt8(ascii: "_"), UInt8(ascii: "~"):
                out.unicodeScalars.append(Unicode.Scalar(b))
            default:
                let hex = String(b, radix: 16, uppercase: true)
                out += "%" + (hex.count == 1 ? "0" + hex : hex)
            }
        }
        return out
    }
}
