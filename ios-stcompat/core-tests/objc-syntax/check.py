#!/usr/bin/env python3
# Copyright 2026 优谷雅言 open.shengzhiai.com
# SPDX-License-Identifier: Apache-2.0
"""Syntax and type check of the Objective-C layer on Linux with libclang.

What this proves: every .m file of the target and of the Objective-C test support target parses as
Objective-C with ARC and blocks for arm64-apple-ios12.0 and x86_64-apple-macos10.15, with
modules on and the package module map loaded, and type-checks against hand-written stub
declarations of the Apple APIs it calls (stubs/). The C API header also parses as plain C.

What this does not prove: the stubs are not the Apple SDK, so a selector or type that differs
between a stub and the SDK is not caught; nothing is code-generated, linked or run. The
authoritative build is ci/ios-stcompat-macos.sh on a Mac.

  python3 check.py [--libclang /path/to/libclang.so] [--json report.json]

Needs the clang.cindex Python bindings with libclang (pip install libclang==18.1.1, in a venv).
"""
import argparse
import glob
import json
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = os.path.dirname(os.path.dirname(HERE))
SRC = os.path.join(PKG, "Sources", "STKouyuEngine")
STUBS = os.path.join(HERE, "stubs")

TARGETS = [("ios", "arm64-apple-ios12.0"), ("macos", "x86_64-apple-macos10.15")]

WARNINGS = ["-Wall", "-Wextra", "-Wno-unused-parameter", "-Wobjc-missing-super-calls", "-Wimplicit-retain-self",
            "-Wstrict-selector-match", "-Wundeclared-selector", "-Wnullable-to-nonnull-conversion",
            "-Wshadow", "-Wformat=2", "-Wno-format-nonliteral", "-Wmissing-prototypes"]


def base_args(triple, cache, objc=True, own_module=True):
    args = ["-target", triple, "-nostdinc", "-fsyntax-only", "-fblocks",
            "-isystem", os.path.join(STUBS, "libc"), "-isystem", STUBS,
            "-I", os.path.join(SRC, "include"), "-I", SRC, "-I", os.path.join(SRC, "core")]
    if objc:
        args = ["-x", "objective-c", "-std=gnu11", "-fobjc-arc", "-fmodules", "-fmodules-cache-path=" + cache,
                "-fmodule-map-file=" + os.path.join(SRC, "include", "module.modulemap")] + args
        if own_module:
            args.append("-fmodule-name=STKouyuEngine")
    else:
        args = ["-x", "c", "-std=c99"] + args
    return args + WARNINGS


def check(index, ci, path, args, unsaved=None):
    tu = index.parse(path, args=args, unsaved_files=unsaved or [],
                     options=ci.TranslationUnit.PARSE_DETAILED_PROCESSING_RECORD)
    out = []
    for d in tu.diagnostics:
        if d.severity < ci.Diagnostic.Warning:
            continue
        loc = d.location
        fname = loc.file.name if loc.file else "?"
        out.append({"severity": "error" if d.severity >= ci.Diagnostic.Error else "warning",
                    "file": os.path.relpath(fname, PKG) if fname != "?" else fname,
                    "line": loc.line, "column": loc.column, "message": d.spelling,
                    "option": d.option})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--libclang", help="path of libclang.so when clang.cindex cannot find it")
    ap.add_argument("--json")
    a = ap.parse_args()
    try:
        import clang.cindex as ci
    except ImportError:
        print("SKIP: clang.cindex is not installed (pip install libclang==18.1.1 in a venv)")
        return 3
    if a.libclang:
        ci.Config.set_library_file(a.libclang)
    index = ci.Index.create()
    support = os.path.join(PKG, "Tests", "STKouyuEngineObjCSupport")
    files = sorted(glob.glob(os.path.join(SRC, "*.m"))) + sorted(glob.glob(os.path.join(support, "*.m")))
    results = []
    errors = warnings = 0
    with tempfile.TemporaryDirectory() as cache:
        for plat, triple in TARGETS:
            for f in files:
                own = f.startswith(SRC)
                extra = [] if own else ["-I", os.path.join(support, "include")]
                diags = check(index, ci, f, base_args(triple, cache, objc=True, own_module=own) + extra)
                results.append({"platform": plat, "file": os.path.relpath(f, PKG), "diagnostics": diags})
            # the C API must stay usable from plain C
            c_src = ('#include "skegn.h"\n'
                     'static int cb(const void *u, const char *id, int t, const void *m, int n)'
                     '{ (void)u; (void)id; (void)t; (void)m; return n; }\n'
                     'int ygst_c_api_probe(void);\n'
                     'int ygst_c_api_probe(void) { char id[64]; struct skegn *e = skegn_new("{}");'
                     ' skegn_start(e, "{}", id, cb, 0); skegn_feed(e, id, 1); skegn_stop(e);'
                     ' skegn_cancel(e); skegn_opt(e, SKEGN_OPT_GET_VERSION, id, 64);'
                     ' skegn_get_device_id(id); return skegn_delete(e) + SKEGN_MESSAGE_TYPE_JSON; }\n')
            probe = os.path.join(SRC, "include", "STKouyuEngine", "__probe.c")
            diags = check(index, ci, probe,
                          base_args(triple, cache, objc=False) + ["-I", os.path.join(SRC, "include", "STKouyuEngine")],
                          unsaved=[(probe, c_src)])
            results.append({"platform": plat, "file": "skegn.h as C", "diagnostics": diags})
    for r in results:
        e = sum(1 for d in r["diagnostics"] if d["severity"] == "error")
        w = len(r["diagnostics"]) - e
        errors += e
        warnings += w
        print("%-6s %-58s %s" % (r["platform"], r["file"], "ok" if not r["diagnostics"] else
                                 "%d error(s), %d warning(s)" % (e, w)))
        for d in r["diagnostics"]:
            print("       %s:%d:%d: %s: %s [%s]" % (d["file"], d["line"], d["column"], d["severity"], d["message"],
                                                     d["option"]))
    print("Objective-C syntax check: %d translation units, %d errors, %d warnings (libclang %s)" % (
        len(results), errors, warnings, ci.conf.lib.clang_getClangVersion and "18"))
    if a.json:
        with open(a.json, "w", encoding="utf-8") as fh:
            json.dump({"units": len(results), "errors": errors, "warnings": warnings, "results": results}, fh,
                      indent=1)
    return 1 if errors or warnings else 0


if __name__ == "__main__":
    sys.exit(main())
