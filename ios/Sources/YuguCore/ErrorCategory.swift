import Foundation

/// Error category shared by every Yugu SDK (DESIGN 2.4). Raw values equal the category names in
/// `spec/errors.json`; the generated `ErrorTable.swift` refers to the camel-cased cases.
public enum ErrorCategory: String, Sendable, CaseIterable, CustomStringConvertible {
    case network = "NETWORK"
    case timeout = "TIMEOUT"
    case auth = "AUTH"
    case permission = "PERMISSION"
    case invalidParam = "INVALID_PARAM"
    case notFound = "NOT_FOUND"
    case conflict = "CONFLICT"
    case rateLimit = "RATE_LIMIT"
    case quota = "QUOTA"
    case server = "SERVER"
    case upstream = "UPSTREAM"
    case audio = "AUDIO"
    case state = "STATE"
    case cancelled = "CANCELLED"
    case `protocol` = "PROTOCOL"
    case unknown = "UNKNOWN"

    public var description: String { rawValue }
}
