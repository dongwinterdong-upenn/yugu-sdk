#!/bin/bash
# Runs every CI step from a clean checkout and writes $CI_OUT/summary.json.
#
#   CI_OUT=/path/to/out ci/run-all.sh [step ...]
#
# Steps: contract java android android-stcompat web miniprogram ios ios-stcompat docs
# Each platform step is ci/<step>.sh, run with CI_OUT=$CI_OUT/<step>; its log goes to
# $CI_OUT/<step>.log. A step's coverage is the number on its last "COVERAGE <pct>" line.
# All steps run even if one fails; the exit code is non-zero when any step failed.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CI_OUT=${CI_OUT:-$ROOT/ci-out}
mkdir -p "$CI_OUT"
STEPS=("$@")
if [ ${#STEPS[@]} -eq 0 ]; then
  STEPS=(contract java android android-stcompat web miniprogram ios ios-stcompat docs)
fi
export PATH="/home/ubuntu/tools/yugu-sdk-toolchain/swift/usr/bin:/home/ubuntu/tools/yugu-sdk-toolchain/gradle-8.9/bin:$PATH"
export ANDROID_HOME=${ANDROID_HOME:-/home/ubuntu/tools/yugu-sdk-toolchain/android-sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME

results=()
overall=0
for step in "${STEPS[@]}"; do
  log="$CI_OUT/$step.log"
  start=$(date +%s)
  status=passed
  case "$step" in
    contract)
      (
        set -e
        cd "$ROOT"
        echo "== error tables generated from spec/errors.json are up to date"
        node tools/gen-errors.mjs --check
        echo "== mock platform selftest"
        (cd tools/mock-server && npm ci --no-audit --no-fund --silent && node selftest.mjs)
        echo "== signature vectors include the contract vector"
        grep -q 'A+6uVB/D7khxQEt8tzgCNjMUC1QtQQd1UF+NCYVYZqE=' spec/fixtures/sign/vectors.json
        echo "== no v1 coordinates or domains left in shipped sources"
        if grep -rIl --exclude-dir=node_modules --exclude-dir=.gradle --exclude-dir=build --exclude-dir=.build \
            --exclude-dir=ci-out -e 'tech\.dragonai\.yugu' -e '@yugu/web-sdk' \
            java/src android/yugu-android-sdk/src android-stcompat/stkouyu-compat/src web/src miniprogram/src ios/Sources ios-stcompat/Sources 2>/dev/null; then
          echo "v1 coordinates found in the files above"
          exit 1
        fi
        echo "COVERAGE -"
      ) > "$log" 2>&1 || status=failed
      ;;
    docs)
      (
        cd "$ROOT"
        LINT=${WRITING_LINT:-/home/ubuntu/开题/J1/系统/app/tools/writing_lint.py}
        if [ -f "$LINT" ]; then
          fails=0
          while IFS= read -r f; do
            echo "== $f"
            python3 "$LINT" --external "$f" || fails=$((fails+1))
          done < <(find . -name 'README.md' -o -name 'CHANGELOG.md' -o -name 'CONTRACT.md' -o -name 'SANDBOX.md' \
                     -o -name 'COMPATIBILITY.md' -o -name 'MIGRATION-2.0.md' -o -name 'SHENGTONG-MIGRATION.md' -o -name 'RESULTS.md' -o -name 'ERRORS.md' \
                     | grep -v node_modules | grep -v '/build/' | grep -v '/\.build/' | sort)
          echo "files with lint findings: $fails"
          echo "COVERAGE -"
          [ "$fails" -eq 0 ]
        else
          echo "writing lint not available on this runner, step informational"
          echo "COVERAGE -"
        fi
      ) > "$log" 2>&1 || status=failed
      ;;
    *)
      script="$ROOT/ci/$step.sh"
      if [ ! -x "$script" ] && [ ! -f "$script" ]; then
        echo "no script ci/$step.sh" > "$log"
        status=missing
      else
        ( cd "$ROOT" && CI_OUT="$CI_OUT/$step" timeout 2400 bash "$script" ) > "$log" 2>&1 || status=failed
      fi
      ;;
  esac
  end=$(date +%s)
  cov=$(grep -E '^COVERAGE ' "$log" | tail -1 | awk '{print $2}')
  [ -z "$cov" ] && cov="-"
  [ "$status" != passed ] && overall=1
  echo "[$step] $status in $((end-start))s coverage=$cov"
  results+=("{\"step\":\"$step\",\"status\":\"$status\",\"seconds\":$((end-start)),\"coverage\":\"$cov\"}")
done
printf '{"steps":[%s]}\n' "$(IFS=,; echo "${results[*]}")" > "$CI_OUT/summary.json"
exit $overall
