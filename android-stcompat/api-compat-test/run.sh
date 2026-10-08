#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Customer-simulation compile test of the Shengtong drop-in layer.
#
#   android-stcompat/api-compat-test/run.sh [compat.jar|compat.aar]
#
# src/ holds code written against the public 17kouyu 1.0.0 API (every public constructor and
# method called, every listener implemented, every abstract class extended, every constant used
# in constant expressions, every public field read and written). It is compiled with plain javac
# twice: against the original jar plus android.jar, and against the compat classes plus
# android.jar. Both must compile.
#
# Environment:
#   ST_ORIGINAL_JAR  original 17kouyu_1.0.0.jar (default: the reference copy outside the repo);
#                    the original compile is skipped with a message when the file is absent,
#                    because Shengtong binaries are never committed or shipped
#   ANDROID_JAR      android.jar (default: newest platform under $ANDROID_HOME or $ANDROID_SDK_ROOT)
# Exit 0 when every compile that ran succeeded, 1 on a compile error, 2 on setup errors.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPAT="${1:-$HERE/../stkouyu-compat/build/outputs/aar/stkouyu-compat-release.aar}"
ORIGINAL="${ST_ORIGINAL_JAR:-/home/ubuntu/yougu/sdk-v2-work/ref/st_public/android/libs/17kouyu_1.0.0.jar}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "${ANDROID_JAR:-}" ] && [ -n "$SDK" ]; then
    ANDROID_JAR="$(ls -1d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
fi
if [ -z "${ANDROID_JAR:-}" ] || [ ! -f "$ANDROID_JAR" ]; then
    echo "api-compat-test: android.jar not found (set ANDROID_JAR or ANDROID_HOME)" >&2
    exit 2
fi
if [ ! -f "$COMPAT" ]; then
    echo "api-compat-test: compat jar or aar not found: $COMPAT (build :stkouyu-compat:assembleRelease first)" >&2
    exit 2
fi

OUT="$HERE/out"
rm -rf "$OUT"
mkdir -p "$OUT/original" "$OUT/compat" "$OUT/lib"
case "$COMPAT" in
    *.aar)
        if command -v unzip >/dev/null 2>&1; then
            unzip -q -o "$COMPAT" classes.jar -d "$OUT/lib" || exit 2
        else
            ( cd "$OUT/lib" && jar xf "$(cd "$(dirname "$COMPAT")" && pwd)/$(basename "$COMPAT")" classes.jar ) || exit 2
        fi
        COMPAT_JAR="$OUT/lib/classes.jar"
        ;;
    *) COMPAT_JAR="$COMPAT" ;;
esac

mapfile -t SOURCES < <(find "$HERE/src" -name '*.java' | sort)
JAVAC_OPTS=(--release 8 -encoding UTF-8 -Xlint:-options -nowarn)
STATUS=0

echo "api-compat-test: ${#SOURCES[@]} source file(s), android.jar $ANDROID_JAR"
if [ -f "$ORIGINAL" ]; then
    if javac "${JAVAC_OPTS[@]}" -d "$OUT/original" -cp "$ORIGINAL:$ANDROID_JAR" "${SOURCES[@]}"; then
        echo "compile against original jar ($ORIGINAL): OK"
    else
        echo "compile against original jar ($ORIGINAL): FAILED"
        STATUS=1
    fi
else
    echo "compile against original jar: SKIPPED, $ORIGINAL not found (set ST_ORIGINAL_JAR to a 17kouyu_1.0.0.jar)"
fi
if javac "${JAVAC_OPTS[@]}" -d "$OUT/compat" -cp "$COMPAT_JAR:$ANDROID_JAR" "${SOURCES[@]}"; then
    echo "compile against compat classes ($COMPAT): OK"
else
    echo "compile against compat classes ($COMPAT): FAILED"
    STATUS=1
fi
exit $STATUS
