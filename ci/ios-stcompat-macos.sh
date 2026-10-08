#!/usr/bin/env bash
# Copyright 2026 优谷雅言 open.shengzhiai.com
# SPDX-License-Identifier: Apache-2.0
#
# CI of the iOS Shengtong drop-in package (ios-stcompat) on a macOS runner with Xcode 15 or later.
# This is the authoritative build of the Objective-C layer. It has not been executed yet: the
# Linux CI host has no Apple SDK.
#
#   ci/ios-stcompat-macos.sh
#
# Environment:
#   CI_OUT               output directory (default ./ci-out/ios-stcompat-macos)
#   ST_ORIGINAL_HEADERS  Shengtong STKouyuEngine.framework/Headers to diff against (optional)
#   ST_ORIGINAL_SKEGN    Shengtong skegn.h (optional)
#   IOS_SIMULATOR        simulator for xcodebuild test, e.g. "iPhone 15" (optional)
#   YUGU_SANDBOX_APPKEY  sandbox keys (SANDBOX.md): when both are set, SandboxTests in swift test
#   YUGU_SANDBOX_SECRET  runs three end-to-end cases against the real platform through the
#                        Objective-C API, at most 5 calls; otherwise it is skipped
#   YUGU_SANDBOX_BASE    sandbox base URL (default https://open.shengzhiai.com)
#
# Steps: Linux core steps (ci/ios-stcompat.sh, with clang, sandbox keys removed so the cases run
# once, through the Objective-C API), swift build for macOS, xcodebuild for iOS device and
# simulator (deployment target iOS 12), swift test with coverage against the mock platform (Swift
# and Objective-C XCTest targets, plus the sandbox cases when the keys are set), optional
# xcodebuild test on an iOS simulator. The last line is "COVERAGE <pct>" of the STKouyuEngine
# target from llvm-cov.
set -uo pipefail

if [ "$(uname -s)" != "Darwin" ] || ! command -v xcrun >/dev/null 2>&1; then
    echo "ci/ios-stcompat-macos.sh needs macOS with Xcode (xcrun). On Linux run ci/ios-stcompat.sh."
    echo "COVERAGE 0.00"
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="$ROOT/ios-stcompat"
OUT="${CI_OUT:-./ci-out/ios-stcompat-macos}"
mkdir -p "$OUT/logs"
OUT="$(cd "$OUT" && pwd)"
FAILED=0
SUMMARY=()
COVERAGE="0.00"
MOCK_PID=""

cleanup() {
    if [ -n "$MOCK_PID" ] && kill -0 "$MOCK_PID" 2>/dev/null; then
        kill "$MOCK_PID" 2>/dev/null
        wait "$MOCK_PID" 2>/dev/null
    fi
}
trap cleanup EXIT INT TERM

run_step() {
    local name="$1"
    shift
    local log="$OUT/logs/$name.log"
    local t0 t1 status
    t0=$(date +%s)
    echo "== $name"
    if "$@" >"$log" 2>&1; then
        status=ok
    else
        local rc=$?
        if [ "$rc" -eq 77 ]; then status=skipped; else status=FAIL; FAILED=1; fi
    fi
    t1=$(date +%s)
    tail -n 30 "$log"
    SUMMARY+=("$(printf '%-18s %-8s %4ss' "$name" "$status" "$((t1 - t0))")")
    echo "-- $name: $status"
}


step_toolchain() {
    sw_vers
    xcodebuild -version
    swift --version
    node --version
}

step_linux_core() {
    # the sandbox cases run in swift test through the Objective-C API, not a second time here
    env -u YUGU_SANDBOX_APPKEY -u YUGU_SANDBOX_SECRET CC="$(xcrun -f clang)" CI_OUT="$OUT/core" "$ROOT/ci/ios-stcompat.sh"
}

step_swift_build_macos() {
    (cd "$PKG" && swift build -c debug)
}

step_xcodebuild_ios() {
    (cd "$PKG" && xcodebuild -scheme STKouyuEngine -destination 'generic/platform=iOS' \
        -derivedDataPath "$OUT/DerivedData" IPHONEOS_DEPLOYMENT_TARGET=12.0 build) || return 1
    (cd "$PKG" && xcodebuild -scheme STKouyuEngine -destination 'generic/platform=iOS Simulator' \
        -derivedDataPath "$OUT/DerivedData" build)
}

start_mock() {
    local port_file="$OUT/mock-port.json"
    (cd "$ROOT/tools/mock-server" && [ -d node_modules/ws ] || npm ci --no-audit --no-fund) || return 1
    node "$ROOT/tools/mock-server/server.mjs" --port 0 >"$port_file" 2>"$OUT/logs/mock.log" &
    MOCK_PID=$!
    for _ in $(seq 1 50); do
        [ -s "$port_file" ] && break
        sleep 0.1
    done
    YUGU_MOCK_BASE_URL="http://127.0.0.1:$(python3 -c 'import json,sys; print(json.loads(open(sys.argv[1]).readline())["port"])' "$port_file")"
    export YUGU_MOCK_BASE_URL
    export YUGU_SPEC_DIR="$ROOT/spec"
    echo "mock platform at $YUGU_MOCK_BASE_URL"
}

step_swift_test() {
    start_mock || return 1
    (cd "$PKG" && swift test --enable-code-coverage --parallel --xunit-output "$OUT/swift-tests.xml") || return 1
    local codecov profdata bin
    codecov="$(cd "$PKG" && swift test --show-codecov-path)"
    profdata="$(dirname "$codecov")/default.profdata"
    bin="$(find "$PKG/.build/debug" -maxdepth 1 -name '*PackageTests.xctest' | head -n 1)/Contents/MacOS/STKouyuEnginePackageTests"
    xcrun llvm-cov report "$bin" -instr-profile "$profdata" "$PKG/Sources/STKouyuEngine" >"$OUT/llvm-cov.txt" || return 1
    cat "$OUT/llvm-cov.txt"
    COVERAGE="$(awk '/^TOTAL/ {gsub("%","",$10); print $10}' "$OUT/llvm-cov.txt")"
    [ -n "$COVERAGE" ] || COVERAGE="0.00"
}

step_ios_simulator_test() {
    if [ -z "${IOS_SIMULATOR:-}" ]; then
        echo "IOS_SIMULATOR not set: iOS simulator tests skipped"
        return 77
    fi
    (cd "$PKG" && xcodebuild test -scheme STKouyuEngine-Package \
        -destination "platform=iOS Simulator,name=$IOS_SIMULATOR" -derivedDataPath "$OUT/DerivedData")
}

run_step toolchain step_toolchain
run_step linux-core step_linux_core
run_step swift-build step_swift_build_macos
run_step xcodebuild-ios step_xcodebuild_ios
run_step swift-test step_swift_test
run_step ios-sim-test step_ios_simulator_test

echo
echo "ios-stcompat macOS CI summary"
printf '%s\n' "${SUMMARY[@]}"
echo "results in $OUT"
echo "COVERAGE $COVERAGE"
exit "$FAILED"
