#!/usr/bin/env bash
# CI step "ios-macos" (macOS runner with Xcode 15 or newer):
#   1. swift build and swift test on macOS, integration tests included (real URLSession
#      WebSocket transport against the mock platform);
#   2. xcodebuild build and test of the package on the iOS Simulator (unit tests; the mock
#      platform cannot be spawned from the simulator, those tests skip);
#   3. the CLI demo and the iOS demo app (XcodeGen) build against this checkout.
#
#   ci/ios-macos.sh                       results in ./ci-out/ios-macos
#   IOS_SIMULATOR="iPhone 15" ci/ios-macos.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${CI_OUT:-./ci-out/ios-macos}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
SIMULATOR="${IOS_SIMULATOR:-iPhone 15}"
DESTINATION="${IOS_DESTINATION:-platform=iOS Simulator,name=$SIMULATOR}"

log() { echo "[ios-macos] $*"; }
die() { echo "[ios-macos] FAILED: $*" >&2; exit 1; }

[ "$(uname -s)" = "Darwin" ] || die "run this script on macOS"
command -v xcodebuild >/dev/null || die "xcodebuild not found"
command -v node >/dev/null || die "node not found, the mock platform needs Node 18 or newer"
xcodebuild -version | head -1
swift --version 2>&1 | head -1

if [ ! -d "$ROOT/tools/mock-server/node_modules/ws" ]; then
  (cd "$ROOT/tools/mock-server" && npm ci --no-audit --no-fund)
fi
export YUGU_MOCK_SERVER="$ROOT/tools/mock-server/server.mjs"
export YUGU_NODE="$(command -v node)"

# 1. macOS: every target, every test.
cd "$ROOT/ios"
log "swift build (macOS)"
swift build
log "swift test (macOS)"
swift test --parallel --enable-code-coverage --xunit-output "$OUT/xunit-macos.xml" 2>&1 | tee "$OUT/swift-test-macos.log"
[ "${PIPESTATUS[0]}" -eq 0 ] || die "swift test failed on macOS"

# 2. iOS Simulator: build every product and run the unit tests.
log "xcodebuild build and test, $DESTINATION"
xcodebuild -scheme YuguSDK-Package -destination "$DESTINATION" -derivedDataPath "$OUT/DerivedData" \
  -resultBundlePath "$OUT/ios-simulator.xcresult" build test 2>&1 | tee "$OUT/xcodebuild-ios.log"
[ "${PIPESTATUS[0]}" -eq 0 ] || die "xcodebuild test failed on the iOS Simulator"
log "xcodebuild build, generic iOS device (no signing)"
xcodebuild -scheme YuguSDK -destination "generic/platform=iOS" -derivedDataPath "$OUT/DerivedData" \
  CODE_SIGNING_ALLOWED=NO build 2>&1 | tee "$OUT/xcodebuild-ios-device.log"
[ "${PIPESTATUS[0]}" -eq 0 ] || die "xcodebuild build for iOS devices failed"

# 3. Demos against this checkout.
log "demos/swift-cli"
(cd "$ROOT/demos/swift-cli" && YUGU_SDK_PATH="$ROOT/ios" swift build)
if command -v xcodegen >/dev/null; then
  log "demos/ios-demo with the local SDK"
  cd "$ROOT/demos/ios-demo"
  sed -e 's#^    url: https://open.shengzhiai.com/git/yugu-ios-sdk.git#    path: ../../ios#' -e '/^    from: 2.0.0/d' project.yml > .project.local.yml
  xcodegen generate --spec .project.local.yml
  xcodebuild -project YuguDemo.xcodeproj -scheme YuguDemo -destination "generic/platform=iOS Simulator" \
    -derivedDataPath "$OUT/DerivedData-demo" CODE_SIGNING_ALLOWED=NO build 2>&1 | tee "$OUT/xcodebuild-demo.log"
  [ "${PIPESTATUS[0]}" -eq 0 ] || die "demo app build failed"
  rm -f .project.local.yml
else
  log "xcodegen not installed (brew install xcodegen), demo app build skipped"
fi

log "macOS and iOS Simulator checks passed"
