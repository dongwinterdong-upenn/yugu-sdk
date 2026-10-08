#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# API identity check of the Shengtong drop-in layer (com.stkouyu) against a 17kouyu jar.
#
#   tools/api-diff/run.sh <original.jar> <compat.jar|compat.aar> [--report-dir DIR] [--allow-extra]
#
# Step 1  japicmp 0.23.1 (downloaded once into tools/api-diff/lib, checksum pinned) on public and
#         protected elements with --error-on-binary-incompatibility and
#         --error-on-source-incompatibility, then a strict reading of its XML report: inside the
#         packages of the original jar every element, annotations and modifiers included, must be
#         unchanged (a newer class file version is tolerated).
# Step 2  javap textual diff (ApiDiff.java): same class headers, members, generic signatures,
#         throws clauses and constant values; "native" is ignored.
#
# Exit 0 when both steps pass, 1 when an element is missing or changed, 2 on usage or setup errors.
# Needs a JDK 11 or newer (java, javap via the JDK) and curl or wget for the first download.
# Environment: JAVA (java binary), JAPICMP_JAR (use this japicmp jar, no download),
# ANDROID_JAR (android.jar for japicmp; default: newest platform under $ANDROID_HOME).
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAPICMP_VERSION=0.23.1
JAPICMP_SHA256=f2300a8531b68e25b678247874a1eae13a07d6842a4a1236845481fc90c5c6c7
JAPICMP_SHA1=e57191ddc78a25ac27a9307e1ae2ed38e8304140
JAPICMP_URL="https://repo1.maven.org/maven2/com/github/siom79/japicmp/japicmp/${JAPICMP_VERSION}/japicmp-${JAPICMP_VERSION}-jar-with-dependencies.jar"
JAVA="${JAVA:-java}"

usage() {
    echo "usage: $0 <original.jar> <compat.jar|compat.aar> [--report-dir DIR] [--allow-extra]" >&2
    exit 2
}

ORIGINAL=""
COMPAT=""
REPORT_DIR="./api-diff-report"
ALLOW_EXTRA=""
while [ $# -gt 0 ]; do
    case "$1" in
        --report-dir) [ $# -ge 2 ] || usage; REPORT_DIR="$2"; shift 2 ;;
        --allow-extra) ALLOW_EXTRA="--allow-extra"; shift ;;
        -h|--help) usage ;;
        -*) echo "unknown option $1" >&2; usage ;;
        *) if [ -z "$ORIGINAL" ]; then ORIGINAL="$1"; elif [ -z "$COMPAT" ]; then COMPAT="$1"; else usage; fi; shift ;;
    esac
done
[ -n "$ORIGINAL" ] && [ -n "$COMPAT" ] || usage
[ -f "$ORIGINAL" ] || { echo "api-diff: original jar not found: $ORIGINAL" >&2; exit 2; }
[ -f "$COMPAT" ] || { echo "api-diff: compat jar or aar not found: $COMPAT" >&2; exit 2; }
command -v "$JAVA" >/dev/null 2>&1 || { echo "api-diff: java not found (set JAVA)" >&2; exit 2; }

mkdir -p "$REPORT_DIR" || exit 2
REPORT_DIR="$(cd "$REPORT_DIR" && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/api-diff.XXXXXX")" || exit 2
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------- inputs
case "$COMPAT" in
    *.aar)
        # an AAR is a zip with classes.jar at its root
        if command -v unzip >/dev/null 2>&1; then
            unzip -q -o "$COMPAT" classes.jar -d "$WORK" || { echo "api-diff: no classes.jar in $COMPAT" >&2; exit 2; }
        else
            ( cd "$WORK" && jar xf "$(cd "$(dirname "$COMPAT")" && pwd)/$(basename "$COMPAT")" classes.jar ) \
                || { echo "api-diff: cannot extract classes.jar (install unzip or a JDK with jar)" >&2; exit 2; }
        fi
        COMPAT_JAR="$WORK/classes.jar"
        ;;
    *) COMPAT_JAR="$COMPAT" ;;
esac

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}
sha1() {
    if command -v sha1sum >/dev/null 2>&1; then sha1sum "$1" | cut -d' ' -f1; else shasum -a 1 "$1" | cut -d' ' -f1; fi
}

JAPICMP="${JAPICMP_JAR:-$HERE/lib/japicmp-${JAPICMP_VERSION}-jar-with-dependencies.jar}"
if [ ! -f "$JAPICMP" ]; then
    mkdir -p "$(dirname "$JAPICMP")" || exit 2
    echo "api-diff: downloading japicmp ${JAPICMP_VERSION} from Maven Central"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --retry 3 -o "$JAPICMP.part" "$JAPICMP_URL"
    else
        wget -q -O "$JAPICMP.part" "$JAPICMP_URL"
    fi || { echo "api-diff: download failed: $JAPICMP_URL" >&2; rm -f "$JAPICMP.part"; exit 2; }
    got1="$(sha1 "$JAPICMP.part")"
    got256="$(sha256 "$JAPICMP.part")"
    if [ "$got1" != "$JAPICMP_SHA1" ] || [ "$got256" != "$JAPICMP_SHA256" ]; then
        echo "api-diff: checksum mismatch for japicmp (sha1 $got1, sha256 $got256)" >&2
        rm -f "$JAPICMP.part"
        exit 2
    fi
    mv "$JAPICMP.part" "$JAPICMP"
fi

if [ -z "${ANDROID_JAR:-}" ] && [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
    ANDROID_JAR="$(ls -1d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
fi
CP_ARGS=()
if [ -n "${ANDROID_JAR:-}" ] && [ -f "$ANDROID_JAR" ]; then
    CP_ARGS=(--old-classpath "$ANDROID_JAR" --new-classpath "$ANDROID_JAR")
else
    echo "api-diff: android.jar not found (set ANDROID_JAR or ANDROID_HOME); japicmp ignores missing super classes"
    CP_ARGS=(--ignore-missing-classes)
fi

# ---------------------------------------------------------------- step 1: japicmp
echo "== step 1: japicmp ${JAPICMP_VERSION}"
"$JAVA" -jar "$JAPICMP" --old "$ORIGINAL" --new "$COMPAT_JAR" -a protected "${CP_ARGS[@]}" \
    --error-on-binary-incompatibility --error-on-source-incompatibility \
    --html-file "$REPORT_DIR/japicmp.html" --xml-file "$REPORT_DIR/japicmp.xml" > "$REPORT_DIR/japicmp.txt" 2>&1
JAPICMP_RC=$?
if [ $JAPICMP_RC -eq 0 ]; then
    echo "japicmp: no binary or source incompatibility"
else
    echo "japicmp: incompatibilities found (exit $JAPICMP_RC), see $REPORT_DIR/japicmp.txt"
    grep -E '^\*\*\*!|^---!|---! |\*\*\*! ' "$REPORT_DIR/japicmp.txt" | head -40
fi
STRICT_RC=2
if [ -f "$REPORT_DIR/japicmp.xml" ]; then
    "$JAVA" "$HERE/ApiDiff.java" --japicmp-xml "$REPORT_DIR/japicmp.xml" --report "$REPORT_DIR/japicmp-strict.txt" \
        | grep -E '^(DIFF|  |NEW|REMOVED|MODIFIED|NOTE|checked|RESULT)'
    STRICT_RC=${PIPESTATUS[0]}
fi

# ---------------------------------------------------------------- step 2: javap
echo "== step 2: javap textual diff"
"$JAVA" "$HERE/ApiDiff.java" "$ORIGINAL" "$COMPAT_JAR" $ALLOW_EXTRA --report "$REPORT_DIR/javap-diff.txt" \
    | grep -Ev '^OK '
JAVAP_RC=${PIPESTATUS[0]}

# ---------------------------------------------------------------- summary
{
    echo "original: $ORIGINAL"
    echo "compat:   $COMPAT"
    echo "japicmp ${JAPICMP_VERSION}: exit $JAPICMP_RC (0 = compatible)"
    echo "japicmp strict: exit $STRICT_RC"
    grep -E '^checked' "$REPORT_DIR/japicmp-strict.txt" 2>/dev/null
    echo "javap diff: exit $JAVAP_RC"
    grep -E '^(compared|missing)' "$REPORT_DIR/javap-diff.txt" 2>/dev/null
} > "$REPORT_DIR/summary.txt"
echo "== reports in $REPORT_DIR"
if [ $JAPICMP_RC -eq 0 ] && [ $STRICT_RC -eq 0 ] && [ $JAVAP_RC -eq 0 ]; then
    echo "API-DIFF: OK" | tee -a "$REPORT_DIR/summary.txt"
    exit 0
fi
if [ $JAPICMP_RC -ge 2 ] && [ $JAPICMP_RC -ne 1 ] && [ ! -s "$REPORT_DIR/japicmp.xml" ]; then
    echo "API-DIFF: ERROR (japicmp did not run)" | tee -a "$REPORT_DIR/summary.txt"
    exit 2
fi
echo "API-DIFF: FAIL" | tee -a "$REPORT_DIR/summary.txt"
exit 1
