#!/usr/bin/env python3
"""Render the SDK documentation to static HTML under /sdk/v2/ and copy the raw sources.

Usage:
  python3 tools/registry/publish_docs.py --repo . --out /home/ubuntu/yougu/frontend/prod-ygyx/index/sdk/v2

Every markdown file listed in DOCS is rendered to <out>/<path>.html with a shared template and
copied as <out>/<path> (raw). Links between markdown files are rewritten to the .html pages.
spec/openapi.yaml, spec/errors.json and the signature vectors are copied as they are.
"""
import argparse
import html
import re
import shutil
from pathlib import Path

import markdown

DOCS = [
    ('README.md', 'SDK 总览'), ('CONTRACT.md', '接口契约'), ('RESULTS.md', '评测结果字段'), ('ERRORS.md', '错误码表'),
    ('SANDBOX.md', '沙箱环境'), ('COMPATIBILITY.md', '版本兼容矩阵'), ('CHANGELOG.md', '变更记录'),
    ('MIGRATION-2.0.md', '升级到 2.0'), ('SHENGTONG-MIGRATION.md', '声通 SDK 平替'),
    ('java/README.md', 'Java 服务端 SDK'), ('java/CHANGELOG.md', 'Java 变更记录'),
    ('android/README.md', '安卓 SDK'), ('android/CHANGELOG.md', '安卓变更记录'),
    ('android-stcompat/README.md', '安卓声通平替'), ('android-stcompat/CHANGELOG.md', '安卓声通平替变更记录'),
    ('web/README.md', '网页 SDK'), ('web/CHANGELOG.md', '网页变更记录'),
    ('miniprogram/README.md', '小程序 SDK'), ('miniprogram/CHANGELOG.md', '小程序变更记录'),
    ('ios/README.md', 'iOS SDK'), ('ios/CHANGELOG.md', 'iOS 变更记录'),
    ('ios-stcompat/README.md', 'iOS 声通平替'), ('ios-stcompat/CHANGELOG.md', 'iOS 声通平替变更记录'),
]
RAW = ['spec/openapi.yaml', 'spec/errors.json', 'spec/fixtures/sign/vectors.json', 'LICENSE', 'NOTICE']

CSS = '''body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:0;color:#1f2328;background:#fff;line-height:1.7}
.wrap{max-width:960px;margin:0 auto;padding:24px 16px 64px}nav{font-size:13px;color:#57606a;margin-bottom:16px}
nav a{color:#0969da;text-decoration:none;margin-right:10px}h1{font-size:26px}h2{font-size:20px;margin-top:32px;border-bottom:1px solid #e5e7eb;padding-bottom:4px}
h3{font-size:16px}code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px;background:#f6f8fa;padding:1px 4px;border-radius:3px}
pre{background:#f6f8fa;padding:12px;overflow-x:auto;border-radius:4px}pre code{background:none;padding:0}
table{border-collapse:collapse;display:block;overflow-x:auto;margin:12px 0}td,th{border:1px solid #e5e7eb;padding:6px 10px;text-align:left;vertical-align:top;font-size:14px}
th{background:#f6f8fa}a{color:#0969da}'''


def rewrite_links(text):
    def fix(m):
        target = m.group(2)
        if target.startswith(('http://', 'https://', '#', 'mailto:')):
            return m.group(0)
        base, _, anchor = target.partition('#')
        if base.endswith('.md'):
            base = base + '.html'
        return f'{m.group(1)}({base}{"#" + anchor if anchor else ""})'
    return re.sub(r'(\[[^\]]*\])\(([^)\s]+)\)', fix, text)


def page(title, body, depth):
    up = '../' * depth
    nav = ''.join(f'<a href="{up}{p}.html">{html.escape(t)}</a>' for p, t in DOCS[:9])
    return f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{html.escape(title)}</title><style>{CSS}</style></head><body><div class="wrap">
<nav>{nav}<a href="/sdk/ci/">持续集成</a><a href="/maven/">Maven 仓库</a><a href="/npm/">npm 源</a></nav>
{body}
</div></body></html>
'''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--repo', default='.')
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    repo, out = Path(a.repo).resolve(), Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    done = 0
    for rel, title in DOCS:
        src = repo / rel
        if not src.exists():
            print('skip missing', rel)
            continue
        text = src.read_text(encoding='utf-8')
        body = markdown.markdown(rewrite_links(text), extensions=['tables', 'fenced_code', 'toc'])
        target = out / (rel + '.html')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(page(title, body, rel.count('/')), encoding='utf-8')
        shutil.copy2(src, out / rel)
        done += 1
    for rel in RAW:
        src = repo / rel
        if src.exists():
            (out / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, out / rel)
    index = out / 'index.html'
    if (out / 'README.md.html').exists():
        shutil.copy2(out / 'README.md.html', index)
    print(f'rendered {done} documents into {out}')


if __name__ == '__main__':
    main()
