// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// Idempotency keys (DESIGN 2.2).

import { localError } from './errors.js';
import { getCrypto, randomBytes, toHex } from './util.js';

const KEY_PATTERN = /^[\x21-\x7E]{1,200}$/;

/** 32 lowercase hex characters: a UUID v4 without dashes. */
export function generateIdempotencyKey(cryptoImpl) {
  const c = getCrypto(cryptoImpl);
  if (c && typeof c.randomUUID === 'function') {
    return c.randomUUID().replace(/-/g, '').toLowerCase();
  }
  const b = randomBytes(16, cryptoImpl);
  b[6] = (b[6] & 0x0f) | 0x40; // version 4
  b[8] = (b[8] & 0x3f) | 0x80; // variant 10
  return toHex(b);
}

/** True when the key is 1 to 200 visible ASCII characters. */
export function isValidIdempotencyKey(key) {
  return typeof key === 'string' && KEY_PATTERN.test(key);
}

/** Throws InvalidParameterException 90010 for a bad caller key. */
export function validateIdempotencyKey(key) {
  if (!isValidIdempotencyKey(key)) {
    throw localError(90010, 'idempotencyKey 必须是 1 到 200 个可见 ASCII 字符');
  }
  return key;
}

/**
 * Key for one logical call: the caller key (validated), a generated key when auto is on, or null.
 */
export function resolveIdempotencyKey(callerKey, auto, cryptoImpl) {
  if (callerKey !== undefined && callerKey !== null) return validateIdempotencyKey(callerKey);
  return auto ? generateIdempotencyKey(cryptoImpl) : null;
}
