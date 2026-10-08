// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// HTTP header helpers without dependencies.

/**
 * Parse a Retry-After header value into milliseconds.
 * Accepts delta seconds (integer or decimal) and HTTP dates. Returns null when absent or invalid.
 */
export function parseRetryAfter(value, nowMs) {
  if (value == null) return null;
  const s = String(value).trim();
  if (s === '') return null;
  if (/^\d+(\.\d+)?$/.test(s)) return Math.round(Number(s) * 1000);
  const t = Date.parse(s);
  if (Number.isNaN(t)) return null;
  const now = typeof nowMs === 'number' ? nowMs : Date.now();
  return Math.max(0, t - now);
}

/** Read a header from a Headers-like object or a plain map, case-insensitively. */
export function getHeader(headers, name) {
  if (!headers) return null;
  if (typeof headers.get === 'function') {
    const v = headers.get(name);
    return v == null ? null : String(v);
  }
  const lower = name.toLowerCase();
  for (const k of Object.keys(headers)) {
    if (k.toLowerCase() === lower) {
      const v = headers[k];
      return v == null ? null : String(Array.isArray(v) ? v[0] : v);
    }
  }
  return null;
}
