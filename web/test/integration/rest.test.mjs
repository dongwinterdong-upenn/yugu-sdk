// V-02 and V-03 against tools/mock-server with the real fetch.
import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import { YuguClient } from '../../src/client.js';
import { AuthException, InvalidParameterException, NetworkException, RateLimitException, ServerException } from '../../src/errors.js';
import { computeRetryDelay, DEFAULT_RETRY_POLICY } from '../../src/retry.js';
import { readAudio, seededRandom, sleep } from '../helpers/common.mjs';
import { MOCK_CREDS, MOCK_TOKEN, startMock } from '../helpers/mock-server.mjs';

const WAV = readAudio('zh_short.wav');
const CFG = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' };
const EVAL = '/api/v1/evaluate';
let mock;

before(async () => {
  mock = await startMock({ processingMs: 50, env: { MOCK_IDEM_WAIT_MS: '3000' } });
});
after(async () => {
  await mock.stop();
});
beforeEach(async () => {
  await mock.reset();
  await mock.faults([], { processingMs: 50 });
});

function client(extra = {}) {
  return new YuguClient({ baseUrl: mock.base, ...MOCK_CREDS, logLevel: 'OFF', retry: { initialDelayMs: 50 }, ...extra });
}

const evaluateLog = async () => (await mock.log()).filter((e) => e.path === EVAL);

describe('V-02 idempotency and billing (acceptance 6.2)', () => {
  test('same key three times, one submission hits a read timeout and retries: billed once', async () => {
    const key = `v02-a-${Date.now()}`;
    const c = client({ readTimeoutMs: 400 });
    const r1 = await c.evaluate(WAV, CFG, { idempotencyKey: key });
    await mock.faults([{ match: EVAL, fault: 'delay:1200' }]);
    const r2 = await c.evaluate(WAV, CFG, { idempotencyKey: key });
    const r3 = await c.evaluate(WAV, CFG, { idempotencyKey: key });
    await sleep(1300); // the timed-out first attempt of r2 finishes on the server
    const bill = await mock.billing();
    assert.equal(bill.billed, 1);
    assert.equal(bill.byKey[key], 1);
    const log = await evaluateLog();
    assert.equal(log.length, 4, 'r1, r2 twice (timeout plus retry), r3');
    assert.ok(log.every((e) => e.idempotencyKey === key), 'every request carried the same Idempotency-Key');
    assert.equal(log[1].fault, 'delay:1200');
    assert.equal(r1.recordId, r2.recordId);
    assert.equal(r2.recordId, r3.recordId);
    assert.deepEqual([r1.replayed, r2.replayed, r3.replayed], [false, true, true]);
    assert.deepEqual([r1.idempotencyKey, r2.idempotencyKey, r3.idempotencyKey], [key, key, key]);
    await c.close();
  });

  test('the first submission is the one that times out: still billed once', async () => {
    const key = `v02-b-${Date.now()}`;
    const c = client({ readTimeoutMs: 400 });
    await mock.faults([{ match: EVAL, fault: 'delay:1200' }]);
    const r1 = await c.evaluate(WAV, CFG, { idempotencyKey: key });
    const r2 = await c.evaluate(WAV, CFG, { idempotencyKey: key });
    const r3 = await c.evaluate(WAV, CFG, { idempotencyKey: key });
    await sleep(1300);
    const bill = await mock.billing();
    assert.equal(bill.billed, 1);
    assert.equal(new Set([r1.recordId, r2.recordId, r3.recordId]).size, 1);
    assert.ok((await evaluateLog()).every((e) => e.idempotencyKey === key));
  });

  test('automatic keys: two concurrent calls with one key wait for the first and replay it', async () => {
    await mock.faults([], { processingMs: 700 });
    const key = `v02-c-${Date.now()}`;
    const c = client();
    const [a, b] = await Promise.all([c.evaluate(WAV, CFG, { idempotencyKey: key }), c.evaluate(WAV, CFG, { idempotencyKey: key })]);
    assert.equal(a.recordId, b.recordId);
    assert.equal([a.replayed, b.replayed].filter(Boolean).length, 1);
    assert.equal((await mock.billing()).billed, 1);
  });

  test('server computing slowly (slow fault): the retry waits for the first evaluation and replays it', async () => {
    const key = `v02-e-${Date.now()}`;
    await mock.faults([{ match: EVAL, fault: 'slow:1000' }]);
    const c = client({ readTimeoutMs: 700 });
    const r = await c.evaluate(WAV, CFG, { idempotencyKey: key });
    assert.equal(r.replayed, true, 'the retry got the result of the first, still running request');
    const log = await evaluateLog();
    assert.equal(log.length, 2);
    assert.ok(log.every((e) => e.idempotencyKey === key));
    assert.notEqual(log[0].fault, null);
    await sleep(200);
    const bill = await mock.billing();
    assert.equal(bill.billed, 1);
  });

  test('a key reused for different audio is ConflictException 40903 and not retried', async () => {
    const key = `v02-d-${Date.now()}`;
    const c = client();
    await c.evaluate(WAV, CFG, { idempotencyKey: key });
    await assert.rejects(c.evaluate(readAudio('en_apple.wav'), CFG, { idempotencyKey: key }), (e) => e.code === 40903 && e.attempts === 1);
    assert.equal((await mock.billing()).billed, 1);
  });

  test('without a caller key every logical call gets its own key and is billed', async () => {
    const c = client();
    const a = await c.evaluate(WAV, CFG);
    const b = await c.evaluate(WAV, CFG);
    assert.notEqual(a.idempotencyKey, b.idempotencyKey);
    assert.equal((await mock.billing()).billed, 2);
  });
});

describe('V-03 retry policy (acceptance 6.3)', () => {
  test('(a) HTTP 500 twice: retried with the same key, delays per the seeded formula', async () => {
    await mock.faults([
      { match: EVAL, fault: 'status:500' },
      { match: EVAL, fault: 'status:500' },
    ]);
    const retries = [];
    const c = client({ random: seededRandom(2026), retry: { initialDelayMs: 200 }, eventListener: { onRetry: (...a) => retries.push(a) } });
    const r = await c.evaluate(WAV, CFG);
    assert.equal(r.overall, 93.7);
    const log = await evaluateLog();
    assert.equal(log.length, 3);
    assert.equal(new Set(log.map((e) => e.idempotencyKey)).size, 1);
    assert.equal(log[0].idempotencyKey, r.idempotencyKey);
    const rnd = seededRandom(2026);
    const expected = [1, 2].map((n) => computeRetryDelay(DEFAULT_RETRY_POLICY, n, null, rnd));
    assert.deepEqual(retries.map((x) => [x[0], x[1], x[2], x[3].httpStatus, x[3].code]), [
      ['evaluate', 1, expected[0], 500, 50000],
      ['evaluate', 2, expected[1], 500, 50000],
    ]);
    assert.ok(expected[0] >= 140 && expected[0] <= 260 && expected[1] >= 280 && expected[1] <= 520);
    assert.ok(log[1].t - log[0].t >= expected[0], `gap ${log[1].t - log[0].t} >= ${expected[0]}`);
    assert.ok(log[2].t - log[1].t >= expected[1], `gap ${log[2].t - log[1].t} >= ${expected[1]}`);
    assert.equal((await mock.billing()).billed, 1);
  });

  test('(b) read timeout: retried with the same key', async () => {
    await mock.faults([{ match: EVAL, fault: 'delay:900' }]);
    const c = client({ readTimeoutMs: 300 });
    const r = await c.evaluate(WAV, CFG);
    const log = await evaluateLog();
    assert.equal(log.length, 2);
    assert.equal(log[0].idempotencyKey, log[1].idempotencyKey);
    assert.equal(r.idempotencyKey, log[0].idempotencyKey);
    await sleep(700);
    assert.equal((await mock.billing()).billed, 1);
  });

  test('(c) HTTP 429 with Retry-After: waits at least Retry-After and reuses the key', async () => {
    await mock.faults([{ match: EVAL, fault: 'status:429:code=42901:retryAfter=1' }]);
    const retries = [];
    const c = client({ eventListener: { onRetry: (...a) => retries.push(a) } });
    const r = await c.evaluate(WAV, CFG);
    const log = await evaluateLog();
    assert.equal(log.length, 2);
    assert.equal(log[0].idempotencyKey, log[1].idempotencyKey);
    assert.ok(log[1].t - log[0].t >= 1000, `waited ${log[1].t - log[0].t} ms`);
    assert.ok(retries[0][2] >= 1000);
    assert.ok(retries[0][3] instanceof RateLimitException);
    assert.equal(retries[0][3].retryAfterMs, 1000);
    assert.equal(r.replayed, false);
  });

  test('(d) HTTP 400: no retry at all, InvalidParameterException at once', async () => {
    await mock.faults([{ match: EVAL, fault: 'status:400:code=40001' }]);
    const retries = [];
    const c = client({ eventListener: { onRetry: (...a) => retries.push(a) } });
    const t0 = Date.now();
    await assert.rejects(c.evaluate(WAV, CFG), (e) => {
      assert.ok(e instanceof InvalidParameterException);
      assert.equal(e.code, 40001);
      assert.equal(e.httpStatus, 400);
      assert.equal(e.attempts, 1);
      assert.equal(e.retryable, false);
      return true;
    });
    assert.ok(Date.now() - t0 < 1000);
    assert.equal((await evaluateLog()).length, 1);
    assert.equal(retries.length, 0);
    assert.equal((await mock.billing()).billed, 0);
  });

  test('retries exhausted: the last error with attempts 3', async () => {
    await mock.faults([1, 2, 3].map(() => ({ match: EVAL, fault: 'status:503' })));
    await assert.rejects(client().evaluate(WAV, CFG), (e) => e instanceof ServerException && e.attempts === 3 && e.httpStatus === 503);
    assert.equal((await evaluateLog()).length, 3);
  });

  test('dropped connection and FastAPI style 502 are retried', async () => {
    await mock.faults([
      { match: EVAL, fault: 'drop' },
      { match: EVAL, fault: 'status:502:detail=engine%20busy' },
    ]);
    const errors = [];
    const r = await client({ eventListener: { onRetry: (_op, _n, _d, e) => errors.push(e) } }).evaluate(WAV, CFG);
    assert.equal(r.overall, 93.7);
    assert.ok(errors[0] instanceof NetworkException);
    assert.ok(errors[1] instanceof ServerException);
    assert.equal(errors[1].category, 'UPSTREAM');
    assert.equal(errors[1].message, 'engine busy');
  });

  test('a per-request fault header hits every attempt; totalTimeoutMs bounds the call', async () => {
    const c = client({ retry: { initialDelayMs: 100 } });
    await assert.rejects(c.evaluate(WAV, CFG, { headers: { 'X-Mock-Fault': 'status:503' } }), (e) => e.attempts === 3);
    const t0 = Date.now();
    await assert.rejects(c.evaluate(WAV, CFG, { headers: { 'X-Mock-Fault': 'hang' }, totalTimeoutMs: 600, timeoutMs: 250 }), (e) => e.code === 90002);
    assert.ok(Date.now() - t0 < 1500, 'no hang past the total timeout');
  });

  test('wrong secret: AuthException 2003, not retried', async () => {
    const c = new YuguClient({ baseUrl: mock.base, appKey: 'mock-app-key', secretKey: 'wrong', logLevel: 'OFF' });
    await assert.rejects(c.evaluate(WAV, CFG), (e) => e instanceof AuthException && e.code === 2003 && e.attempts === 1);
  });
});

describe('other REST calls against the mock platform', () => {
  test('token auth, compat REST, TTS and report with a 503 retried without key', async () => {
    const t = new YuguClient({ baseUrl: mock.base, token: MOCK_TOKEN, logLevel: 'OFF' });
    const r = await t.evaluate(new Blob([WAV], { type: 'audio/wav' }), CFG);
    assert.equal(r.overall, 93.7);
    const c = client();
    const compat = await c.evaluateCompat('sent.eval.cn', { refText: '今天天气很好', language: 'zh-CN' }, WAV);
    assert.equal(compat.mode, 'compat');
    assert.ok(compat.overall > 0);
    await assert.rejects(c.evaluateCompat('pinyin', { refText: '重庆' }, WAV), (e) => e instanceof InvalidParameterException && /refPinyin/.test(e.message));
    await assert.rejects(t.evaluateCompat('sent.eval.cn', { refText: '今天天气很好' }, WAV), (e) => e.code === 90010 && /X-App-Key/.test(e.message));
    const tts = await c.tts({ text: '你好世界', voice: 'xiaoyan' });
    assert.match(tts.audioUrl, /^\/audio\/mock-/);
    assert.equal(tts.absoluteUrl, `${mock.base}/tts${tts.audioUrl}`);
    await mock.faults([{ match: '/api/v1/report/', fault: 'status:503' }]);
    const report = await c.getReport(r.recordId);
    assert.equal(report.recordId, r.recordId);
    const reportLog = (await mock.log()).filter((e) => e.path.startsWith('/api/v1/report/'));
    assert.equal(reportLog.length, 2);
    assert.ok(reportLog.every((e) => e.idempotencyKey === null));
    const writes = (await mock.log()).filter((e) => e.method === 'POST');
    assert.ok(writes.every((e) => /^[0-9a-f]{32}$/.test(e.idempotencyKey)));
    assert.ok(writes.every((e) => e.userAgent === 'yugu-web-sdk/2.0.0'));
    const bill = await mock.billing();
    assert.deepEqual(bill.records.map((x) => x.op).sort(), ['compat', 'evaluate', 'tts']);
  });
});
