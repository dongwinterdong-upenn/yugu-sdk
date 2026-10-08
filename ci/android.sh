#!/usr/bin/env bash
# CI step for the Android core SDK (android/ and demos/android-demo/).
#
#   ANDROID_HOME=/path/to/android-sdk ci/android.sh
#
# 1. checks that android/.../errors/ErrorTable.kt equals the output of tools/gen-errors.mjs
# 2. clean build, JVM unit and integration tests (tools/mock-server), JaCoCo report and 70 % gate, lint
# 3. publishes the release publication into a temporary Maven directory and verifies its files
# 4. builds demos/android-demo (assembleDebug) against that directory with -PyuguMavenUrl
# 5. copies JUnit XML, test and coverage HTML into ${CI_OUT:-./ci-out/android}
# The last line printed is "COVERAGE <line coverage percent>". Exit status is non-zero on failure.
set -uo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT=${CI_OUT:-./ci-out/android}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/yugu-android-ci.XXXXXX")
REPO="$WORK/maven"
MARK="yugu-android-ci-$$-$RANDOM"
GRADLE_ARGS=(--no-daemon -Dorg.gradle.jvmargs=-Xmx1536m --max-workers=2 --stacktrace)
STATUS=0
COVERAGE=0

log() { echo "[android-ci $(date -u +%H:%M:%S)] $*"; }
fail() { log "FAILED: $*"; STATUS=1; }

# Mock servers started by this run inherit YUGU_CI_MARK; only those are stopped here.
stop_own_mock_servers() {
  local p pid
  for p in /proc/[0-9]*; do
    [ -O "$p" ] || continue
    pid=${p#/proc/}
    if { tr '\0' '\n' < "$p/environ"; } 2>/dev/null | grep -qx "YUGU_CI_MARK=$MARK"; then
      if { tr '\0' ' ' < "$p/cmdline"; } 2>/dev/null | grep -q "mock-server/server.mjs"; then
        kill "$pid" 2>/dev/null || true
      fi
    fi
  done
}
cleanup() {
  stop_own_mock_servers
  rm -rf "$WORK"
}
trap cleanup EXIT

finish() {
  cleanup
  trap - EXIT
  log "reports in $OUT"
  echo "COVERAGE $COVERAGE"
  exit "$STATUS"
}

# ---------------------------------------------------------------------------- 0 toolchain
SDK_DIR=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK_DIR" ] || [ ! -d "$SDK_DIR/platforms" ]; then
  log "ANDROID_HOME or ANDROID_SDK_ROOT must point to an Android SDK with platform 34"
  STATUS=2
  finish
fi
export ANDROID_HOME="$SDK_DIR"
JAVA_MAJOR=$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')
case "${JAVA_MAJOR:-}" in ''|1.*) JAVA_MAJOR=0 ;; esac
if [ "$JAVA_MAJOR" -lt 17 ]; then
  log "JDK 17 or newer is required, found ${JAVA_MAJOR:-none}"
  STATUS=2
  finish
fi
NODE_BIN=${YUGU_NODE:-$(command -v node || true)}
if [ -z "$NODE_BIN" ]; then
  log "node is required for the integration tests (tools/mock-server)"
  STATUS=2
  finish
fi
export YUGU_NODE="$NODE_BIN" YUGU_REQUIRE_MOCK=1 YUGU_CI_MARK="$MARK"
log "root=$ROOT java=$JAVA_MAJOR node=$("$NODE_BIN" --version) android=$ANDROID_HOME"

# local.properties is generated from the environment, never committed.
for dir in "$ROOT/android" "$ROOT/demos/android-demo"; do
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$dir/local.properties"
done

# ---------------------------------------------------------------------------- 1 error table
GEN="$WORK/gen"
mkdir -p "$GEN/tools" "$GEN/spec"
cp "$ROOT/tools/gen-errors.mjs" "$GEN/tools/"
cp "$ROOT/spec/errors.json" "$GEN/spec/"
TABLE=android/yugu-android-sdk/src/main/kotlin/com/shengzhiai/yugu/errors/ErrorTable.kt
if "$NODE_BIN" "$GEN/tools/gen-errors.mjs" > /dev/null && cmp -s "$GEN/$TABLE" "$ROOT/$TABLE"; then
  log "ErrorTable.kt matches tools/gen-errors.mjs"
else
  fail "ErrorTable.kt differs from tools/gen-errors.mjs output, run: node tools/gen-errors.mjs"
fi

# ---------------------------------------------------------------------------- 2 mock server
if [ ! -d "$ROOT/tools/mock-server/node_modules/ws" ]; then
  log "installing tools/mock-server dependencies"
  (cd "$ROOT/tools/mock-server" && npm ci --no-audit --no-fund) || fail "npm ci in tools/mock-server"
fi

# ---------------------------------------------------------------------------- 3 build, test, publish
log "build, tests, coverage, lint, publish to $REPO"
(cd "$ROOT/android" && ./gradlew "${GRADLE_ARGS[@]}" --continue \
  clean \
  :yugu-android-sdk:testDebugUnitTest \
  :yugu-android-sdk:jacocoTestReport \
  :yugu-android-sdk:jacocoCoverageVerification \
  :yugu-android-sdk:lintDebug \
  :yugu-android-sdk:assembleRelease \
  :yugu-android-sdk:publishToYuguDir \
  -PyuguPublishDir="$REPO") || fail "gradle build of android/"

BUILD="$ROOT/android/yugu-android-sdk/build"
mkdir -p "$OUT/junit" "$OUT/coverage" "$OUT/tests" "$OUT/lint"
cp "$BUILD"/test-results/testDebugUnitTest/*.xml "$OUT/junit/" 2>/dev/null || fail "no JUnit XML"
cp -r "$BUILD/reports/tests/testDebugUnitTest/." "$OUT/tests/" 2>/dev/null || true
cp -r "$BUILD/reports/jacoco/jacocoTestReport/html/." "$OUT/coverage/" 2>/dev/null || fail "no coverage HTML"
cp "$BUILD/reports/jacoco/jacocoTestReport/jacocoTestReport.xml" "$OUT/coverage.xml" 2>/dev/null || true
cp "$BUILD"/reports/lint-results-debug.* "$OUT/lint/" 2>/dev/null || true

if [ -f "$OUT/coverage.xml" ]; then
  COVERAGE=$(python3 -I - "$OUT/coverage.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
line = [c for c in root.findall("counter") if c.get("type") == "LINE"][0]
covered, missed = int(line.get("covered")), int(line.get("missed"))
print(f"{100.0 * covered / (covered + missed):.1f}")
PY
  ) || COVERAGE=0
fi
python3 -I - "$OUT/junit" <<'PY' || fail "test summary"
import glob, sys
import xml.etree.ElementTree as ET
t = f = e = s = 0
for path in glob.glob(sys.argv[1] + "/*.xml"):
    r = ET.parse(path).getroot()
    t += int(r.get("tests")); f += int(r.get("failures")); e += int(r.get("errors")); s += int(r.get("skipped"))
print(f"[android-ci] tests={t} failures={f} errors={e} skipped={s}")
sys.exit(1 if (f or e or t == 0) else 0)
PY

# ---------------------------------------------------------------------------- 4 verify publication
BASE="$REPO/com/shengzhiai/yugu/yugu-android-sdk"
for f in 2.0.0/yugu-android-sdk-2.0.0.aar 2.0.0/yugu-android-sdk-2.0.0.pom 2.0.0/yugu-android-sdk-2.0.0-sources.jar \
         2.0.0/yugu-android-sdk-2.0.0-javadoc.jar 2.0.0/yugu-android-sdk-2.0.0.module maven-metadata.xml; do
  [ -s "$BASE/$f" ] || fail "missing published file $f"
  for sum in md5 sha1 sha256 sha512; do
    [ -s "$BASE/$f.$sum" ] || fail "missing checksum $f.$sum"
  done
done
if [ -s "$BASE/2.0.0/yugu-android-sdk-2.0.0-javadoc.jar" ]; then
  python3 -I - "$BASE/2.0.0/yugu-android-sdk-2.0.0-javadoc.jar" <<'PY' || fail "javadoc jar has no HTML"
import sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
html = [n for n in names if n.endswith(".html")]
print(f"[android-ci] javadoc jar: {len(html)} html files")
sys.exit(0 if any(n.endswith("index.html") for n in html) else 1)
PY
fi
(cd "$REPO" && find . -type f | sort) > "$OUT/published-files.txt" 2>/dev/null || true

# ---------------------------------------------------------------------------- 5 demo
log "demo build against file://$REPO"
(cd "$ROOT/demos/android-demo" && ./gradlew "${GRADLE_ARGS[@]}" clean assembleDebug -PyuguMavenUrl="file://$REPO") \
  || fail "demos/android-demo assembleDebug"
APK="$ROOT/demos/android-demo/app/build/outputs/apk/debug/app-debug.apk"
if [ -s "$APK" ]; then
  cp "$APK" "$OUT/yugu-android-demo-debug.apk"
  log "demo apk $(stat -c %s "$APK") bytes"
else
  fail "demo apk missing"
fi

finish
