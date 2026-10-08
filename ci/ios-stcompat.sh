#!/usr/bin/env bash
# Copyright 2026 优谷雅言 open.shengzhiai.com
# SPDX-License-Identifier: Apache-2.0
#
# CI of the iOS Shengtong drop-in package (ios-stcompat) on Linux.
#
#   ci/ios-stcompat.sh
#
# Environment:
#   CI_OUT               output directory (default ./ci-out/ios-stcompat)
#   CC                   C compiler (default cc)
#   GCOV                 gcov of that compiler (default gcov; llvm-cov gcov for clang)
#   ST_ORIGINAL_HEADERS  Shengtong STKouyuEngine.framework/Headers to diff against; skipped when absent.
#                        Shengtong files are never committed to this repository.
#   ST_ORIGINAL_SKEGN    Shengtong skegn.h (default: ../skegn.h next to the framework directory)
#   OBJC_CHECK_PYTHON    python with clang.cindex for the Objective-C syntax check (optional)
#   SWIFT                swift binary for the Package.swift check (optional, default: swift in PATH)
#   WRITING_LINT         Chinese writing lint for README and CHANGELOG (optional)
#   YUGU_SANDBOX_APPKEY  sandbox keys (SANDBOX.md): when both are set, the sandbox step runs three
#   YUGU_SANDBOX_SECRET  end-to-end cases against the real platform, at most 6 calls; otherwise the
#                        step is reported as skipped. Push builds never set them.
#   YUGU_SANDBOX_BASE    sandbox base URL (default https://open.shengzhiai.com)
#
# Steps: C error table in sync with spec/errors.json, strict C99 build of the core, core unit tests
# with gcov coverage, the same tests under ASan and UBSan, integration tests against the mock
# platform, sandbox cases through the same C core over HTTPS (OpenSSL), coverage report, header
# identity check and its self test, Objective-C syntax check with libclang against stub SDK
# declarations, docs lint. The last line is "COVERAGE <pct>".
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="$ROOT/ios-stcompat"
OUT="${CI_OUT:-./ci-out/ios-stcompat}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
BUILD="$OUT/build"
CC="${CC:-cc}"
GCOV="${GCOV:-gcov}"
NICE=(nice -n 10)
ST_ORIGINAL_HEADERS="${ST_ORIGINAL_HEADERS:-/home/ubuntu/yougu/sdk-v2-work/ref/st_public/ios/STKouyuEngine.framework/Headers}"
ST_ORIGINAL_SKEGN="${ST_ORIGINAL_SKEGN:-$(dirname "$ST_ORIGINAL_HEADERS")/../skegn.h}"
WRITING_LINT="${WRITING_LINT:-/home/ubuntu/开题/J1/系统/app/tools/writing_lint.py}"
CORE="$PKG/Sources/STKouyuEngine/core"
SPEC="$ROOT/spec"
MOCK="$ROOT/tools/mock-server/server.mjs"

rm -rf "$BUILD"
mkdir -p "$BUILD/obj" "$BUILD/asan" "$OUT/logs"

FAILED=0
SUMMARY=()
COVERAGE="0.00"

run_step() {
    local name="$1"
    shift
    local log="$OUT/logs/$name.log"
    local t0 t1 status
    t0=$(date +%s)
    echo "== $name"
    if "$@" >"$log" 2>&1; then
        status=ok
    else
        local rc=$?
        if [ "$rc" -eq 77 ]; then
            status=skipped
        else
            status=FAIL
            FAILED=1
        fi
    fi
    t1=$(date +%s)
    tail -n 25 "$log"
    SUMMARY+=("$(printf '%-16s %-8s %4ss' "$name" "$status" "$((t1 - t0))")")
    echo "-- $name: $status"
}

# ------------------------------------------------------------------ steps

step_errors_table() {
    if [ ! -f "$SPEC/errors.json" ]; then
        echo "spec/errors.json not found (published package without the monorepo): skipped"
        return 77
    fi
    python3 "$PKG/tools/gen_c_errors.py" --spec "$SPEC/errors.json" --check
}

CFLAGS_STRICT=(-std=c99 -pedantic -Wall -Wextra -Wshadow -Wstrict-prototypes -Wmissing-prototypes -Wconversion
    -Wno-sign-conversion -Werror)

step_core_strict() {
    local f
    for f in "$CORE"/*.c; do
        "${NICE[@]}" "$CC" "${CFLAGS_STRICT[@]}" -O2 -c "$f" -o "$BUILD/strict-$(basename "$f" .c).o" || return 1
    done
    echo "core compiles warning-free with: $CC ${CFLAGS_STRICT[*]}"
}

step_core_tests() {
    local f
    # compiled from the package directory: clang records source paths relative to the working
    # directory in the coverage notes, and the coverage report resolves them against the package
    for f in "$CORE"/*.c; do
        (cd "$PKG" && "${NICE[@]}" "$CC" -std=c99 -O0 -g --coverage -I"$CORE" -c "$f" -o "$BUILD/obj/$(basename "$f" .c).o") ||
            return 1
    done
    "${NICE[@]}" "$CC" -std=c99 -O0 -g -I"$CORE" -I"$PKG/core-tests/unit" "$PKG"/core-tests/unit/*.c "$BUILD"/obj/*.o \
        --coverage -lm -o "$BUILD/ygst_tests" || return 1
    "${NICE[@]}" "$CC" -std=c99 -O0 -g -I"$CORE" "$PKG/core-tests/integration/it_driver.c" "$BUILD"/obj/*.o \
        --coverage -lm -o "$BUILD/it_driver" || return 1
    # the HTTPS driver of the sandbox step, built on every run so its transport stays compiled
    if printf '#include <openssl/ssl.h>\nint main(void) { return SSL_CTX_new(TLS_client_method()) ? 0 : 1; }\n' |
        "$CC" -x c - -lssl -lcrypto -o "$BUILD/openssl-probe" >/dev/null 2>&1; then
        "${NICE[@]}" "$CC" -std=c99 -O1 -g -Wall -Wextra -Werror -DYGST_IT_TLS -I"$CORE" \
            "$PKG/core-tests/integration/it_driver.c" "$CORE"/*.c -lssl -lcrypto -lm -o "$BUILD/it_driver_tls" || return 1
        echo "it_driver_tls built (HTTPS transport of the sandbox step)"
    else
        echo "OpenSSL development files not found: it_driver_tls not built, the sandbox step needs it"
    fi
    "$BUILD/ygst_tests" --spec "$SPEC" --junit "$OUT/core-tests.xml"
}

step_core_sanitizers() {
    if ! "$CC" -fsanitize=address,undefined -x c - -o "$BUILD/asan/probe" >/dev/null 2>&1 <<<'int main(void){return 0;}'; then
        echo "compiler without ASan or UBSan: skipped"
        return 77
    fi
    "${NICE[@]}" "$CC" -std=c99 -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -I"$CORE" \
        -I"$PKG/core-tests/unit" "$CORE"/*.c "$PKG"/core-tests/unit/*.c -lm -o "$BUILD/asan/ygst_tests" || return 1
    # LeakSanitizer exists on Linux only; AddressSanitizer on macOS refuses to start with detect_leaks=1
    local asan_options=""
    [ "$(uname -s)" = "Linux" ] && asan_options="detect_leaks=1"
    ASAN_OPTIONS="$asan_options" UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1 \
        "$BUILD/asan/ygst_tests" --spec "$SPEC" | tail -n 3
    return "${PIPESTATUS[0]}"
}

step_integration() {
    if ! command -v node >/dev/null 2>&1; then
        echo "node not found: the mock platform cannot start"
        return 1
    fi
    if [ ! -d "$ROOT/tools/mock-server/node_modules/ws" ]; then
        (cd "$ROOT/tools/mock-server" && npm ci --no-audit --no-fund) || return 1
    fi
    python3 "$PKG/core-tests/integration/run_it.py" --driver "$BUILD/it_driver" --mock "$MOCK" --spec "$SPEC" \
        --junit "$OUT/integration.xml" --log "$OUT/integration.log"
}

step_sandbox() {
    # run_sandbox.py exits 77 (skipped) without both keys and fails when they are set but the HTTPS
    # driver could not be built. Key values never reach a command line or the logs.
    local args=(--spec "$SPEC" --junit "$OUT/sandbox.xml" --log "$OUT/sandbox.log")
    if [ -x "$BUILD/it_driver_tls" ]; then
        args+=(--driver "$BUILD/it_driver_tls")
    fi
    python3 "$PKG/core-tests/integration/run_sandbox.py" "${args[@]}"
}

step_coverage() {
    python3 "$PKG/tools/coverage_report.py" --objdir "$BUILD/obj" --out "$OUT/coverage" --gcov "$GCOV" || return 1
    COVERAGE="$(python3 -c 'import json,sys; print("%.2f" % json.load(open(sys.argv[1]))["total"]["percent"])' \
        "$OUT/coverage/coverage.json")"
    echo "C core line coverage $COVERAGE % (gate 70 %), report $OUT/coverage/index.html"
    python3 -c 'import sys; sys.exit(0 if float(sys.argv[1]) >= 70.0 else 1)' "$COVERAGE"
}

step_headers_diff() {
    local args=()
    if [ ! -d "$ST_ORIGINAL_HEADERS" ]; then
        echo "ST_ORIGINAL_HEADERS ($ST_ORIGINAL_HEADERS) not found: header identity check skipped."
        echo "Set ST_ORIGINAL_HEADERS to a STKouyuEngine.framework/Headers directory to run it."
        return 77
    fi
    args=(--original "$ST_ORIGINAL_HEADERS")
    if [ -f "$ST_ORIGINAL_SKEGN" ]; then
        args+=(--original "$ST_ORIGINAL_SKEGN")
    else
        echo "skegn.h not found at $ST_ORIGINAL_SKEGN: C API header not compared"
    fi
    python3 "$PKG/tools/headers-diff.py" "${args[@]}" --strict --json "$OUT/headers-diff.json"
}

step_headers_selftest() {
    python3 "$PKG/tools/test_headers_diff.py"
}

step_objc_syntax() {
    local py="${OBJC_CHECK_PYTHON:-}"
    local cache="${YGST_TOOL_CACHE:-$HOME/.cache/yugu-sdk-ci}/venv-libclang"
    if [ -z "$py" ]; then
        if [ ! -x "$cache/bin/python" ]; then
            python3 -m venv "$cache" >/dev/null 2>&1 && "$cache/bin/pip" install --quiet libclang==18.1.1 >/dev/null 2>&1 ||
                { echo "libclang not available (no venv or no network): Objective-C syntax check skipped"; rm -rf "$cache"; return 77; }
        fi
        py="$cache/bin/python"
    fi
    # the check runs against the stub declarations only: an SDKROOT set for the C build on macOS
    # would add the macOS SDK as sysroot of the iOS parse as well
    env -u SDKROOT "$py" "$PKG/core-tests/objc-syntax/check.py" --json "$OUT/objc-syntax.json"
}

step_manifest() {
    local swift="${SWIFT:-$(command -v swift || true)}"
    local spm="$BUILD/spm"
    if [ -z "$swift" ] || [ ! -x "$swift" ]; then
        echo "no Swift toolchain (set SWIFT): Package.swift check skipped"
        return 77
    fi
    "$swift" --version | head -n 1
    (cd "$PKG" && "$swift" package --scratch-path "$spm/build" --cache-path "$spm/cache" --config-path "$spm/config" \
        --security-path "$spm/security" describe) >"$OUT/package-describe.txt" 2>&1 || { cat "$OUT/package-describe.txt"; return 1; }
    if grep -E '^(warning|error):' "$OUT/package-describe.txt"; then
        return 1
    fi
    grep -E '^    Name: STKouyuEngine|Module type' "$OUT/package-describe.txt"
    echo "Package.swift loads without warnings (Linux cannot compile the Objective-C targets)"
}

step_docs_lint() {
    if [ ! -f "$WRITING_LINT" ]; then
        echo "writing lint not found at $WRITING_LINT: skipped"
        return 77
    fi
    python3 "$WRITING_LINT" --external "$PKG/README.md" "$PKG/CHANGELOG.md"
}

run_step errors-table step_errors_table
run_step core-strict step_core_strict
run_step core-tests step_core_tests
run_step core-sanitizers step_core_sanitizers
run_step integration step_integration
run_step sandbox step_sandbox
run_step coverage step_coverage
run_step headers-diff step_headers_diff
run_step headers-selftest step_headers_selftest
run_step objc-syntax step_objc_syntax
run_step manifest step_manifest
run_step docs-lint step_docs_lint

{
    echo "{"
    echo "  \"package\": \"ios-stcompat\","
    echo "  \"coverage\": $COVERAGE,"
    echo "  \"failed\": $FAILED,"
    echo "  \"steps\": ["
    for i in "${!SUMMARY[@]}"; do
        read -r n s d <<<"${SUMMARY[$i]}"
        printf '    {"name": "%s", "status": "%s", "seconds": %s}%s\n' "$n" "$s" "${d%s}" \
            "$([ "$i" -lt $((${#SUMMARY[@]} - 1)) ] && echo ,)"
    done
    echo "  ]"
    echo "}"
} >"$OUT/summary.json"

echo
echo "ios-stcompat CI summary"
printf '%s\n' "${SUMMARY[@]}"
echo
echo "NOTE: the Objective-C layer (KYTestEngine, recorder, NSURLSession uploader, skegn C API) is verified only"
echo "      on macOS by ci/ios-stcompat-macos.sh. On Linux it is syntax-checked with libclang against stub"
echo "      declarations, not compiled against the Apple SDK, not linked and not run."
echo "results in $OUT"
echo "COVERAGE $COVERAGE"
exit "$FAILED"
