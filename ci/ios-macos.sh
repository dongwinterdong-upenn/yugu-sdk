#!/usr/bin/env bash
# CI step "ios-macos" (macOS runner with Xcode 15 or newer; GitHub macos-14 runs Xcode 15.4):
#   1. swift build and swift test on macOS with coverage, integration tests included (real URLSession
#      WebSocket transport against the mock platform);
#   2. xcodebuild build and test of the package on an iOS Simulator with coverage (unit tests; the
#      mock platform cannot be spawned from the simulator, those tests skip);
#   3. xcodebuild build of YuguSDK for iOS devices, without signing;
#   4. the CLI demo and the iOS demo app (XcodeGen, installed with Homebrew on CI) build against this
#      checkout.
# Every step runs even if an earlier one failed; the exit status is non-zero when any failed. The
# last line is "COVERAGE <pct>", the line coverage of ios/Sources in the macOS swift test.
#
#   ci/ios-macos.sh                       results in ./ci-out/ios-macos
#   IOS_SIMULATOR="iPhone 15 Pro" ci/ios-macos.sh
#   IOS_DESTINATION="platform=iOS Simulator,id=<UDID>" ci/ios-macos.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${CI_OUT:-./ci-out/ios-macos}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
SIMULATOR="${IOS_SIMULATOR:-iPhone 15}"
COVERAGE="0.00"
MACOS_TESTS=""
SIM_TESTS=""
FAILED=()

log() { echo "[ios-macos] $*"; }
die() { echo "[ios-macos] FAILED: $*" >&2; echo "COVERAGE $COVERAGE"; exit 1; }

run_step() {
  local name="$1"
  shift
  log "== $name"
  if "$@"; then
    log "-- $name: ok"
  else
    log "-- $name: FAILED"
    FAILED+=("$name")
  fi
}

[ "$(uname -s)" = "Darwin" ] || die "run this script on macOS"
command -v xcodebuild >/dev/null || die "xcodebuild not found"
command -v node >/dev/null || die "node not found, the mock platform needs Node 18 or newer"
xcodebuild -version
swift --version 2>&1

if [ -n "${IOS_DESTINATION:-}" ]; then
  DESTINATION="$IOS_DESTINATION"
else
  # by UDID: a name alone matches one simulator per runtime and xcodebuild may pick "My Mac"
  UDID="$(bash "$ROOT/ci/ios-simulator.sh" "$SIMULATOR")" || die "no iOS simulator available"
  DESTINATION="platform=iOS Simulator,id=$UDID"
fi

if [ ! -d "$ROOT/tools/mock-server/node_modules/ws" ]; then
  (cd "$ROOT/tools/mock-server" && npm ci --no-audit --no-fund) || die "npm ci in tools/mock-server"
fi
export YUGU_MOCK_SERVER="$ROOT/tools/mock-server/server.mjs"
export YUGU_NODE="$(command -v node)"

summary_line() { grep -E 'Executed [0-9]+ tests' "$1" | tail -n 1 | sed 's/^[[:space:]]*//'; }

# 1. macOS: every target, every test.
step_macos() {
  (cd "$ROOT/ios" && swift build) || return 1
  (cd "$ROOT/ios" && swift test --enable-code-coverage) 2>&1 | tee "$OUT/swift-test-macos.log"
  local rc=${PIPESTATUS[0]}
  MACOS_TESTS="$(summary_line "$OUT/swift-test-macos.log")"
  return "$rc"
}

step_macos_coverage() {
  local codecov bin
  codecov="$(cd "$ROOT/ios" && swift test --show-codecov-path)" || return 1
  bin="$(cd "$ROOT/ios" && swift build --show-bin-path)/YuguSDKPackageTests.xctest/Contents/MacOS/YuguSDKPackageTests"
  xcrun llvm-cov report "$bin" -instr-profile "$(dirname "$codecov")/default.profdata" "$ROOT/ios/Sources" \
    >"$OUT/llvm-cov-macos.txt" || return 1
  cat "$OUT/llvm-cov-macos.txt"
  COVERAGE="$(awk '/^TOTAL/ {gsub("%", "", $10); print $10}' "$OUT/llvm-cov-macos.txt")"
  [ -n "$COVERAGE" ] || { COVERAGE="0.00"; return 1; }
}

# 2. iOS Simulator: build every product and run the unit tests.
step_ios_simulator() {
  log "destination $DESTINATION"
  (cd "$ROOT/ios" && xcodebuild -scheme YuguSDK-Package -destination "$DESTINATION" -derivedDataPath "$OUT/DerivedData" \
    -resultBundlePath "$OUT/ios-simulator.xcresult" -enableCodeCoverage YES build test) 2>&1 | tee "$OUT/xcodebuild-ios.log"
  local rc=${PIPESTATUS[0]}
  SIM_TESTS="$(summary_line "$OUT/xcodebuild-ios.log")"
  [ "$rc" -eq 0 ] || return "$rc"
  if ! grep -q 'Debug-iphonesimulator/YuguCoreTests.xctest' "$OUT/xcodebuild-ios.log"; then
    log "the tests were not built for the iOS Simulator, check the destination"
    return 1
  fi
  xcrun xccov view --report --only-targets "$OUT/ios-simulator.xcresult" | tee "$OUT/xccov-ios-simulator.txt"
}

# 3. iOS devices: compile and link without signing.
step_ios_device() {
  (cd "$ROOT/ios" && xcodebuild -scheme YuguSDK -destination "generic/platform=iOS" -derivedDataPath "$OUT/DerivedData" \
    CODE_SIGNING_ALLOWED=NO build) 2>&1 | tee "$OUT/xcodebuild-ios-device.log"
  return "${PIPESTATUS[0]}"
}

# 4. Demos against this checkout.
step_swift_cli() {
  (cd "$ROOT/demos/swift-cli" && YUGU_SDK_PATH="$ROOT/ios" swift build)
}

step_demo_app() {
  local rc
  if ! command -v xcodegen >/dev/null && [ -n "${CI:-}" ] && command -v brew >/dev/null; then
    log "installing XcodeGen with Homebrew"
    HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALL_CLEANUP=1 brew install xcodegen || return 1
  fi
  if ! command -v xcodegen >/dev/null; then
    log "xcodegen not installed (brew install xcodegen), demo app build skipped"
    [ -z "${CI:-}" ]
    return
  fi
  log "XcodeGen $(xcodegen --version 2>&1 | tail -n 1)"
  (
    cd "$ROOT/demos/ios-demo" || exit 1
    sed -e 's#^    url: https://open.shengzhiai.com/git/yugu-ios-sdk.git#    path: ../../ios#' -e '/^    from: 2.0.0/d' \
      project.yml >.project.local.yml
    xcodegen generate --spec .project.local.yml || exit 1
    xcodebuild -project YuguDemo.xcodeproj -scheme YuguDemo -destination "generic/platform=iOS Simulator" \
      -derivedDataPath "$OUT/DerivedData-demo" CODE_SIGNING_ALLOWED=NO build 2>&1 | tee "$OUT/xcodebuild-demo.log"
    rc=${PIPESTATUS[0]}
    rm -f .project.local.yml
    exit "$rc"
  )
}

run_step macos step_macos
run_step macos-coverage step_macos_coverage
run_step ios-simulator step_ios_simulator
run_step ios-device step_ios_device
run_step swift-cli step_swift_cli
run_step demo-app step_demo_app

log "macOS swift test: ${MACOS_TESTS:-no summary}"
log "iOS Simulator xcodebuild test: ${SIM_TESTS:-no summary}"
log "line coverage of ios/Sources on macOS: $COVERAGE %"
if [ ${#FAILED[@]} -gt 0 ]; then
  log "FAILED steps: ${FAILED[*]}"
  echo "COVERAGE $COVERAGE"
  exit 1
fi
log "macOS and iOS Simulator checks passed"
echo "COVERAGE $COVERAGE"
