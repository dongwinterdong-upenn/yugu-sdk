import Foundation
#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
@testable import YuguCore

/// Test-only RFC 6455 client over POSIX sockets, `ws://` with an IPv4 or `localhost` host.
///
/// swift-corelibs-foundation on this CI cannot open WebSockets (libcurl without ws), so the
/// integration tests use this transport to run the real session state machine on the wire
/// against the mock platform. It is not part of the SDK.
final class PosixWebSocketTransport: YuguWebSocketTransport {
    private let lock = NSLock()
    private var fd: Int32 = -1
    private var onEvent: ((YuguWebSocketEvent) -> Void)?
    private var finished = false
    private var pingWaiters: [(Error?) -> Void] = []
    private let writeLock = NSLock()
    /// Test hook: abort the TCP connection without a close frame (network switch).
    func resetConnection() {
        let f = lock.sync { fd }
        if f >= 0 { _ = shutdown(f, Int32(SHUT_RDWR)) }
    }

    func connect(url: URL, headers: [String: String], onEvent: @escaping (YuguWebSocketEvent) -> Void) {
        lock.sync { self.onEvent = onEvent }
        let thread = Thread { [self] in self.run(url: url, headers: headers) }
        thread.start()
    }

    private func emit(_ e: YuguWebSocketEvent, final: Bool = false) {
        let h: ((YuguWebSocketEvent) -> Void)? = lock.sync {
            if finished { return nil }
            if final {
                finished = true
                let h = onEvent
                onEvent = nil
                return h
            }
            return onEvent
        }
        h?(e)
        if final { failPings(URLError(.networkConnectionLost)) }
    }

    private func failPings(_ e: Error) {
        let waiters: [(Error?) -> Void] = lock.sync {
            let w = pingWaiters
            pingWaiters.removeAll()
            return w
        }
        waiters.forEach { $0(e) }
    }

    private func run(url: URL, headers: [String: String]) {
        let host = url.host == "localhost" ? "127.0.0.1" : (url.host ?? "127.0.0.1")
        let port = url.port ?? 80
        #if os(Linux)
        let s = socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
        #else
        let s = socket(AF_INET, SOCK_STREAM, 0)
        #endif
        guard s >= 0 else { return emit(.failed(URLError(.cannotConnectToHost)), final: true) }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(UInt16(port)).bigEndian
        #if canImport(Darwin)
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        var one: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        #endif
        guard inet_pton(AF_INET, host, &addr.sin_addr) == 1 else {
            Glue.close(s)
            return emit(.failed(URLError(.cannotFindHost)), final: true)
        }
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Glue.connect(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard rc == 0 else {
            Glue.close(s)
            return emit(.failed(URLError(.cannotConnectToHost)), final: true)
        }
        let abandoned: Bool = lock.sync {
            if finished { return true }
            fd = s
            return false
        }
        if abandoned {
            Glue.close(s)
            return
        }
        // Handshake.
        let key = Data((0..<16).map { _ in UInt8.random(in: 0...255) }).base64EncodedString()
        var target = url.path.isEmpty ? "/" : url.path
        if let q = url.query { target += "?" + q }
        var req = "GET \(target) HTTP/1.1\r\nHost: \(host):\(port)\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        req += "Sec-WebSocket-Key: \(key)\r\nSec-WebSocket-Version: 13\r\n"
        for (k, v) in headers { req += "\(k): \(v)\r\n" }
        req += "\r\n"
        guard write(Data(req.utf8)) else { return finish(code: nil) }
        var buffer = Data()
        var status = 0
        while true {
            guard let chunk = read() else { return finish(code: nil) }
            buffer.append(chunk)
            if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
                let head = String(decoding: buffer[..<end.lowerBound], as: UTF8.self)
                status = Int(head.split(separator: " ").dropFirst().first ?? "") ?? 0
                buffer = Data(buffer[end.upperBound...])
                break
            }
        }
        guard status == 101 else {
            closeSocket()
            return emit(.failed(YuguErrors.fromHTTP(status: status, body: nil)), final: true)
        }
        emit(.opened)
        // Frames.
        var message = Data()
        var messageOpcode: UInt8 = 0
        while true {
            while let frame = Self.parseFrame(&buffer) {
                switch frame.opcode {
                case 0x0:
                    message.append(frame.payload)
                case 0x1, 0x2:
                    message = frame.payload
                    messageOpcode = frame.opcode
                case 0x8:
                    let p = frame.payload
                    let code = p.count >= 2 ? Int(p[p.startIndex]) << 8 | Int(p[p.startIndex + 1]) : 1005
                    let reason = p.count > 2 ? String(decoding: p.dropFirst(2), as: UTF8.self) : nil
                    _ = write(Self.frame(opcode: 0x8, payload: p.prefix(2)))
                    closeSocket()
                    return emit(.closed(code: code, reason: reason), final: true)
                case 0x9:
                    _ = write(Self.frame(opcode: 0xA, payload: frame.payload))
                    continue
                case 0xA:
                    let w: ((Error?) -> Void)? = lock.sync { pingWaiters.isEmpty ? nil : pingWaiters.removeFirst() }
                    w?(nil)
                    continue
                default:
                    continue
                }
                if frame.fin && (frame.opcode != 0x0 || messageOpcode != 0) {
                    if messageOpcode == 0x1 {
                        emit(.text(String(decoding: message, as: UTF8.self)))
                    } else {
                        emit(.binary(message))
                    }
                    message = Data()
                    messageOpcode = 0
                }
            }
            guard let chunk = read() else { return finish(code: nil) }
            buffer.append(chunk)
        }
    }

    private func finish(code: Int?) {
        closeSocket()
        emit(.failed(URLError(.networkConnectionLost)), final: true)
    }

    private func closeSocket() {
        let f: Int32 = lock.sync {
            let f = fd
            fd = -1
            return f
        }
        if f >= 0 {
            _ = shutdown(f, Int32(SHUT_RDWR))
            Glue.close(f)
        }
    }

    private func read() -> Data? {
        let f = lock.sync { fd }
        guard f >= 0 else { return nil }
        var buf = [UInt8](repeating: 0, count: 65536)
        let n = recv(f, &buf, buf.count, 0)
        if n <= 0 { return nil }
        return Data(buf[0..<n])
    }

    private func write(_ d: Data) -> Bool {
        writeLock.lock()
        defer { writeLock.unlock() }
        let f = lock.sync { fd }
        guard f >= 0 else { return false }
        var sent = 0
        let bytes = [UInt8](d)
        while sent < bytes.count {
            #if os(Linux)
            let n = bytes[sent...].withUnsafeBufferPointer { Glue.send(f, $0.baseAddress, $0.count, Int32(MSG_NOSIGNAL)) }
            #else
            let n = bytes[sent...].withUnsafeBufferPointer { Glue.send(f, $0.baseAddress, $0.count, 0) }
            #endif
            if n <= 0 { return false }
            sent += n
        }
        return true
    }

    struct Frame {
        let fin: Bool
        let opcode: UInt8
        let payload: Data
    }

    static func parseFrame(_ buffer: inout Data) -> Frame? {
        let b = [UInt8](buffer.prefix(14))
        guard b.count >= 2 else { return nil }
        let fin = b[0] & 0x80 != 0
        let opcode = b[0] & 0x0F
        let masked = b[1] & 0x80 != 0
        var len = Int(b[1] & 0x7F)
        var pos = 2
        if len == 126 {
            guard b.count >= 4 else { return nil }
            len = Int(b[2]) << 8 | Int(b[3])
            pos = 4
        } else if len == 127 {
            guard b.count >= 10 else { return nil }
            len = 0
            for i in 2..<10 { len = len << 8 | Int(b[i]) }
            pos = 10
        }
        var mask: [UInt8] = []
        if masked {
            guard b.count >= pos + 4 else { return nil }
            mask = Array(b[pos..<(pos + 4)])
            pos += 4
        }
        guard buffer.count >= pos + len else { return nil }
        let start = buffer.startIndex
        var payload = Data(buffer[(start + pos)..<(start + pos + len)])
        if masked {
            for i in 0..<payload.count { payload[payload.startIndex + i] ^= mask[i % 4] }
        }
        buffer = Data(buffer[(start + pos + len)...])
        return Frame(fin: fin, opcode: opcode, payload: payload)
    }

    /// A masked client frame.
    static func frame(opcode: UInt8, payload: Data) -> Data {
        var out = Data([0x80 | opcode])
        let n = payload.count
        if n < 126 {
            out.append(0x80 | UInt8(n))
        } else if n < 65536 {
            out.append(0x80 | 126)
            out.append(UInt8(n >> 8))
            out.append(UInt8(n & 0xFF))
        } else {
            out.append(0x80 | 127)
            for shift in stride(from: 56, through: 0, by: -8) { out.append(UInt8((n >> shift) & 0xFF)) }
        }
        let mask = (0..<4).map { _ in UInt8.random(in: 0...255) }
        out.append(contentsOf: mask)
        var i = 0
        for byte in payload {
            out.append(byte ^ mask[i % 4])
            i += 1
        }
        return out
    }

    func send(text: String, completion: @escaping (Error?) -> Void) {
        completion(write(Self.frame(opcode: 0x1, payload: Data(text.utf8))) ? nil : URLError(.networkConnectionLost))
    }

    func send(data: Data, completion: @escaping (Error?) -> Void) {
        completion(write(Self.frame(opcode: 0x2, payload: data)) ? nil : URLError(.networkConnectionLost))
    }

    func sendPing(completion: @escaping (Error?) -> Void) {
        lock.sync { pingWaiters.append(completion) }
        if !write(Self.frame(opcode: 0x9, payload: Data("yugu".utf8))) {
            failPings(URLError(.networkConnectionLost))
        }
    }

    func close(code: Int, reason: String?) {
        let already: Bool = lock.sync {
            let a = finished
            finished = true
            onEvent = nil
            return a
        }
        if !already {
            var p = Data([UInt8(code >> 8), UInt8(code & 0xFF)])
            if let r = reason { p.append(Data(r.utf8.prefix(100))) }
            _ = write(Self.frame(opcode: 0x8, payload: p))
        }
        closeSocket()
        failPings(URLError(.cancelled))
    }
}

/// Unqualified POSIX calls that would otherwise resolve to the methods above.
enum Glue {
    static func connect(_ s: Int32, _ a: UnsafePointer<sockaddr>, _ l: socklen_t) -> Int32 {
        #if canImport(Glibc)
        return Glibc.connect(s, a, l)
        #else
        return Darwin.connect(s, a, l)
        #endif
    }

    static func close(_ s: Int32) {
        #if canImport(Glibc)
        _ = Glibc.close(s)
        #else
        _ = Darwin.close(s)
        #endif
    }

    static func send(_ s: Int32, _ p: UnsafeRawPointer?, _ n: Int, _ flags: Int32) -> Int {
        #if canImport(Glibc)
        return Glibc.send(s, p, n, flags)
        #else
        return Darwin.send(s, p, n, flags)
        #endif
    }
}
