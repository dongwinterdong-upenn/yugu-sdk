import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import * as E from '../../src/errors.js';
import { ERROR_TABLE, ErrorCodes, HTTP_FALLBACK, LOCAL_TABLE, RETRYABLE_HTTP, WARNING_TABLE } from '../../src/error-table.js';
import { readJson } from '../helpers/common.mjs';

const spec = readJson('../errors.json');

const CLASS_OF = {
  NETWORK: E.NetworkException,
  TIMEOUT: E.RequestTimeoutException,
  AUTH: E.AuthException,
  PERMISSION: E.PermissionException,
  INVALID_PARAM: E.InvalidParameterException,
  NOT_FOUND: E.NotFoundException,
  CONFLICT: E.ConflictException,
  RATE_LIMIT: E.RateLimitException,
  QUOTA: E.QuotaExceededException,
  SERVER: E.ServerException,
  UPSTREAM: E.ServerException,
  AUDIO: E.AudioQualityException,
  STATE: E.IllegalSessionStateException,
  CANCELLED: E.RequestCancelledException,
  PROTOCOL: E.ProtocolViolationException,
};

// Codes that exist both as server errors and as warnings: the error table wins for fromCode.
const COLLIDING = new Set(spec.errors.map((e) => e.code).filter((c) => spec.warnings.some((w) => w.code === c)));

describe('error mapping: every server error code of errors.json (acceptance 6.6)', () => {
  for (const e of spec.errors) {
    test(`${e.code} ${e.name} -> ${e.category}, retryable ${e.retryable}`, () => {
      const err = E.YuguErrors.fromCode(e.code, { httpStatus: e.http });
      assert.ok(err instanceof CLASS_OF[e.category], `class for ${e.category}`);
      assert.ok(err instanceof E.YuguError && err instanceof Error);
      assert.equal(err.category, e.category);
      assert.equal(err.code, e.code);
      assert.equal(err.httpStatus, e.http);
      assert.equal(err.retryable, e.retryable);
      assert.equal(E.isRetryable(err), e.retryable);
      assert.equal(err.message, e.message);
      // The same code inside a platform error body gives the same answer.
      const body = JSON.stringify({ code: e.code, message: `server says ${e.name}`, timestamp: 1 });
      const fromBody = E.YuguErrors.fromHttp(e.http, body);
      assert.ok(fromBody instanceof CLASS_OF[e.category]);
      assert.equal(fromBody.code, e.code);
      assert.equal(fromBody.message, `server says ${e.name}`);
      assert.equal(fromBody.rawBody, body);
      assert.equal(E.isRetryable(fromBody), e.retryable);
      assert.equal(ERROR_TABLE[e.code].name, e.name);
      assert.equal(ErrorCodes[e.name], e.code);
    });
  }
});

describe('error mapping: every warning code of errors.json', () => {
  for (const w of spec.warnings) {
    test(`${w.code} ${w.name} -> AudioQualityException, retryable ${w.retryable}`, () => {
      const err = E.YuguErrors.fromWarning(w.code);
      assert.ok(err instanceof E.AudioQualityException);
      assert.equal(err.category, 'AUDIO');
      assert.equal(err.code, w.code);
      assert.equal(err.message, w.message);
      assert.equal(E.WarningCode[w.name], w.code);
      assert.equal(WARNING_TABLE[w.code].message, w.message);
      if (!COLLIDING.has(w.code)) {
        const viaCode = E.YuguErrors.fromCode(w.code);
        assert.ok(viaCode instanceof E.AudioQualityException, 'fromCode builds AudioQualityException for warning codes');
        assert.equal(viaCode.retryable, w.retryable);
        assert.equal(E.isRetryable(viaCode), w.retryable);
      }
      assert.equal(ErrorCodes[`W_${w.name}`], w.code);
    });
  }
  test('codes 1004 and 1005 are both user errors and warnings: fromCode picks the error table', () => {
    assert.deepEqual([...COLLIDING].sort(), [1004, 1005]);
    assert.ok(E.YuguErrors.fromCode(1004) instanceof E.PermissionException);
    assert.ok(E.YuguErrors.fromWarning(1004) instanceof E.AudioQualityException);
  });
});

describe('error mapping: every local code of errors.json', () => {
  for (const l of spec.local) {
    test(`${l.code} ${l.name} -> ${l.category}, retryable ${l.retryable}`, () => {
      const err = E.YuguErrors.fromCode(l.code);
      assert.ok(err instanceof CLASS_OF[l.category]);
      assert.equal(err.category, l.category);
      assert.equal(err.retryable, l.retryable);
      assert.equal(E.isRetryable(err), l.retryable);
      assert.equal(err.httpStatus, 0);
      assert.equal(LOCAL_TABLE[l.code].name, l.name);
    });
  }
  test('rule 1: only 90001, 90002 and 90007 are retryable local codes', () => {
    const retryable = spec.local.filter((l) => l.retryable).map((l) => l.code);
    assert.deepEqual(retryable, [90001, 90002, 90007]);
  });
});

describe('error mapping: HTTP status fallback', () => {
  for (const [status, category] of Object.entries(spec.httpFallback)) {
    test(`HTTP ${status} without a code -> ${category}`, () => {
      const err = E.YuguErrors.fromHttp(Number(status), '<html>bad gateway</html>');
      assert.ok(err instanceof CLASS_OF[category]);
      assert.equal(err.category, category);
      assert.equal(err.code, 0);
      assert.equal(err.retryable, spec.retryableHttp.includes(Number(status)));
      assert.equal(HTTP_FALLBACK[status], category);
    });
  }
  test('statuses missing from httpFallback map by class (DESIGN 2.4)', () => {
    for (const [status, Cls, category, retryable] of [
      [418, E.InvalidParameterException, 'INVALID_PARAM', false],
      [451, E.InvalidParameterException, 'INVALID_PARAM', false],
      [499, E.InvalidParameterException, 'INVALID_PARAM', false],
      [505, E.ServerException, 'SERVER', false],
      [507, E.ServerException, 'SERVER', false],
      [599, E.ServerException, 'SERVER', false],
    ]) {
      assert.equal(spec.httpFallback[String(status)], undefined, `${status} is not in httpFallback`);
      const err = E.YuguErrors.fromHttp(status, '');
      assert.ok(err instanceof Cls, `HTTP ${status}`);
      assert.equal(err.category, category);
      assert.equal(err.retryable, retryable);
      assert.equal(E.YuguErrors.httpCategory(status), category);
    }
    for (const status of [0, 302, 600]) {
      const err = E.YuguErrors.fromHttp(status, '');
      assert.equal(err.constructor, E.YuguError, `HTTP ${status}`);
      assert.equal(err.category, 'UNKNOWN');
      assert.equal(err.retryable, false);
    }
    assert.equal(E.YuguErrors.fromCode(77777, { httpStatus: 418 }).category, 'INVALID_PARAM');
  });
  test('unknown business code falls back to the HTTP status rules', () => {
    const err = E.YuguErrors.fromHttp(503, JSON.stringify({ code: 77777, message: 'new code' }));
    assert.equal(err.code, 77777);
    assert.equal(err.category, 'UPSTREAM');
    assert.equal(err.retryable, true);
    assert.deepEqual([...RETRYABLE_HTTP], [408, 425, 429, 500, 502, 503, 504]);
  });
});

describe('error body shapes', () => {
  test('FastAPI detail string', () => {
    const err = E.YuguErrors.fromHttp(502, JSON.stringify({ detail: 'engine timeout' }));
    assert.ok(err instanceof E.ServerException);
    assert.equal(err.category, 'UPSTREAM');
    assert.equal(err.message, 'engine timeout');
    assert.equal(err.retryable, true);
  });
  test('FastAPI detail array', () => {
    const err = E.YuguErrors.fromHttp(422, JSON.stringify({ detail: [{ loc: ['body', 'refText'], msg: 'field required' }, { msg: 'bad' }] }));
    assert.ok(err instanceof E.InvalidParameterException);
    assert.equal(err.message, 'field required；bad');
  });
  test('FastAPI detail object', () => {
    assert.equal(E.YuguErrors.fromHttp(500, JSON.stringify({ detail: { message: 'x' } })).message, 'x');
    assert.match(E.YuguErrors.fromHttp(500, JSON.stringify({ detail: { why: 1 } })).message, /why/);
  });
  test('engine compat detail [2001] is AUTH (2001 to 2003)', () => {
    for (const code of [2001, 2002, 2003]) {
      const err = E.YuguErrors.fromHttp(400, JSON.stringify({ detail: `[${code}] auth triple missing` }));
      assert.ok(err instanceof E.AuthException, `code ${code}`);
      assert.equal(err.code, code);
      assert.equal(err.message, 'auth triple missing');
    }
  });
  test('engine detail with a code outside the table keeps the code and uses the HTTP status', () => {
    const err = E.YuguErrors.fromHttp(500, JSON.stringify({ detail: '[5123] decoder crashed' }));
    assert.equal(err.code, 5123);
    assert.ok(err instanceof E.ServerException);
  });
  test('code 0 in an error body counts as absent', () => {
    const err = E.YuguErrors.fromHttp(503, JSON.stringify({ code: 0, msg: 'busy' }));
    assert.equal(err.code, 0);
    assert.equal(err.message, 'busy');
    assert.equal(err.retryable, true);
  });
  test('error field and non-JSON body', () => {
    assert.equal(E.YuguErrors.fromHttp(500, JSON.stringify({ error: 'boom' })).message, 'boom');
    assert.equal(E.YuguErrors.fromHttp(504, 'gateway timeout').message, 'HTTP 504');
  });
  test('Retry-After and X-Trace-Id are kept', () => {
    const headers = new Headers({ 'Retry-After': '30', 'X-Trace-Id': 'abc' });
    const err = E.YuguErrors.fromHttp(429, JSON.stringify({ code: 42900, message: '排队超时' }), { headers, idempotencyKey: 'k' });
    assert.ok(err instanceof E.RateLimitException);
    assert.equal(err.retryAfterMs, 30000);
    assert.equal(err.traceId, 'abc');
    assert.equal(err.idempotencyKey, 'k');
    const plain = E.YuguErrors.fromHttp(409, JSON.stringify({ code: 40901 }), { headers: { 'retry-after': '2' } });
    assert.ok(plain instanceof E.ConflictException);
    assert.equal(plain.retryAfterMs, 2000);
    assert.equal(plain.retryable, true);
  });
  test('real platform fixtures', () => {
    const sig = readJson('platform/error_native_bad_signature.json');
    const a = E.YuguErrors.fromHttp(sig.status, sig.body, { headers: sig.headers });
    assert.ok(a instanceof E.AuthException);
    assert.equal(a.code, 2003);
    assert.equal(a.traceId, sig.headers['x-trace-id']);
    const pin = readJson('platform/error_compat_pinyin_missing_refpinyin.json');
    const b = E.YuguErrors.fromHttp(pin.status, pin.body, { headers: pin.headers });
    assert.ok(b instanceof E.InvalidParameterException);
    assert.equal(b.code, 40001);
    assert.equal(b.retryable, false);
    assert.match(b.message, /refPinyin/);
  });
  test('rawBody is truncated to 4 KB', () => {
    const err = E.YuguErrors.fromHttp(500, 'x'.repeat(10000));
    assert.equal(err.rawBody.length, 4096);
  });
  test('parseErrorBody returns code, message and json', () => {
    assert.deepEqual(E.YuguErrors.parseErrorBody('{"code":40001,"message":"m"}').code, 40001);
    assert.equal(E.YuguErrors.parseErrorBody('[1,2]').code, 0);
    assert.equal(E.YuguErrors.parseErrorBody(null).message, '');
  });
});

describe('WebSocket error frames', () => {
  test('frame with a retryable code', () => {
    const err = E.YuguErrors.fromWsFrame({ event: 'error', code: 50200, message: 'mock upstream error' });
    assert.ok(err instanceof E.ServerException);
    assert.equal(err.retryable, true);
    assert.match(err.rawBody, /50200/);
  });
  test('frame with a terminal code', () => {
    const err = E.YuguErrors.fromWsFrame({ event: 'error', code: 40903, message: 'reused' });
    assert.ok(err instanceof E.ConflictException);
    assert.equal(err.retryable, false);
  });
  test('frame without code is UNKNOWN and terminal', () => {
    const err = E.YuguErrors.fromWsFrame({ event: 'error', message: 'no audio/config' });
    assert.equal(err.category, 'UNKNOWN');
    assert.equal(err.retryable, false);
    assert.equal(E.YuguErrors.fromWsFrame(null).message, '实时评测服务返回错误');
  });
  test('engine pattern inside a frame message', () => {
    const err = E.YuguErrors.fromWsFrame({ event: 'error', message: '[2002] timestamp out of window' });
    assert.ok(err instanceof E.AuthException);
    assert.equal(err.code, 2002);
  });
});

describe('YuguError class', () => {
  test('instanceof chain and names survive for every subclass', () => {
    for (const [category, Cls] of Object.entries(CLASS_OF)) {
      const err = new Cls('m');
      assert.ok(err instanceof Cls && err instanceof E.YuguError && err instanceof Error);
      assert.equal(err.name, Cls.name);
      if (category !== 'UPSTREAM') assert.equal(err.category, category);
      assert.match(err.stack, /m/);
    }
    assert.equal(new E.ServerException('m', { category: 'UPSTREAM' }).category, 'UPSTREAM');
  });
  test('fields, defaults, cause and toJSON', () => {
    const cause = new Error('inner');
    const err = new E.NetworkException('outer', { code: 90001, cause, attempts: 3, idempotencyKey: 'k', recordId: 7 });
    assert.equal(err.cause, cause);
    assert.equal(err.retryable, true);
    assert.equal(err.recordId, '7');
    const j = err.toJSON();
    assert.equal(j.name, 'NetworkException');
    assert.equal(j.attempts, 3);
    assert.equal(JSON.parse(JSON.stringify(err)).code, 90001);
    const plain = new E.YuguError();
    assert.equal(plain.message, '');
    assert.equal(plain.category, 'UNKNOWN');
    assert.equal(plain.code, 0);
    assert.equal(plain.rawBody, null);
    assert.equal('cause' in plain, false);
  });
  test('explicit retryable overrides the computed flag on the field, isRetryable keeps the rules', () => {
    const err = new E.YuguError('x', { code: 90001, retryable: false });
    assert.equal(err.retryable, false);
    assert.equal(E.isRetryable(err), true);
  });
  test('isRetryable of foreign values is false', () => {
    assert.equal(E.isRetryable(new Error('x')), false);
    assert.equal(E.isRetryable(null), false);
    assert.equal(E.isRetryable({ code: 90001 }), false);
  });
  test('categoryFor, classForCategory, describeError, localError', () => {
    assert.equal(E.categoryFor(40001, 0), 'INVALID_PARAM');
    assert.equal(E.categoryFor(0, 429), 'RATE_LIMIT');
    assert.equal(E.classForCategory('NOPE'), E.YuguError);
    assert.equal(E.describeError(E.YuguErrors.fromHttp(503, '{"code":50200}')), 'HTTP 503 code=50200');
    assert.equal(E.describeError(E.localError(90002)), 'RequestTimeoutException code=90002');
    assert.equal(E.describeError(new Error('plain')), 'plain');
    assert.equal(E.localError(90010, 'detail').message, '调用参数不合法：detail');
    assert.equal(E.localError(99999).message, '错误码 99999');
    assert.equal(E.YuguErrors.fromCode(0).message, '未知错误');
    assert.equal(E.YuguErrors.fromWarning(31337).message, '音频质量警告 31337');
    assert.equal(Object.keys(E.ErrorCategory).length, spec.categories.length);
  });
});
