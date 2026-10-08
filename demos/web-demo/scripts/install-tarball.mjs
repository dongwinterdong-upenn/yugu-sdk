#!/usr/bin/env node
// Installs @shengzhiai/yugu-web-sdk from a local tarball (npm pack output) without any network
// access. Used by CI before the package is published, and for offline trials.
//
//   node scripts/install-tarball.mjs ../../web/shengzhiai-yugu-web-sdk-2.0.0.tgz
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const tgz = process.argv[2] || process.env.YUGU_WEB_SDK_TARBALL;
if (!tgz || !fs.existsSync(tgz)) {
  console.error('用法：node scripts/install-tarball.mjs <shengzhiai-yugu-web-sdk-2.0.0.tgz>');
  process.exit(2);
}
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'yugu-sdk-'));
const r = spawnSync('tar', ['-xzf', path.resolve(tgz), '-C', tmp], { stdio: 'inherit' });
if (r.status !== 0) {
  console.error('tar 解包失败');
  process.exit(1);
}
const pkgDir = path.join(tmp, 'package');
const pkg = JSON.parse(fs.readFileSync(path.join(pkgDir, 'package.json'), 'utf8'));
if (pkg.name !== '@shengzhiai/yugu-web-sdk') {
  console.error(`不是 @shengzhiai/yugu-web-sdk：${pkg.name}`);
  process.exit(1);
}
const dest = path.join(HERE, 'node_modules/@shengzhiai/yugu-web-sdk');
fs.rmSync(dest, { recursive: true, force: true });
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.cpSync(pkgDir, dest, { recursive: true });
fs.rmSync(tmp, { recursive: true, force: true });
console.log(`installed ${pkg.name}@${pkg.version} from ${tgz}`);
