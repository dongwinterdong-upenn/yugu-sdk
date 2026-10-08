// swift-tools-version:5.9
//
// STKouyuEngine: drop-in replacement of the Shengtong (声通) iOS framework STKouyuEngine.framework
// that evaluates on the Yugu (优谷雅言) cloud platform. Published as
// https://open.shengzhiai.com/git/stkouyu-ios-compat.git, tag 2.0.0.
//
// Copyright 2026 优谷雅言 open.shengzhiai.com
// SPDX-License-Identifier: Apache-2.0
import PackageDescription

let package = Package(
    name: "STKouyuEngine",
    platforms: [
        .iOS(.v12),
        .macOS(.v10_15),
    ],
    products: [
        .library(name: "STKouyuEngine", targets: ["STKouyuEngine"]),
    ],
    targets: [
        .target(
            name: "STKouyuEngine",
            path: "Sources/STKouyuEngine",
            publicHeadersPath: "include",
            cSettings: [
                .headerSearchPath("core"),
            ],
            linkerSettings: [
                .linkedFramework("Foundation"),
                .linkedFramework("AVFoundation"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("UIKit", .when(platforms: [.iOS])),
            ]
        ),
        // Test support, written for a macOS runner (ci/ios-stcompat-macos.sh), not executed on the
        // Linux CI. SwiftPM does not run Objective-C test targets, so the Objective-C checks are a
        // plain target that the Swift test target calls; no product exposes it.
        .target(
            name: "STKouyuEngineObjCSupport",
            dependencies: ["STKouyuEngine"],
            path: "Tests/STKouyuEngineObjCSupport",
            publicHeadersPath: "include"
        ),
        .testTarget(
            name: "STKouyuEngineTests",
            dependencies: ["STKouyuEngine", "STKouyuEngineObjCSupport"],
            path: "Tests/STKouyuEngineTests"
        ),
    ]
)
