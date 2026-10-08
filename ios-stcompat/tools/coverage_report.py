#!/usr/bin/env python3
# Copyright 2026 优谷雅言 open.shengzhiai.com
# SPDX-License-Identifier: Apache-2.0
"""Line coverage report for the C core from gcov data (gcc --coverage).

  coverage_report.py --objdir build/cov --out ci-out/coverage [--include Sources/STKouyuEngine/core/]

Writes coverage.txt, coverage.json and index.html (per file summary plus annotated sources) and
prints a last line "TOTAL <percent>". The numbers can be recomputed with plain `gcov -n` on the
same object directory.
"""
import argparse
import html
import json
import os
import subprocess
import sys


def collect_text(objdir, include, gcov, obj, srcroot):
    """Fallback for gcov without JSON output (llvm-cov gcov on macOS): annotated text on stdout."""
    files = {}
    p = subprocess.run([gcov, "-t", "-o", objdir, obj], cwd=srcroot, capture_output=True, text=True)
    if p.returncode != 0:
        return files, None
    source, lines = None, None
    for raw in p.stdout.splitlines():
        parts = raw.split(":", 2)
        if len(parts) < 3:
            continue
        count, line_no = parts[0].strip(), parts[1].strip()
        if line_no == "0":
            if parts[2].startswith("Source:"):
                if source and lines:
                    files[source] = lines
                src = parts[2][len("Source:"):].strip()
                rel = os.path.relpath(os.path.normpath(src if os.path.isabs(src) else os.path.join(srcroot, src)),
                                      srcroot)
                source, lines = (rel, {}) if (not include or rel.startswith(include)) else (None, None)
            continue
        if lines is None or count == "-" or not line_no.isdigit():
            continue
        lines[int(line_no)] = 0 if count.startswith("#") or count.startswith("=") else int(count.rstrip("*") or 0)
    if source and lines:
        files[source] = lines
    return files, srcroot


def collect(objdir, include, gcov, srcroot, force_text=False):
    files = {}
    cwd_seen = None
    for name in sorted(os.listdir(objdir)):
        if not name.endswith(".gcda"):
            continue
        obj = os.path.join(objdir, name[:-5] + ".o")
        p = subprocess.run([gcov, "-j", "-t", obj], cwd=objdir, capture_output=True, text=True)
        if force_text or p.returncode != 0 or not p.stdout.strip().startswith("{"):
            text_files, cwd = collect_text(objdir, include, gcov, obj, srcroot)
            if not text_files:
                print("gcov failed for %s: %s" % (obj, p.stderr.strip()), file=sys.stderr)
            for path, lines in text_files.items():
                merged = files.setdefault(path, {})
                for n, c in lines.items():
                    merged[n] = max(merged.get(n, 0), c)
            cwd_seen = cwd_seen or cwd
            continue
        for chunk in p.stdout.splitlines():
            chunk = chunk.strip()
            if not chunk:
                continue
            data = json.loads(chunk)
            cwd = data.get("current_working_directory") or objdir
            for f in data["files"]:
                path = f["file"]
                full = path if os.path.isabs(path) else os.path.join(cwd, path)
                rel = os.path.relpath(os.path.normpath(full), srcroot)
                if include and not rel.startswith(include):
                    continue
                lines = files.setdefault(rel, {})
                for ln in f["lines"]:
                    n = ln["line_number"]
                    lines[n] = max(lines.get(n, 0), ln["count"])
            cwd_seen = srcroot
    return files, cwd_seen


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--objdir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--include", default="Sources/STKouyuEngine/core/")
    ap.add_argument("--gcov", default="gcov")
    ap.add_argument("--title", default="STKouyuEngine C core line coverage")
    ap.add_argument("--srcroot", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    help="directory the sources were compiled from (text fallback only)")
    ap.add_argument("--force-text", action="store_true", help="use the text fallback even when JSON works")
    a = ap.parse_args()
    files, cwd = collect(a.objdir, a.include, a.gcov, a.srcroot, a.force_text)
    os.makedirs(a.out, exist_ok=True)
    rows = []
    tot_lines = tot_cov = 0
    for path in sorted(files):
        lines = files[path]
        n = len(lines)
        cov = sum(1 for c in lines.values() if c > 0)
        tot_lines += n
        tot_cov += cov
        rows.append((path, cov, n, 100.0 * cov / n if n else 100.0))
    pct = 100.0 * tot_cov / tot_lines if tot_lines else 0.0

    with open(os.path.join(a.out, "coverage.txt"), "w", encoding="utf-8") as f:
        f.write("%s\n\n" % a.title)
        f.write("%-52s %8s %8s %8s\n" % ("file", "covered", "lines", "percent"))
        for path, cov, n, p in rows:
            f.write("%-52s %8d %8d %7.2f%%\n" % (path, cov, n, p))
        f.write("%-52s %8d %8d %7.2f%%\n" % ("TOTAL", tot_cov, tot_lines, pct))
    with open(os.path.join(a.out, "coverage.json"), "w", encoding="utf-8") as f:
        json.dump({"title": a.title, "total": {"covered": tot_cov, "lines": tot_lines, "percent": round(pct, 2)},
                   "files": [{"file": p, "covered": c, "lines": n, "percent": round(x, 2)} for p, c, n, x in rows]},
                  f, indent=1)

    parts = ["<!doctype html><html><head><meta charset='utf-8'><title>Core coverage</title>",
             "<meta name='viewport' content='width=device-width,initial-scale=1'>",
             "<style>body{font:14px system-ui,sans-serif;margin:16px;color:#1f2328;background:#fff}"
             "table{border-collapse:collapse}td,th{padding:2px 10px;text-align:right}td:first-child,th:first-child"
             "{text-align:left}pre{font:12px ui-monospace,monospace;margin:0}.hit{background:#e6f4ea}"
             ".miss{background:#fde7e9}.n{color:#8c959f;display:inline-block;width:5em}"
             "@media(prefers-color-scheme:dark){body{background:#0d1117;color:#e6edf3}.hit{background:#12361f}"
             ".miss{background:#4a1620}}</style></head><body>",
             "<h1>%s</h1><p>Total %.2f%% (%d of %d lines)</p><table><tr><th>file</th><th>covered</th>"
             "<th>lines</th><th>percent</th></tr>" % (html.escape(a.title), pct, tot_cov, tot_lines)]
    for path, cov, n, p in rows:
        anchor = os.path.basename(path)
        parts.append("<tr><td><a href='#%s'>%s</a></td><td>%d</td><td>%d</td><td>%.2f%%</td></tr>" % (
            anchor, html.escape(path), cov, n, p))
    parts.append("</table>")
    for path, cov, n, p in rows:
        src = os.path.join(cwd or ".", path)
        try:
            text = open(src, encoding="utf-8").read().splitlines()
        except OSError:
            text = []
        parts.append("<h2 id='%s'>%s %.2f%%</h2><pre>" % (os.path.basename(path), html.escape(path), p))
        for i, line in enumerate(text, 1):
            c = files[path].get(i)
            cls = "" if c is None else (" class='hit'" if c > 0 else " class='miss'")
            parts.append("<span%s><span class='n'>%d %s</span>%s</span>\n" % (
                cls, i, "" if c is None else str(c), html.escape(line)))
        parts.append("</pre>")
    parts.append("</body></html>")
    with open(os.path.join(a.out, "index.html"), "w", encoding="utf-8") as f:
        f.write("".join(parts))

    for path, cov, n, p in rows:
        print("%-52s %5d/%-5d %6.2f%%" % (path, cov, n, p))
    print("TOTAL %.2f" % pct)
    return 0


if __name__ == "__main__":
    sys.exit(main())
