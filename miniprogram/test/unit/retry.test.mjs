import test from 'node:test';
import assert from 'node:assert/strict';
import { seeded } from '../helpers/common.mjs';
import {
  DEFAULT_RETRY_POLICY, DEFAULT_RECONNECT_POLICY, computeBackoffDelay, computeRetryDelay, parseRetryAfter,
  normalizeRetryPolicy, normalizeReconnectPolicy,
} from '../../src/retry.js';
import { generateIdempotencyKey, validateIdempotencyKey, isValidIdempotencyKey } from '../../src/idempotency.js';
import { InvalidParameterException } from '../../src/errors.js';

// The DESIGN 2.3 formula written out independently of the SDK.
const formula = (n, p, r) => Math.round(Math.min(p.maxDelayMs, p.initialDelayMs * p.multiplier ** (n - 1)) * (1 + (r * 2 - 1) * p.jitter));

test('default policies are the documented values', () => {
  assert.deepEqual({ ...DEFAULT_RETRY_POLICY }, {
    maxRetries: 2, initialDelayMs: 200, multiplier: 2, maxDelayMs: 4000, jitter: 0.3, respectRetryAfter: true, maxRetryAfterMs: 30000,
  });
  assert.deepEqual({ ...DEFAULT_RECONNECT_POLICY }, {
    enabled: true, maxAttempts: 8, initialDelayMs: 500, multiplier: 2, maxDelayMs: 4000, jitter: 0.3,
  });
  // 8 waits of 0.5, 1, 2, 4, 4, 4, 4, 4 s: about 23.5 s, at least 16.45 s with the jitter, so a 10 s
  // drop is always bridged by the defaults
  const mid = () => 0.5;
  const waits = [1, 2, 3, 4, 5, 6, 7, 8].map((n) => computeBackoffDelay(n, DEFAULT_RECONNECT_POLICY, mid));
  assert.deepEqual(waits, [500, 1000, 2000, 4000, 4000, 4000, 4000, 4000]);
  assert.equal(waits.reduce((a, b) => a + b, 0), 23500);
  const shortest = [1, 2, 3, 4, 5, 6, 7, 8].map((n) => computeBackoffDelay(n, DEFAULT_RECONNECT_POLICY, () => 0)).reduce((a, b) => a + b, 0);
  assert.ok(shortest > 10000, String(shortest));
});

test('backoff without jitter: 200, 400, 800 ms then the cap', () => {
  const mid = () => 0.5;
  assert.deepEqual([1, 2, 3, 4, 5, 6, 10].map((n) => computeBackoffDelay(n, DEFAULT_RETRY_POLICY, mid)), [200, 400, 800, 1600, 3200, 4000, 4000]);
  assert.deepEqual([1, 2, 3, 4, 5].map((n) => computeBackoffDelay(n, DEFAULT_RECONNECT_POLICY, mid)), [500, 1000, 2000, 4000, 4000]);
});

test('jitter stays within plus or minus 30 percent', () => {
  assert.equal(computeBackoffDelay(1, DEFAULT_RETRY_POLICY, () => 0), 140);
  assert.equal(computeBackoffDelay(1, DEFAULT_RETRY_POLICY, () => 0.9999999), 260);
  const rnd = seeded(7);
  for (let i = 0; i < 2000; i++) {
    const d = computeBackoffDelay(2, DEFAULT_RETRY_POLICY, rnd);
    assert.ok(d >= 280 && d <= 520, String(d));
  }
});

test('seeded backoff math equals the DESIGN formula draw by draw', () => {
  const a = seeded(42);
  const b = seeded(42);
  for (let n = 1; n <= 8; n++) {
    const got = computeBackoffDelay(n, DEFAULT_RETRY_POLICY, a);
    assert.equal(got, formula(n, DEFAULT_RETRY_POLICY, b()), 'n=' + n);
  }
  const c = seeded(1);
  const d = seeded(1);
  for (let n = 1; n <= 5; n++) assert.equal(computeBackoffDelay(n, DEFAULT_RECONNECT_POLICY, c), formula(n, DEFAULT_RECONNECT_POLICY, d()));
});

test('Retry-After raises the delay, capped at maxRetryAfterMs, and can be ignored', () => {
  const mid = () => 0.5;
  assert.equal(computeRetryDelay(1, DEFAULT_RETRY_POLICY, mid, 1000), 1000);
  assert.equal(computeRetryDelay(1, DEFAULT_RETRY_POLICY, mid, 50), 200);
  assert.equal(computeRetryDelay(1, DEFAULT_RETRY_POLICY, mid, 120000), 30000);
  assert.equal(computeRetryDelay(1, DEFAULT_RETRY_POLICY, mid, undefined), 200);
  const ignore = normalizeRetryPolicy({ respectRetryAfter: false });
  assert.equal(computeRetryDelay(1, ignore, mid, 5000), 200);
  assert.equal(typeof computeRetryDelay(1, undefined, undefined, undefined), 'number');
});

test('Retry-After header values', () => {
  assert.equal(parseRetryAfter('1'), 1000);
  assert.equal(parseRetryAfter(' 30 '), 30000);
  assert.equal(parseRetryAfter('0'), 0);
  const now = Date.parse('Thu, 08 Oct 2026 08:00:00 GMT');
  assert.equal(parseRetryAfter('Thu, 08 Oct 2026 08:00:05 GMT', now), 5000);
  assert.equal(parseRetryAfter('Thu, 08 Oct 2026 07:00:00 GMT', now), 0);
  assert.equal(parseRetryAfter('soon'), undefined);
  assert.equal(parseRetryAfter(''), undefined);
  assert.equal(parseRetryAfter(null), undefined);
  assert.equal(parseRetryAfter(undefined), undefined);
});

test('policy overrides merge and are validated', () => {
  assert.equal(normalizeRetryPolicy(false).maxRetries, 0);
  assert.equal(normalizeRetryPolicy({ maxRetries: 5 }).initialDelayMs, 200);
  assert.equal(normalizeRetryPolicy({ maxRetries: 1 }, normalizeRetryPolicy({ initialDelayMs: 10 })).initialDelayMs, 10);
  assert.ok(Object.isFrozen(normalizeRetryPolicy(undefined)));
  for (const bad of [{ maxRetries: -1 }, { maxRetries: 1.5 }, { multiplier: 0.5 }, { jitter: 2 }, { initialDelayMs: NaN },
    { respectRetryAfter: 'yes' }, { maxDelayMs: -1 }, { maxRetryAfterMs: -5 }, 7]) {
    assert.throws(() => normalizeRetryPolicy(bad), InvalidParameterException, JSON.stringify(bad));
  }
  assert.equal(normalizeReconnectPolicy(false).enabled, false);
  assert.equal(normalizeReconnectPolicy({ maxAttempts: 6 }).initialDelayMs, 500);
  for (const bad of [{ enabled: 1 }, { maxAttempts: -1 }, { multiplier: 0 }, { jitter: -0.1 }, { maxDelayMs: 'x' }, { initialDelayMs: -1 }, 'x']) {
    assert.throws(() => normalizeReconnectPolicy(bad), InvalidParameterException, JSON.stringify(bad));
  }
});

test('idempotency keys are 32 lowercase hex characters of a UUID v4, unique', () => {
  const seen = new Set();
  for (let i = 0; i < 10000; i++) {
    const k = generateIdempotencyKey();
    assert.match(k, /^[0-9a-f]{32}$/);
    assert.equal(k[12], '4');
    assert.match(k[16], /[89ab]/);
    seen.add(k);
  }
  assert.equal(seen.size, 10000);
});

test('caller keys must match ^[\\x21-\\x7E]{1,200}$', () => {
  for (const ok of ['!', '~'.repeat(200), 'order-42:item_7', generateIdempotencyKey()]) {
    assert.equal(validateIdempotencyKey(ok), ok);
    assert.equal(isValidIdempotencyKey(ok), true);
  }
  for (const bad of ['', 'a'.repeat(201), 'has space', 'tab\t', '键', 42, null, undefined]) {
    assert.throws(() => validateIdempotencyKey(bad), (e) => e instanceof InvalidParameterException && e.code === 90010, String(bad));
  }
});
