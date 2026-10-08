import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  generateIdempotencyKey,
  isValidIdempotencyKey,
  resolveIdempotencyKey,
  validateIdempotencyKey,
} from '../../src/idempotency.js';
import { InvalidParameterException } from '../../src/errors.js';

describe('idempotency keys (DESIGN 2.2)', () => {
  test('generated keys are 32 lowercase hex, UUID v4 bits, unique', () => {
    const keys = new Set();
    for (let i = 0; i < 500; i++) {
      const k = generateIdempotencyKey();
      assert.match(k, /^[0-9a-f]{32}$/);
      assert.equal(k[12], '4');
      assert.ok('89ab'.includes(k[16]));
      keys.add(k);
    }
    assert.equal(keys.size, 500);
  });
  test('fallback without randomUUID still yields v4 hex', () => {
    const k = generateIdempotencyKey({ getRandomValues: (a) => a.fill(0xff) });
    assert.equal(k, 'ffffffffffff4fffbfffffffffffffff');
  });
  test('caller keys: 1 to 200 visible ASCII characters', () => {
    assert.equal(isValidIdempotencyKey('a'), true);
    assert.equal(isValidIdempotencyKey('x'.repeat(200)), true);
    assert.equal(isValidIdempotencyKey('order-42:retry~!'), true);
    for (const bad of ['', 'x'.repeat(201), 'has space', 'tab\t', '中文', 'nul\u0000', 42, null, undefined]) {
      assert.equal(isValidIdempotencyKey(bad), false, JSON.stringify(bad));
    }
    assert.throws(() => validateIdempotencyKey('bad key'), (e) => e instanceof InvalidParameterException && e.code === 90010);
  });
  test('resolve: caller key wins, auto generates, off gives null', () => {
    assert.equal(resolveIdempotencyKey('mine', true), 'mine');
    assert.match(resolveIdempotencyKey(undefined, true), /^[0-9a-f]{32}$/);
    assert.equal(resolveIdempotencyKey(undefined, false), null);
    assert.equal(resolveIdempotencyKey('mine', false), 'mine');
    assert.throws(() => resolveIdempotencyKey('', true), InvalidParameterException);
  });
});
