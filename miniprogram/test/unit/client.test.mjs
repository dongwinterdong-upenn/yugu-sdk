// YuguClient REST behaviour against scripted wx.request answers: idempotency (A-01), retry (A-02),
// errors (B-04), lifecycle (B-05), observability (C-01), precheck (C-04).
import test from 'node:test';
import assert from 'node:assert/strict';
import { scriptedWx } from '../helpers/scripted-wx.mjs';
import { fixtureBytes, fixtureJson, toArrayBuffer, captureLogs, seeded, sleep } from '../helpers/common.mjs';
import {
  YuguClient, InvalidParameterException, ServerException, RateLimitException, ConflictException, AuthException,
  RequestTimeoutException, NetworkException, RequestCancelledException, IllegalSessionStateException,
  AudioQualityException, ProtocolViolationException, NotFoundException, signParams, createAbortController,
  computeRetryDelay, DEFAULT_RETRY_POLICY,
} from '../../src/index.js';

const WAV = toArrayBuffer(fixtureBytes('audio/zh_short.wav'));
const NATIVE = fixtureJson('platform/native_evaluate_sentence_zh.json');
const AUTH = { appKey: 'mock-app-key', secretKey: 'mock-secret-key' };
const FAST = { initialDelayMs: 1, maxDelayMs: 5, jitter: 0 };
const params = (extra = {}) => ({ coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN', audio: WAV, ...extra });

function client(responses, opts = {}) {
  const wx = scriptedWx(responses);
  const c = new YuguClient({ wx, auth: AUTH, retry: FAST, ...opts });
  return { c, wx, reqs: wx.__scripted.requests };
}

const ok = (body = NATIVE, headers = {}) => ({ status: 200, body, headers });
const header = (req, name) => {
  const k = Object.keys(req.header).find((x) => x.toLowerCase() === name.toLowerCase());
  return k === undefined ? undefined : req.header[k];
};

test('constructor validates options and applies the documented defaults', async () => {
  const wx = scriptedWx([ok()]);
  const bad = (o) => assert.throws(() => new YuguClient(o), (e) => e instanceof InvalidParameterException && e.code === 90010, JSON.stringify(Object.keys(o || {})));
  bad(undefined);
  bad({ auth: AUTH });
  bad({ wx });
  bad({ wx, auth: AUTH, baseUrl: 'ftp://x' });
  bad({ wx, auth: AUTH, wsBaseUrl: 'https://x' });
  bad({ wx, auth: AUTH, logLevel: 'TRACE' });
  bad({ wx, auth: AUTH, audioPrecheck: 'MAYBE' });
  bad({ wx, auth: AUTH, logger: 'console' });
  bad({ wx, auth: AUTH, random: 0.5 });
  bad({ wx, auth: AUTH, readTimeoutMs: 0 });
  bad({ wx, auth: AUTH, heartbeatIntervalMs: -1 });
  bad({ wx, auth: AUTH, audioBufferPolicy: 'KEEP' });
  bad({ wx, auth: AUTH, retry: { maxRetries: -1 } });
  bad({ wx, auth: AUTH, reconnect: { maxAttempts: 'x' } });
  const c = new YuguClient({ wx, auth: AUTH });
  await c.evaluate(params());
  const req = wx.__scripted.requests[0];
  assert.equal(req.url, 'https://open.shengzhiai.com/api/v1/evaluate');
  assert.equal(req.timeout, 120000 + 1000);
  assert.equal(req.method, 'POST');
  assert.equal(req.dataType, 'text');
  assert.equal(req.responseType, 'text');
  const g = globalThis;
  g.wx = scriptedWx([ok()]);
  try {
    const viaGlobal = new YuguClient({ auth: { token: 'jwt-token' }, baseUrl: 'http://localhost:1/' });
    await viaGlobal.evaluate(params());
    assert.equal(g.wx.__scripted.requests[0].url, 'http://localhost:1/api/v1/evaluate');
    assert.equal(g.wx.__scripted.requests[0].header.Authorization, 'Bearer jwt-token');
  } finally {
    delete g.wx;
  }
});

test('evaluate sends the 415-safe multipart body, signs {config}, identifies the SDK', async () => {
  const { c, reqs } = client([ok()]);
  const r = await c.evaluate(params({ includeReport: true, slack: 0.2, extra: { futureFlag: 1 } }));
  assert.equal(r.overall, 93.7);
  assert.equal(r.attempts, 1);
  const req = reqs[0];
  const ct = header(req, 'content-type');
  assert.match(ct, /^multipart\/form-data; boundary=----YuguFormBoundary/);
  assert.equal(Object.keys(req.header).filter((k) => k.toLowerCase() === 'content-type').length, 1);
  assert.ok(Object.prototype.toString.call(req.data) === '[object ArrayBuffer]');
  const body = Buffer.from(req.data).toString('latin1');
  const configText = JSON.stringify({ coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN', includeReport: true, slack: 0.2, futureFlag: 1 });
  assert.ok(body.includes('Content-Disposition: form-data; name="config"\r\nContent-Type: application/json; charset=utf-8\r\n\r\n' + Buffer.from(configText).toString('latin1')));
  assert.ok(body.includes('name="audio"; filename="audio.wav"\r\nContent-Type: audio/wav'));
  assert.equal(header(req, 'X-Signature'), signParams({ config: configText }, AUTH.secretKey));
  assert.equal(header(req, 'X-Yugu-SDK'), 'yugu-miniprogram-sdk/2.0.0');
  assert.equal(header(req, 'User-Agent'), undefined);
  assert.match(header(req, 'Idempotency-Key'), /^[0-9a-f]{32}$/);
  assert.equal(r.idempotencyKey, header(req, 'Idempotency-Key'));
});

test('validation errors are thrown before any I/O', async () => {
  const { c, reqs } = client([ok()]);
  const is90010 = (e) => e instanceof InvalidParameterException && e.code === 90010;
  await assert.rejects(c.evaluate(params({ coreType: 'essay' })), is90010);
  await assert.rejects(c.evaluate(params({ referenceText: '' })), is90010);
  await assert.rejects(c.evaluate(params({ audio: undefined })), is90010);
  await assert.rejects(c.evaluate(params(), { idempotencyKey: 'has space' }), is90010);
  await assert.rejects(c.evaluate(params(), { timeoutMs: -5 }), is90010);
  await assert.rejects(c.evaluate(params(), { audioPrecheck: 'X' }), is90010);
  await assert.rejects(c.evaluate(params(), { signal: 'x' }), is90010);
  await assert.rejects(c.evaluate(params(), 'opts'), is90010);
  await assert.rejects(c.evaluate(params({ extra: [1] })), is90010);
  await assert.rejects(c.evaluateCompat('sent.eval.xx', { refText: 'a', audio: WAV }), is90010);
  await assert.rejects(c.evaluateCompat('pinyin', { refText: '重庆', audio: WAV }), is90010);
  await assert.rejects(c.evaluateCompat('sent.eval.cn', { audio: WAV }), is90010);
  await assert.rejects(c.evaluateCompat('sent.eval.cn', { refText: 'a', audio: WAV, fields: { x: { y: 1 } } }), is90010);
  await assert.rejects(c.tts({ text: '' }), is90010);
  await assert.rejects(c.getReport(''), is90010);
  await assert.rejects(c.evaluate(null), is90010);
  assert.equal(reqs.length, 0);
});

test('A-01: one idempotency key per logical call, reused by every retry', async () => {
  const { c, reqs } = client([{ status: 500, body: { code: 50000, message: 'busy' } }, { status: 503 }, ok()]);
  const r = await c.evaluate(params());
  assert.equal(reqs.length, 3);
  const keys = reqs.map((q) => header(q, 'Idempotency-Key'));
  assert.equal(new Set(keys).size, 1);
  assert.equal(r.idempotencyKey, keys[0]);
  assert.equal(r.attempts, 3);
  assert.equal(new Set(reqs.map((q) => header(q, 'X-Nonce'))).size, 3, 'nonce is fresh per attempt');
  assert.equal(new Set(reqs.map((q) => header(q, 'X-Signature'))).size, 1, 'same signed set, same signature');
  // the next logical call gets a new key
  const { c: c2, reqs: reqs2 } = client([ok(), ok()]);
  await c2.evaluate(params());
  await c2.evaluate(params());
  assert.notEqual(header(reqs2[0], 'Idempotency-Key'), header(reqs2[1], 'Idempotency-Key'));
});

test('A-01: a caller key is sent, reused and exposed on errors', async () => {
  const { c, reqs } = client([{ status: 502, body: { code: 50200 } }, { status: 502, body: { code: 50200 } }, { status: 502, body: { code: 50200 } }]);
  const err = await c.evaluate(params(), { idempotencyKey: 'order-42' }).catch((e) => e);
  assert.ok(err instanceof ServerException);
  assert.equal(err.category, 'UPSTREAM');
  assert.equal(err.idempotencyKey, 'order-42');
  assert.equal(err.attempts, 3);
  assert.equal(err.op, 'evaluate');
  assert.deepEqual(reqs.map((q) => header(q, 'Idempotency-Key')), ['order-42', 'order-42', 'order-42']);
});

test('without a key write calls are not retried; GET report is retried without a key', async () => {
  const logs = captureLogs();
  const { c, reqs } = client([{ status: 503 }, ok()], { autoIdempotencyKey: false, logLevel: 'DEBUG', logger: logs.sink });
  const err = await c.evaluate(params()).catch((e) => e);
  assert.equal(err.httpStatus, 503);
  assert.equal(reqs.length, 1);
  assert.equal(header(reqs[0], 'Idempotency-Key'), undefined);
  assert.match(logs.text(), /retries disabled because the call carries no idempotency key/);
  const report = { code: 0, message: 'ok', data: { recordId: 'eval_1', overall: 90 } };
  const { c: c2, reqs: reqs2 } = client([{ status: 504 }, { status: 200, body: report }]);
  const data = await c2.getReport('eval_1');
  assert.deepEqual(data, report.data);
  assert.equal(reqs2.length, 2);
  assert.equal(reqs2[0].method, 'GET');
  assert.equal(reqs2[0].url, 'https://open.shengzhiai.com/api/v1/report/eval_1');
  assert.equal(header(reqs2[0], 'Idempotency-Key'), undefined);
  assert.equal(header(reqs2[0], 'X-Signature'), signParams({}, AUTH.secretKey));
  await assert.rejects(client([{ status: 200, body: { code: 40400, message: 'gone' } }]).c.getReport('eval_x'), NotFoundException);
});

test('A-02: retryable versus final errors', async () => {
  const cases = [
    [{ status: 400, body: { code: 40001, message: 'bad' } }, InvalidParameterException, 1],
    [{ status: 401, body: { code: 2003 } }, AuthException, 1],
    [{ status: 409, body: { code: 40903 } }, ConflictException, 1],
    [{ status: 409, body: { code: 40902 } }, null, 1],
    [{ status: 429, body: { code: 42903 } }, null, 1],
    [{ status: 429, body: { code: 42900 } }, RateLimitException, 3],
    [{ status: 409, body: { code: 40901 } }, ConflictException, 3],
    [{ status: 500, body: { code: 50010 } }, ServerException, 1],
    [{ status: 502, body: { detail: 'engine down' } }, ServerException, 3],
    [{ status: 503, body: '<html>busy</html>' }, ServerException, 3],
    [{ status: 418 }, null, 1],
    [{ fail: 'request:fail timeout' }, RequestTimeoutException, 3],
    [{ fail: 'request:fail -1009' }, NetworkException, 3],
    [{ fail: 'request:fail certificate has expired' }, NetworkException, 1],
    [{ status: 200, body: 'not json' }, ProtocolViolationException, 1],
  ];
  for (const [resp, Cls, attempts] of cases) {
    const { c, reqs } = client([resp, resp, resp]);
    const err = await c.evaluate(params()).catch((e) => e);
    assert.equal(reqs.length, attempts, JSON.stringify(resp));
    assert.equal(err.attempts, attempts);
    if (Cls) assert.ok(err instanceof Cls, err.name + ' for ' + JSON.stringify(resp));
  }
});

test('A-02: events, WARN log line and backoff with a seeded random source', async () => {
  const logs = captureLogs();
  const events = [];
  const ev = {
    onRequestStart: (...a) => events.push(['start', ...a]),
    onRequestEnd: (...a) => events.push(['end', a[0], a[1], typeof a[2], a[3], a[4] && a[4].code]),
    onRetry: (...a) => events.push(['retry', a[0], a[1], a[2], a[3].code]),
  };
  const { c, reqs } = client([{ status: 502, body: { code: 50200 } }, { status: 503 }, ok()], {
    retry: { jitter: 0.3 }, random: seeded(9), logger: logs.sink, eventListener: ev,
  });
  await c.evaluate(params());
  const rnd = seeded(9);
  const d1 = computeRetryDelay(1, DEFAULT_RETRY_POLICY, rnd);
  const d2 = computeRetryDelay(2, DEFAULT_RETRY_POLICY, rnd);
  assert.deepEqual(events, [
    ['start', 'evaluate', 'POST', '/api/v1/evaluate', 1],
    ['retry', 'evaluate', 1, d1, 50200],
    ['start', 'evaluate', 'POST', '/api/v1/evaluate', 2],
    ['retry', 'evaluate', 2, d2, 0],
    ['start', 'evaluate', 'POST', '/api/v1/evaluate', 3],
    ['end', 'evaluate', 200, 'number', 3, undefined],
  ]);
  const warns = logs.lines.filter((l) => l.level === 'WARN').map((l) => l.message);
  assert.deepEqual(warns, [`retry 1/2 in ${d1} ms: HTTP 502 code=50200`, `retry 2/2 in ${d2} ms: HTTP 503 code=0`]);
  assert.ok(reqs[1].t - reqs[0].t >= d1 - 2, 'first backoff respected');
  assert.ok(reqs[2].t - reqs[1].t >= d2 - 2, 'second backoff respected');
});

test('A-02: Retry-After is honoured, capped, and a failure event closes the call', async () => {
  const events = [];
  const { c, reqs } = client([{ status: 429, body: { code: 42900 }, headers: { 'Retry-After': '1' } }, ok()], {
    retry: { initialDelayMs: 1, jitter: 0, maxRetryAfterMs: 300 }, eventListener: { onRetry: (op, n, d) => events.push(d) },
  });
  await c.evaluate(params());
  assert.deepEqual(events, [300]);
  assert.ok(reqs[1].t - reqs[0].t >= 290);
  const ends = [];
  const { c: c2 } = client([{ status: 400, body: { code: 40001 } }], { eventListener: { onRequestEnd: (...a) => ends.push(a) } });
  await c2.evaluate(params()).catch(() => {});
  assert.equal(ends.length, 1);
  assert.equal(ends[0][1], 400);
  assert.equal(ends[0][3], 1);
  assert.equal(ends[0][4].code, 40001);
});

test('A-02: total timeout stops retries instead of hanging', async () => {
  const logs = captureLogs();
  const { c, reqs } = client([{ status: 503 }, { status: 503 }, ok()], {
    retry: { initialDelayMs: 400, jitter: 0 }, totalTimeoutMs: 300, logger: logs.sink,
  });
  const t0 = Date.now();
  const err = await c.evaluate(params()).catch((e) => e);
  assert.equal(err.httpStatus, 503);
  assert.equal(reqs.length, 1);
  assert.ok(Date.now() - t0 < 300);
  assert.match(logs.text(), /no retry, the next attempt would pass the total timeout/);
  // a hanging server is cut by the per attempt timeout, then by the total deadline
  const { c: c2, reqs: reqs2 } = client([{ hang: true }], { readTimeoutMs: 60, totalTimeoutMs: 150, retry: { initialDelayMs: 1, jitter: 0, maxRetries: 10 } });
  const t1 = Date.now();
  const e2 = await c2.evaluate(params()).catch((e) => e);
  assert.ok(e2 instanceof RequestTimeoutException);
  assert.ok(Date.now() - t1 < 400, 'no hang');
  assert.ok(reqs2.length >= 2 && reqs2.length <= 4, String(reqs2.length));
  assert.ok(reqs2.every((q) => q.aborted), 'timed out tasks are aborted');
  assert.equal(reqs2[0].timeout, 60 + 1000);
});

test('per call options override retry and timeouts', async () => {
  const { c, reqs } = client(() => ({ status: 503 }));
  await c.evaluate(params(), { retry: false }).catch(() => {});
  assert.equal(reqs.length, 1);
  await c.evaluate(params(), { retry: { maxRetries: 4 } }).catch(() => {});
  assert.equal(reqs.length, 6);
  await c.evaluate(params(), { retry: false, timeoutMs: 1234 }).catch(() => {});
  assert.equal(reqs[6].timeout, 1234 + 1000);
});

test('cancellation: before the call, during a request and during backoff', async () => {
  const { c, reqs } = client([{ hang: true }]);
  const pre = createAbortController();
  pre.abort();
  await assert.rejects(c.evaluate(params(), { signal: pre.signal }), (e) => e instanceof RequestCancelledException && e.code === 90003);
  assert.equal(reqs.length, 0);
  const during = createAbortController();
  const p = c.evaluate(params(), { signal: during.signal });
  await sleep(20);
  during.abort();
  await assert.rejects(p, RequestCancelledException);
  assert.equal(reqs[0].aborted, true);
  const { c: c2, reqs: reqs2 } = client([{ status: 503 }, ok()], { retry: { initialDelayMs: 5000, jitter: 0 } });
  const std = new AbortController();
  const t0 = Date.now();
  const p2 = c2.evaluate(params(), { signal: std.signal });
  await sleep(30);
  std.abort();
  await assert.rejects(p2, (e) => e instanceof RequestCancelledException && e.attempts === 1);
  assert.ok(Date.now() - t0 < 1000);
  assert.equal(reqs2.length, 1);
});

test('B-05: close() aborts running calls with 90004, later calls fail, close is idempotent', async () => {
  const { c, reqs } = client([{ hang: true }]);
  const running = c.evaluate(params());
  await sleep(20);
  c.close();
  c.close();
  assert.equal(c.isClosed(), true);
  await assert.rejects(running, (e) => e instanceof IllegalSessionStateException && e.code === 90004);
  assert.equal(reqs[0].aborted, true);
  for (const call of [() => c.evaluate(params()), () => c.evaluateCompat('sent.eval.cn', { refText: 'a', audio: WAV }), () => c.tts({ text: 'a' }), () => c.getReport('r')]) {
    await assert.rejects(call(), (e) => e.code === 90004);
  }
  assert.throws(() => c.streamEvaluate({ coreType: 'word', referenceText: 'a' }, { onResult() {}, onError() {} }), (e) => e.code === 90004);
  assert.throws(() => c.streamEvaluateCompat('word.eval', { refText: 'a' }, { onResult() {}, onError() {} }), (e) => e.code === 90004);
  assert.throws(() => c.createRecorder(), (e) => e.code === 90004);
});

test('replay header, strictAudio and server warnings', async () => {
  const withWarn = { ...NATIVE, result: { ...NATIVE.result, warning: [1001] }, warnings: [1002] };
  const { c } = client([ok(NATIVE, { 'idempotency-replayed': 'true' }), ok(withWarn), ok(withWarn), ok(withWarn)]);
  const r1 = await c.evaluate(params());
  assert.equal(r1.replayed, true);
  const r2 = await c.evaluate(params());
  assert.equal(r2.replayed, false);
  assert.deepEqual(r2.warnings.map((w) => w.code), [1001, 1002]);
  const strict = await c.evaluate(params(), { strictAudio: true }).catch((e) => e);
  assert.ok(strict instanceof AudioQualityException);
  assert.equal(strict.code, 1001);
  assert.equal(strict.recordId, NATIVE.recordId);
  assert.equal(strict.retryable, false);
  const { c: c3 } = client([ok(withWarn)], { strictAudio: true });
  await assert.rejects(c3.evaluate(params()), AudioQualityException);
});

test('C-04: precheck REJECT stops before upload, WARN reports localWarnings, OFF skips', async () => {
  const silent = toArrayBuffer(fixtureBytes('audio/silent.wav'));
  const { c, reqs } = client([ok(), ok(), ok()]);
  await assert.rejects(c.evaluate(params({ audio: silent }), { audioPrecheck: 'REJECT' }), (e) => e instanceof AudioQualityException && e.code === 90103);
  assert.equal(reqs.length, 0);
  const warned = await c.evaluate(params({ audio: silent }));
  assert.deepEqual(warned.localWarnings.map((w) => w.code), [90103]);
  const off = await c.evaluate(params({ audio: silent }), { audioPrecheck: 'OFF' });
  assert.deepEqual(off.localWarnings, []);
  const { c: c2, reqs: r2 } = client([ok()], { audioPrecheck: 'REJECT' });
  await assert.rejects(c2.evaluate(params({ audio: silent })), AudioQualityException);
  assert.equal(r2.length, 0);
});

test('evaluate with an image part and with the 1.x audioPath field', async () => {
  const { c, reqs, wx } = client([ok(), ok()]);
  await c.evaluate(params({ coreType: 'open', taskType: 'picture', image: new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1]) }));
  const body = Buffer.from(reqs[0].data).toString('latin1');
  assert.match(body, /name="image"; filename="image\.jpg"\r\nContent-Type: image\/jpeg/);
  const file = wx.__mock.writeTemp('v1.wav', fixtureBytes('audio/zh_short.wav'));
  const r = await c.evaluate({ coreType: 'sentence', referenceText: 'x', audioPath: file });
  assert.equal(r.overall, 93.7);
});

test('evaluateCompat signs exactly the text fields that are sent', async () => {
  const compat = fixtureJson('platform/compat_sent.eval.cn.json');
  const { c, reqs } = client([ok(compat), ok(compat)]);
  const r = await c.evaluateCompat('sent.eval.cn', { refText: '今天天气很好', language: 'zh-CN', refPinyin: '', fields: { paragraph_need_word_score: 1, scale: 100 }, audio: WAV });
  assert.equal(r.overall, 94.6);
  const req = reqs[0];
  assert.equal(req.url, 'https://open.shengzhiai.com/sent.eval.cn');
  const fields = { refText: '今天天气很好', language: 'zh-CN', paragraph_need_word_score: '1', scale: '100' };
  assert.equal(header(req, 'X-Signature'), signParams(fields, AUTH.secretKey));
  const body = Buffer.from(req.data).toString('utf8');
  assert.ok(body.includes('name="refText"\r\n\r\n今天天气很好\r\n'));
  assert.ok(!body.includes('name="refPinyin"'));
  assert.ok(body.includes('name="paragraph_need_word_score"\r\n\r\n1\r\n'));
  await c.evaluateCompat('pinyin', { referenceText: '重庆', refPinyin: 'chong2 qing4', audio: WAV });
  assert.ok(Buffer.from(reqs[1].data).toString('utf8').includes('name="refPinyin"\r\n\r\nchong2 qing4'));
});

test('evaluateCompat needs signature auth: a token alone fails before any request', async () => {
  const { c, reqs } = client([ok()], { auth: { token: 'jwt' } });
  await assert.rejects(c.evaluateCompat('sent.eval.cn', { refText: 'a', audio: WAV }), (e) => e instanceof InvalidParameterException && e.code === 90010 && /X-App-Key/.test(e.message));
  assert.equal(reqs.length, 0);
});

test('tts sends the signed JSON text as a string and unwraps the envelope', async () => {
  const tts = fixtureJson('platform/tts_generate.json');
  const { c, reqs } = client([ok(tts), { status: 200, body: { code: 50000, message: 'busy' } }, ok(tts), { status: 200, body: { code: 40001, message: 'bad text' } }]);
  const r = await c.tts({ text: '你好世界', speed: 60 });
  assert.equal(r.fullUrl, tts.data.audioUrl);
  assert.equal(r.attempts, 1);
  assert.match(r.idempotencyKey, /^[0-9a-f]{32}$/);
  assert.equal(r.raw.code, 0);
  const req = reqs[0];
  assert.equal(typeof req.data, 'string');
  assert.equal(req.data, '{"text":"你好世界","language":"zh-CN","voice":"xiaoyan","format":"mp3","speed":60,"pitch":50,"volume":50}');
  assert.equal(header(req, 'content-type'), 'application/json; charset=utf-8');
  assert.equal(header(req, 'X-Signature'), signParams({ text: '你好世界', language: 'zh-CN', voice: 'xiaoyan', format: 'mp3', speed: '60', pitch: '50', volume: '50' }, AUTH.secretKey));
  const r2 = await c.tts({ text: 'hello', language: 'en-US', style: 'calm', extra: { sampleRate: 16000 } });
  assert.equal(r2.attempts, 2, 'code 50000 inside a 200 envelope is retried');
  assert.equal(JSON.parse(reqs[1].data).voice, 'female');
  assert.equal(JSON.parse(reqs[1].data).sampleRate, 16000);
  await assert.rejects(c.tts({ text: 'x' }), (e) => e instanceof InvalidParameterException && e.code === 40001);
});

test('C-01: secrets, tokens and signatures never reach the log, also at DEBUG', async () => {
  const logs = captureLogs();
  const { c, reqs } = client([{ status: 503, body: { code: 0, message: 'x' } }, { fail: 'request:fail timeout' }, ok(), { status: 401, body: { code: 2003 } }], { logLevel: 'DEBUG', logger: logs.sink });
  await c.evaluate(params());
  await c.evaluate(params()).catch(() => {});
  const signatures = reqs.map((q) => header(q, 'X-Signature'));
  const text = logs.text();
  assert.ok(text.length > 100);
  for (const secret of [AUTH.secretKey, ...signatures]) assert.ok(!text.includes(secret), 'leaked ' + secret);
  assert.ok(!text.includes(AUTH.appKey), 'appKey only masked');
  assert.match(text, /mock\*\*\*/);
  const tokenLogs = captureLogs();
  const t = client([ok()], { auth: { token: 'jwt-secret-token' }, logLevel: 'DEBUG', logger: tokenLogs.sink });
  await t.c.evaluate(params());
  assert.ok(!tokenLogs.text().includes('jwt-secret-token'));
});
