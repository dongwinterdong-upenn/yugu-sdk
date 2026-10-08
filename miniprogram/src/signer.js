// Request signing (CONTRACT 0.2, DESIGN 5.2) and authentication headers or query parameters.
import { hmacSha256, base64Encode, utf8Encode } from './codec.js';
import { localError } from './errors.js';
import { isNil, randomHex } from './util.js';

function text(v) {
  return typeof v === 'string' ? v : String(v);
}

/**
 * Signed payload: keys in UTF-16 code unit order (Java TreeMap order), null and empty values dropped,
 * `k=v` joined with `&`, no URL encoding.
 */
export function buildSignPayload(params) {
  const p = params || {};
  const keys = [];
  const all = Object.keys(p);
  for (let i = 0; i < all.length; i++) {
    const v = p[all[i]];
    if (isNil(v)) continue;
    if (text(v) === '') continue;
    keys.push(all[i]);
  }
  keys.sort();
  const parts = [];
  for (let i = 0; i < keys.length; i++) parts.push(keys[i] + '=' + text(p[keys[i]]));
  return parts.join('&');
}

/** Base64(HMAC_SHA256(payload, secretKey)) over UTF-8 bytes. */
export function signParams(params, secretKey) {
  return base64Encode(hmacSha256(utf8Encode(String(secretKey)), utf8Encode(buildSignPayload(params))));
}

/**
 * Resolves the auth option. Accepts `{auth: {token}}`, `{auth: {appKey, secretKey}}` and, for 1.x
 * code, the same fields at the top level of the client options.
 */
export function createAuth(options) {
  const a = options && options.auth && typeof options.auth === 'object' ? options.auth : options || {};
  if (typeof a.token === 'string' && a.token) {
    return Object.freeze({ mode: 'token', token: a.token, secrets: [a.token] });
  }
  if (typeof a.appKey === 'string' && a.appKey && typeof a.secretKey === 'string' && a.secretKey) {
    return Object.freeze({ mode: 'sign', appKey: a.appKey, secretKey: a.secretKey, secrets: [a.secretKey] });
  }
  throw localError(90010, 'auth is required: {token} or {appKey, secretKey}');
}

export function nowSeconds(nowMs) {
  return String(Math.floor((typeof nowMs === 'number' ? nowMs : Date.now()) / 1000));
}

/** REST headers. Timestamp and nonce are fresh for every attempt, the signature covers signSet only. */
export function restAuthHeaders(auth, signSet, nowMs) {
  if (auth.mode === 'token') return { Authorization: 'Bearer ' + auth.token };
  return {
    'X-App-Key': auth.appKey,
    'X-Timestamp': nowSeconds(nowMs),
    'X-Nonce': randomHex(8),
    'X-Signature': signParams(signSet || {}, auth.secretKey),
  };
}

/** WS handshake query. The signature covers every query parameter except itself. */
export function wsAuthQuery(auth, query, nowMs) {
  const q = {};
  const src = query || {};
  const keys = Object.keys(src);
  for (let i = 0; i < keys.length; i++) {
    if (!isNil(src[keys[i]]) && src[keys[i]] !== '') q[keys[i]] = text(src[keys[i]]);
  }
  if (auth.mode === 'token') {
    q.token = auth.token;
    return q;
  }
  q.appKey = auth.appKey;
  q.timestamp = nowSeconds(nowMs);
  q.nonce = randomHex(8);
  q.signature = signParams(q, auth.secretKey);
  return q;
}
