/**
 * Parses a Retry-After header value: delta seconds ("30") or an HTTP date.
 * Returns milliseconds, or undefined when absent or malformed.
 */
export function parseRetryAfter(value, now) {
  if (value === null || value === undefined) return undefined;
  const s = String(value).trim();
  if (!s) return undefined;
  if (/^\d+$/.test(s)) return parseInt(s, 10) * 1000;
  const t = Date.parse(s);
  if (isNaN(t)) return undefined;
  const ref = typeof now === 'number' ? now : Date.now();
  return Math.max(0, t - ref);
}
