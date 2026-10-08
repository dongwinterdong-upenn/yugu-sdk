#!/usr/bin/env bash
# CI step "ios" (Linux): YuguCore build, XCTest with coverage, CLI demo against the mock platform.
#
#   ci/ios.sh                      results in ./ci-out/ios
#   CI_OUT=/path ci/ios.sh         results elsewhere
#
# Environment: SWIFT_BIN (toolchain bin dir), JOBS (default 2), MIN_COVERAGE (default 70).
# Output: xunit.xml, swift-test.log, coverage.txt, coverage-summary.json, coverage.lcov,
# coverage-html/, swift-cli.txt. The last line is "COVERAGE <percent>".
# Apple-only code (YuguSDK recorder, iOS demo app) is verified by ci/ios-macos.sh on macOS.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SWIFT_BIN="${SWIFT_BIN:-/home/ubuntu/tools/yugu-sdk-toolchain/swift/usr/bin}"
if [ -d "$SWIFT_BIN" ]; then export PATH="$SWIFT_BIN:$PATH"; fi
JOBS="${JOBS:-2}"
MIN_COVERAGE="${MIN_COVERAGE:-70}"
OUT="${CI_OUT:-./ci-out/ios}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
NICE="nice -n 10"
MOCK_PID=""

log() { echo "[ios] $*"; }
die() { echo "[ios] FAILED: $*" >&2; exit 1; }
cleanup() { if [ -n "$MOCK_PID" ]; then kill "$MOCK_PID" 2>/dev/null || true; fi; }
trap cleanup EXIT

command -v swift >/dev/null || die "swift not found, set SWIFT_BIN"
command -v node >/dev/null || die "node not found, the mock platform needs Node 18 or newer"
command -v llvm-cov >/dev/null || die "llvm-cov not found next to swift"
swift --version 2>&1 | head -1

# 1. Copies of shared fixtures and the generated error table must match spec/.
log "checking fixture copies and the generated error table"
for d in platform sign audio; do
  diff -r "$ROOT/spec/fixtures/$d" "$ROOT/ios/Tests/YuguCoreTests/Fixtures/$d" >/dev/null || die "ios/Tests/YuguCoreTests/Fixtures/$d differs from spec/fixtures/$d"
done
cmp -s "$ROOT/spec/errors.json" "$ROOT/ios/Tests/YuguCoreTests/Fixtures/errors.json" || die "Fixtures/errors.json differs from spec/errors.json"
if node "$ROOT/tools/gen-errors.mjs" --check 2>&1 | grep -q '^stale: ios/'; then
  die "ios/Sources/YuguCore/ErrorTable.swift is stale, run node tools/gen-errors.mjs"
fi

# 2. Mock platform dependencies.
if [ ! -d "$ROOT/tools/mock-server/node_modules/ws" ]; then
  log "installing mock server dependencies"
  (cd "$ROOT/tools/mock-server" && npm ci --no-audit --no-fund)
fi
export YUGU_MOCK_SERVER="$ROOT/tools/mock-server/server.mjs"

# 3. Build: YuguCore and YuguSDK (on Linux YuguSDK holds only the re-export).
cd "$ROOT/ios"
log "swift build"
$NICE swift build -j "$JOBS"

# 4. Tests with coverage and xUnit output. SwiftPM writes the XCTest xUnit file only in parallel
#    mode; each test then runs in its own process and coverage is merged.
log "swift test (unit, REST integration and WebSocket wire tests against the mock platform)"
rm -f "$(swift build --show-bin-path)"/codecov/*.profraw
set +e
$NICE swift test -j "$JOBS" --parallel --num-workers "$JOBS" --enable-code-coverage --xunit-output "$OUT/xunit.xml" 2>&1 | tee "$OUT/swift-test.log"
TEST_STATUS=${PIPESTATUS[0]}
set -e
[ "$TEST_STATUS" -eq 0 ] || die "swift test exited with $TEST_STATUS"
[ -s "$OUT/xunit.xml" ] || die "no xUnit report at $OUT/xunit.xml"
node -e 'const x=require("fs").readFileSync(process.argv[1],"utf8"); const m=/<testsuite [^>]*tests="(\d+)" failures="(\d+)"/.exec(x); console.log("[ios] xUnit: " + (m ? m[1] + " tests, " + m[2] + " failures" : "unreadable")); if (!m || m[2] !== "0") process.exit(1)' "$OUT/xunit.xml" || die "xUnit report shows failures"

# 5. Coverage of Sources/YuguCore.
BIN_DIR="$(swift build --show-bin-path)"
PROFDATA="$BIN_DIR/codecov/default.profdata"
TEST_BIN="$BIN_DIR/YuguSDKPackageTests.xctest"
[ -f "$PROFDATA" ] || die "no coverage data at $PROFDATA"
SOURCES="$ROOT/ios/Sources/YuguCore"
llvm-cov report "$TEST_BIN" -instr-profile "$PROFDATA" "$SOURCES" > "$OUT/coverage.txt"
llvm-cov export -summary-only "$TEST_BIN" -instr-profile "$PROFDATA" "$SOURCES" > "$OUT/coverage-summary.json"
llvm-cov export -format=lcov "$TEST_BIN" -instr-profile "$PROFDATA" "$SOURCES" > "$OUT/coverage.lcov"
rm -rf "$OUT/coverage-html"
llvm-cov show -format=html -show-line-counts-or-regions -output-dir "$OUT/coverage-html" \
  -instr-profile "$PROFDATA" "$TEST_BIN" "$SOURCES"
cat "$OUT/coverage.txt" | tail -3
PCT="$(node -e 'const j=require(process.argv[1]); console.log(j.data[0].totals.lines.percent.toFixed(2))' "$OUT/coverage-summary.json")"
node -e 'process.exit(Number(process.argv[1]) >= Number(process.argv[2]) ? 0 : 1)' "$PCT" "$MIN_COVERAGE" \
  || die "YuguCore line coverage $PCT % is below $MIN_COVERAGE %"
log "YuguCore line coverage $PCT %, HTML report in $OUT/coverage-html/index.html"

# 6. CLI demo against the mock platform, built from this checkout.
log "building demos/swift-cli"
cd "$ROOT/demos/swift-cli"
export YUGU_SDK_PATH="$ROOT/ios"
CLI_SCRATCH="$OUT/.build-swift-cli"
$NICE swift build -j "$JOBS" --scratch-path "$CLI_SCRATCH"
CLI="$(swift build --scratch-path "$CLI_SCRATCH" --show-bin-path)/yugu-eval"
node "$ROOT/tools/mock-server/server.mjs" --port 0 > "$OUT/mock-cli.out" 2>&1 &
MOCK_PID=$!
PORT=""
for _ in $(seq 1 100); do
  PORT="$(node -e 'try { console.log(JSON.parse(require("fs").readFileSync(process.argv[1], "utf8").split("\n")[0]).port) } catch (e) {}' "$OUT/mock-cli.out")"
  [ -n "$PORT" ] && break
  sleep 0.1
done
[ -n "$PORT" ] || die "mock server did not start"
log "running yugu-eval against http://127.0.0.1:$PORT"
"$CLI" --base-url "http://127.0.0.1:$PORT" --app-key mock-app-key --secret-key mock-secret-key \
  --text "今天天气很好" --language zh-CN "$ROOT/spec/fixtures/audio/zh_short.wav" | tee "$OUT/swift-cli.txt"
grep -q "^overall  *93.7" "$OUT/swift-cli.txt" || die "yugu-eval did not print the expected score"
kill "$MOCK_PID" 2>/dev/null || true
MOCK_PID=""
rm -rf "$CLI_SCRATCH"

log "NOTE: Apple-only targets (YuguSDK recorder, ios-demo app) are not built on Linux; ci/ios-macos.sh verifies them on macOS."
echo "COVERAGE $PCT"
