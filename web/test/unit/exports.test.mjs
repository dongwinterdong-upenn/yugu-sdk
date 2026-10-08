import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import vm from 'node:vm';
import { describe, test } from 'node:test';
import * as SDK from '../../src/index.js';
import { WEB, readJson } from '../helpers/common.mjs';

const require = createRequire(import.meta.url);
const pkg = JSON.parse(fs.readFileSync(path.join(WEB, 'package.json'), 'utf8'));
const vector = readJson('sign/vectors.json').cases[0];

describe('package entry points', () => {
  test('version, defaults and namespace object', () => {
    assert.equal(SDK.SDK_VERSION, pkg.version);
    assert.equal(SDK.SDK_VERSION, '2.0.0');
    assert.equal(SDK.DEFAULT_BASE_URL, 'https://open.shengzhiai.com');
    assert.equal(SDK.DEFAULT_WS_BASE_URL, 'wss://open.shengzhiai.com');
    assert.equal(SDK.CLIENT_DEFAULTS.userAgent, 'yugu-web-sdk/2.0.0');
    assert.ok(Object.isFrozen(SDK.default));
    for (const k of Object.keys(SDK)) if (k !== 'default') assert.equal(SDK.default[k], SDK[k], k);
    assert.equal('streamRealtime' in SDK.YuguClient.prototype, false, 'streamRealtime removed in 2.0.0');
  });

  test('ESM bundle works and exports the same names', async () => {
    const esm = await import(path.join(WEB, 'dist/yugu-sdk.mjs'));
    assert.deepEqual(Object.keys(esm).sort(), Object.keys(SDK).sort());
    assert.equal(await esm.signHmacSha256(vector.params, vector.secret), vector.signature);
    const err = esm.YuguErrors.fromCode(42900);
    assert.ok(err instanceof esm.RateLimitException && err instanceof esm.YuguError && err.retryable);
    assert.equal(err.name, 'RateLimitException');
  });

  test('UMD bundle: CommonJS require and browser global YuguSDK, minified too', async () => {
    const cjs = require(path.join(WEB, 'dist/yugu-sdk.umd.js'));
    assert.deepEqual(Object.keys(cjs).sort(), Object.keys(SDK).sort());
    assert.equal(await cjs.signHmacSha256(vector.params, vector.secret), vector.signature);
    for (const file of ['dist/yugu-sdk.umd.js', 'dist/yugu-sdk.umd.min.js']) {
      const code = fs.readFileSync(path.join(WEB, file), 'utf8');
      const sandbox = { TextEncoder, TextDecoder, crypto: globalThis.crypto, AbortController, setTimeout, clearTimeout, console, Blob, URLSearchParams, URL, btoa, atob };
      sandbox.window = sandbox;
      sandbox.self = sandbox;
      sandbox.globalThis = sandbox;
      vm.createContext(sandbox);
      vm.runInContext(code, sandbox, { filename: file });
      const g = sandbox.YuguSDK;
      assert.equal(typeof g.YuguClient, 'function', file);
      assert.equal(g.SDK_VERSION, '2.0.0');
      const e = g.YuguErrors.fromCode(90003);
      assert.equal(e.name, 'RequestCancelledException', `${file}: names survive minification`);
      assert.equal(await g.signHmacSha256(vector.params, vector.secret), vector.signature);
    }
  });

  test('worklet module and dist/package.json', () => {
    assert.match(fs.readFileSync(path.join(WEB, 'dist/yugu-pcm-worklet.js'), 'utf8'), /registerProcessor/);
    assert.deepEqual(JSON.parse(fs.readFileSync(path.join(WEB, 'dist/package.json'), 'utf8')), { type: 'commonjs' });
  });
});
