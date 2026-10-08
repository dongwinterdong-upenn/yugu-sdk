// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// HMAC-SHA256 signature (CONTRACT 0.2, DESIGN 5.2) with Web Crypto, available in browsers and Node 18+.

import { localError } from './errors.js';
import { bytesToBase64, getCrypto, utf8 } from './util.js';

/**
 * The signed text: drop null, undefined and empty string values, sort keys in natural order,
 * join as key=value with &, no URL encoding.
 */
export function buildSignPayload(params) {
  const p = params || {};
  return Object.keys(p)
    .filter((k) => p[k] !== null && p[k] !== undefined && p[k] !== '')
    .sort()
    .map((k) => `${k}=${p[k]}`)
    .join('&');
}

function subtleOf(cryptoImpl) {
  const c = getCrypto(cryptoImpl);
  const subtle = c && c.subtle;
  if (!subtle || typeof subtle.importKey !== 'function') {
    throw localError(
      90010,
      'Web Crypto subtle 不可用。浏览器需运行在 https 或 localhost 安全上下文，Node 18 需传入 crypto 选项，例如 webcrypto',
    );
  }
  return subtle;
}

/** Imports the HMAC key once; the promise can be cached by the caller. */
export function importHmacKey(secret, cryptoImpl) {
  if (typeof secret !== 'string' || secret === '') {
    return Promise.reject(localError(90010, '签名需要非空的 secretKey'));
  }
  let subtle;
  try {
    subtle = subtleOf(cryptoImpl);
  } catch (e) {
    return Promise.reject(e);
  }
  return subtle.importKey('raw', utf8(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
}

/** Signs an already built payload with an imported key. */
export async function signPayloadWithKey(payload, key, cryptoImpl) {
  const subtle = subtleOf(cryptoImpl);
  const sig = await subtle.sign('HMAC', key, utf8(payload));
  return bytesToBase64(new Uint8Array(sig));
}

/** Base64(HMAC_SHA256(buildSignPayload(params), secret)). */
export async function signHmacSha256(params, secret, cryptoImpl) {
  const key = await importHmacKey(secret, cryptoImpl);
  return signPayloadWithKey(buildSignPayload(params), key, cryptoImpl);
}
