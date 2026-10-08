#!/usr/bin/env python3
"""Merge a staging Maven repository into the served repository.

Usage:
  python3 tools/registry/maven_publish.py --staging DIR --repo SERVED_DIR [--dry-run]

Rules:
- Released versions are immutable. A version directory that already exists is accepted only when
  every file is byte-identical, otherwise the publish fails and nothing is written.
- Every artifact file gets .md5 .sha1 .sha256 .sha512 checksums.
- maven-metadata.xml at artifact level is rebuilt from the version directories present after the
  merge, with checksums.
- An index.html listing is written at the repository root.
"""
import argparse
import hashlib
import html
import os
import re
import shutil
import sys
import time
from pathlib import Path

CHECKSUMS = ('md5', 'sha1', 'sha256', 'sha512')
SKIP_SUFFIXES = tuple('.' + c for c in CHECKSUMS) + ('.asc',)


def digest(path, algo):
    h = hashlib.new(algo)
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def version_key(v):
    parts = re.split(r'[.-]', v)
    key = []
    for p in parts:
        key.append((0, int(p), '') if p.isdigit() else (1, 0, p))
    return key


def find_version_dirs(root):
    """Yield (group_path, artifact, version, dir) for every directory holding a .pom."""
    for dirpath, _dirs, files in os.walk(root):
        poms = [f for f in files if f.endswith('.pom')]
        if not poms:
            continue
        d = Path(dirpath)
        version = d.name
        artifact = d.parent.name
        group_path = d.parent.parent.relative_to(root)
        yield group_path, artifact, version, d


def write_checksums(path):
    for algo in CHECKSUMS:
        Path(str(path) + '.' + algo).write_text(digest(path, algo))


def rebuild_metadata(artifact_dir, group_id, artifact_id):
    versions = sorted([p.name for p in artifact_dir.iterdir() if p.is_dir() and any(f.suffix == '.pom' for f in p.iterdir())],
                      key=version_key)
    if not versions:
        return
    releases = [v for v in versions if not v.endswith('-SNAPSHOT')]
    stamp = time.strftime('%Y%m%d%H%M%S', time.gmtime())
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<metadata>',
             f'  <groupId>{group_id}</groupId>', f'  <artifactId>{artifact_id}</artifactId>', '  <versioning>',
             f'    <latest>{versions[-1]}</latest>']
    if releases:
        lines.append(f'    <release>{releases[-1]}</release>')
    lines.append('    <versions>')
    lines += [f'      <version>{v}</version>' for v in versions]
    lines += ['    </versions>', f'    <lastUpdated>{stamp}</lastUpdated>', '  </versioning>', '</metadata>', '']
    meta = artifact_dir / 'maven-metadata.xml'
    meta.write_text('\n'.join(lines), encoding='utf-8')
    write_checksums(meta)


def write_index(repo):
    rows = []
    for group_path, artifact, version, d in sorted(find_version_dirs(repo), key=lambda t: (str(t[0]), t[1], version_key(t[2]))):
        gid = str(group_path).replace(os.sep, '.')
        rel = d.relative_to(repo)
        files = sorted(p.name for p in d.iterdir() if p.is_file() and not p.name.endswith(SKIP_SUFFIXES))
        links = '，'.join(f'<a href="{html.escape(str(rel / f))}">{html.escape(f)}</a>' for f in files)
        rows.append(f'<tr><td><code>{html.escape(gid)}:{html.escape(artifact)}:{html.escape(version)}</code></td><td>{links}</td></tr>')
    page = f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>优谷雅言 Maven 仓库</title>
<style>body{{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:32px auto;max-width:960px;padding:0 16px;color:#1f2328;background:#fff}}
code{{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px}}table{{border-collapse:collapse;width:100%}}td,th{{border-bottom:1px solid #e5e7eb;padding:8px;text-align:left;vertical-align:top;font-size:14px}}
pre{{background:#f6f8fa;padding:12px;overflow-x:auto}}</style></head><body>
<h1>优谷雅言 Maven 仓库</h1>
<p>匿名只读，无需账号。仓库地址：<code>https://open.shengzhiai.com/maven/</code>。已发布的版本不会被覆盖，可以随时回退到任一历史版本。</p>
<pre>repositories {{ maven {{ url "https://open.shengzhiai.com/maven/" }} }}</pre>
<table><thead><tr><th>坐标</th><th>文件</th></tr></thead><tbody>
{''.join(rows)}
</tbody></table></body></html>
'''
    (repo / 'index.html').write_text(page, encoding='utf-8')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--staging', required=True)
    ap.add_argument('--repo', required=True)
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()
    staging, repo = Path(a.staging).resolve(), Path(a.repo).resolve()
    repo.mkdir(parents=True, exist_ok=True)

    plan, conflicts = [], []
    for group_path, artifact, version, d in find_version_dirs(staging):
        target = repo / group_path / artifact / version
        files = [p for p in d.iterdir() if p.is_file() and not p.name.endswith(SKIP_SUFFIXES) and not p.name.startswith('maven-metadata')]
        if target.exists():
            for p in files:
                t = target / p.name
                if not t.exists() or digest(p, 'sha256') != digest(t, 'sha256'):
                    conflicts.append(f'{group_path}/{artifact}/{version}/{p.name}')
            plan.append(('identical', group_path, artifact, version, d, target, files))
        else:
            plan.append(('new', group_path, artifact, version, d, target, files))
    if conflicts:
        print('refusing to overwrite released files:', *conflicts, sep='\n  ', file=sys.stderr)
        sys.exit(2)
    for kind, group_path, artifact, version, d, target, files in plan:
        print(f'{kind:9} {str(group_path).replace(os.sep, ".")}:{artifact}:{version} ({len(files)} files)')
    if a.dry_run:
        return
    touched = set()
    for kind, group_path, artifact, version, d, target, files in plan:
        if kind == 'new':
            target.mkdir(parents=True, exist_ok=True)
            for p in files:
                shutil.copy2(p, target / p.name)
                write_checksums(target / p.name)
        touched.add((group_path, artifact))
    for group_path, artifact in touched:
        rebuild_metadata(repo / group_path / artifact, str(group_path).replace(os.sep, '.'), artifact)
    write_index(repo)
    print('published to', repo)


if __name__ == '__main__':
    main()
