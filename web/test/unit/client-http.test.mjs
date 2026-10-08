import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { YuguClient } from '../../src/client.js';
import {
  AudioQualityException,
  AuthException,
  IllegalSessionStateException,
  InvalidParameterException,
  NetworkException,
  ProtocolViolationException,
  RequestCancelledException,
  RequestTimeoutException,
  ServerException,
} from '../../src/errors.js';
import { computeRetryDelay, DEFAULT_RETRY_POLICY } from '../../src/retry.js';
import { signHmacSha256 } from '../../src/signer.js';
import { utf8Decode } from '../../src/util.js';
import { readAudio, readJson, seededRandom } from '../helpers/common.mjs';

const BASE = 'https://api.test';
const SECRET = 'test_secret_key_123';
const NATIVE = readJson('platform/native_evaluate_sentence_zh.json');
const WAV = readAudio('zh_short.wav');
const CONFIG = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' };

/** fetch stub: replies from a script; 'hang' waits for abort; Error instances are thrown. */
function fakeFetch(script) {
  const calls = [];
  const fn = async (url, init) => {
    calls.push({ url, init, at: Date.now() });
    const step = typeof script === 'function' ? script(calls.length, url, init) : script[Math.min(calls.length, script.length) - 1];
    if (step instanceof Error) throw step;
    if (step === 'hang') {
      return new Promise((_, reject) =>
        init.signal.addEventListener('abort', () => reject(Object.assign(new Error('This operation was aborted'), { name: 'AbortError' }))),
      );
    }
    const body = step.body !== undefined ? step.body : JSON.stringify(step.json === undefined ? NATIVE : step.json);
    return new Response(body, { status: step.status || 200, headers: step.headers || { 'content-type': 'application/json' } });
  };
  fn.calls = calls;
  return fn;
}

function client(fetch, extra = {}) {
  return new YuguClient({ baseUrl: BASE, appKey: 'ak_test_key', secretKey: SECRET, fetch, logLevel: 'OFF', retry: { initialDelayMs: 5, maxDelayMs: 20 }, ...extra });
}

/** Minimal multipart parser for assertions. */
function parseMultipart(body, contentType) {
  const boundary = /boundary=(.+)$/.exec(contentType)[1];
  const text = utf8Decode(new Uint8Array(body));
  const parts = [];
  for (const chunk of text.split(`--${boundary}`).slice(1, -1)) {
    const [head, ...rest] = chunk.replace(/^\r\n/, '').split('\r\n\r\n');
    const value = rest.join('\r\n\r\n').replace(/\r\n$/, '');
    const disp = /Content-Disposition: ([^\r]+)/.exec(head)[1];
    const ct = /Content-Type: ([^\r]+)/.exec(head);
    parts.push({ name: /name="([^"]*)"/.exec(disp)[1], filename: (/filename="([^"]*)"/.exec(disp) || [])[1] || null, contentType: ct ? ct[1] : null, value, head });
  }
  return parts;
}

describe('evaluate: request construction (DESIGN 5.2)', () => {
  test('signature mode: headers, multipart parts and the signed config text', async () => {
    const f = fakeFetch([{ json: NATIVE }]);
    const c = client(f);
    const r = await c.evaluate(WAV, CONFIG);
    assert.equal(f.calls.length, 1);
    const { url, init } = f.calls[0];
    assert.equal(url, `${BASE}/api/v1/evaluate`);
    assert.equal(init.method, 'POST');
    const h = init.headers;
    assert.match(h['Content-Type'], /^multipart\/form-data; boundary=----YuguFormBoundary[0-9a-f]{24}$/);
    assert.match(h['Idempotency-Key'], /^[0-9a-f]{32}$/);
    assert.equal(h['X-App-Key'], 'ak_test_key');
    assert.ok(Math.abs(Number(h['X-Timestamp']) - Date.now() / 1000) < 5, 'timestamp in seconds');
    assert.match(h['X-Nonce'], /^[0-9a-f]{16}$/);
    assert.equal(h['User-Agent'], 'yugu-web-sdk/2.0.0');
    assert.equal(h.Accept, 'application/json');
    assert.ok(init.body instanceof ArrayBuffer);
    const parts = parseMultipart(init.body, h['Content-Type']);
    assert.deepEqual(parts.map((p) => p.name), ['config', 'audio']);
    assert.equal(parts[0].filename, null, 'config part has no filename');
    assert.equal(parts[0].contentType, 'application/json; charset=utf-8');
    assert.equal(parts[0].value, JSON.stringify(CONFIG));
    assert.equal(parts[1].filename, 'audio.wav');
    assert.equal(parts[1].contentType, 'audio/wav');
    assert.equal(h['X-Signature'], await signHmacSha256({ config: JSON.stringify(CONFIG) }, SECRET));
    assert.equal(r.overall, 93.7);
    assert.equal(r.idempotencyKey, h['Idempotency-Key']);
    assert.equal(r.replayed, false);
    assert.equal(r.mode, 'native');
    assert.equal(r.coreType, 'sentence');
  });

  test('token mode: Authorization header, token provider called per attempt', async () => {
    let n = 0;
    const f = fakeFetch([{ status: 503, json: { code: 50200 } }, { json: NATIVE }]);
    const c = new YuguClient({ baseUrl: BASE, token: async () => `jwt-${++n}`, fetch: f, logLevel: 'OFF', retry: { initialDelayMs: 1 } });
    await c.evaluate(WAV.buffer.slice(WAV.byteOffset, WAV.byteOffset + WAV.byteLength), CONFIG);
    assert.equal(f.calls[0].init.headers.Authorization, 'Bearer jwt-1');
    assert.equal(f.calls[1].init.headers.Authorization, 'Bearer jwt-2');
    assert.equal(f.calls[0].init.headers['X-Signature'], undefined);
    assert.equal(c.authMode, 'token');
    assert.equal(c.hasTokenAuth, true);
    assert.equal(c.hasSigAuth, false);
  });

  test('Idempotency-Replayed header sets replayed', async () => {
    const f = fakeFetch([{ json: NATIVE, headers: { 'Idempotency-Replayed': 'true' } }]);
    const r = await client(f).evaluate(new Blob([WAV]), CONFIG, { idempotencyKey: 'my-key-1' });
    assert.equal(r.replayed, true);
    assert.equal(r.idempotencyKey, 'my-key-1');
    assert.equal(f.calls[0].init.headers['Idempotency-Key'], 'my-key-1');
  });

  test('image part, extra headers, filename and content type overrides', async () => {
    const f = fakeFetch([{ json: NATIVE }]);
    const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]);
    await client(f).evaluate(WAV, { coreType: 'open', referenceText: '描述图片', taskType: 'picture' }, {
      image: png,
      headers: { 'X-Request-Source': 'test' },
      filename: 'take1.wav',
    });
    const parts = parseMultipart(f.calls[0].init.body, f.calls[0].init.headers['Content-Type']);
    assert.deepEqual(parts.map((p) => [p.name, p.filename, p.contentType]), [
      ['config', null, 'application/json; charset=utf-8'],
      ['audio', 'take1.wav', 'audio/wav'],
      ['image', 'image.png', 'image/png'],
    ]);
    assert.equal(f.calls[0].init.headers['X-Request-Source'], 'test');
    const jpg = await (async () => {
      const g = fakeFetch([{ json: NATIVE }]);
      await client(g).evaluate(WAV, CONFIG, { image: new File([new Uint8Array([0xff, 0xd8, 0xff, 0])], 'p.jpg', { type: 'image/jpeg' }) });
      return parseMultipart(g.calls[0].init.body, g.calls[0].init.headers['Content-Type'])[2];
    })();
    assert.deepEqual([jpg.filename, jpg.contentType], ['p.jpg', 'image/jpeg']);
    await assert.rejects(client(fakeFetch([{}])).evaluate(WAV, CONFIG, { image: 'x' }), InvalidParameterException);
    await assert.rejects(client(fakeFetch([{}])).evaluate(WAV, CONFIG, { image: new Uint8Array(0) }), InvalidParameterException);
  });

  test('raw PCM input is wrapped into WAV before upload', async () => {
    const f = fakeFetch([{ json: NATIVE }]);
    const pcm = WAV.slice(78);
    await client(f).evaluate(pcm, CONFIG, { audioFormat: 'pcm' });
    const audio = parseMultipart(f.calls[0].init.body, f.calls[0].init.headers['Content-Type'])[1];
    assert.equal(audio.filename, 'audio.wav');
    assert.match(audio.value, /^RIFF/);
  });

  test('argument validation happens before any I/O', async () => {
    const f = fakeFetch([{ json: NATIVE }]);
    const c = client(f);
    await assert.rejects(c.evaluate(WAV, CONFIG, { idempotencyKey: 'has space' }), (e) => e instanceof InvalidParameterException && e.code === 90010);
    await assert.rejects(c.evaluate(WAV, { referenceText: 'x' }), /coreType/);
    await assert.rejects(c.evaluate(WAV, { coreType: 'word' }), /referenceText/);
    await assert.rejects(c.evaluate(WAV, null), /config/);
    await assert.rejects(c.evaluate(WAV, CONFIG, 'bad'), InvalidParameterException);
    await assert.rejects(c.evaluate(WAV, CONFIG, { headers: 'x' }), InvalidParameterException);
    await assert.rejects(c.evaluate(WAV, CONFIG, { audioFormat: 'mp3' }), InvalidParameterException);
    await assert.rejects(c.evaluate(WAV, CONFIG, { timeoutMs: 0 }), InvalidParameterException);
    await assert.rejects(c.evaluate(WAV, CONFIG, { signal: {} }), InvalidParameterException);
    await assert.rejects(c.evaluate(WAV, CONFIG, { audioPrecheck: 'LOUD' }), InvalidParameterException);
    await assert.rejects(c.evaluate(WAV, CONFIG, { sampleRate: 100 }), InvalidParameterException);
    const cyclic = { ...CONFIG };
    cyclic.self = cyclic;
    await assert.rejects(c.evaluate(WAV, cyclic), (e) => e.code === 90010 && /JSON/.test(e.message));
    assert.equal(f.calls.length, 0);
  });
});

describe('retry (A-02, DESIGN 2.3)', () => {
  test('retryable errors reuse the same key; delays follow the seeded formula; events and WARN logs', async () => {
    const logs = [];
    const events = [];
    const f = fakeFetch([
      { status: 503, json: { code: 50200, message: 'upstream' } },
      { status: 500, json: { code: 50000, message: 'oops' } },
      { json: NATIVE },
    ]);
    const c = new YuguClient({
      baseUrl: BASE,
      appKey: 'ak_test_key',
      secretKey: SECRET,
      fetch: f,
      random: seededRandom(99),
      retry: { initialDelayMs: 20, maxDelayMs: 100 },
      logger: (level, tag, message) => logs.push([level, tag, message]),
      eventListener: {
        onRequestStart: (...a) => events.push(['start', ...a]),
        onRetry: (...a) => events.push(['retry', ...a]),
        onRequestEnd: (...a) => events.push(['end', ...a]),
      },
    });
    const r = await c.evaluate(WAV, CONFIG);
    const keys = f.calls.map((x) => x.init.headers['Idempotency-Key']);
    assert.equal(new Set(keys).size, 1, 'one key for every attempt');
    assert.equal(r.idempotencyKey, keys[0]);
    const nonces = f.calls.map((x) => x.init.headers['X-Nonce']);
    assert.equal(new Set(nonces).size, 3, 'fresh nonce per attempt');
    const expectRnd = seededRandom(99);
    const policy = { ...DEFAULT_RETRY_POLICY, initialDelayMs: 20, maxDelayMs: 100 };
    const expected = [computeRetryDelay(policy, 1, null, expectRnd), computeRetryDelay(policy, 2, null, expectRnd)];
    const retries = events.filter((e) => e[0] === 'retry');
    assert.deepEqual(retries.map((e) => [e[1], e[2], e[3]]), [['evaluate', 1, expected[0]], ['evaluate', 2, expected[1]]]);
    assert.equal(retries[0][4].code, 50200);
    assert.ok(f.calls[1].at - f.calls[0].at >= expected[0] - 1, 'waited the first backoff');
    assert.ok(f.calls[2].at - f.calls[1].at >= expected[1] - 1, 'waited the second backoff');
    assert.deepEqual(events.filter((e) => e[0] === 'start').map((e) => e.slice(1)), [
      ['evaluate', 'POST', '/api/v1/evaluate', 1],
      ['evaluate', 'POST', '/api/v1/evaluate', 2],
      ['evaluate', 'POST', '/api/v1/evaluate', 3],
    ]);
    const end = events.filter((e) => e[0] === 'end');
    assert.equal(end.length, 1);
    assert.deepEqual([end[0][1], end[0][2], end[0][4], end[0][5]], ['evaluate', 200, 3, null]);
    const warn = logs.filter((l) => l[0] === 'WARN' && l[1] === 'retry').map((l) => l[2]);
    assert.deepEqual(warn, [`retry 1/2 in ${expected[0]} ms: HTTP 503 code=50200`, `retry 2/2 in ${expected[1]} ms: HTTP 500 code=50000`]);
  });

  test('400 is never retried', async () => {
    const f = fakeFetch([{ status: 400, json: { code: 40001, message: 'refText 不能为空' } }, { json: NATIVE }]);
    await assert.rejects(client(f).evaluate(WAV, CONFIG), (e) => {
      assert.ok(e instanceof InvalidParameterException);
      assert.equal(e.code, 40001);
      assert.equal(e.httpStatus, 400);
      assert.equal(e.attempts, 1);
      assert.equal(e.retryable, false);
      assert.match(e.idempotencyKey, /^[0-9a-f]{32}$/);
      return true;
    });
    assert.equal(f.calls.length, 1);
  });

  test('401 signature failure is AuthException and not retried', async () => {
    const f = fakeFetch([{ status: 401, json: { code: 2003, message: '签名验证失败' } }]);
    await assert.rejects(client(f).evaluate(WAV, CONFIG), (e) => e instanceof AuthException && e.code === 2003);
    assert.equal(f.calls.length, 1);
  });

  test('retries stop after maxRetries with the last error and attempts', async () => {
    const f = fakeFetch([{ status: 502, json: { code: 50200 } }]);
    await assert.rejects(client(f).evaluate(WAV, CONFIG), (e) => e instanceof ServerException && e.attempts === 3 && e.retryable);
    assert.equal(f.calls.length, 3);
    const g = fakeFetch([{ status: 502, json: { code: 50200 } }]);
    await assert.rejects(client(g, { retry: { maxRetries: 0 } }).evaluate(WAV, CONFIG), ServerException);
    assert.equal(g.calls.length, 1);
    const h = fakeFetch([{ status: 502, json: { code: 50200 } }]);
    await assert.rejects(client(h).evaluate(WAV, CONFIG, { retry: false }), ServerException);
    assert.equal(h.calls.length, 1);
  });

  test('autoIdempotencyKey off and no key: no header, write call not retried', async () => {
    const logs = [];
    const f = fakeFetch([{ status: 503, json: { code: 50200 } }, { json: NATIVE }]);
    const c = client(f, { autoIdempotencyKey: false, logLevel: 'DEBUG', logger: (...a) => logs.push(a) });
    await assert.rejects(c.evaluate(WAV, CONFIG), ServerException);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].init.headers['Idempotency-Key'], undefined);
    assert.ok(logs.some((l) => l[0] === 'DEBUG' && /not retried/.test(l[2])));
    const g = fakeFetch([{ status: 503, json: { code: 50200 } }, { json: NATIVE }]);
    const r = await client(g, { autoIdempotencyKey: false }).evaluate(WAV, CONFIG, { idempotencyKey: 'caller' });
    assert.equal(g.calls.length, 2, 'a caller key makes the call retryable again');
    assert.equal(r.idempotencyKey, 'caller');
  });

  test('read timeout is retried with the same key', async () => {
    const f = fakeFetch(['hang', { json: NATIVE }]);
    const r = await client(f, { readTimeoutMs: 40 }).evaluate(WAV, CONFIG);
    assert.equal(f.calls.length, 2);
    assert.equal(f.calls[0].init.headers['Idempotency-Key'], f.calls[1].init.headers['Idempotency-Key']);
    assert.equal(r.overall, 93.7);
    const g = fakeFetch(['hang']);
    await assert.rejects(client(g, { readTimeoutMs: 20 }).evaluate(WAV, CONFIG), (e) => e instanceof RequestTimeoutException && e.code === 90002 && e.attempts === 3);
  });

  test('network errors are retried, TLS errors are not', async () => {
    const f = fakeFetch([new TypeError('fetch failed', { cause: Object.assign(new Error('connect ECONNREFUSED'), { code: 'ECONNREFUSED' }) }), { json: NATIVE }]);
    await client(f).evaluate(WAV, CONFIG);
    assert.equal(f.calls.length, 2);
    const g = fakeFetch([new TypeError('fetch failed')]);
    await assert.rejects(client(g).evaluate(WAV, CONFIG), (e) => e instanceof NetworkException && e.code === 90001 && e.attempts === 3);
    const t = fakeFetch([new TypeError('fetch failed', { cause: Object.assign(new Error('certificate has expired'), { code: 'CERT_HAS_EXPIRED' }) })]);
    await assert.rejects(client(t).evaluate(WAV, CONFIG), (e) => e instanceof NetworkException && e.code === 90011 && !e.retryable);
    assert.equal(t.calls.length, 1);
  });

  test('Retry-After is respected and capped by totalTimeoutMs', async () => {
    const f = fakeFetch([{ status: 429, json: { code: 42900, message: '排队超时' }, headers: { 'Retry-After': '1' } }, { json: NATIVE }]);
    const t0 = Date.now();
    await client(f).evaluate(WAV, CONFIG);
    assert.ok(Date.now() - t0 >= 990, 'waited Retry-After');
    const g = fakeFetch([{ status: 429, json: { code: 42900 }, headers: { 'Retry-After': '2' } }]);
    await assert.rejects(client(g).evaluate(WAV, CONFIG, { totalTimeoutMs: 300 }), (e) => e.code === 42900 && e.attempts === 1);
    assert.equal(g.calls.length, 1, 'no retry that would pass the deadline');
  });

  test('409 40901 in progress is retried, 40903 is not', async () => {
    const f = fakeFetch([{ status: 409, json: { code: 40901 }, headers: { 'Retry-After': '0' } }, { json: NATIVE }]);
    await client(f).evaluate(WAV, CONFIG);
    assert.equal(f.calls.length, 2);
    const g = fakeFetch([{ status: 409, json: { code: 40903 } }]);
    await assert.rejects(client(g).evaluate(WAV, CONFIG), (e) => e.code === 40903 && !e.retryable);
    assert.equal(g.calls.length, 1);
  });

  test('a throwing EventListener does not break the call', async () => {
    const f = fakeFetch([{ status: 503, json: { code: 50200 } }, { json: NATIVE }]);
    const boom = () => {
      throw new Error('listener bug');
    };
    const c = client(f, { eventListener: { onRequestStart: boom, onRetry: boom, onRequestEnd: boom } });
    assert.equal((await c.evaluate(WAV, CONFIG)).overall, 93.7);
  });
});

describe('cancellation and lifecycle (B-05)', () => {
  test('AbortSignal before, during fetch and during the retry wait', async () => {
    const pre = new AbortController();
    pre.abort();
    const f0 = fakeFetch([{ json: NATIVE }]);
    await assert.rejects(client(f0).evaluate(WAV, CONFIG, { signal: pre.signal }), (e) => e instanceof RequestCancelledException && e.code === 90003);
    assert.equal(f0.calls.length, 0);
    const during = new AbortController();
    const f1 = fakeFetch(['hang']);
    const p1 = client(f1).evaluate(WAV, CONFIG, { signal: during.signal });
    setTimeout(() => during.abort(), 20);
    await assert.rejects(p1, (e) => e instanceof RequestCancelledException && e.attempts === 1 && e.retryable === false);
    const wait = new AbortController();
    const f2 = fakeFetch([{ status: 503, json: { code: 50200 }, headers: { 'Retry-After': '5' } }]);
    const p2 = client(f2).evaluate(WAV, CONFIG, { signal: wait.signal });
    setTimeout(() => wait.abort(), 50);
    await assert.rejects(p2, (e) => e instanceof RequestCancelledException);
    assert.equal(f2.calls.length, 1);
  });

  test('close() aborts calls in flight with 90004, later calls fail, close is idempotent', async () => {
    const f = fakeFetch(['hang']);
    const c = client(f);
    const p = c.evaluate(WAV, CONFIG);
    await new Promise((r) => setTimeout(r, 10));
    const closing = c.close();
    assert.equal(c.close(), closing, 'same promise');
    await assert.rejects(p, (e) => e instanceof IllegalSessionStateException && e.code === 90004);
    await closing;
    assert.equal(c.isClosed(), true);
    await assert.rejects(c.evaluate(WAV, CONFIG), (e) => e instanceof IllegalSessionStateException && e.code === 90004);
    await assert.rejects(c.tts({ text: 'x' }), IllegalSessionStateException);
    await assert.rejects(c.getReport('eval_1'), IllegalSessionStateException);
    assert.throws(() => c.streamEvaluate(CONFIG, { onResult() {}, onError() {} }), IllegalSessionStateException);
    const g = fakeFetch([{ status: 503, json: { code: 50200 }, headers: { 'Retry-After': '5' } }]);
    const c2 = client(g);
    const p2 = c2.evaluate(WAV, CONFIG);
    await new Promise((r) => setTimeout(r, 30));
    await c2.close();
    await assert.rejects(p2, (e) => e.code === 90004);
  });
});

describe('responses', () => {
  test('a 2xx that is not JSON is ProtocolViolationException and not retried', async () => {
    const f = fakeFetch([{ body: '<html>maintenance</html>' }]);
    await assert.rejects(client(f).evaluate(WAV, CONFIG), (e) => e instanceof ProtocolViolationException && e.code === 90005 && e.rawBody.includes('maintenance'));
    assert.equal(f.calls.length, 1);
  });

  test('strictAudio throws AudioQualityException for warning 1001 only', async () => {
    const silentResult = { ...NATIVE, result: { ...NATIVE.result, warning: [{ code: 1001, message: 'No valid audio detected!' }] } };
    const f = fakeFetch([{ json: silentResult }]);
    const r = await client(f).evaluate(WAV, CONFIG);
    assert.deepEqual(r.warnings.map((w) => w.code), [1001]);
    const g = fakeFetch([{ json: silentResult }]);
    await assert.rejects(client(g, { strictAudio: true }).evaluate(WAV, CONFIG), (e) => e instanceof AudioQualityException && e.code === 1001 && e.recordId === NATIVE.recordId);
    const h = fakeFetch([{ json: { ...NATIVE, warnings: [1002] } }]);
    assert.equal((await client(h, { strictAudio: true }).evaluate(WAV, CONFIG)).warnings[0].code, 1002);
    const k = fakeFetch([{ json: silentResult }]);
    assert.equal((await client(k, { strictAudio: true }).evaluate(WAV, CONFIG, { strictAudio: false })).warnings[0].code, 1001);
  });

  test('precheck: REJECT throws before upload, WARN reports localWarnings, OFF skips', async () => {
    const silent = readAudio('silent.wav');
    const f = fakeFetch([{ json: NATIVE }]);
    await assert.rejects(client(f, { audioPrecheck: 'REJECT' }).evaluate(silent, CONFIG), (e) => {
      assert.ok(e instanceof AudioQualityException);
      assert.equal(e.code, 90103);
      assert.match(e.idempotencyKey, /^[0-9a-f]{32}$/);
      return true;
    });
    assert.equal(f.calls.length, 0, 'nothing uploaded');
    const logs = [];
    const g = fakeFetch([{ json: NATIVE }]);
    const r = await client(g, { logLevel: 'WARN', logger: (...a) => logs.push(a) }).evaluate(silent, CONFIG);
    assert.deepEqual(r.localWarnings.map((w) => w.code), [90103]);
    assert.ok(logs.some((l) => l[1] === 'precheck' && /90103/.test(l[2])));
    const h = fakeFetch([{ json: NATIVE }]);
    const off = await client(h).evaluate(silent, CONFIG, { audioPrecheck: 'OFF' });
    assert.deepEqual(off.localWarnings, []);
    const low = await client(fakeFetch([{ json: NATIVE }]), { audioPrecheck: 'REJECT' }).evaluate(readAudio('low_volume.wav'), CONFIG);
    assert.deepEqual(low.localWarnings.map((w) => w.code), [90104], '90104 stays a warning in REJECT mode');
  });
});

describe('evaluateCompat, tts, getReport', () => {
  test('compat: path, text fields without Content-Type, signature over the fields', async () => {
    const compat = readJson('platform/compat_sent.eval.json');
    const f = fakeFetch([{ json: compat }]);
    const params = { refText: 'How are you', language: 'en-US', paragraph_need_word_score: 1, refPinyin: '', skip: null, extra: { a: 1 }, flag: true };
    const r = await client(f).evaluateCompat('sent.eval', params, WAV);
    const { url, init } = f.calls[0];
    assert.equal(url, `${BASE}/sent.eval`);
    const parts = parseMultipart(init.body, init.headers['Content-Type']);
    assert.deepEqual(parts.map((p) => [p.name, p.value, p.contentType]).slice(0, 5), [
      ['refText', 'How are you', null],
      ['language', 'en-US', null],
      ['paragraph_need_word_score', '1', null],
      ['extra', '{"a":1}', null],
      ['flag', 'true', null],
    ]);
    assert.equal(parts[5].name, 'audio');
    const signed = { refText: 'How are you', language: 'en-US', paragraph_need_word_score: '1', extra: '{"a":1}', flag: 'true' };
    assert.equal(init.headers['X-Signature'], await signHmacSha256(signed, SECRET));
    assert.match(init.headers['Idempotency-Key'], /^[0-9a-f]{32}$/);
    assert.equal(r.mode, 'compat');
    assert.equal(r.coreType, 'sent.eval');
    assert.equal(r.overall, 93.9);
    const tokenOnly = fakeFetch([{ json: compat }]);
    const tc = new YuguClient({ baseUrl: BASE, token: 'jwt', fetch: tokenOnly, logLevel: 'OFF' });
    await assert.rejects(tc.evaluateCompat('sent.eval', params, WAV), (e) => e instanceof InvalidParameterException && /X-App-Key/.test(e.message));
    assert.equal(tokenOnly.calls.length, 0, 'compat REST needs X-App-Key: rejected before any request');
    const c = client(fakeFetch([{ json: compat }]));
    await assert.rejects(c.evaluateCompat('choice.rec', {}, WAV), (e) => e.code === 90010 && /choice\.rec/.test(e.message));
    await assert.rejects(c.evaluateCompat('sent.eval', { audio: 'x' }, WAV), InvalidParameterException);
    await assert.rejects(c.evaluateCompat('sent.eval', 'x', WAV), InvalidParameterException);
    await assert.rejects(c.evaluateCompat('sent.eval', { n: 1e21 }, WAV), InvalidParameterException);
    const nul = fakeFetch([{ json: compat }]);
    await client(nul).evaluateCompat('word.eval', null, WAV);
    assert.equal(parseMultipart(nul.calls[0].init.body, nul.calls[0].init.headers['Content-Type']).length, 1);
  });

  test('tts: JSON body, defaults, signature over top-level scalars, envelope errors retried', async () => {
    const tts = readJson('platform/tts_generate.json');
    const f = fakeFetch([{ status: 200, json: { code: 50000, message: 'busy' } }, { json: tts }]);
    const r = await client(f).tts({ text: '你好世界', voice: 'xiaoyan', speed: 60, sentenceBreak: true });
    assert.equal(f.calls.length, 2, 'envelope code 50000 is retryable');
    const { url, init } = f.calls[1];
    assert.equal(url, `${BASE}/api/v1/tts/generate`);
    assert.equal(init.headers['Content-Type'], 'application/json; charset=utf-8');
    const body = JSON.parse(init.body);
    assert.deepEqual(body, { text: '你好世界', language: 'zh-CN', format: 'mp3', speed: 60, pitch: 50, volume: 50, style: null, voice: 'xiaoyan', sentenceBreak: true });
    const signed = { text: '你好世界', language: 'zh-CN', format: 'mp3', speed: '60', pitch: '50', volume: '50', voice: 'xiaoyan', sentenceBreak: 'true' };
    assert.equal(init.headers['X-Signature'], await signHmacSha256(signed, SECRET));
    assert.equal(r.audioUrl, tts.data.audioUrl);
    assert.equal(r.idempotencyKey, init.headers['Idempotency-Key']);
    const c = client(fakeFetch([{ json: tts }]));
    await assert.rejects(c.tts({ text: '' }), InvalidParameterException);
    await assert.rejects(c.tts('x'), InvalidParameterException);
    await assert.rejects(c.tts({ text: 'x', speed: 1e-7 }), (e) => e.code === 90010 && /speed/.test(e.message));
    const g = fakeFetch([{ json: { code: 40001, message: 'text 不能为空' } }]);
    await assert.rejects(client(g).tts({ text: 'x' }), (e) => e instanceof InvalidParameterException && e.httpStatus === 200);
    const rel = await client(fakeFetch([{ json: { code: 0, data: { audioUrl: '/audio/a.mp3' } } }])).tts({ text: 'x', style: undefined });
    assert.equal(rel.absoluteUrl, `${BASE}/tts/audio/a.mp3`);
  });

  test('getReport: GET without key, signs the empty set, retried without a key', async () => {
    const data = { recordId: 'eval_1/x', overall: 93.7 };
    const f = fakeFetch([{ status: 503, json: { code: 50200 } }, { json: { code: 0, message: 'success', data } }]);
    const r = await client(f).getReport('eval_1/x');
    assert.deepEqual(r, data);
    assert.equal(f.calls.length, 2, 'GET is naturally idempotent and retried');
    const { url, init } = f.calls[1];
    assert.equal(url, `${BASE}/api/v1/report/eval_1%2Fx`);
    assert.equal(init.method, 'GET');
    assert.equal(init.body, undefined);
    assert.equal(init.headers['Idempotency-Key'], undefined);
    assert.equal(init.headers['Content-Type'], undefined);
    assert.equal(init.headers['X-Signature'], await signHmacSha256({}, SECRET));
    const g = fakeFetch([{ json: { code: 40400, message: '评测记录不存在' } }]);
    await assert.rejects(client(g).getReport('eval_x'), (e) => e.code === 40400);
    await assert.rejects(client(g).getReport(''), InvalidParameterException);
    const bare = await client(fakeFetch([{ json: { recordId: 'r' } }])).getReport('r');
    assert.equal(bare.recordId, 'r');
  });
});

describe('options', () => {
  test('defaults, derived wsBaseUrl, getters and validation', () => {
    const d = new YuguClient({ token: 't' });
    assert.equal(d.baseUrl, 'https://open.shengzhiai.com');
    assert.equal(d.wsBaseUrl, 'wss://open.shengzhiai.com');
    const m = new YuguClient({ token: 't', baseUrl: 'http://127.0.0.1:8080/' });
    assert.equal(m.baseUrl, 'http://127.0.0.1:8080');
    assert.equal(m.wsBaseUrl, 'ws://127.0.0.1:8080');
    assert.equal(new YuguClient({ token: 't', baseUrl: 'https://a.b', wsBaseUrl: 'wss://c.d/' }).wsBaseUrl, 'wss://c.d');
    assert.equal(new YuguClient({ auth: { appKey: 'a', secretKey: 's' } }).authMode, 'signature');
    assert.equal(new YuguClient({ auth: { token: () => 't' } }).authMode, 'token');
    const bad = [
      undefined,
      {},
      [],
      { token: 't', appKey: 'a', secretKey: 's' },
      { appKey: 'a' },
      { secretKey: 's' },
      { token: 5 },
      { token: 't', baseUrl: 'ftp://x' },
      { token: 't', wsBaseUrl: 'https://x' },
      { token: 't', logLevel: 'TRACE' },
      { token: 't', logger: 'console' },
      { token: 't', eventListener: 5 },
      { token: 't', readTimeoutMs: -1 },
      { token: 't', connectTimeoutMs: 'x' },
      { token: 't', maxReplayBytes: 1.5 },
      { token: 't', audioBufferPolicy: 'KEEP' },
      { token: 't', heartbeat: 'yes' },
      { token: 't', audioPrecheck: 'warn' },
      { token: 't', userAgent: '中文' },
      { token: 't', fetch: 'x' },
      { token: 't', WebSocket: {} },
      { token: 't', random: 0.5 },
      { token: 't', retry: { jitter: 5 } },
      { token: 't', reconnect: { maxAttempts: -2 } },
    ];
    for (const o of bad) assert.throws(() => new YuguClient(o), (e) => e instanceof InvalidParameterException && e.code === 90010, JSON.stringify(o));
  });

  test('browsers get X-Yugu-SDK instead of User-Agent; custom userAgent', async () => {
    globalThis.window = {};
    globalThis.document = {};
    try {
      const f = fakeFetch([{ json: NATIVE }]);
      await client(f, { userAgent: 'my-app/1.0 yugu-web-sdk/2.0.0' }).evaluate(WAV, CONFIG);
      assert.equal(f.calls[0].init.headers['X-Yugu-SDK'], 'my-app/1.0 yugu-web-sdk/2.0.0');
      assert.equal(f.calls[0].init.headers['User-Agent'], undefined);
    } finally {
      delete globalThis.window;
      delete globalThis.document;
    }
  });

  test('DEBUG logs show redacted headers, never the secret or the signature', async () => {
    const logs = [];
    const f = fakeFetch([{ json: NATIVE }]);
    await client(f, { logLevel: 'DEBUG', logger: (...a) => logs.push(a) }).evaluate(WAV, CONFIG);
    const text = logs.map((l) => l[2]).join('\n');
    assert.match(text, /"X-Signature":"\*\*\*"/);
    assert.match(text, /"X-App-Key":"ak_t\*\*\*"/);
    assert.ok(!text.includes(SECRET));
    assert.ok(!text.includes(f.calls[0].init.headers['X-Signature']));
  });

  test('missing fetch and missing WebSocket are reported', async () => {
    const saved = globalThis.fetch;
    const savedWs = globalThis.WebSocket;
    delete globalThis.fetch;
    delete globalThis.WebSocket;
    try {
      const c = new YuguClient({ token: 't' });
      await assert.rejects(c.evaluate(WAV, CONFIG), (e) => e.code === 90010 && /fetch/.test(e.message));
      assert.throws(() => c.streamEvaluate(CONFIG, { onResult() {}, onError() {} }), (e) => e.code === 90010 && /WebSocket/.test(e.message));
    } finally {
      globalThis.fetch = saved;
      if (savedWs) globalThis.WebSocket = savedWs;
    }
  });

  test('resolveTtsUrl and resolveAudioUrl', () => {
    const c = new YuguClient({ token: 't', baseUrl: BASE });
    assert.equal(c.resolveTtsUrl('/audio/a.mp3'), `${BASE}/tts/audio/a.mp3`);
    assert.equal(c.resolveAudioUrl('https://cdn/x.wav'), 'https://cdn/x.wav');
  });
});
