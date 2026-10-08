// The published bundle: CommonJS, ES2017 syntax, no newer runtime APIs, no Node globals, the same
// exports as src/index.js and types/index.d.ts, and package.json metadata (A-04, B-01).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import * as acorn from 'acorn';
import { PKG_ROOT, fixtureJson } from '../helpers/common.mjs';
import * as src from '../../src/index.js';

const require = createRequire(import.meta.url);
const DIST = path.join(PKG_ROOT, 'miniprogram_dist/index.js');
const pkg = JSON.parse(fs.readFileSync(path.join(PKG_ROOT, 'package.json'), 'utf8'));
const code = fs.readFileSync(DIST, 'utf8');

test('package.json carries the published coordinates', () => {
  assert.equal(pkg.name, '@shengzhiai/yugu-miniprogram-sdk');
  assert.equal(pkg.version, '2.0.0');
  assert.equal(pkg.license, 'Apache-2.0');
  assert.equal(pkg.main, 'miniprogram_dist/index.js');
  assert.equal(pkg.miniprogram, 'miniprogram_dist');
  assert.equal(pkg.types, 'types/index.d.ts');
  assert.deepEqual(pkg.repository, { type: 'git', url: 'https://open.shengzhiai.com/git/yugu-sdk.git', directory: 'miniprogram' });
  assert.equal(pkg.homepage, 'https://open.shengzhiai.com/docs.html#sdk');
  assert.equal(pkg.publishConfig.registry, 'https://open.shengzhiai.com/npm/');
  assert.equal(pkg.dependencies, undefined, 'zero runtime dependencies');
  for (const f of ['miniprogram_dist', 'types', 'README.md', 'CHANGELOG.md', 'LICENSE']) assert.ok(pkg.files.includes(f), f);
  assert.equal(src.SDK_VERSION, pkg.version);
  assert.equal(src.USER_AGENT, 'yugu-miniprogram-sdk/' + pkg.version);
});

test('the CommonJS bundle exports exactly what src/index.js exports', () => {
  const dist = require(DIST);
  assert.deepEqual(Object.keys(dist).sort(), Object.keys(src).sort());
  const v = fixtureJson('sign/vectors.json').cases[0];
  assert.equal(dist.signParams(v.params, v.secret), v.signature);
  assert.equal(dist.fromCode(40901).name, 'ConflictException');
  assert.ok(Object.isFrozen(dist.ERROR_TABLE));
  assert.ok(code.startsWith('/*! @shengzhiai/yugu-miniprogram-sdk 2.0.0'));
});

test('the bundle is ES2017 syntax and calls no newer runtime API', () => {
  assert.doesNotThrow(() => acorn.parse(code, { ecmaVersion: 2017, sourceType: 'script' }));
  const names = new Set();
  for (const tok of acorn.tokenizer(code, { ecmaVersion: 2017 })) if (tok.type.label === 'name') names.add(tok.value);
  const forbidden = ['fromEntries', 'globalThis', 'TextEncoder', 'TextDecoder', 'AbortController', 'structuredClone',
    'allSettled', 'flatMap', 'matchAll', 'replaceAll', 'trimStart', 'trimEnd', 'finally', 'BigInt', 'queueMicrotask',
    'Buffer', 'process', 'require', 'setImmediate', 'crypto', 'WeakRef'];
  assert.deepEqual(forbidden.filter((n) => names.has(n)), []);
});

test('every runtime export is declared in types/index.d.ts and every declared value exists', () => {
  const dts = fs.readFileSync(path.join(PKG_ROOT, 'types/index.d.ts'), 'utf8');
  const declared = new Set();
  for (const m of dts.matchAll(/export declare (?:const|function|class) (\w+)/g)) declared.add(m[1]);
  const reexport = /export \{([^}]+)\} from '\.\/error-table'/.exec(dts);
  for (const n of reexport[1].split(',')) if (n.trim()) declared.add(n.trim());
  assert.deepEqual([...declared].sort(), Object.keys(src).sort());
});

test('types-test/index.ts imports and uses every runtime export', () => {
  const ts = fs.readFileSync(path.join(PKG_ROOT, 'types-test/index.ts'), 'utf8');
  const valueImport = /import \{([^}]+)\} from '@shengzhiai\/yugu-miniprogram-sdk'/.exec(ts);
  const imported = new Set(valueImport[1].split(',').map((n) => n.trim()).filter(Boolean));
  assert.deepEqual([...imported].sort(), Object.keys(src).sort());
  const body = ts.slice(valueImport.index + valueImport[0].length);
  for (const name of imported) assert.match(body, new RegExp('\\b' + name + '\\b'), name + ' is imported but never used');
});

test('the bundle runs in a bare JavaScript context without Node globals', async () => {
  const requests = [];
  const sandbox = {
    console: { log() {}, info() {}, warn() {}, error() {} },
    setTimeout, clearTimeout, setInterval, clearInterval,
    wx: {
      request(o) {
        requests.push(o);
        setTimeout(() => o.success({ statusCode: 200, header: { 'Idempotency-Replayed': 'true' }, data: JSON.stringify({ code: 0, data: { audioUrl: '/audio/a.mp3', duration: '1.2' } }) }), 0);
        return { abort() {} };
      },
      connectSocket() { throw new Error('not used'); },
      getRecorderManager() { return {}; },
      getFileSystemManager() { return { readFile() {} }; },
    },
  };
  sandbox.module = { exports: {} };
  sandbox.exports = sandbox.module.exports;
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox, { filename: 'index.js' });
  const sdk = sandbox.module.exports;
  const client = new sdk.YuguClient({ auth: { appKey: 'ak', secretKey: 'sk' } });
  const r = await client.tts({ text: '你好' });
  assert.equal(r.fullUrl, 'https://open.shengzhiai.com/tts/audio/a.mp3');
  assert.equal(r.replayed, true);
  assert.equal(requests[0].header['X-Signature'], src.signParams({ text: '你好', language: 'zh-CN', voice: 'xiaoyan', format: 'mp3', speed: '50', pitch: '50', volume: '50' }, 'sk'));
  const err = sdk.fromCode(42900);
  assert.equal(err instanceof sdk.RateLimitException, true);
  assert.equal(err instanceof sdk.YuguError, true);
  client.close();
});
