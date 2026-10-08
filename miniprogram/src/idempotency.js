// Idempotency keys (DESIGN 2.2).
import { localError } from './errors.js';
import { randomBytes, toHex } from './util.js';

const KEY_PATTERN = /^[\x21-\x7E]{1,200}$/;

/** 32 lowercase hex characters: a UUID v4 without dashes. */
export function generateIdempotencyKey() {
  const b = randomBytes(16);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  return toHex(b);
}

export function isValidIdempotencyKey(key) {
  return typeof key === 'string' && KEY_PATTERN.test(key);
}

/** Throws InvalidParameterException 90010 unless key matches ^[\x21-\x7E]{1,200}$. */
export function validateIdempotencyKey(key) {
  if (!isValidIdempotencyKey(key)) {
    throw localError(90010, 'idempotencyKey must be 1 to 200 visible ASCII characters (^[\\x21-\\x7E]{1,200}$)');
  }
  return key;
}
