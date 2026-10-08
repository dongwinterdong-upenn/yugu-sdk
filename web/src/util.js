// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// Small helpers shared by the SDK modules. Browser and Node 18+ compatible, no dependencies.

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder('utf-8');

/** UTF-8 encode a string. */
export function utf8(str) {
  return textEncoder.encode(String(str));
}

/** UTF-8 decode bytes. */
export function utf8Decode(bytes) {
  return textDecoder.decode(bytes);
}

const toStringTag = (x) => Object.prototype.toString.call(x);

/** True for ArrayBuffer and SharedArrayBuffer, also across realms (iframes, vm contexts). */
export function isArrayBuffer(x) {
  if (x instanceof ArrayBuffer) return true;
  const tag = toStringTag(x);
  return tag === '[object ArrayBuffer]' || tag === '[object SharedArrayBuffer]';
}

/** The global object in browsers, workers and Node, also where globalThis is missing. */
export function getGlobal() {
  if (typeof globalThis !== 'undefined') return globalThis;
  /* c8 ignore next 3 */
  if (typeof self !== 'undefined') return self;
  if (typeof window !== 'undefined') return window;
  return {};
}

/** True for Blob and File (anything with size plus arrayBuffer(), or an instance of Blob). */
export function isBlobLike(x) {
  if (x == null || typeof x !== 'object') return false;
  const g = getGlobal();
  if (typeof g.Blob === 'function' && x instanceof g.Blob) return true;
  return typeof x.arrayBuffer === 'function' && typeof x.size === 'number';
}

/**
 * Bytes of a Blob. Blob.arrayBuffer() exists from Chrome 76, Firefox 69 and Safari 14; older
 * browsers read through FileReader.
 */
export function readBlob(blob) {
  if (typeof blob.arrayBuffer === 'function') return blob.arrayBuffer();
  const g = getGlobal();
  if (typeof g.FileReader === 'function') {
    return new Promise((resolve, reject) => {
      const fr = new g.FileReader();
      fr.onload = () => resolve(fr.result);
      fr.onerror = () => reject(fr.error);
      fr.readAsArrayBuffer(blob);
    });
  }
  /* c8 ignore next */
  return new g.Response(blob).arrayBuffer();
}

/**
 * View ArrayBuffer or any ArrayBufferView as Uint8Array without copying.
 * Returns null for other inputs.
 */
export function toBytes(data) {
  if (data instanceof Uint8Array) return data;
  if (isArrayBuffer(data)) return new Uint8Array(data);
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  return null;
}

/** Copy of the bytes in a fresh ArrayBuffer of exactly that length. */
export function toExactArrayBuffer(bytes) {
  const buf = bytes.buffer;
  if (bytes.byteOffset === 0 && bytes.byteLength === buf.byteLength && (buf instanceof ArrayBuffer || toStringTag(buf) === '[object ArrayBuffer]')) {
    return buf;
  }
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

/** Concatenate byte chunks. */
export function concatBytes(chunks) {
  let total = 0;
  for (const c of chunks) total += c.length;
  const out = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) {
    out.set(c, off);
    off += c.length;
  }
  return out;
}

/** Index of needle in haystack (bytes), or -1. */
export function indexOfBytes(haystack, needle, from = 0) {
  const n = needle.length;
  if (n === 0) return from;
  const first = needle[0];
  const last = haystack.length - n;
  outer: for (let i = from; i <= last; i++) {
    if (haystack[i] !== first) continue;
    for (let j = 1; j < n; j++) if (haystack[i + j] !== needle[j]) continue outer;
    return i;
  }
  return -1;
}

/** Standard base64 (with padding) of ArrayBuffer or ArrayBufferView. */
export function bytesToBase64(input) {
  const bytes = toBytes(input);
  if (!bytes) throw new TypeError('bytesToBase64 expects an ArrayBuffer or ArrayBufferView');
  if (typeof btoa === 'function') {
    let bin = '';
    const CHUNK = 0x8000;
    for (let i = 0; i < bytes.length; i += CHUNK) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
    }
    return btoa(bin);
  }
  /* c8 ignore next 2 */
  // eslint-disable-next-line no-undef
  return Buffer.from(bytes).toString('base64');
}

/** Lowercase hex of bytes. */
export function toHex(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
  return s;
}

/** The Web Crypto object to use: injected first, then globalThis.crypto. */
export function getCrypto(injected) {
  if (injected) return injected;
  const g = getGlobal();
  return g.crypto || null;
}

/** n random bytes from crypto.getRandomValues, Math.random only when no crypto exists. */
export function randomBytes(n, cryptoImpl) {
  const out = new Uint8Array(n);
  const c = getCrypto(cryptoImpl);
  if (c && typeof c.getRandomValues === 'function') {
    c.getRandomValues(out);
    return out;
  }
  for (let i = 0; i < n; i++) out[i] = Math.floor(Math.random() * 256);
  return out;
}

/** Truncate a string to max UTF-16 units. */
export function truncate(s, max) {
  if (s == null) return s;
  const str = String(s);
  return str.length > max ? str.slice(0, max) : str;
}

/** Plain object check. */
export function isPlainObject(x) {
  if (x === null || typeof x !== 'object') return false;
  const p = Object.getPrototypeOf(x);
  return p === Object.prototype || p === null;
}

/** Remove trailing slashes. */
export function stripTrailingSlash(url) {
  return String(url).replace(/\/+$/, '');
}

/** Detect a browser or web worker (where fetch cannot set User-Agent). */
export function isBrowserLike() {
  const g = getGlobal();
  if (typeof g.window !== 'undefined' && typeof g.document !== 'undefined') return true;
  if (typeof g.importScripts === 'function') return true;
  return false;
}

/**
 * Promise that resolves after ms, or rejects with the value returned by onAbort when any of the
 * given AbortSignal-like objects fires.
 */
export function abortableDelay(ms, signals, onAbort) {
  return new Promise((resolve, reject) => {
    const list = (signals || []).filter(Boolean);
    for (const s of list) {
      if (s.aborted) {
        reject(onAbort(s));
        return;
      }
    }
    let timer = null;
    const cleanups = [];
    const done = () => {
      if (timer !== null) clearTimeout(timer);
      for (const c of cleanups) c();
    };
    for (const s of list) {
      const h = () => {
        done();
        reject(onAbort(s));
      };
      s.addEventListener('abort', h, { once: true });
      cleanups.push(() => s.removeEventListener('abort', h));
    }
    timer = setTimeout(() => {
      timer = null;
      done();
      resolve();
    }, Math.max(0, ms));
  });
}

/** Number check that also rejects NaN and Infinity. */
export function isFiniteNumber(x) {
  return typeof x === 'number' && Number.isFinite(x);
}
