#!/usr/bin/env bash
# CI step for the Java server SDK (java/).
#
#   ci/java.sh                      build, unit and integration tests, JaCoCo gate (line coverage >= 70 %)
#   YUGU_SLOW_TESTS=1 ci/java.sh    also run the tests tagged "slow" (10 s network outage), for nightly builds
#   CI_OUT=/path ci/java.sh         where reports go, default ./ci-out/java relative to the current directory
#
# Works from a clean checkout: the Maven wrapper downloads Maven, npm installs the mock server
# dependencies when they are missing. Copies the surefire XML and the JaCoCo HTML report to $CI_OUT,
# prints "COVERAGE <line %>" as the last line and exits non-zero when anything failed.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${CI_OUT:-./ci-out/java}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

status=0

if [ ! -d "$ROOT/tools/mock-server/node_modules/ws" ]; then
  echo "installing mock server dependencies"
  (cd "$ROOT/tools/mock-server" && npm ci --no-audit --no-fund) || status=1
fi

MVN_ARGS=(-B -ntp clean verify)
if [ "${YUGU_SLOW_TESTS:-0}" = "1" ]; then
  MVN_ARGS+=(-Pslow)
fi

if [ "$status" -eq 0 ]; then
  (cd "$ROOT/java" && ./mvnw "${MVN_ARGS[@]}") || status=$?
fi

rm -rf "$OUT/surefire-reports" "$OUT/jacoco"
mkdir -p "$OUT/surefire-reports" "$OUT/jacoco"
cp "$ROOT"/java/target/surefire-reports/*.xml "$OUT/surefire-reports/" 2>/dev/null || true
cp -R "$ROOT"/java/target/site/jacoco/. "$OUT/jacoco/" 2>/dev/null || true

coverage="n/a"
CSV="$ROOT/java/target/site/jacoco/jacoco.csv"
if [ -f "$CSV" ]; then
  # columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,...
  coverage="$(awk -F, 'NR > 1 { m += $8; c += $9 } END { if (m + c > 0) printf "%.1f", 100 * c / (m + c); else printf "n/a" }' "$CSV")"
fi
echo "$coverage" > "$OUT/coverage.txt"
if [ "$status" -ne 0 ]; then
  echo "java: FAILED (exit $status)"
fi
echo "COVERAGE $coverage"
exit "$status"
