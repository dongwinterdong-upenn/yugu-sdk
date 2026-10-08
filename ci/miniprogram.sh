#!/usr/bin/env bash
# CI step of the WeChat mini program SDK (@shengzhiai/yugu-miniprogram-sdk).
#
#   ci/miniprogram.sh            run from any directory
#   CI_OUT=/path ci/miniprogram.sh
#
# Steps: npm ci, generated error table check, build, unit and integration tests with c8 coverage
# (gate 70 % lines on src/), TypeScript declaration check, npm pack content check, the demo project
# run against the packed tarball. Results go to ${CI_OUT:-./ci-out/miniprogram}: junit.xml,
# test-output.txt, coverage/ (HTML report), coverage-summary.json, pack.json, summary.json.
# The last line printed is "COVERAGE <pct>". Exits non-zero when a step fails.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="$ROOT/miniprogram"
OUT="${CI_OUT:-./ci-out/miniprogram}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
STARTED=$(date +%s)
STEP="setup"
COVERAGE="0"

finish() {
  local code=$?
  local status="passed"
  [ "$code" -eq 0 ] || status="failed"
  printf '{"step":"miniprogram","status":"%s","failedAt":"%s","coverageLines":%s,"durationSeconds":%s}\n' \
    "$status" "$([ "$code" -eq 0 ] && echo "" || echo "$STEP")" "$COVERAGE" "$(( $(date +%s) - STARTED ))" > "$OUT/summary.json"
  if [ "$code" -ne 0 ]; then
    echo "miniprogram CI failed at step: $STEP" >&2
  fi
  echo "COVERAGE $COVERAGE"
  exit "$code"
}
trap finish EXIT

echo "== node $(node --version), npm $(npm --version)"
cd "$PKG"

STEP="npm ci"
npm ci --no-audit --no-fund

STEP="mock server dependencies"
if [ ! -d "$ROOT/tools/mock-server/node_modules/ws" ]; then
  (cd "$ROOT/tools/mock-server" && npm ci --no-audit --no-fund)
fi

STEP="generated error table"
GEN_OUT="$(node "$ROOT/tools/gen-errors.mjs" --check 2>&1 || true)"
if echo "$GEN_OUT" | grep -q '^stale: miniprogram/'; then
  echo "$GEN_OUT" >&2
  echo "miniprogram/src/error-table.js or types/error-table.d.ts differs from spec/errors.json; run node tools/gen-errors.mjs" >&2
  exit 1
fi

STEP="build"
npm run build

STEP="tests"
rm -rf coverage
set +e
npx c8 node --test --test-concurrency=4 \
  --test-reporter=spec --test-reporter-destination="$OUT/test-output.txt" \
  --test-reporter=junit --test-reporter-destination="$OUT/junit.xml" \
  test/unit/*.test.mjs test/integration/*.test.mjs > "$OUT/coverage-text.txt" 2>&1
TEST_CODE=$?
set -e
cat "$OUT/coverage-text.txt"
grep -E '^ℹ (tests|pass|fail|cancelled|duration_ms)' "$OUT/test-output.txt" || true
if [ -f coverage/coverage-summary.json ]; then
  COVERAGE="$(node -e "console.log(require('./coverage/coverage-summary.json').total.lines.pct)")"
  cp coverage/coverage-summary.json "$OUT/coverage-summary.json"
  rm -rf "$OUT/coverage" && cp -r coverage "$OUT/coverage"
fi
[ "$TEST_CODE" -eq 0 ] || exit "$TEST_CODE"

STEP="typecheck"
npm run typecheck

STEP="pack"
npm pack --dry-run --json > "$OUT/pack.json" 2>/dev/null
node - "$OUT/pack.json" <<'EOF'
const fs = require('fs');
const [pack] = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const files = pack.files.map((f) => f.path);
const required = ['package.json', 'miniprogram_dist/index.js', 'types/index.d.ts', 'types/error-table.d.ts', 'README.md', 'CHANGELOG.md', 'LICENSE', 'NOTICE'];
const missing = required.filter((f) => !files.includes(f));
const unexpected = files.filter((f) => /^(src|test|types-test|scripts|coverage|node_modules|ci-out)\//.test(f));
console.log(`${pack.name}@${pack.version}: ${files.length} files, ${pack.size} bytes packed, ${pack.unpackedSize} bytes unpacked`);
if (pack.name !== '@shengzhiai/yugu-miniprogram-sdk' || pack.version !== '2.0.0') { console.error('unexpected name or version'); process.exit(1); }
if (missing.length || unexpected.length) { console.error('missing: ' + missing.join(', ') + ' unexpected: ' + unexpected.join(', ')); process.exit(1); }
EOF

STEP="demo against the packed tarball"
TMP="$(mktemp -d)"
npm pack --pack-destination "$TMP" > /dev/null 2>&1
tar -xzf "$TMP"/shengzhiai-yugu-miniprogram-sdk-2.0.0.tgz -C "$TMP"
YUGU_SDK_PACKAGE_DIR="$TMP/package" node --test --test-reporter=spec test/integration/demo.test.mjs > "$OUT/demo-output.txt" 2>&1 || { cat "$OUT/demo-output.txt"; rm -rf "$TMP"; exit 1; }
grep -E '^ℹ (tests|pass|fail)' "$OUT/demo-output.txt" || true
rm -rf "$TMP"

STEP=""
echo "== miniprogram CI passed"
