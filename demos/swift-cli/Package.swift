// swift-tools-version:5.9
//
// yugu-eval: evaluates a WAV file with YuguCore from the command line (macOS and Linux).
//
// The dependency is the published package. To build against a local checkout instead, set
// YUGU_SDK_PATH to the directory that holds its Package.swift, as CI does.
import Foundation
import PackageDescription

let localPath = ProcessInfo.processInfo.environment["YUGU_SDK_PATH"] ?? ""
let sdkDependency: Package.Dependency
let sdkPackage: String
if localPath.isEmpty {
    sdkDependency = .package(url: "https://open.shengzhiai.com/git/yugu-ios-sdk.git", from: "2.0.0")
    sdkPackage = "yugu-ios-sdk"
} else {
    sdkDependency = .package(path: localPath)
    sdkPackage = URL(fileURLWithPath: localPath).lastPathComponent
}

let package = Package(
    name: "yugu-eval",
    platforms: [.macOS(.v11)],
    dependencies: [sdkDependency],
    targets: [
        .executableTarget(
            name: "yugu-eval",
            dependencies: [.product(name: "YuguCore", package: sdkPackage)],
            path: "Sources/yugu-eval"
        )
    ]
)
