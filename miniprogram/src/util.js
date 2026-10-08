// Small helpers shared by every module. Everything here must run on the mini program runtime,
// which is ES2017 at best: no TextEncoder, no AbortController, no globalThis.

const toStringTag = Object.prototype.toString;

export function isNil(v) {
  return v === null || v === undefined;
}

export function hasOwn(obj, key) {
  return Object.prototype.hasOwnProperty.call(obj, key);
}

export function isPlainObject(v) {
  return !!v && typeof v === 'object' && !Array.isArray(v) && !isBytesLike(v);
}

// Realm independent check. wx APIs and test sandboxes may hand over buffers from another realm,
// where `instanceof ArrayBuffer` is false.
export function isArrayBuffer(x) {
  return !!x && toStringTag.call(x) === '[object ArrayBuffer]';
}

export function isBytesLike(x) {
  return isArrayBuffer(x) || (!!x && typeof x === 'object' && ArrayBuffer.isView(x));
}

/**
 * ArrayBuffer or any ArrayBufferView to a plain Uint8Array view (no copy), null for anything else.
 * Subclasses (Node's Buffer, whose slice() does not copy) are re-wrapped as plain Uint8Array.
 */
export function toUint8(x) {
  if (x instanceof Uint8Array && x.constructor === Uint8Array) return x;
  if (isArrayBuffer(x)) return new Uint8Array(x);
  if (x && typeof x === 'object' && ArrayBuffer.isView(x)) return new Uint8Array(x.buffer, x.byteOffset, x.byteLength);
  return null;
}

/** A new plain Uint8Array with a copy of the bytes. */
export function copyBytes(u8) {
  const out = new Uint8Array(u8.length);
  out.set(u8);
  return out;
}

/** An ArrayBuffer holding exactly the bytes of the view, copying only when the view is partial. */
export function exactArrayBuffer(u8) {
  if (u8.byteOffset === 0 && u8.byteLength === u8.buffer.byteLength) return u8.buffer;
  return copyBytes(u8).buffer;
}

export function concatBytes(chunks) {
  let total = 0;
  for (let i = 0; i < chunks.length; i++) total += chunks[i].length;
  const out = new Uint8Array(total);
  let pos = 0;
  for (let i = 0; i < chunks.length; i++) {
    out.set(chunks[i], pos);
    pos += chunks[i].length;
  }
  return out;
}

export function toHex(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
  return s;
}

export function randomBytes(n, random) {
  const rnd = typeof random === 'function' ? random : Math.random;
  const b = new Uint8Array(n);
  for (let i = 0; i < n; i++) b[i] = Math.floor(rnd() * 256) & 0xff;
  return b;
}

export function randomHex(nBytes) {
  return toHex(randomBytes(nBytes));
}

/** Case-insensitive header lookup. wx returns header names in server casing, sometimes lower case. */
export function headerValue(headers, name) {
  if (!headers || typeof headers !== 'object') return undefined;
  const wanted = name.toLowerCase();
  const keys = Object.keys(headers);
  for (let i = 0; i < keys.length; i++) {
    if (keys[i].toLowerCase() === wanted) {
      const v = headers[keys[i]];
      return Array.isArray(v) ? v.join(', ') : v;
    }
  }
  return undefined;
}

/** Query string from a flat object, dropping null, undefined and empty strings. */
export function encodeQuery(params) {
  const parts = [];
  const keys = Object.keys(params);
  for (let i = 0; i < keys.length; i++) {
    const v = params[keys[i]];
    if (isNil(v) || v === '') continue;
    parts.push(encodeURIComponent(keys[i]) + '=' + encodeURIComponent(String(v)));
  }
  return parts.join('&');
}

export function trimTrailingSlash(url) {
  return String(url).replace(/\/+$/, '');
}

export function noop() {}

export function microtask(fn) {
  Promise.resolve().then(fn);
}

export function toInt(v) {
  if (typeof v === 'number' && isFinite(v)) return Math.trunc(v);
  if (typeof v === 'string' && /^\s*-?\d+\s*$/.test(v)) return parseInt(v, 10);
  return 0;
}

export function toNumberOrNull(v) {
  if (typeof v === 'number') return isFinite(v) ? v : null;
  if (typeof v === 'string' && v.trim() !== '' && isFinite(Number(v))) return Number(v);
  return null;
}

export function truncate(s, max) {
  if (typeof s !== 'string') return s;
  return s.length > max ? s.slice(0, max) : s;
}
