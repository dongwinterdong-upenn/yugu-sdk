#!/usr/bin/env python3
"""Self-hosted CI for the Yugu SDK monorepo.

  ci_runner.py enqueue --trigger push|nightly|manual [--sha SHA] [--ref main]
  ci_runner.py run          process every queued job, one at a time
  ci_runner.py render       rebuild the public pages from recorded builds

Layout (CI_HOME, default /home/ubuntu/yougu/sdk-ci):
  repo.git        private bare repository the developers push to
  queue/*.job     pending jobs (JSON)
  builds/<n>/     meta.json, console.log, out/ (step logs, junit, coverage)
  counter         last build number
Public pages are written to PUBLISH_DIR (default .../prod-ygyx/index/sdk/ci).
"""
import argparse
import datetime as dt
import html
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

CI_HOME = Path(os.environ.get('CI_HOME', '/home/ubuntu/yougu/sdk-ci'))
REPO = CI_HOME / 'repo.git'
QUEUE = CI_HOME / 'queue'
BUILDS = CI_HOME / 'builds'
WORK = CI_HOME / 'work'
PUBLISH = Path(os.environ.get('PUBLISH_DIR', '/home/ubuntu/yougu/frontend/prod-ygyx/index/sdk/ci'))
HOME = os.environ.get('HOME', '/home/ubuntu')
STEP_NAMES = {
    'contract': '契约与错误码', 'java': 'Java 服务端', 'android': '安卓', 'android-stcompat': '安卓声通平替',
    'web': '网页', 'miniprogram': '小程序', 'ios': 'iOS', 'ios-stcompat': 'iOS 声通平替', 'docs': '文档规范',
}
STATUS_ZH = {'passed': '通过', 'failed': '失败', 'missing': '缺少脚本', 'running': '进行中', 'error': '出错'}
TRIGGER_ZH = {'push': '提交', 'nightly': '每晚', 'manual': '手动'}


def now_iso():
    return dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def git(*args, cwd=None, check=True):
    return subprocess.run(['git', *args], cwd=cwd, check=check, capture_output=True, text=True).stdout.strip()


def enqueue(trigger, sha=None, ref='main'):
    QUEUE.mkdir(parents=True, exist_ok=True)
    if not sha:
        sha = git('--git-dir', str(REPO), 'rev-parse', ref)
    job = {'trigger': trigger, 'sha': sha, 'ref': ref, 'enqueued_at': now_iso()}
    name = f'{int(time.time() * 1000)}-{sha[:12]}-{trigger}.job'
    tmp = QUEUE / (name + '.tmp')
    tmp.write_text(json.dumps(job))
    tmp.rename(QUEUE / name)
    print('queued', name)


def next_number():
    c = CI_HOME / 'counter'
    n = int(c.read_text().strip()) + 1 if c.exists() else 1
    c.write_text(str(n))
    return n


def sanitize(text):
    text = text.replace(HOME + '/', '~/')
    text = re.sub(r'(?i)(secret(key)?|signature|token|password)(["\']?\s*[:=]\s*["\']?)[A-Za-z0-9+/=_\-]{12,}', r'\1\3***', text)
    return text


def run_job(job_file):
    job = json.loads(job_file.read_text())
    n = next_number()
    bdir = BUILDS / str(n)
    out = bdir / 'out'
    out.mkdir(parents=True, exist_ok=True)
    meta = {'number': n, **job, 'started_at': now_iso(), 'status': 'running', 'steps': []}
    (bdir / 'meta.json').write_text(json.dumps(meta, ensure_ascii=False, indent=1))
    job_file.unlink()
    wdir = WORK / str(n)
    shutil.rmtree(wdir, ignore_errors=True)
    t0 = time.time()
    console = bdir / 'console.log'
    try:
        git('clone', '--quiet', str(REPO), str(wdir))
        git('checkout', '--quiet', job['sha'], cwd=wdir)
        meta['short'] = job['sha'][:8]
        meta['subject'] = git('log', '-1', '--format=%s', cwd=wdir)
        meta['author'] = git('log', '-1', '--format=%an', cwd=wdir)
        meta['committed_at'] = git('log', '-1', '--format=%cI', cwd=wdir)
        render()
        env = dict(os.environ, CI_OUT=str(out), CI_BUILD_NUMBER=str(n), CI_COMMIT=job['sha'])
        with open(console, 'w') as f:
            p = subprocess.run(['bash', 'ci/run-all.sh'], cwd=wdir, env=env, stdout=f, stderr=subprocess.STDOUT, timeout=3 * 3600)
        summary = json.loads((out / 'summary.json').read_text()) if (out / 'summary.json').exists() else {'steps': []}
        meta['steps'] = summary.get('steps', [])
        meta['status'] = 'passed' if p.returncode == 0 else 'failed'
    except subprocess.TimeoutExpired:
        meta['status'] = 'failed'
        meta['error'] = 'build timed out after 3 hours'
    except Exception as e:  # noqa: BLE001
        meta['status'] = 'error'
        meta['error'] = str(e)[:500]
    meta['finished_at'] = now_iso()
    meta['duration_s'] = int(time.time() - t0)
    (bdir / 'meta.json').write_text(json.dumps(meta, ensure_ascii=False, indent=1))
    publish_build(n)
    shutil.rmtree(wdir, ignore_errors=True)
    render()
    print(f'build {n} {meta["status"]} in {meta["duration_s"]}s')


def publish_build(n):
    src = BUILDS / str(n)
    dst = PUBLISH / 'builds' / str(n)
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True, exist_ok=True)
    for p in src.rglob('*'):
        rel = p.relative_to(src)
        target = dst / rel
        if p.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        if p.suffix in ('.log', '.txt', '.json', '.xml') and p.stat().st_size < 50 * 1024 * 1024:
            target.write_text(sanitize(p.read_text(errors='replace')))
        else:
            shutil.copy2(p, target)


def load_builds():
    builds = []
    if BUILDS.exists():
        for d in BUILDS.iterdir():
            m = d / 'meta.json'
            if m.exists():
                try:
                    builds.append(json.loads(m.read_text()))
                except json.JSONDecodeError:
                    pass
    return sorted(builds, key=lambda b: b['number'], reverse=True)


CSS = '''body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:32px auto;max-width:1100px;padding:0 16px;color:#1f2328;background:#fff}
h1{font-size:22px}code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12.5px}
table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #e5e7eb;padding:7px 8px;text-align:left;vertical-align:top;font-size:13.5px}
th{color:#57606a;font-weight:600}.passed{color:#1a7f37}.failed,.error{color:#cf222e}.missing{color:#9a6700}.running{color:#0969da}
.wrap{overflow-x:auto}a{color:#0969da;text-decoration:none}p{line-height:1.7}'''


def cov_of(steps, name):
    for s in steps:
        if s['step'] == name:
            return s.get('coverage', '-')
    return '-'


def render():
    PUBLISH.mkdir(parents=True, exist_ok=True)
    builds = load_builds()
    rows = []
    for b in builds:
        st = b.get('status', 'running')
        steps = ''.join(f'<span class="{s["status"]}" title="{html.escape(STEP_NAMES.get(s["step"], s["step"]))} {STATUS_ZH.get(s["status"], s["status"])} {s.get("seconds", 0)} 秒">{"●" if s["status"] == "passed" else "✕" if s["status"] == "failed" else "○"}</span> ' for s in b.get('steps', []))
        rows.append(f'<tr><td><a href="builds/{b["number"]}/index.html">#{b["number"]}</a></td>'
                    f'<td class="{st}">{STATUS_ZH.get(st, st)}</td><td>{TRIGGER_ZH.get(b.get("trigger"), b.get("trigger"))}</td>'
                    f'<td><code>{html.escape(b.get("short", b.get("sha", "")[:8]))}</code> {html.escape(b.get("subject", ""))}</td>'
                    f'<td>{steps}</td><td>{html.escape(b.get("started_at", ""))}</td><td>{b.get("duration_s", "")}</td></tr>')
        render_build(b)
    last = builds[0] if builds else None
    window = dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=30)
    recent = [b for b in builds if b.get('started_at') and dt.datetime.strptime(b['started_at'], '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=dt.timezone.utc) >= window]
    page = f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>优谷雅言 SDK 持续集成</title><style>{CSS}</style></head><body>
<h1>优谷雅言 SDK 持续集成</h1>
<p>代码仓库 <code>https://open.shengzhiai.com/git/yugu-sdk.git</code> 每次提交与每晚北京时间 2 点各构建一次，构建脚本是仓库里的 <code>ci/run-all.sh</code>，五端单元测试，集成测试，覆盖率与声通接口比对都在其中。最近 30 天共 {len(recent)} 次构建，其中 {sum(1 for b in recent if b.get("status") == "passed")} 次通过。</p>
<div class="wrap"><table><thead><tr><th>构建</th><th>结果</th><th>触发</th><th>提交</th><th>各步骤</th><th>开始时间 UTC</th><th>耗时秒</th></tr></thead><tbody>
{''.join(rows)}
</tbody></table></div></body></html>
'''
    (PUBLISH / 'index.html').write_text(page, encoding='utf-8')
    status = {'last': last and {k: last.get(k) for k in ('number', 'status', 'sha', 'started_at', 'finished_at')},
              'builds_30d': len(recent), 'passed_30d': sum(1 for b in recent if b.get('status') == 'passed')}
    (PUBLISH / 'status.json').write_text(json.dumps(status, ensure_ascii=False, indent=1))


def render_build(b):
    d = PUBLISH / 'builds' / str(b['number'])
    d.mkdir(parents=True, exist_ok=True)
    rows = []
    for s in b.get('steps', []):
        step = s['step']
        links = [f'<a href="out/{step}.log">日志</a>']
        cov_dir = d / 'out' / step
        if cov_dir.exists():
            for idx in sorted(cov_dir.rglob('index.html')):
                links.append(f'<a href="{html.escape(str(idx.relative_to(d)))}">报告 {html.escape(str(idx.parent.relative_to(cov_dir)) or step)}</a>')
                if len(links) > 4:
                    break
        rows.append(f'<tr><td>{html.escape(STEP_NAMES.get(step, step))}</td><td class="{s["status"]}">{STATUS_ZH.get(s["status"], s["status"])}</td>'
                    f'<td>{s.get("seconds", "")}</td><td>{html.escape(str(s.get("coverage", "-")))}</td><td>{"，".join(links)}</td></tr>')
    st = b.get('status', 'running')
    page = f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>构建 #{b["number"]}</title><style>{CSS}</style></head><body>
<p><a href="../../index.html">全部构建</a></p>
<h1>构建 #{b["number"]}，<span class="{st}">{STATUS_ZH.get(st, st)}</span></h1>
<p>触发方式：{TRIGGER_ZH.get(b.get("trigger"), b.get("trigger"))}。提交：<code>{html.escape(b.get("sha", ""))}</code>，{html.escape(b.get("subject", ""))}，作者 {html.escape(b.get("author", ""))}。开始 {html.escape(b.get("started_at", ""))}，结束 {html.escape(b.get("finished_at", ""))}，耗时 {b.get("duration_s", "")} 秒。完整输出见 <a href="console.log">console.log</a>。</p>
{f'<p class="error">{html.escape(b["error"])}</p>' if b.get("error") else ''}
<div class="wrap"><table><thead><tr><th>步骤</th><th>结果</th><th>耗时秒</th><th>行覆盖率</th><th>日志与报告</th></tr></thead><tbody>
{''.join(rows)}
</tbody></table></div></body></html>
'''
    (d / 'index.html').write_text(page, encoding='utf-8')
    (d / 'meta.json').write_text(json.dumps(b, ensure_ascii=False, indent=1))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    e = sub.add_parser('enqueue')
    e.add_argument('--trigger', default='manual')
    e.add_argument('--sha')
    e.add_argument('--ref', default='main')
    sub.add_parser('run')
    sub.add_parser('render')
    a = ap.parse_args()
    if a.cmd == 'enqueue':
        enqueue(a.trigger, a.sha, a.ref)
    elif a.cmd == 'run':
        while True:
            jobs = sorted(QUEUE.glob('*.job')) if QUEUE.exists() else []
            if not jobs:
                break
            run_job(jobs[0])
        render()
    else:
        render()


if __name__ == '__main__':
    main()
