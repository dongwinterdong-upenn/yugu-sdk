import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { fixtureJson } from '../helpers/common.mjs';
import { buildSignPayload, signParams, createAuth, restAuthHeaders, wsAuthQuery } from '../../src/signer.js';
import { buildJsonBody, formatPlainNumber, scalarText } from '../../src/json.js';
import { InvalidParameterException } from '../../src/errors.js';

const vectors = fixtureJson('sign/vectors.json');

test('every shared signing vector of spec/fixtures/sign/vectors.json matches', () => {
  assert.ok(vectors.cases.length >= 7);
  for (const c of vectors.cases) {
    assert.equal(buildSignPayload(c.params), c.payload, c.name + ' payload');
    assert.equal(signParams(c.params, c.secret), c.signature, c.name + ' signature');
  }
});

test('the signature equals an independent HMAC over the payload', () => {
  const params = { b: '2', a: '1', z: '', n: null, u: undefined, x: 0, f: false };
  assert.equal(buildSignPayload(params), 'a=1&b=2&f=false&x=0');
  const want = crypto.createHmac('sha256', 's').update('a=1&b=2&f=false&x=0', 'utf8').digest('base64');
  assert.equal(signParams(params, 's'), want);
  // UTF-16 code unit order, the order of Java's TreeMap
  assert.equal(buildSignPayload({ Z: '1', a: '2', _: '3', 中: '4' }), 'Z=1&_=3&a=2&中=4');
});

test('TTS body: signed values are the literal text of the JSON that is sent', () => {
  const v = vectors.cases.find((c) => c.name === 'tts_json_body');
  const body = { text: '你好世界', language: 'zh-CN', voice: 'xiaoyan', format: 'mp3', speed: 50, pitch: 50, volume: 50, style: null };
  const { text, signSet } = buildJsonBody(body);
  assert.equal(text, '{"text":"你好世界","language":"zh-CN","voice":"xiaoyan","format":"mp3","speed":50,"pitch":50,"volume":50}');
  assert.equal(buildSignPayload(signSet), v.payload);
  assert.equal(signParams(signSet, v.secret), v.signature);
  const nested = buildJsonBody({ a: 1e-7, b: true, c: { x: 1 }, d: [1], e: undefined });
  assert.equal(nested.text, '{"a":0.0000001,"b":true,"c":{"x":1},"d":[1]}');
  assert.deepEqual(nested.signSet, { a: '0.0000001', b: 'true' });
});

test('numbers are written in plain decimal form', () => {
  assert.equal(formatPlainNumber(50), '50');
  assert.equal(formatPlainNumber(0.2), '0.2');
  assert.equal(formatPlainNumber(-0), '0');
  assert.equal(formatPlainNumber(1e-7), '0.0000001');
  assert.equal(formatPlainNumber(-2.5e-8), '-0.000000025');
  assert.equal(formatPlainNumber(1.5e21), '1500000000000000000000');
  assert.equal(formatPlainNumber(123.456), '123.456');
  assert.throws(() => formatPlainNumber(NaN), InvalidParameterException);
  assert.throws(() => formatPlainNumber(Infinity), InvalidParameterException);
  assert.equal(scalarText(false), 'false');
  assert.equal(scalarText({}), null);
});

test('auth: token, appKey plus secretKey, 1.x top level fields, missing auth', () => {
  assert.equal(createAuth({ auth: { token: 't0ken' } }).mode, 'token');
  const k = createAuth({ auth: { appKey: 'ak', secretKey: 'sk' } });
  assert.equal(k.mode, 'sign');
  assert.deepEqual(k.secrets, ['sk']);
  assert.equal(createAuth({ appKey: 'ak', secretKey: 'sk' }).mode, 'sign');
  assert.equal(createAuth({ token: 'jwt' }).mode, 'token');
  for (const bad of [{}, { auth: {} }, { auth: { appKey: 'ak' } }, { auth: { token: '' } }, null]) {
    assert.throws(() => createAuth(bad), (e) => e instanceof InvalidParameterException && e.code === 90010);
  }
});

test('REST headers: fresh timestamp and nonce, signature over the signed set only', () => {
  const auth = createAuth({ auth: { appKey: 'mock-app-key', secretKey: 'mock-secret-key' } });
  const h1 = restAuthHeaders(auth, { config: '{"a":1}' });
  const h2 = restAuthHeaders(auth, { config: '{"a":1}' });
  assert.equal(h1['X-App-Key'], 'mock-app-key');
  assert.match(h1['X-Timestamp'], /^\d{10}$/);
  assert.ok(Math.abs(Number(h1['X-Timestamp']) - Date.now() / 1000) < 5);
  assert.match(h1['X-Nonce'], /^[0-9a-f]{16}$/);
  assert.notEqual(h1['X-Nonce'], h2['X-Nonce']);
  assert.equal(h1['X-Signature'], signParams({ config: '{"a":1}' }, 'mock-secret-key'));
  assert.deepEqual(restAuthHeaders(createAuth({ auth: { token: 'jwt' } }), {}), { Authorization: 'Bearer jwt' });
});

test('WS handshake query: every parameter except signature is signed', () => {
  const v = vectors.cases.find((c) => c.name === 'ws_handshake_query');
  const auth = createAuth({ auth: { appKey: 'ak_test', secretKey: v.secret } });
  const q = wsAuthQuery(auth, { idempotencyKey: '3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c', mockFault: '', extra: 7 });
  const { signature, ...rest } = q;
  assert.equal(signature, signParams(rest, v.secret));
  assert.equal(rest.extra, '7');
  assert.equal(rest.mockFault, undefined);
  assert.deepEqual(Object.keys(rest).sort(), ['appKey', 'extra', 'idempotencyKey', 'nonce', 'timestamp']);
  assert.equal(signParams(v.params, v.secret), v.signature);
  const t = wsAuthQuery(createAuth({ auth: { token: 'jwt' } }), { idempotencyKey: 'k' });
  assert.deepEqual(t, { idempotencyKey: 'k', token: 'jwt' });
});
