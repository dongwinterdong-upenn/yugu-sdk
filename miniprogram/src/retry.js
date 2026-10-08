// RetryPolicy (DESIGN 2.3) and ReconnectPolicy (DESIGN 2.5) with the exact backoff formula.
import { localError } from './errors.js';

export { parseRetryAfter } from './retry-after.js';

export const DEFAULT_RETRY_POLICY = Object.freeze({
  maxRetries: 2,
  initialDelayMs: 200,
  multiplier: 2.0,
  maxDelayMs: 4000,
  jitter: 0.3,
  respectRetryAfter: true,
  maxRetryAfterMs: 30000,
});

// 8 consecutive attempts wait 0.5, 1, 2, 4, 4, 4, 4, 4 s (about 23 s plus or minus 30 percent), so the
// defaults ride out a 10 s network drop (acceptance 6.4). The count restarts after a reconnect succeeds.
export const DEFAULT_RECONNECT_POLICY = Object.freeze({
  enabled: true,
  maxAttempts: 8,
  initialDelayMs: 500,
  multiplier: 2,
  maxDelayMs: 4000,
  jitter: 0.3,
});

/**
 * delay(n) = min(maxDelayMs, initialDelayMs * multiplier^(n-1)) * (1 + U(-jitter, +jitter)), n >= 1.
 * `random` returns a number in [0, 1); U(-j, +j) = (2 * random() - 1) * j. Result rounded to whole ms.
 */
export function computeBackoffDelay(n, policy, random) {
  const p = policy || DEFAULT_RETRY_POLICY;
  const rnd = typeof random === 'function' ? random : Math.random;
  const base = Math.min(p.maxDelayMs, p.initialDelayMs * Math.pow(p.multiplier, Math.max(0, n - 1)));
  const u = (rnd() * 2 - 1) * (p.jitter || 0);
  return Math.max(0, Math.round(base * (1 + u)));
}

/**
 * Backoff delay of retry n, raised to the server's Retry-After (capped at maxRetryAfterMs)
 * when respectRetryAfter is on.
 */
export function computeRetryDelay(n, policy, random, retryAfterMs) {
  const p = policy || DEFAULT_RETRY_POLICY;
  let d = computeBackoffDelay(n, p, random);
  if (p.respectRetryAfter && typeof retryAfterMs === 'number' && retryAfterMs >= 0) {
    d = Math.max(d, Math.min(retryAfterMs, p.maxRetryAfterMs));
  }
  return d;
}

function check(cond, what) {
  if (!cond) throw localError(90010, 'invalid policy field ' + what);
}

function merge(base, override) {
  const out = {};
  const keys = Object.keys(base);
  for (let i = 0; i < keys.length; i++) out[keys[i]] = base[keys[i]];
  if (override && typeof override === 'object') {
    const ok = Object.keys(override);
    for (let i = 0; i < ok.length; i++) {
      if (override[ok[i]] !== undefined) out[ok[i]] = override[ok[i]];
    }
  }
  return out;
}

const isNonNegInt = (v) => typeof v === 'number' && isFinite(v) && v >= 0 && Math.floor(v) === v;
const isNonNeg = (v) => typeof v === 'number' && isFinite(v) && v >= 0;

/** Merges a partial RetryPolicy over a base policy. `false` disables retries. */
export function normalizeRetryPolicy(override, base) {
  const b = base || DEFAULT_RETRY_POLICY;
  if (override === false) return Object.freeze(merge(b, { maxRetries: 0 }));
  if (override !== undefined && override !== null && typeof override !== 'object') {
    throw localError(90010, 'retry must be an object or false');
  }
  const p = merge(b, override);
  check(isNonNegInt(p.maxRetries), 'maxRetries');
  check(isNonNeg(p.initialDelayMs), 'initialDelayMs');
  check(typeof p.multiplier === 'number' && isFinite(p.multiplier) && p.multiplier >= 1, 'multiplier');
  check(isNonNeg(p.maxDelayMs), 'maxDelayMs');
  check(typeof p.jitter === 'number' && p.jitter >= 0 && p.jitter <= 1, 'jitter');
  check(typeof p.respectRetryAfter === 'boolean', 'respectRetryAfter');
  check(isNonNeg(p.maxRetryAfterMs), 'maxRetryAfterMs');
  return Object.freeze(p);
}

/** Merges a partial ReconnectPolicy over a base policy. `false` disables reconnects. */
export function normalizeReconnectPolicy(override, base) {
  const b = base || DEFAULT_RECONNECT_POLICY;
  if (override === false) return Object.freeze(merge(b, { enabled: false }));
  if (override !== undefined && override !== null && typeof override !== 'object') {
    throw localError(90010, 'reconnect must be an object or false');
  }
  const p = merge(b, override);
  check(typeof p.enabled === 'boolean', 'enabled');
  check(isNonNegInt(p.maxAttempts), 'maxAttempts');
  check(isNonNeg(p.initialDelayMs), 'initialDelayMs');
  check(typeof p.multiplier === 'number' && isFinite(p.multiplier) && p.multiplier >= 1, 'multiplier');
  check(isNonNeg(p.maxDelayMs), 'maxDelayMs');
  check(typeof p.jitter === 'number' && p.jitter >= 0 && p.jitter <= 1, 'jitter');
  return Object.freeze(p);
}
