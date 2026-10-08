import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  computeBackoffDelay,
  computeRetryDelay,
  DEFAULT_RECONNECT_POLICY,
  DEFAULT_RETRY_POLICY,
  normalizeReconnectPolicy,
  normalizeRetryPolicy,
  parseRetryAfter,
} from '../../src/retry.js';
import { InvalidParameterException } from '../../src/errors.js';
import { seededRandom } from '../helpers/common.mjs';

describe('RetryPolicy defaults (DESIGN 2.3)', () => {
  test('values', () => {
    assert.deepEqual({ ...DEFAULT_RETRY_POLICY }, {
      maxRetries: 2,
      initialDelayMs: 200,
      multiplier: 2,
      maxDelayMs: 4000,
      jitter: 0.3,
      respectRetryAfter: true,
      maxRetryAfterMs: 30000,
    });
    assert.deepEqual({ ...DEFAULT_RECONNECT_POLICY }, {
      enabled: true,
      maxAttempts: 8,
      initialDelayMs: 500,
      multiplier: 2,
      maxDelayMs: 4000,
      jitter: 0.3,
    });
    assert.ok(Object.isFrozen(DEFAULT_RETRY_POLICY));
  });
  test('no jitter gives 200, 400, 800 then the cap', () => {
    const p = { ...DEFAULT_RETRY_POLICY, jitter: 0 };
    assert.deepEqual([1, 2, 3, 4, 5, 6].map((n) => computeBackoffDelay(p, n)), [200, 400, 800, 1600, 3200, 4000]);
  });
  test('jitter bounds are plus or minus 30 %', () => {
    assert.equal(computeBackoffDelay(DEFAULT_RETRY_POLICY, 1, () => 0), 140);
    assert.equal(computeBackoffDelay(DEFAULT_RETRY_POLICY, 1, () => 0.5), 200);
    assert.equal(computeBackoffDelay(DEFAULT_RETRY_POLICY, 2, () => 0.999999), 520);
  });
});

describe('seeded backoff math', () => {
  test('exact formula with a seeded random source', () => {
    const rnd = seededRandom(42);
    const expect = seededRandom(42);
    for (let n = 1; n <= 8; n++) {
      const u = (expect() * 2 - 1) * 0.3;
      const base = Math.min(4000, 200 * Math.pow(2, n - 1));
      assert.equal(computeBackoffDelay(DEFAULT_RETRY_POLICY, n, rnd), Math.round(base * (1 + u)), `n=${n}`);
    }
  });
  test('same seed, same delays; within the jitter band for many seeds', () => {
    const a = [1, 2, 3].map((n, _i, _a, r = seededRandom(7)) => computeBackoffDelay(DEFAULT_RETRY_POLICY, n, r));
    const b = [1, 2, 3].map((n, _i, _a, r = seededRandom(7)) => computeBackoffDelay(DEFAULT_RETRY_POLICY, n, r));
    assert.deepEqual(a, b);
    for (let seed = 1; seed < 200; seed++) {
      const r = seededRandom(seed);
      const d1 = computeBackoffDelay(DEFAULT_RETRY_POLICY, 1, r);
      const d2 = computeBackoffDelay(DEFAULT_RETRY_POLICY, 2, r);
      assert.ok(d1 >= 140 && d1 <= 260, `d1 ${d1}`);
      assert.ok(d2 >= 280 && d2 <= 520, `d2 ${d2}`);
    }
  });
  test('reconnect delays 500, 1000, 2000, then 4000 capped, plus or minus 30 %, at least 16 s in total', () => {
    for (let seed = 1; seed < 50; seed++) {
      const r = seededRandom(seed);
      const ds = [1, 2, 3, 4, 5, 6, 7, 8].map((n) => computeBackoffDelay(DEFAULT_RECONNECT_POLICY, n, r));
      [500, 1000, 2000, 4000, 4000, 4000, 4000, 4000].forEach((base, i) => assert.ok(ds[i] >= base * 0.7 && ds[i] <= base * 1.3, `seed ${seed} n ${i + 1}: ${ds[i]}`));
      assert.ok(ds.reduce((a, b) => a + b, 0) >= 16450, 'the default budget outlasts a 10 s network drop');
    }
  });
});

describe('Retry-After', () => {
  test('parse seconds, decimals and HTTP dates', () => {
    assert.equal(parseRetryAfter('30'), 30000);
    assert.equal(parseRetryAfter(' 1.5 '), 1500);
    assert.equal(parseRetryAfter(2), 2000);
    const now = Date.parse('Thu, 08 Oct 2026 08:00:00 GMT');
    assert.equal(parseRetryAfter('Thu, 08 Oct 2026 08:00:05 GMT', now), 5000);
    assert.equal(parseRetryAfter('Thu, 08 Oct 2026 07:00:00 GMT', now), 0);
    assert.equal(parseRetryAfter('soon'), null);
    assert.equal(parseRetryAfter(''), null);
    assert.equal(parseRetryAfter(null), null);
    assert.ok(parseRetryAfter(new Date(Date.now() + 60000).toUTCString()) > 50000);
  });
  test('delay = max(backoff, min(retryAfter, maxRetryAfterMs))', () => {
    const half = () => 0.5;
    assert.equal(computeRetryDelay(DEFAULT_RETRY_POLICY, 1, 1000, half), 1000);
    assert.equal(computeRetryDelay(DEFAULT_RETRY_POLICY, 1, 50, half), 200);
    assert.equal(computeRetryDelay(DEFAULT_RETRY_POLICY, 1, 120000, half), 30000);
    assert.equal(computeRetryDelay(DEFAULT_RETRY_POLICY, 1, null, half), 200);
    assert.equal(computeRetryDelay({ ...DEFAULT_RETRY_POLICY, respectRetryAfter: false }, 1, 5000, half), 200);
  });
});

describe('policy validation', () => {
  test('merge and freeze', () => {
    const p = normalizeRetryPolicy({ maxRetries: 4 });
    assert.equal(p.maxRetries, 4);
    assert.equal(p.initialDelayMs, 200);
    assert.ok(Object.isFrozen(p));
    assert.equal(normalizeRetryPolicy(false).maxRetries, 0);
    assert.equal(normalizeRetryPolicy(undefined), DEFAULT_RETRY_POLICY);
    assert.equal(normalizeRetryPolicy({ respectRetryAfter: false }).respectRetryAfter, false);
    assert.equal(normalizeReconnectPolicy(false).enabled, false);
    assert.equal(normalizeReconnectPolicy({ maxAttempts: 6 }).maxAttempts, 6);
    assert.equal(normalizeReconnectPolicy(null), DEFAULT_RECONNECT_POLICY);
  });
  test('bad values are InvalidParameterException 90010', () => {
    for (const bad of [{ maxRetries: -1 }, { maxRetries: 1.5 }, { jitter: 2 }, { multiplier: 0.5 }, { initialDelayMs: NaN }, 'x']) {
      assert.throws(() => normalizeRetryPolicy(bad), (e) => e instanceof InvalidParameterException && e.code === 90010);
    }
    for (const bad of [{ maxAttempts: -1 }, { jitter: -0.1 }, 3]) {
      assert.throws(() => normalizeReconnectPolicy(bad), InvalidParameterException);
    }
  });
});
