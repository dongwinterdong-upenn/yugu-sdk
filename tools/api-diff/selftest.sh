#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Self-test of tools/api-diff/run.sh on small synthetic jars (no third-party binaries): an
# identical API must pass; a removed method, a changed constant, an added final modifier, a
# removed class, a changed return type and a removed annotation must each fail.
#
#   tools/api-diff/selftest.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/api-diff-selftest.XXXXXX")" || exit 2
trap 'rm -rf "$WORK"' EXIT

write_base() {
    # $1 directory, then a list of edits applied with sed on the base source
    mkdir -p "$1/src/demo/api"
    cat > "$1/src/demo/api/Engine.java" <<'EOF'
package demo.api;

public class Engine {
    public static final String VERSION = "1.0.62";
    public static final int CODE = 7;
    public static long handle;

    public Engine() {
    }

    public java.util.List<String> names() {
        return null;
    }

    @Deprecated
    public void legacy(int x) {
    }

    public void start(String a) throws java.io.IOException {
    }

    public interface Callback {
        int run(byte[] id, int type);
    }
}
EOF
    cat > "$1/src/demo/api/Extra.java" <<'EOF'
package demo.api;

public class Extra {
    public int value() {
        return 1;
    }
}
EOF
}

build() {
    local dir="$1"
    mkdir -p "$dir/classes"
    javac --release 8 -nowarn -d "$dir/classes" $(find "$dir/src" -name '*.java') || return 1
    ( cd "$dir/classes" && jar cf "../api.jar" . ) || return 1
}

variant() {
    local name="$1" expr="$2" file="${3:-Engine.java}"
    write_base "$WORK/$name"
    if [ "$expr" = "rm-extra" ]; then
        rm "$WORK/$name/src/demo/api/Extra.java"
    elif [ -n "$expr" ]; then
        sed -i "$expr" "$WORK/$name/src/demo/api/$file"
    fi
    build "$WORK/$name" || { echo "selftest: cannot build $name" >&2; exit 2; }
}

variant original ""
variant same ""
variant removed-method '/public void start/,/^    }/d'
variant changed-constant 's/"1.0.62"/"9.9.9"/'
variant added-final 's/public java.util.List<String> names()/public final java.util.List<String> names()/'
variant removed-class rm-extra
variant changed-return 's/public int value()/public long value()/' Extra.java
variant removed-annotation '/@Deprecated/d'

FAIL=0
expect() {
    local name="$1" want="$2"
    "$HERE/run.sh" "$WORK/original/api.jar" "$WORK/$name/api.jar" --report-dir "$WORK/report-$name" > "$WORK/$name.log" 2>&1
    local rc=$?
    if { [ "$want" = pass ] && [ $rc -eq 0 ]; } || { [ "$want" = fail ] && [ $rc -eq 1 ]; }; then
        echo "ok   $name (exit $rc)"
    else
        echo "FAIL $name: expected $want, exit $rc"
        tail -20 "$WORK/$name.log"
        FAIL=1
    fi
}
expect same pass
expect removed-method fail
expect changed-constant fail
expect added-final fail
expect removed-class fail
expect changed-return fail
expect removed-annotation fail
if [ $FAIL -eq 0 ]; then
    echo "api-diff selftest passed"
fi
exit $FAIL
