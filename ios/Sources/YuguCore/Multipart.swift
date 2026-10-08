import Foundation

/// Hand-built `multipart/form-data` body (DESIGN 5.2).
///
/// A text part has no `filename`, so the platform reads it as a form parameter and signs its
/// exact text. The `config` part of a native evaluation is such a text part with
/// `Content-Type: application/json; charset=utf-8`. Audio and image parts carry a filename and are
/// never signed.
struct MultipartBuilder {
    let boundary: String
    private var parts: [(header: String, body: Data)] = []

    init(boundary: String) {
        self.boundary = boundary
    }

    /// `----YuguFormBoundary` followed by 32 random hex characters.
    static func makeBoundary(_ random: YuguRandom) -> String {
        "----YuguFormBoundary" + random.hex(byteCount: 16)
    }

    var contentType: String { "multipart/form-data; boundary=\(boundary)" }

    /// Plain form field: no filename, no Content-Type.
    mutating func addField(name: String, value: String) {
        parts.append(("Content-Disposition: form-data; name=\"\(MultipartBuilder.escape(name))\"\r\n", Data(value.utf8)))
    }

    /// JSON form field: no filename, `Content-Type: application/json; charset=utf-8`.
    mutating func addJSONField(name: String, json: String) {
        parts.append((
            "Content-Disposition: form-data; name=\"\(MultipartBuilder.escape(name))\"\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n",
            Data(json.utf8)
        ))
    }

    /// File part.
    mutating func addFile(name: String, filename: String, contentType: String, data: Data) {
        parts.append((
            "Content-Disposition: form-data; name=\"\(MultipartBuilder.escape(name))\"; filename=\"\(MultipartBuilder.escape(filename))\"\r\n"
                + "Content-Type: \(contentType)\r\n",
            data
        ))
    }

    /// `true` when the boundary does not occur inside any part.
    var boundaryIsSafe: Bool {
        let marker = Data(("--" + boundary).utf8)
        return !parts.contains { $0.body.range(of: marker) != nil }
    }

    func build() -> Data {
        var out = Data()
        for p in parts {
            out.append(Data("--\(boundary)\r\n".utf8))
            out.append(Data(p.header.utf8))
            out.append(Data("\r\n".utf8))
            out.append(p.body)
            out.append(Data("\r\n".utf8))
        }
        out.append(Data("--\(boundary)--\r\n".utf8))
        return out
    }

    /// Escapes a name for a quoted Content-Disposition parameter (HTML form encoding rules).
    static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "\"", with: "%22")
            .replacingOccurrences(of: "\r", with: "%0D")
            .replacingOccurrences(of: "\n", with: "%0A")
    }
}
