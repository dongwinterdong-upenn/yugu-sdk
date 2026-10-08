#!/usr/bin/env node
// Static checks without third-party linters: syntax, forbidden patterns, license headers,
// package metadata and freshness of the generated error tables.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO = path.resolve(ROOT, '..');
const problems = [];
const rel = (f) => path.relative(ROOT, f);

function walk(dir, exts) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p, exts));
    else if (exts.some((x) => e.name.endsWith(x))) out.push(p);
  }
  return out;
}

const srcFiles = walk(path.join(ROOT, 'src'), ['.js']);
const jsFiles = [...srcFiles, ...walk(path.join(ROOT, 'scripts'), ['.mjs']), ...walk(path.join(ROOT, 'test'), ['.mjs'])];

for (const f of jsFiles) {
  const r = spawnSync(process.execPath, ['--check', f], { encoding: 'utf8' });
  if (r.status !== 0) problems.push(`${rel(f)}: syntax error\n${r.stderr}`);
}

const GENERATED = new Set(['error-table.js']);
for (const f of srcFiles) {
  const text = fs.readFileSync(f, 'utf8');
  const name = path.basename(f);
  if (!GENERATED.has(name) && !text.startsWith('// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.')) {
    problems.push(`${rel(f)}: missing license header`);
  }
  const rules = [
    [/console\.log\(/, 'console.log (use the logger)'],
    [/\bdebugger\b/, 'debugger statement'],
    [/new FormData\b/, 'FormData (build multipart by hand, DESIGN 5.2)'],
    [/streamRealtime/, 'streamRealtime was removed in 2.0.0'],
    [/\?\?|\?\./, 'optional chaining or nullish coalescing (keep sources plain ES2018)'],
  ];
  for (const [re, what] of rules) if (re.test(text)) problems.push(`${rel(f)}: ${what}`);
}

const HOSTS = /ygyx\.dragonai\.tech|voiceapi\.dragonai\.tech|dragonai\.tech/;
for (const f of [...srcFiles, ...walk(path.join(ROOT, 'types'), ['.ts']), path.join(ROOT, 'README.md')]) {
  if (fs.existsSync(f) && HOSTS.test(fs.readFileSync(f, 'utf8'))) problems.push(`${rel(f)}: internal host name`);
}

const pkg = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8'));
const version = /SDK_VERSION = '([^']+)'/.exec(fs.readFileSync(path.join(ROOT, 'src/version.js'), 'utf8'))[1];
const expect = (cond, msg) => {
  if (!cond) problems.push(`package.json: ${msg}`);
};
expect(pkg.name === '@shengzhiai/yugu-web-sdk', 'name');
expect(pkg.version === version, `version ${pkg.version} differs from SDK_VERSION ${version}`);
expect(pkg.license === 'Apache-2.0', 'license must be Apache-2.0');
expect(pkg.type === 'module', 'type must be module');
expect(pkg.sideEffects === false, 'sideEffects must be false');
expect(pkg.types === './types/index.d.ts', 'types');
expect(pkg.publishConfig && pkg.publishConfig.registry === 'https://open.shengzhiai.com/npm/', 'publishConfig.registry');
expect(pkg.engines && pkg.engines.node === '>=18', 'engines.node');
expect(!pkg.dependencies || Object.keys(pkg.dependencies).length === 0, 'runtime dependencies must be empty');
for (const f of ['dist', 'src', 'types', 'README.md', 'CHANGELOG.md', 'LICENSE']) expect(pkg.files.includes(f), `files lacks ${f}`);

// Generated tables: regenerate from spec/errors.json in a scratch copy and compare.
const gen = path.join(REPO, 'tools/gen-errors.mjs');
const spec = path.join(REPO, 'spec/errors.json');
if (fs.existsSync(gen) && fs.existsSync(spec)) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'yugu-gen-'));
  fs.mkdirSync(path.join(tmp, 'tools'));
  fs.mkdirSync(path.join(tmp, 'spec'));
  fs.copyFileSync(gen, path.join(tmp, 'tools/gen-errors.mjs'));
  fs.copyFileSync(spec, path.join(tmp, 'spec/errors.json'));
  const r = spawnSync(process.execPath, [path.join(tmp, 'tools/gen-errors.mjs')], { encoding: 'utf8' });
  if (r.status !== 0) problems.push(`gen-errors failed: ${r.stderr}`);
  for (const f of ['web/src/error-table.js', 'web/types/error-table.d.ts']) {
    const want = fs.readFileSync(path.join(tmp, f), 'utf8');
    const have = fs.readFileSync(path.join(REPO, f), 'utf8');
    if (want !== have) problems.push(`${f}: stale, run node tools/gen-errors.mjs`);
  }
  fs.rmSync(tmp, { recursive: true, force: true });
} else {
  problems.push('tools/gen-errors.mjs or spec/errors.json not found');
}

if (problems.length) {
  for (const p of problems) console.error(`lint: ${p}`);
  console.error(`lint failed: ${problems.length} problem(s)`);
  process.exit(1);
}
console.log(`lint ok: ${jsFiles.length} files`);
