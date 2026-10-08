// Pure JS UTF-8, Base64, SHA-256 and HMAC-SHA256. The mini program runtime has neither
// TextEncoder nor crypto.subtle, so the SDK carries its own implementations.
import { toUint8 } from './util.js';

/** UTF-8 encode a string. Lone surrogates become U+FFFD, the same as TextEncoder. */
export function utf8Encode(str) {
  const s = String(str);
  const out = new Uint8Array(s.length * 3);
  let p = 0;
  for (let i = 0; i < s.length; i++) {
    let c = s.charCodeAt(i);
    if (c < 0x80) {
      out[p++] = c;
      continue;
    }
    if (c < 0x800) {
      out[p++] = 0xc0 | (c >> 6);
      out[p++] = 0x80 | (c & 0x3f);
      continue;
    }
    if (c >= 0xd800 && c <= 0xdbff) {
      const d = i + 1 < s.length ? s.charCodeAt(i + 1) : 0;
      if (d >= 0xdc00 && d <= 0xdfff) {
        const cp = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00);
        out[p++] = 0xf0 | (cp >> 18);
        out[p++] = 0x80 | ((cp >> 12) & 0x3f);
        out[p++] = 0x80 | ((cp >> 6) & 0x3f);
        out[p++] = 0x80 | (cp & 0x3f);
        i++;
        continue;
      }
      c = 0xfffd;
    } else if (c >= 0xdc00 && c <= 0xdfff) {
      c = 0xfffd;
    }
    out[p++] = 0xe0 | (c >> 12);
    out[p++] = 0x80 | ((c >> 6) & 0x3f);
    out[p++] = 0x80 | (c & 0x3f);
  }
  return out.slice(0, p);
}

/** UTF-8 decode with U+FFFD replacement for malformed input (WHATWG decoder rules). */
export function utf8Decode(input) {
  const b = toBytes(input);
  const n = b.length;
  let out = '';
  let units = [];
  let i = 0;
  while (i < n) {
    const c = b[i];
    if (c < 0x80) {
      units.push(c);
      i++;
    } else {
      let need = 0;
      let cp = 0;
      let lower = 0x80;
      let upper = 0xbf;
      if (c >= 0xc2 && c <= 0xdf) {
        need = 1;
        cp = c & 0x1f;
      } else if (c >= 0xe0 && c <= 0xef) {
        need = 2;
        cp = c & 0x0f;
        if (c === 0xe0) lower = 0xa0;
        if (c === 0xed) upper = 0x9f;
      } else if (c >= 0xf0 && c <= 0xf4) {
        need = 3;
        cp = c & 0x07;
        if (c === 0xf0) lower = 0x90;
        if (c === 0xf4) upper = 0x8f;
      } else {
        units.push(0xfffd);
        i++;
        continue;
      }
      let j = 1;
      let ok = true;
      for (; j <= need; j++) {
        const d = i + j < n ? b[i + j] : -1;
        if (d < lower || d > upper) {
          ok = false;
          break;
        }
        lower = 0x80;
        upper = 0xbf;
        cp = (cp << 6) | (d & 0x3f);
      }
      if (!ok) {
        units.push(0xfffd);
        i += j;
        continue;
      }
      i += need + 1;
      if (cp >= 0x10000) {
        cp -= 0x10000;
        units.push(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
      } else {
        units.push(cp);
      }
    }
    if (units.length >= 8192) {
      out += String.fromCharCode.apply(null, units);
      units = [];
    }
  }
  if (units.length) out += String.fromCharCode.apply(null, units);
  return out;
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/** Standard Base64 with padding. */
export function base64Encode(input) {
  const b = toBytes(input);
  let out = '';
  let i = 0;
  for (; i + 2 < b.length; i += 3) {
    const v = (b[i] << 16) | (b[i + 1] << 8) | b[i + 2];
    out += B64[(v >>> 18) & 63] + B64[(v >>> 12) & 63] + B64[(v >>> 6) & 63] + B64[v & 63];
  }
  const rest = b.length - i;
  if (rest === 1) {
    const v = b[i] << 16;
    out += B64[(v >>> 18) & 63] + B64[(v >>> 12) & 63] + '==';
  } else if (rest === 2) {
    const v = (b[i] << 16) | (b[i + 1] << 8);
    out += B64[(v >>> 18) & 63] + B64[(v >>> 12) & 63] + B64[(v >>> 6) & 63] + '=';
  }
  return out;
}

const K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

/** SHA-256 digest (32 bytes) of a string (UTF-8) or bytes. */
export function sha256(input) {
  const msg = toBytes(input);
  const n = msg.length;
  const total = Math.ceil((n + 9) / 64) * 64;
  const buf = new Uint8Array(total);
  buf.set(msg);
  buf[n] = 0x80;
  const dv = new DataView(buf.buffer);
  dv.setUint32(total - 8, Math.floor(n / 0x20000000), false);
  dv.setUint32(total - 4, (n * 8) >>> 0, false);

  let h0 = 0x6a09e667;
  let h1 = 0xbb67ae85 | 0;
  let h2 = 0x3c6ef372;
  let h3 = 0xa54ff53a | 0;
  let h4 = 0x510e527f;
  let h5 = 0x9b05688c | 0;
  let h6 = 0x1f83d9ab;
  let h7 = 0x5be0cd19;
  const w = new Int32Array(64);

  for (let off = 0; off < total; off += 64) {
    for (let t = 0; t < 16; t++) w[t] = dv.getInt32(off + t * 4, false);
    for (let t = 16; t < 64; t++) {
      const x = w[t - 15];
      const y = w[t - 2];
      const s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
      const s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
      w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
    }
    let a = h0;
    let b = h1;
    let c = h2;
    let d = h3;
    let e = h4;
    let f = h5;
    let g = h6;
    let h = h7;
    for (let t = 0; t < 64; t++) {
      const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      const ch = (e & f) ^ (~e & g);
      const t1 = (h + S1 + ch + K[t] + w[t]) | 0;
      const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) | 0;
      h = g;
      g = f;
      f = e;
      e = (d + t1) | 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) | 0;
    }
    h0 = (h0 + a) | 0;
    h1 = (h1 + b) | 0;
    h2 = (h2 + c) | 0;
    h3 = (h3 + d) | 0;
    h4 = (h4 + e) | 0;
    h5 = (h5 + f) | 0;
    h6 = (h6 + g) | 0;
    h7 = (h7 + h) | 0;
  }

  const out = new Uint8Array(32);
  const odv = new DataView(out.buffer);
  odv.setInt32(0, h0, false);
  odv.setInt32(4, h1, false);
  odv.setInt32(8, h2, false);
  odv.setInt32(12, h3, false);
  odv.setInt32(16, h4, false);
  odv.setInt32(20, h5, false);
  odv.setInt32(24, h6, false);
  odv.setInt32(28, h7, false);
  return out;
}

/** HMAC-SHA256 (RFC 2104) of message under key. Strings are UTF-8 encoded. */
export function hmacSha256(key, message) {
  let k = toBytes(key);
  if (k.length > 64) k = sha256(k);
  const msg = toBytes(message);
  const inner = new Uint8Array(64 + msg.length);
  const outer = new Uint8Array(64 + 32);
  for (let i = 0; i < 64; i++) {
    const kb = i < k.length ? k[i] : 0;
    inner[i] = kb ^ 0x36;
    outer[i] = kb ^ 0x5c;
  }
  inner.set(msg, 64);
  outer.set(sha256(inner), 64);
  return sha256(outer);
}

function toBytes(input) {
  if (typeof input === 'string') return utf8Encode(input);
  const u8 = toUint8(input);
  if (!u8) throw new TypeError('expected a string, ArrayBuffer or ArrayBufferView');
  return u8;
}
