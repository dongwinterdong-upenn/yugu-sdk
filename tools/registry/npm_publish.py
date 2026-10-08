#!/usr/bin/env python3
"""Add an npm package tarball to the static npm registry served under /npm/.

Usage:
  python3 tools/registry/npm_publish.py --tgz pkg.tgz --registry-dir SERVED_NPM_DIR \
      --base-url https://open.shengzhiai.com/npm/ [--dry-run]

Layout written:
  <dir>/@scope/name/index.json            packument (npm, pnpm and yarn read it for installs)
  <dir>/@scope/name/-/name-<ver>.tgz      tarball

Rules: a published version is immutable (an identical re-publish is a no-op, a different tarball
for the same version fails), dist-tags.latest follows the highest release version, shasum is the
sha1 hex and integrity the sha512 SRI of the tarball.
"""
import argparse
import base64
import hashlib
import html
import json
import re
import shutil
import sys
import tarfile
import time
from pathlib import Path


def version_key(v):
    main, _, pre = v.partition('-')
    nums = [int(x) if x.isdigit() else 0 for x in main.split('.')]
    return (nums, 0 if pre else 1, pre)


def read_package_json(tgz):
    with tarfile.open(tgz, 'r:gz') as t:
        for m in t.getmembers():
            if m.name in ('package/package.json', './package/package.json'):
                return json.load(t.extractfile(m))
            if re.match(r'^[^/]+/package\.json$', m.name):
                return json.load(t.extractfile(m))
    raise SystemExit('package.json not found in ' + str(tgz))


def read_readme(tgz):
    with tarfile.open(tgz, 'r:gz') as t:
        for m in t.getmembers():
            if re.match(r'^[^/]+/README(\.md)?$', m.name, re.I):
                return t.extractfile(m).read().decode('utf-8', 'replace')
    return ''


def write_index(root):
    rows = []
    for idx in sorted(root.glob('@*/*/index.json')):
        p = json.loads(idx.read_text(encoding='utf-8'))
        vers = sorted(p.get('versions', {}), key=version_key)
        rows.append(f'<tr><td><code>{html.escape(p["name"])}</code></td><td>{html.escape("，".join(vers))}</td>'
                    f'<td>{html.escape(p.get("description", ""))}</td></tr>')
    page = f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>优谷雅言 npm 源</title>
<style>body{{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:32px auto;max-width:960px;padding:0 16px;color:#1f2328;background:#fff}}
code{{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px}}table{{border-collapse:collapse;width:100%}}td,th{{border-bottom:1px solid #e5e7eb;padding:8px;text-align:left;vertical-align:top;font-size:14px}}
pre{{background:#f6f8fa;padding:12px;overflow-x:auto}}</style></head><body>
<h1>优谷雅言 npm 源</h1>
<p>匿名只读，无需账号。在项目的 <code>.npmrc</code> 里加一行作用域配置后，按包名与版本号安装，已发布的版本不会被覆盖。</p>
<pre>@shengzhiai:registry=https://open.shengzhiai.com/npm/</pre>
<table><thead><tr><th>包名</th><th>版本</th><th>说明</th></tr></thead><tbody>
{''.join(rows)}
</tbody></table></body></html>
'''
    (root / 'index.html').write_text(page, encoding='utf-8')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--tgz', required=True)
    ap.add_argument('--registry-dir', required=True)
    ap.add_argument('--base-url', required=True)
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()
    tgz = Path(a.tgz).resolve()
    root = Path(a.registry_dir).resolve()
    base = a.base_url.rstrip('/') + '/'
    pkg = read_package_json(tgz)
    name, version = pkg['name'], pkg['version']
    if not name.startswith('@') or '/' not in name:
        raise SystemExit('only scoped packages are supported: ' + name)
    scope, short = name[1:].split('/', 1)
    pdir = root / ('@' + scope) / short
    tar_name = f'{short}-{version}.tgz'
    tar_path = pdir / '-' / tar_name
    data = tgz.read_bytes()
    shasum = hashlib.sha1(data).hexdigest()
    integrity = 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode()

    idx_path = pdir / 'index.json'
    packument = json.loads(idx_path.read_text(encoding='utf-8')) if idx_path.exists() else {
        '_id': name, 'name': name, 'dist-tags': {}, 'versions': {}, 'time': {}}
    if version in packument['versions']:
        old = packument['versions'][version]['dist']
        if old.get('integrity') == integrity:
            print(f'{name}@{version} already published, identical, nothing to do')
            return
        raise SystemExit(f'refusing to overwrite released {name}@{version}')

    manifest = dict(pkg)
    for k in ('scripts', 'devDependencies'):
        manifest.pop(k, None)
    manifest['_id'] = f'{name}@{version}'
    manifest['dist'] = {'tarball': f'{base}@{scope}/{short}/-/{tar_name}', 'shasum': shasum, 'integrity': integrity}
    now = time.strftime('%Y-%m-%dT%H:%M:%S.000Z', time.gmtime())
    packument['versions'][version] = manifest
    packument['time'][version] = now
    packument['time'].setdefault('created', now)
    packument['time']['modified'] = now
    releases = [v for v in packument['versions'] if '-' not in v]
    packument['dist-tags']['latest'] = max(releases or packument['versions'], key=version_key)
    for k in ('description', 'license', 'homepage', 'repository', 'keywords', 'author'):
        if k in pkg:
            packument[k] = pkg[k]
    packument['readme'] = read_readme(tgz)
    print(f'publish {name}@{version} shasum={shasum}')
    if a.dry_run:
        return
    tar_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(tgz, tar_path)
    idx_path.write_text(json.dumps(packument, ensure_ascii=False, indent=1), encoding='utf-8')
    write_index(root)
    print('published to', pdir)


if __name__ == '__main__':
    main()
