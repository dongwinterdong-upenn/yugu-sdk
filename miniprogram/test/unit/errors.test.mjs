// Acceptance 6.6: every error and warning code of spec/errors.json maps to the right type, keeps the
// original code and agrees with the retry rules.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { SPEC, fixtureJson } from '../helpers/common.mjs';
import * as E from '../../src/errors.js';
import { ERROR_TABLE, WARNING_TABLE, LOCAL_TABLE, HTTP_FALLBACK, RETRYABLE_HTTP, ErrorCodes } from '../../src/error-table.js';

const spec = JSON.parse(fs.readFileSync(path.join(SPEC, 'errors.json'), 'utf8'));

const CLASS = {
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
  UNKNOWN: E.YuguError,
};

function check(err, entry, what) {
  assert.ok(err instanceof CLASS[entry.category], `${what}: ${err.name} for ${entry.category}`);
  assert.ok(err instanceof E.YuguError && err instanceof Error, what);
  assert.equal(err.category, entry.category, what + ' category');
  assert.equal(err.code, entry.code, what + ' code');
  assert.equal(err.retryable, entry.retryable, what + ' retryable');
  assert.equal(E.isRetryable(err), entry.retryable, what + ' isRetryable');
}

test('the generated table equals spec/errors.json', () => {
  assert.equal(Object.keys(ERROR_TABLE).length, spec.errors.length);
  assert.equal(Object.keys(WARNING_TABLE).length, spec.warnings.length);
  assert.equal(Object.keys(LOCAL_TABLE).length, spec.local.length);
  assert.deepEqual([...RETRYABLE_HTTP], spec.retryableHttp);
  assert.deepEqual({ ...HTTP_FALLBACK }, spec.httpFallback);
  for (const e of spec.errors) assert.equal(ErrorCodes[e.name], e.code);
  for (const e of spec.local) assert.equal(ErrorCodes[e.name], e.code);
  for (const w of spec.warnings) {
    assert.equal(ErrorCodes['W_' + w.name], w.code);
    assert.equal(E.WarningCode[w.name], w.code);
  }
  assert.equal(Object.keys(E.WarningCode).length, spec.warnings.length);
  assert.deepEqual(Object.keys(E.ErrorCategory), spec.categories);
});

test('every platform error code: fromCode and HTTP response mapping', () => {
  for (const e of spec.errors) {
    check(E.fromCode(e.code), e, 'fromCode ' + e.code);
    const err = E.fromHttpResponse(e.http, JSON.stringify({ code: e.code, message: 'srv ' + e.message, timestamp: 1 }), { idempotencyKey: 'k1' });
    check(err, e, 'http ' + e.code);
    assert.equal(err.httpStatus, e.http);
    assert.equal(err.message, 'srv ' + e.message);
    assert.equal(err.idempotencyKey, 'k1');
    assert.ok(err.rawBody.includes(String(e.code)));
    // codes as strings are accepted as well
    check(E.fromHttpResponse(e.http, JSON.stringify({ code: String(e.code), message: 'x' })), e, 'string code ' + e.code);
  }
});

test('every warning code builds an AudioQualityException', () => {
  for (const w of spec.warnings) {
    const err = E.fromWarningCode(w.code);
    check(err, w, 'warning ' + w.code);
    assert.equal(err.message, w.message);
  }
  // 1001, 1002, 1003 and 1009 exist only as warnings, so fromCode builds the warning type too
  for (const c of [1001, 1002, 1003, 1009]) assert.ok(E.fromCode(c) instanceof E.AudioQualityException);
  // 1004 and 1005 are both platform errors and warnings: fromCode prefers the platform meaning
  assert.ok(E.fromCode(1004) instanceof E.PermissionException);
  assert.ok(E.fromWarningCode(1004) instanceof E.AudioQualityException);
  assert.equal(E.YuguErrors.fromCode(1001).category, 'AUDIO');
});

test('every local code, and the local retry rule', () => {
  for (const l of spec.local) {
    const err = E.fromCode(l.code);
    check(err, l, 'local ' + l.code);
    assert.equal(err.httpStatus, 0);
    assert.equal(err.retryable, [90001, 90002, 90007].includes(l.code));
  }
});

test('HTTP status fallback when the body carries no code', () => {
  for (const [status, category] of Object.entries(spec.httpFallback)) {
    const s = Number(status);
    for (const body of ['', '<html><body>bad gateway</body></html>', '{"timestamp":1}']) {
      const err = E.fromHttpResponse(s, body);
      assert.ok(err instanceof CLASS[category], `${s} ${body.slice(0, 6)} -> ${err.name}`);
      assert.equal(err.category, category);
      assert.equal(err.code, 0);
      assert.equal(err.httpStatus, s);
      assert.equal(err.retryable, spec.retryableHttp.includes(s));
      assert.equal(E.isRetryable(err), spec.retryableHttp.includes(s));
    }
  }
  // statuses missing from httpFallback map by class (DESIGN 2.4)
  for (const s of [402, 406, 410, 418, 451, 499]) {
    const err = E.fromHttpResponse(s, '');
    assert.ok(err instanceof E.InvalidParameterException, String(s));
    assert.equal(err.retryable, false);
  }
  for (const s of [505, 507, 511, 599]) {
    const err = E.fromHttpResponse(s, '');
    assert.ok(err instanceof E.ServerException, String(s));
    assert.equal(err.category, 'SERVER');
    assert.equal(err.retryable, false, 'not in retryableHttp');
  }
  for (const s of [0, 200, 302, 600]) assert.equal(E.fromHttpResponse(s, '').category, 'UNKNOWN', String(s));
});

test('code namespaces: 1004 and 1005 are looked up by context, never by number alone', () => {
  assert.equal(E.fromCode(1004).codeNamespace, 'errors');
  assert.ok(E.fromCode(1005) instanceof E.PermissionException);
  assert.equal(E.fromWarningCode(1004).codeNamespace, 'warnings');
  assert.ok(E.fromWarningCode(1005) instanceof E.AudioQualityException);
  assert.equal(E.fromCode(90002).codeNamespace, 'local');
  // a warning code inside an error body is not a platform error code: HTTP status decides
  const body = E.fromHttpResponse(400, '{"code":1009,"message":"x"}');
  assert.equal(body.codeNamespace, 'unknown');
  assert.equal(body.category, 'INVALID_PARAM');
  assert.equal(body.retryable, false);
  assert.equal(E.isRetryable(body), false);
  const warn = E.fromWarningCode(1009);
  assert.equal(warn.retryable, true);
  assert.equal(E.isRetryable(warn), true);
  assert.equal(E.fromHttpResponse(500, '{"code":1004}').category, 'PERMISSION', 'in an error body 1004 is USER_DISABLED, the body code wins over the status');
  assert.ok(E.fromHttpResponse(400, '{"code":1004}') instanceof E.PermissionException);
  assert.equal(E.fromHttpResponse(503, '{"code":90001}').codeNamespace, 'unknown', 'local codes never come from a server');
  assert.equal(E.fromWsErrorFrame({ event: 'error', code: 1002 }).category, 'SERVER');
  const unknownWarning = E.fromWarningCode(7777);
  assert.ok(unknownWarning instanceof E.AudioQualityException);
  assert.equal(unknownWarning.retryable, false);
  assert.equal(Object.keys(E.fromCode(40001)).includes('codeNamespace'), false, 'not enumerable');
});

test('unknown business codes fall back to the HTTP status and keep the code', () => {
  const err = E.fromHttpResponse(503, '{"code":99999,"message":"new"}');
  assert.ok(err instanceof E.ServerException);
  assert.equal(err.category, 'UPSTREAM');
  assert.equal(err.code, 99999);
  assert.equal(err.retryable, true);
  const err2 = E.fromHttpResponse(400, '{"code":12345,"message":"x"}');
  assert.equal(err2.retryable, false);
  assert.equal(err2.category, 'INVALID_PARAM');
  assert.ok(E.fromCode(12345) instanceof E.YuguError);
  assert.equal(E.fromCode(12345).codeNamespace, 'unknown');
  assert.equal(E.fromCode(12345, { httpStatus: 429 }).category, 'RATE_LIMIT');
});

test('FastAPI detail bodies and the compat engine [2001] pattern', () => {
  const s = E.fromHttpResponse(502, '{"detail":"engine overloaded"}');
  assert.ok(s instanceof E.ServerException);
  assert.equal(s.message, 'engine overloaded');
  assert.equal(s.retryable, true);
  const v = E.fromHttpResponse(422, '{"detail":[{"loc":["body","refText"],"msg":"field required"},{"msg":"bad"},"x"]}');
  assert.ok(v instanceof E.InvalidParameterException);
  assert.equal(v.message, 'field required; bad; x');
  for (const c of [2001, 2002, 2003]) {
    const a = E.fromHttpResponse(500, JSON.stringify({ detail: `[${c}] auth failed` }));
    assert.ok(a instanceof E.AuthException, String(c));
    assert.equal(a.code, c);
    assert.equal(a.retryable, false);
  }
  const u = E.fromHttpResponse(500, '{"detail":"[50200] upstream"}');
  assert.equal(u.code, 50200);
  assert.equal(u.category, 'UPSTREAM');
  const other = E.fromHttpResponse(500, '{"detail":"[7777] who knows"}');
  assert.equal(other.code, 0);
  assert.equal(other.category, 'SERVER');
});

test('real error responses captured from the platform', () => {
  const sig = fixtureJson('platform/error_native_bad_signature.json');
  const a = E.fromHttpResponse(sig.status, sig.body, { headers: sig.headers });
  assert.ok(a instanceof E.AuthException);
  assert.equal(a.code, 2003);
  assert.equal(a.retryable, false);
  const pin = fixtureJson('platform/error_compat_pinyin_missing_refpinyin.json');
  const b = E.fromHttpResponse(pin.status, pin.body);
  assert.ok(b instanceof E.InvalidParameterException);
  assert.equal(b.code, 40001);
  assert.match(b.message, /refPinyin/);
});

test('Retry-After, recordId and a 4 KB raw body limit', () => {
  const err = E.fromHttpResponse(429, '{"code":42900,"message":"排队超时","recordId":"eval_1"}', { headers: { 'retry-after': '30' } });
  assert.equal(err.retryAfterMs, 30000);
  assert.equal(err.recordId, 'eval_1');
  const big = E.fromHttpResponse(500, 'x'.repeat(10000));
  assert.equal(big.rawBody.length, 4096);
});

test('WebSocket error frames', () => {
  const r = E.fromWsErrorFrame({ event: 'error', code: 50200, message: 'upstream' }, { idempotencyKey: 'k' });
  assert.ok(r instanceof E.ServerException);
  assert.equal(r.retryable, true);
  assert.equal(r.idempotencyKey, 'k');
  const t = E.fromWsErrorFrame({ event: 'error', message: 'no audio' });
  assert.ok(t instanceof E.ServerException);
  assert.equal(t.code, 0);
  assert.equal(t.retryable, false);
  assert.equal(E.fromWsErrorFrame({ event: 'error', code: 40901 }).retryable, true);
  assert.equal(E.fromWsErrorFrame(null).message, 'server error frame');
});

test('wx fail callbacks map to local codes', () => {
  assert.equal(E.fromWxFail({ errMsg: 'request:fail timeout' }).code, 90002);
  assert.equal(E.fromWxFail({ errMsg: 'request:fail abort' }).code, 90003);
  assert.equal(E.fromWxFail({ errMsg: 'request:fail certificate verify failed' }).code, 90011);
  const d = E.fromWxFail({ errMsg: 'request:fail url not in domain list' });
  assert.equal(d.code, 90001);
  assert.match(d.message, /legal domains/);
  assert.equal(E.fromWxFail({ errMsg: 'request:fail -1009' }).code, 90001);
  assert.equal(E.fromWxFail(undefined, 'socket').code, 90001);
});

test('isRetryable is one rule for every input shape', () => {
  assert.equal(E.isRetryable(null), false);
  assert.equal(E.isRetryable('x'), false);
  assert.equal(E.isRetryable(new Error('x')), false);
  assert.equal(E.isRetryable({ code: 50000 }), true);
  assert.equal(E.isRetryable({ code: 0, httpStatus: 503 }), true);
  assert.equal(E.isRetryable({ code: 90005 }), false);
  assert.equal(E.YuguErrors.isRetryable({ httpStatus: 429 }), true);
});

test('error objects: names, fields, JSON form and wrapping of foreign errors', () => {
  for (const C of new Set(Object.values(CLASS))) {
    const e = new C('m', { code: 90001, httpStatus: 0, idempotencyKey: 'k', attempts: 2, cause: 'c', op: 'evaluate' });
    assert.equal(e.name, C.name);
    assert.ok(e instanceof C && e instanceof E.YuguError && e instanceof Error);
    assert.equal(e.idempotencyKey, 'k');
    assert.equal(e.attempts, 2);
    assert.equal(e.cause, 'c');
  }
  const j = E.fromCode(40400, { idempotencyKey: 'k', recordId: 'r' }).toJSON();
  assert.equal(j.name, 'NotFoundException');
  assert.equal(j.recordId, 'r');
  const wrapped = E.asYuguError(new TypeError('boom'));
  assert.equal(wrapped.category, 'UNKNOWN');
  assert.ok(wrapped.cause instanceof TypeError);
  assert.equal(E.asYuguError('plain').message, 'plain');
  const same = E.fromCode(50000);
  assert.equal(E.asYuguError(same), same);
  assert.equal(E.createError('UNKNOWN', 'u').constructor, E.YuguError);
  assert.equal(E.createError(undefined, 'u').category, 'UNKNOWN');
  assert.equal(E.localError(90009).message, LOCAL_TABLE[90009].message);
});
