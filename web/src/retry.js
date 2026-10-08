// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// Retry and reconnect policies with the exact backoff formula of DESIGN 2.3 and 2.5.

import { localError } from './errors.js';
import { isFiniteNumber } from './util.js';

export { parseRetryAfter } from './headers.js';

/** RetryPolicy defaults: 3 attempts in total, 200 ms then 400 ms (each plus or minus 30 %). */
export const DEFAULT_RETRY_POLICY = Object.freeze({
  maxRetries: 2,
  initialDelayMs: 200,
  multiplier: 2,
  maxDelayMs: 4000,
  jitter: 0.3,
  respectRetryAfter: true,
  maxRetryAfterMs: 30000,
});

/**
 * ReconnectPolicy defaults for WebSocket sessions: up to 8 consecutive attempts, waits about
 * 0.5, 1, 2, 4, 4, 4, 4, 4 s (23.5 s in total), so a 10 s network drop is ridden out.
 */
export const DEFAULT_RECONNECT_POLICY = Object.freeze({
  enabled: true,
  maxAttempts: 8,
  initialDelayMs: 500,
  multiplier: 2,
  maxDelayMs: 4000,
  jitter: 0.3,
});

function checkNumber(obj, key, min, max, integer) {
  const v = obj[key];
  if (!isFiniteNumber(v) || v < min || v > max || (integer && !Number.isInteger(v))) {
    throw localError(90010, `${key} 取值 ${String(v)} 不在 ${min} 到 ${max} 之间${integer ? '或不是整数' : ''}`);
  }
}

/** Merge a partial RetryPolicy over a base and validate it. false disables retries. */
export function normalizeRetryPolicy(partial, base) {
  const b = base || DEFAULT_RETRY_POLICY;
  if (partial === false) return Object.freeze(Object.assign({}, b, { maxRetries: 0 }));
  if (partial == null) return b;
  if (typeof partial !== 'object') throw localError(90010, 'retry 必须是对象或 false');
  const p = Object.assign({}, b, partial);
  checkNumber(p, 'maxRetries', 0, 10, true);
  checkNumber(p, 'initialDelayMs', 0, 600000, false);
  checkNumber(p, 'multiplier', 1, 10, false);
  checkNumber(p, 'maxDelayMs', 0, 600000, false);
  checkNumber(p, 'jitter', 0, 1, false);
  checkNumber(p, 'maxRetryAfterMs', 0, 600000, false);
  p.respectRetryAfter = p.respectRetryAfter !== false;
  return Object.freeze(p);
}

/** Merge a partial ReconnectPolicy over a base and validate it. false disables reconnects. */
export function normalizeReconnectPolicy(partial, base) {
  const b = base || DEFAULT_RECONNECT_POLICY;
  if (partial === false) return Object.freeze(Object.assign({}, b, { enabled: false }));
  if (partial == null) return b;
  if (typeof partial !== 'object') throw localError(90010, 'reconnect 必须是对象或 false');
  const p = Object.assign({}, b, partial);
  checkNumber(p, 'maxAttempts', 0, 100, true);
  checkNumber(p, 'initialDelayMs', 0, 600000, false);
  checkNumber(p, 'multiplier', 1, 10, false);
  checkNumber(p, 'maxDelayMs', 0, 600000, false);
  checkNumber(p, 'jitter', 0, 1, false);
  p.enabled = p.enabled !== false;
  return Object.freeze(p);
}

/**
 * delay(n) = min(maxDelayMs, initialDelayMs * multiplier^(n-1)) * (1 + U(-jitter, +jitter)), n = 1, 2, ...
 * random() returns a number in [0, 1), Math.random by default. The result is rounded to whole ms.
 */
export function computeBackoffDelay(policy, n, random) {
  const rnd = typeof random === 'function' ? random : Math.random;
  const base = Math.min(policy.maxDelayMs, policy.initialDelayMs * Math.pow(policy.multiplier, Math.max(0, n - 1)));
  const u = (rnd() * 2 - 1) * policy.jitter;
  return Math.max(0, Math.round(base * (1 + u)));
}

/**
 * Retry delay with Retry-After: delay = max(backoff, min(retryAfterMs, maxRetryAfterMs))
 * when respectRetryAfter is set and the response had Retry-After.
 */
export function computeRetryDelay(policy, n, retryAfterMs, random) {
  let d = computeBackoffDelay(policy, n, random);
  if (policy.respectRetryAfter && isFiniteNumber(retryAfterMs) && retryAfterMs >= 0) {
    d = Math.max(d, Math.min(retryAfterMs, policy.maxRetryAfterMs));
  }
  return d;
}
