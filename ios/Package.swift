// swift-tools-version:5.9
//
// YuguSDK, the iOS and macOS SDK of the Yugu speech evaluation platform.
//
//   YuguCore  Foundation only. REST, WebSocket sessions, retry, idempotency, signing, errors,
//             audio precheck. Builds and tests on Linux.
//   YuguSDK   Apple platforms. AVAudioEngine recorder, re-exports YuguCore.
//
// Published as https://open.shengzhiai.com/git/yugu-ios-sdk.git, tag 2.0.0.
import PackageDescription

let package = Package(
    name: "YuguSDK",
    platforms: [
        .iOS(.v13),
        .macOS(.v11),
    ],
    products: [
        .library(name: "YuguCore", targets: ["YuguCore"]),
        .library(name: "YuguSDK", targets: ["YuguSDK"]),
    ],
    targets: [
        .target(
            name: "YuguCore",
            path: "Sources/YuguCore"
        ),
        .target(
            name: "YuguSDK",
            dependencies: ["YuguCore"],
            path: "Sources/YuguSDK"
        ),
        .testTarget(
            name: "YuguCoreTests",
            dependencies: ["YuguCore"],
            path: "Tests/YuguCoreTests",
            resources: [.copy("Fixtures")]
        ),
    ]
)
