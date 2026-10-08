#!/usr/bin/env node
// Installs the packed tarball into a clean scratch project (no network, no npm) and uses it the
// way a customer would: ESM import, CommonJS require, TypeScript through the package exports,
// a REST evaluation and a streaming evaluation against the mock platform. With --demo it also
// installs the tarball into demos/web-demo and drives the demo server through its proxy.
//
//   node scripts/consumer-smoke.mjs <tarball> <workdir> [--demo <demo dir>]
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO = path.resolve(WEB, '..');
const [tarball, workdirArg] = process.argv.slice(2);
const demoIdx = process.argv.indexOf('--demo');
const demoDir = demoIdx > 0 ? path.resolve(process.argv[demoIdx + 1]) : null;
if (!tarball || !workdirArg) {
  console.error('usage: node scripts/consumer-smoke.mjs <tarball> <workdir> [--demo <dir>]');
  process.exit(2);
}
const work = path.resolve(workdirArg);
const failures = [];
const check = (cond, what) => {
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${what}`);
  if (!cond) failures.push(what);
};

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', ...opts });
  return { status: r.status, out: `${r.stdout || ''}${r.stderr || ''}` };
}

function startJsonServer(args) {
  return new Promise((resolve, reject) => {
    const p = spawn(process.execPath, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let buf = '';
    const timer = setTimeout(() => reject(new Error(`no start line from ${args[0]}`)), 10000);
    p.stdout.on('data', (d) => {
      buf += d.toString();
      const nl = buf.indexOf('\n');
      if (nl >= 0) {
        clearTimeout(timer);
        resolve({ proc: p, info: JSON.parse(buf.slice(0, nl)) });
      }
    });
    p.on('exit', (code) => reject(new Error(`${args[0]} exited ${code}`)));
  });
}

// 1. clean project with the tarball extracted into node_modules
fs.rmSync(work, { recursive: true, force: true });
const pkgDir = path.join(work, 'node_modules/@shengzhiai/yugu-web-sdk');
fs.mkdirSync(pkgDir, { recursive: true });
const untar = run('tar', ['-xzf', path.resolve(tarball), '-C', pkgDir, '--strip-components=1']);
check(untar.status === 0, 'tarball extracts');
fs.writeFileSync(path.join(work, 'package.json'), JSON.stringify({ name: 'consumer', private: true, type: 'module' }));
const wsEntry = path.join(WEB, 'node_modules/ws/wrapper.mjs');
const wav = path.join(REPO, 'spec/fixtures/audio/zh_short.wav');

fs.writeFileSync(
  path.join(work, 'esm.mjs'),
  `import fs from 'node:fs';
import WebSocket from ${JSON.stringify(wsEntry)};
import { YuguClient, SDK_VERSION, isRetryable, YuguError } from '@shengzhiai/yugu-web-sdk';
const base = process.env.YUGU_BASE;
const client = new YuguClient({ baseUrl: base, appKey: 'mock-app-key', secretKey: 'mock-secret-key', WebSocket, logLevel: 'OFF' });
const audio = fs.readFileSync(${JSON.stringify(wav)});
const rest = await client.evaluate(audio, { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' });
const pcm = audio.subarray(78);
const stream = await new Promise((resolve, reject) => {
  const s = client.streamEvaluate({ coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' }, { onResult: resolve, onError: reject });
  for (let i = 0; i < pcm.length; i += 640) s.sendAudio(pcm.subarray(i, i + 640));
  s.end();
});
await client.close();
console.log(JSON.stringify({ version: SDK_VERSION, rest: rest.overall, stream: stream.overall, key: rest.idempotencyKey, fn: typeof isRetryable, err: typeof YuguError }));
`,
);
fs.writeFileSync(
  path.join(work, 'cjs.cjs'),
  `const sdk = require('@shengzhiai/yugu-web-sdk');
console.log(JSON.stringify({ version: sdk.SDK_VERSION, client: typeof sdk.YuguClient, n: Object.keys(sdk).length }));
`,
);
fs.writeFileSync(
  path.join(work, 'usage.ts'),
  `import { YuguClient, type EvalResult } from '@shengzhiai/yugu-web-sdk';
export async function score(audio: ArrayBuffer): Promise<number | null> {
  const c = new YuguClient({ token: 't' });
  const r: EvalResult = await c.evaluate(audio, { coreType: 'word', referenceText: 'apple' });
  return r.connected ? r.connected.overall : r.overall;
}
`,
);
fs.writeFileSync(
  path.join(work, 'tsconfig.json'),
  JSON.stringify({ compilerOptions: { strict: true, noEmit: true, module: 'NodeNext', moduleResolution: 'NodeNext', target: 'ES2020', lib: ['ES2020', 'DOM'], types: [], skipLibCheck: false }, files: ['usage.ts'] }),
);

// 2. CommonJS and TypeScript need no server
const cjs = run(process.execPath, ['cjs.cjs'], { cwd: work });
check(cjs.status === 0 && /"version":"2\.0\.0"/.test(cjs.out) && /"client":"function"/.test(cjs.out), `require() of the package: ${cjs.out.trim()}`);
const tsc = run(process.execPath, [path.join(WEB, 'node_modules/typescript/bin/tsc'), '-p', 'tsconfig.json'], { cwd: work });
check(tsc.status === 0, `TypeScript resolves the published types: ${tsc.out.trim() || 'no errors'}`);
for (const f of ['dist/yugu-sdk.mjs', 'dist/yugu-sdk.umd.js', 'dist/yugu-sdk.umd.min.js', 'types/index.d.ts', 'types/error-table.d.ts', 'README.md', 'CHANGELOG.md', 'LICENSE', 'NOTICE']) {
  check(fs.existsSync(path.join(pkgDir, f)), `tarball contains ${f}`);
}
for (const f of ['test', 'scripts', 'coverage', 'types-test', 'node_modules']) {
  check(!fs.existsSync(path.join(pkgDir, f)), `tarball leaves out ${f}`);
}

// 3. ESM against the mock platform, directly and through the demo server
const mock = await startJsonServer([path.join(REPO, 'tools/mock-server/server.mjs'), '--port', '0']);
const procs = [mock.proc];
try {
  const base = `http://127.0.0.1:${mock.info.port}`;
  const esm = run(process.execPath, ['esm.mjs'], { cwd: work, env: { ...process.env, YUGU_BASE: base } });
  check(esm.status === 0 && /"rest":93\.7/.test(esm.out) && /"stream":93\.7/.test(esm.out), `ESM import, REST and streaming against the mock: ${esm.out.trim()}`);
  if (demoDir) {
    const inst = run(process.execPath, [path.join(demoDir, 'scripts/install-tarball.mjs'), path.resolve(tarball)]);
    check(inst.status === 0, `demo installs the tarball offline: ${inst.out.trim()}`);
    const demo = await startJsonServer([path.join(demoDir, 'server.mjs'), '--port', '0', '--api', base]);
    procs.push(demo.proc);
    const url = demo.info.url.replace(/\/$/, '');
    const page = await fetch(`${url}/`);
    check(page.status === 200 && /优谷雅言/.test(await page.text()), 'demo page served');
    const bundle = await fetch(`${url}/sdk/yugu-sdk.mjs`);
    check(bundle.status === 200 && /javascript/.test(bundle.headers.get('content-type')), 'demo serves the SDK bundle from node_modules');
    const app = await fetch(`${url}/app.js`);
    check(app.status === 200, 'demo script served');
    const cfg = await (await fetch(`${url}/demo-config.json`)).json();
    check(cfg.proxied === true, 'demo proxies the platform');
    const viaDemo = run(process.execPath, ['esm.mjs'], { cwd: work, env: { ...process.env, YUGU_BASE: url } });
    check(viaDemo.status === 0 && /"rest":93\.7/.test(viaDemo.out) && /"stream":93\.7/.test(viaDemo.out), `REST and WebSocket through the demo proxy: ${viaDemo.out.trim()}`);
  }
} finally {
  for (const p of procs) p.kill('SIGTERM');
}

if (failures.length) {
  console.error(`consumer smoke failed: ${failures.length}`);
  process.exit(1);
}
console.log('consumer smoke ok');
