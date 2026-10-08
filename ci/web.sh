#!/usr/bin/env bash
# CI step of the PC Web SDK (@shengzhiai/yugu-web-sdk).
#
#   ci/web.sh                    results in ./ci-out/web
#   CI_OUT=/path/to/out ci/web.sh
#
# Steps: npm ci, lint (includes the generated error table check), build, tests with c8 coverage
# and JUnit, typecheck plus declaration drift check, package content check, offline install of
# the packed tarball into a clean project and into demos/web-demo. Exits non-zero on any failure.
# The last line printed is "COVERAGE <line pct>".
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB="$ROOT/web"
OUT="${CI_OUT:-./ci-out/web}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
NICE="nice -n 10"
step() { printf '\n== web: %s\n' "$*"; }

cd "$WEB"
step "toolchain node $(node --version), npm $(npm --version)"

step "npm ci"
$NICE npm ci --no-audit --no-fund

if [ ! -f "$ROOT/tools/mock-server/node_modules/ws/package.json" ]; then
  step "mock server dependencies"
  (cd "$ROOT/tools/mock-server" && $NICE npm ci --no-audit --no-fund)
fi

step "lint"
$NICE npm run lint

step "build"
$NICE npm run build

step "test with coverage"
set +e
$NICE npm test 2>&1 | tee "$OUT/test-output.txt"
TEST_STATUS=${PIPESTATUS[0]}
set -e
mkdir -p "$OUT/junit"
if [ -f test-results/junit.xml ]; then cp test-results/junit.xml "$OUT/junit/web.xml"; fi
rm -rf "$OUT/coverage"
if [ -d coverage ]; then cp -r coverage "$OUT/coverage"; fi
if [ "$TEST_STATUS" -ne 0 ]; then
  echo "web: tests failed (exit $TEST_STATUS)"
  exit "$TEST_STATUS"
fi

step "typecheck"
$NICE npm run typecheck

step "package content"
PACK_JSON="$OUT/pack-dry-run.json"
npm pack --dry-run --json > "$PACK_JSON" 2> "$OUT/pack-dry-run.log"
node - "$PACK_JSON" <<'NODE'
const fs = require('node:fs');
const files = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))[0].files.map((f) => f.path);
const need = ['package.json', 'README.md', 'CHANGELOG.md', 'LICENSE', 'NOTICE', 'dist/yugu-sdk.mjs', 'dist/yugu-sdk.umd.js',
  'dist/yugu-sdk.umd.min.js', 'dist/yugu-pcm-worklet.js', 'dist/package.json', 'src/index.js', 'src/error-table.js', 'types/index.d.ts', 'types/error-table.d.ts'];
const missing = need.filter((f) => !files.includes(f));
const unexpected = files.filter((f) => /^(test|scripts|coverage|types-test|test-results|node_modules)\//.test(f));
for (const dir of ['dist/', 'src/', 'types/']) if (!files.some((f) => f.startsWith(dir))) missing.push(dir);
if (missing.length || unexpected.length) {
  console.error(`package content: missing ${missing.join(', ') || 'none'}; unexpected ${unexpected.join(', ') || 'none'}`);
  process.exit(1);
}
console.log(`package content ok: ${files.length} files, dist src types README CHANGELOG LICENSE present`);
NODE

step "pack and install the tarball offline"
rm -f "$OUT"/shengzhiai-yugu-web-sdk-*.tgz
npm pack --pack-destination "$OUT" > "$OUT/pack.log" 2>&1
TARBALL="$(ls "$OUT"/shengzhiai-yugu-web-sdk-*.tgz)"
(cd "$OUT" && sha256sum "$(basename "$TARBALL")" > "$(basename "$TARBALL").sha256")
$NICE node scripts/consumer-smoke.mjs "$TARBALL" "$OUT/consumer" --demo "$ROOT/demos/web-demo"
rm -rf "$OUT/consumer"

PCT="$(node -e "console.log(require(process.argv[1]).total.lines.pct)" "$WEB/coverage/coverage-summary.json")"
echo "COVERAGE ${PCT}"
