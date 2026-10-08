#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# CI step "android-stcompat": the Shengtong (17kouyu 1.0.0) drop-in layer.
#
#   ci/android-stcompat.sh
#
# 1. Gradle wrapper build from the checkout: clean, unit and Robolectric tests (the integration
#    tests start tools/mock-server), JaCoCo coverage with the 70 % gate, release AAR, publish to a
#    temporary file repository (property yuguPublishDir), :compat-demo:assembleDebug
# 2. API identity: tools/api-diff/selftest.sh, then tools/api-diff/run.sh against the original jar
#    (ST_ORIGINAL_JAR)
# 3. Customer-simulation compile test: android-stcompat/api-compat-test/run.sh
# Reports (JUnit XML, test HTML, coverage HTML and XML, api-diff, compile test log) are copied to
# ${CI_OUT:-./ci-out/android-stcompat}. The last line printed is "COVERAGE <pct>".
# Exit 0 when every step passed, 1 otherwise.
#
# Environment: ANDROID_HOME (default: the SDK of the build host), ST_ORIGINAL_JAR (the Shengtong
# jar is never committed; the api-diff and the original compile are skipped with a message when
# it is absent), NODE (node binary for the mock server), CI_OUT.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT/android-stcompat"
CI_OUT="${CI_OUT:-./ci-out/android-stcompat}"
mkdir -p "$CI_OUT" || exit 1
CI_OUT="$(cd "$CI_OUT" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/home/ubuntu/tools/yugu-sdk-toolchain/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
ST_ORIGINAL_JAR="${ST_ORIGINAL_JAR:-/home/ubuntu/yougu/sdk-v2-work/ref/st_public/android/libs/17kouyu_1.0.0.jar}"
export ST_ORIGINAL_JAR
GRADLE_OPTS_CI=(--no-daemon -Dorg.gradle.jvmargs=-Xmx1536m --max-workers=2 --console=plain)
NICE=()
command -v nice >/dev/null 2>&1 && NICE=(nice -n 10)
WORK="$(mktemp -d "${TMPDIR:-/tmp}/android-stcompat-ci.XXXXXX")" || exit 1
trap 'rm -rf "$WORK"' EXIT

FAILED=()
step() { echo; echo "==== android-stcompat: $*"; }
fail() { FAILED+=("$1"); echo "FAILED: $1"; }

step "environment"
java -version 2>&1 | head -1
echo "ANDROID_HOME=$ANDROID_HOME"
echo "CI_OUT=$CI_OUT"
if [ ! -d "$ROOT/tools/mock-server/node_modules" ]; then
    step "install mock server dependencies (npm ci)"
    ( cd "$ROOT/tools/mock-server" && npm ci --no-audit --no-fund ) || fail "mock-server npm ci"
fi

# ---------------------------------------------------------------- 1. build, tests, coverage
step "gradle: clean, tests, coverage"
cd "$PROJECT" || exit 1
"${NICE[@]}" ./gradlew "${GRADLE_OPTS_CI[@]}" clean :stkouyu-compat:testDebugUnitTest :stkouyu-compat:coverageSummary \
    2>&1 | tee "$CI_OUT/gradle-test.log" | grep -Ev '^warning: \[options\]' | tail -40
TEST_RC=${PIPESTATUS[0]}
[ "$TEST_RC" -eq 0 ] || fail "tests or coverage gate (gradle exit $TEST_RC)"

mkdir -p "$CI_OUT/junit" "$CI_OUT/tests-html" "$CI_OUT/coverage"
cp stkouyu-compat/build/test-results/testDebugUnitTest/*.xml "$CI_OUT/junit/" 2>/dev/null || true
cp -r stkouyu-compat/build/reports/tests/testDebugUnitTest/. "$CI_OUT/tests-html/" 2>/dev/null || true
cp -r stkouyu-compat/build/reports/coverage/test/debug/. "$CI_OUT/coverage/" 2>/dev/null || true
TESTS_SUMMARY="$(python3 - "$CI_OUT/junit" <<'EOF' 2>/dev/null
import glob, sys, xml.etree.ElementTree as ET
t = f = e = s = 0
for p in glob.glob(sys.argv[1] + '/*.xml'):
    r = ET.parse(p).getroot()
    t += int(r.get('tests', 0)); f += int(r.get('failures', 0)); e += int(r.get('errors', 0)); s += int(r.get('skipped', 0))
print('tests %d, failures %d, errors %d, skipped %d' % (t, f, e, s))
EOF
)"
echo "junit: ${TESTS_SUMMARY:-no results}"

step "gradle: release AAR, publish to a temporary repository, demo app"
"${NICE[@]}" ./gradlew "${GRADLE_OPTS_CI[@]}" :stkouyu-compat:assembleRelease \
    :stkouyu-compat:publishReleasePublicationToYuguRepository -PyuguPublishDir="$WORK/repo" :compat-demo:assembleDebug \
    2>&1 | tee "$CI_OUT/gradle-build.log" | grep -Ev '^warning: \[options\]' | tail -15
BUILD_RC=${PIPESTATUS[0]}
[ "$BUILD_RC" -eq 0 ] || fail "assemble, publish or demo (gradle exit $BUILD_RC)"
AAR="$PROJECT/stkouyu-compat/build/outputs/aar/stkouyu-compat-release.aar"
if [ -d "$WORK/repo" ]; then
    ( cd "$WORK/repo" && find . -type f | sort ) > "$CI_OUT/published-files.txt"
    echo "published $(wc -l < "$CI_OUT/published-files.txt") files"
    [ -f "$WORK/repo/com/shengzhiai/yugu/stkouyu-compat/2.0.0/stkouyu-compat-2.0.0.aar" ] || fail "published AAR missing"
fi
[ -f "$AAR" ] && cp "$AAR" "$CI_OUT/"

# ---------------------------------------------------------------- 2. API identity
step "api-diff self-test on synthetic jars"
"$ROOT/tools/api-diff/selftest.sh" 2>&1 | tee "$CI_OUT/api-diff-selftest.log" | tail -10
[ "${PIPESTATUS[0]}" -eq 0 ] || fail "api-diff self-test"

step "api-diff against the original 17kouyu jar"
if [ ! -f "$ST_ORIGINAL_JAR" ]; then
    echo "SKIPPED: original jar not found at $ST_ORIGINAL_JAR (set ST_ORIGINAL_JAR; Shengtong binaries are not in the repo)"
    echo "SKIPPED: original jar not found" > "$CI_OUT/api-diff-skipped.txt"
elif [ ! -f "$AAR" ]; then
    fail "api-diff (no AAR)"
else
    "$ROOT/tools/api-diff/run.sh" "$ST_ORIGINAL_JAR" "$AAR" --report-dir "$CI_OUT/api-diff" 2>&1 | tail -25
    [ "${PIPESTATUS[0]}" -eq 0 ] || fail "api-diff"
fi

# ---------------------------------------------------------------- 3. customer-simulation compile test
step "customer-simulation compile test"
if [ -f "$AAR" ]; then
    "$PROJECT/api-compat-test/run.sh" "$AAR" 2>&1 | tee "$CI_OUT/api-compat-test.log" | grep -v '^Note:'
    [ "${PIPESTATUS[0]}" -eq 0 ] || fail "api-compat-test"
else
    fail "api-compat-test (no AAR)"
fi

# ---------------------------------------------------------------- summary
COVERAGE="$(sed -n 's/^COVERAGE \([0-9.]*\).*/\1/p' "$PROJECT/stkouyu-compat/build/reports/coverage/test/debug/coverage.txt" 2>/dev/null | head -1)"
{
    echo "junit: ${TESTS_SUMMARY:-no results}"
    echo "coverage (line, :stkouyu-compat): ${COVERAGE:-n/a}"
    if [ ${#FAILED[@]} -eq 0 ]; then echo "status: OK"; else printf 'failed: %s\n' "${FAILED[@]}"; fi
} | tee "$CI_OUT/summary.txt"
STATUS=0
[ ${#FAILED[@]} -eq 0 ] || STATUS=1
echo "COVERAGE ${COVERAGE:-0}"
exit $STATUS
