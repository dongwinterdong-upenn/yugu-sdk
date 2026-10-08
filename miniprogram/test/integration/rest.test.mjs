// REST against tools/mock-server through a wx mock that performs real HTTP requests.
// 415 regression (DESIGN 5.1), V-02 idempotency and billing (6.2), V-03 retries (6.3).
import test from 'node:test';
import assert from 'node:assert/strict';
import { startMockServer, MOCK_APP_KEY, MOCK_SECRET, MOCK_TOKEN } from '../helpers/mock-server.mjs';
import { createWxMock } from '../helpers/wx-mock.mjs';
import { fixtureBytes, toArrayBuffer, captureLogs, seeded, sleep } from '../helpers/common.mjs';
import {
  YuguClient, generateIdempotencyKey, computeRetryDelay, normalizeRetryPolicyForTest,
} from './support.mjs';
import {
  InvalidParameterException, RequestTimeoutException, ConflictException, AuthException, signParams,
} from '../../src/index.js';

const WAV = fixtureBytes('audio/zh_short.wav');
const SENTENCE = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' };

let mock;
let wx;
test.before(async () => {
  mock = await startMockServer({ processingMs: 30 });
  wx = createWxMock({ frameIntervalMs: 1 });
});
test.after(async () => {
  await mock.stop();
  wx.__mock.cleanup();
});
test.beforeEach(() => mock.reset());

function client(opts = {}) {
  return new YuguClient({ wx, auth: { appKey: MOCK_APP_KEY, secretKey: MOCK_SECRET }, baseUrl: mock.baseUrl, wsBaseUrl: mock.wsBaseUrl, ...opts });
}

const evalLog = async (p = '/api/v1/evaluate') => (await mock.log()).filter((e) => e.path === p);

test('415 regression: a config part without application/json is rejected, the SDK body is accepted', async () => {
  // The 1.x upload path: wx.uploadFile sends formData parts without a Content-Type.
  const config = JSON.stringify(SENTENCE);
  const b = '----v1upload';
  const body = Buffer.concat([
    Buffer.from(`--${b}\r\nContent-Disposition: form-data; name="config"\r\n\r\n${config}\r\n--${b}\r\nContent-Disposition: form-data; name="audio"; filename="a.wav"\r\n\r\n`),
    WAV, Buffer.from(`\r\n--${b}--\r\n`),
  ]);
  const sigHeaders = { 'X-App-Key': MOCK_APP_KEY, 'X-Timestamp': String(Math.floor(Date.now() / 1000)), 'X-Nonce': 'n1', 'X-Signature': signParams({ config }, MOCK_SECRET) };
  const v1 = await fetch(mock.baseUrl + '/api/v1/evaluate', { method: 'POST', body, headers: { 'Content-Type': 'multipart/form-data; boundary=' + b, ...sigHeaders } });
  assert.equal(v1.status, 415);
  const r = await client().evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) });
  assert.equal(r.overall, 93.7);
  assert.match(r.recordId, /^eval_/);
  const log = await evalLog();
  assert.equal(log.length, 2);
  assert.match(log[1].contentType, /^multipart\/form-data; boundary=----YuguFormBoundary/);
  assert.deepEqual(log[1].config, SENTENCE);
  assert.equal(log[1].sdk, 'yugu-miniprogram-sdk/2.0.0');
});

test('every REST call with signature auth and with token auth', async () => {
  for (const auth of [{ appKey: MOCK_APP_KEY, secretKey: MOCK_SECRET }, { token: MOCK_TOKEN }]) {
    const c = client({ auth });
    const file = wx.__mock.writeTemp('rest.wav', WAV);
    const r = await c.evaluate({ ...SENTENCE, audio: file, includeReport: true });
    assert.equal(r.overall, 93.7);
    assert.equal(r.replayed, false);
    const tts = await c.tts({ text: '你好世界' });
    assert.match(tts.fullUrl, new RegExp('^' + mock.baseUrl + '/tts/audio/mock-'));
    const report = await c.getReport(r.recordId);
    assert.equal(report.recordId, r.recordId);
    if (auth.token) {
      // the compat interface requires X-App-Key; the SDK says so without sending anything
      await assert.rejects(c.evaluateCompat('sent.eval.cn', { refText: '今天天气很好', audio: toArrayBuffer(WAV) }), (e) => e.code === 90010);
      c.close();
      continue;
    }
    const compat = await c.evaluateCompat('sent.eval.cn', { refText: '今天天气很好', audio: toArrayBuffer(WAV) });
    assert.equal(compat.overall, 94.6);
    const word = await c.evaluateCompat('word.eval', { refText: 'apple', audio: toArrayBuffer(fixtureBytes('audio/en_apple.wav')) });
    assert.equal(word.overall, 65);
    const para = await c.evaluateCompat('para.eval.cn', { refText: '今天天气很好。我们一起去公园散步。', fields: { paragraph_need_word_score: 1 }, audio: toArrayBuffer(fixtureBytes('audio/zh_para.wav')) });
    assert.equal(para.sentences.length, 2);
    const pin = await c.evaluateCompat('pinyin', { refText: '重庆', refPinyin: 'chong2 qing4', audio: toArrayBuffer(WAV) });
    assert.ok(pin.recordId);
    c.close();
  }
  const billing = await mock.billing();
  assert.equal(billing.billed, 8, 'evaluate, tts and four compat calls with signature auth, evaluate and tts with a token');
  assert.equal((await mock.log()).filter((e) => /^\/(sent|word|para)\.eval|^\/pinyin/.test(e.path)).length, 4);
});

test('auth failures map to AuthException without retries', async () => {
  const bad = client({ auth: { appKey: MOCK_APP_KEY, secretKey: 'wrong' } });
  const err = await bad.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }).catch((e) => e);
  assert.ok(err instanceof AuthException);
  assert.equal(err.code, 2003);
  assert.equal(err.httpStatus, 401);
  assert.equal((await evalLog()).length, 1);
  const unknown = await client({ auth: { appKey: 'nobody', secretKey: 'x' } }).tts({ text: 'a' }).catch((e) => e);
  assert.equal(unknown.code, 2010);
});

test('V-02: the same key three times, one with an injected read timeout, bills once', async () => {
  const c = client({ retry: { jitter: 0 } });
  const key = generateIdempotencyKey();
  const t0 = Date.now();
  // The mock holds the first request for 900 ms before it reaches the idempotency guard, so the
  // SDK times out after 300 ms and retries with the same key.
  await mock.faults([{ match: '/api/v1/evaluate', fault: 'delay:900' }]);
  const first = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }, { idempotencyKey: key, timeoutMs: 300 });
  const second = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }, { idempotencyKey: key });
  const third = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }, { idempotencyKey: key });
  assert.ok(first.attempts >= 2, 'the read timeout triggered an internal retry');
  assert.equal(second.replayed, true);
  assert.equal(third.replayed, true);
  assert.equal(new Set([first.recordId, second.recordId, third.recordId]).size, 1);
  // let the held first request finish on the server: it must replay, not bill again
  await sleep(Math.max(0, 1100 - (Date.now() - t0)));
  const billing = await mock.billing();
  assert.equal(billing.billed, 1);
  assert.equal(billing.byKey[key], 1);
  const log = await evalLog();
  assert.ok(log.length >= 4);
  assert.ok(log.every((e) => e.idempotencyKey === key));
  assert.ok(log.filter((e) => e.replayed).length >= 3, 'the late first request and the repeats replayed');
});

test('V-02: a read timeout while the platform is still evaluating gets the replay of that evaluation', async () => {
  const c = client({ retry: { jitter: 0 } });
  // slow: the first request holds the idempotency key and takes 900 ms; the SDK gives up after 300 ms
  // and retries with the same key, which waits for the first result and replays it
  await mock.faults([{ match: '/api/v1/evaluate', fault: 'slow:900' }]);
  const r = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }, { timeoutMs: 300, totalTimeoutMs: 10000 });
  assert.equal(r.replayed, true);
  assert.ok(r.attempts >= 2);
  const again = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }, { idempotencyKey: r.idempotencyKey });
  assert.equal(again.recordId, r.recordId);
  assert.equal(again.replayed, true);
  const billing = await mock.billing();
  assert.equal(billing.billed, 1);
  assert.equal(billing.byKey[r.idempotencyKey], 1);
});

test('V-03a: HTTP 500 is retried with the same key and the configured backoff', async () => {
  const delays = [];
  const c = client({ random: seeded(5), eventListener: { onRetry: (op, n, d) => delays.push(d) } });
  await mock.faults([{ match: '/api/v1/evaluate', fault: 'status:500' }, { match: '/api/v1/evaluate', fault: 'status:500' }]);
  const r = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) });
  assert.equal(r.attempts, 3);
  const rnd = seeded(5);
  const policy = normalizeRetryPolicyForTest();
  assert.deepEqual(delays, [computeRetryDelay(1, policy, rnd), computeRetryDelay(2, policy, rnd)]);
  const log = await evalLog();
  assert.equal(log.length, 3);
  assert.equal(new Set(log.map((e) => e.idempotencyKey)).size, 1);
  assert.equal(log[0].idempotencyKey, r.idempotencyKey);
  assert.equal((await mock.billing()).byKey[r.idempotencyKey], 1);
  assert.ok(log[1].t - log[0].t >= delays[0] - 5, 'first wait');
  assert.ok(log[2].t - log[1].t >= delays[1] - 5, 'second wait');
  assert.ok(delays[0] >= 140 && delays[0] <= 260 && delays[1] >= 280 && delays[1] <= 520);
});

test('V-03b: a read timeout is retried with the same key and bills once', async () => {
  const c = client({ retry: { jitter: 0 } });
  await mock.faults([{ match: '/api/v1/evaluate', fault: 'delay:700' }]);
  const t0 = Date.now();
  const r = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }, { timeoutMs: 250 });
  assert.ok(r.attempts >= 2);
  await sleep(Math.max(0, 850 - (Date.now() - t0)));
  const log = await evalLog();
  assert.equal(new Set(log.map((e) => e.idempotencyKey)).size, 1);
  assert.equal((await mock.billing()).byKey[r.idempotencyKey], 1);
  // without retries the timeout surfaces as RequestTimeoutException
  await mock.faults([{ match: '/api/v1/evaluate', fault: 'delay:600' }]);
  const err = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }, { timeoutMs: 200, retry: false }).catch((e) => e);
  assert.ok(err instanceof RequestTimeoutException);
  assert.equal(err.code, 90002);
  assert.equal(err.attempts, 1);
  await sleep(500);
});

test('V-03c: HTTP 429 with Retry-After is retried after the server delay with the same key', async () => {
  const c = client({ retry: { jitter: 0 } });
  await mock.faults([{ match: '/api/v1/evaluate', fault: 'status:429:code=42901:retryAfter=1' }]);
  const t0 = Date.now();
  const r = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) });
  assert.equal(r.attempts, 2);
  const log = await evalLog();
  assert.ok(log[1].t - log[0].t >= 990, 'Retry-After: 1 respected');
  assert.ok(Date.now() - t0 < 3000);
  assert.equal(log[0].idempotencyKey, log[1].idempotencyKey);
});

test('V-03d: HTTP 400 is not retried and throws InvalidParameterException at once', async () => {
  const retries = [];
  const c = client({ eventListener: { onRetry: () => retries.push(1) } });
  await mock.faults([{ match: '/api/v1/evaluate', fault: 'status:400:code=40001' }]);
  const t0 = Date.now();
  const err = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }).catch((e) => e);
  assert.ok(err instanceof InvalidParameterException);
  assert.equal(err.code, 40001);
  assert.equal(err.httpStatus, 400);
  assert.equal(err.attempts, 1);
  assert.ok(Date.now() - t0 < 500);
  assert.deepEqual(retries, []);
  assert.equal((await evalLog()).length, 1);
  assert.equal((await mock.billing()).billed, 0);
});

test('dropped connections, FastAPI errors and hanging servers', async () => {
  const c = client({ retry: { jitter: 0, initialDelayMs: 20 } });
  await mock.faults([{ match: '/api/v1/tts', fault: 'drop' }, { match: '/api/v1/tts', fault: 'status:502:detail=engine%20busy' }]);
  const t = await c.tts({ text: '你好' });
  assert.equal(t.attempts, 3);
  const hang = client({ readTimeoutMs: 200, totalTimeoutMs: 500, retry: { jitter: 0, initialDelayMs: 10, maxRetries: 5 } });
  await mock.faults([{ match: '/api/v1/evaluate', fault: 'hang' }, { match: '/api/v1/evaluate', fault: 'hang' }, { match: '/api/v1/evaluate', fault: 'hang' }]);
  const t0 = Date.now();
  const err = await hang.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }).catch((e) => e);
  assert.ok(err instanceof RequestTimeoutException);
  assert.ok(Date.now() - t0 < 1500, 'no hang beyond the total timeout');
});

test('the same key with a different request is a conflict and is not retried', async () => {
  const c = client();
  const key = 'conflict-' + generateIdempotencyKey();
  await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }, { idempotencyKey: key });
  const err = await c.evaluate({ ...SENTENCE, referenceText: '别的句子', audio: toArrayBuffer(WAV) }, { idempotencyKey: key }).catch((e) => e);
  assert.ok(err instanceof ConflictException);
  assert.equal(err.code, 40903);
  assert.equal(err.retryable, false);
  assert.equal(err.idempotencyKey, key);
  assert.equal((await evalLog()).length, 2);
});

test('external audio: recorder result, tempFilePath and PCM bytes evaluate the same', async () => {
  const c = client();
  const rec = c.createRecorder();
  rec.start();
  const recording = await new Promise((resolve) => rec.setListener({ onStop: resolve }));
  assert.equal(recording.format, 'pcm');
  const fromRecording = await c.evaluate({ ...SENTENCE, audio: recording });
  const fromPcm = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(fixtureBytes('audio/zh_short.wav').subarray(78)), audioFormat: 'pcm' });
  assert.equal(fromRecording.overall, 93.7);
  assert.equal(fromPcm.overall, 93.7);
  const log = await evalLog();
  assert.ok(log.every((e) => e.bytes > 61440));
  const logs = captureLogs();
  const quiet = client({ logger: logs.sink });
  const silent = await quiet.evaluate({ ...SENTENCE, audio: toArrayBuffer(fixtureBytes('audio/silent.wav')) });
  assert.deepEqual(silent.localWarnings.map((w) => w.code), [90103]);
  c.close();
});
