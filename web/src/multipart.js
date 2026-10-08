// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// multipart/form-data body built by hand as bytes (DESIGN 5.2). No FormData: a FormData Blob part
// always carries a filename, which turns the config part into a file part and breaks the signature.

import { concatBytes, indexOfBytes, randomBytes, toBytes, toHex, utf8 } from './util.js';

const CRLF = utf8('\r\n');

/** Content-Type of the native config part. */
export const CONFIG_PART_CONTENT_TYPE = 'application/json; charset=utf-8';

/** Escape a field name or filename the way browsers do for form-data. */
export function escapeFormName(name) {
  return String(name).replace(/"/g, '%22').replace(/\r/g, '%0D').replace(/\n/g, '%0A');
}

/** Random boundary: ----YuguFormBoundary plus 24 hex characters. */
export function makeBoundary(cryptoImpl) {
  return `----YuguFormBoundary${toHex(randomBytes(12, cryptoImpl))}`;
}

/**
 * Build a multipart body.
 * parts: [{name, value}] for text fields, [{name, data, filename, contentType}] for files.
 * A part gets a filename only when filename is given, and a Content-Type line only when
 * contentType is given.
 * Returns {body: Uint8Array, contentType, boundary}.
 */
export function buildMultipart(parts, options) {
  const opts = options || {};
  const encoded = parts.map((p) => {
    const bytes = p.data !== undefined ? toBytes(p.data) : utf8(p.value == null ? '' : String(p.value));
    if (!bytes) throw new TypeError(`multipart part ${p.name} has unsupported data`);
    return { name: p.name, filename: p.filename, contentType: p.contentType, bytes };
  });
  let boundary = opts.boundary || makeBoundary(opts.crypto);
  if (!opts.boundary) {
    for (let tries = 0; tries < 8; tries++) {
      const marker = utf8(`--${boundary}`);
      if (!encoded.some((p) => indexOfBytes(p.bytes, marker) >= 0)) break;
      boundary = makeBoundary(opts.crypto);
    }
  }
  const chunks = [];
  for (const p of encoded) {
    let head = `--${boundary}\r\nContent-Disposition: form-data; name="${escapeFormName(p.name)}"`;
    if (p.filename != null) head += `; filename="${escapeFormName(p.filename)}"`;
    head += '\r\n';
    if (p.contentType) head += `Content-Type: ${p.contentType}\r\n`;
    head += '\r\n';
    chunks.push(utf8(head), p.bytes, CRLF);
  }
  chunks.push(utf8(`--${boundary}--\r\n`));
  return { body: concatBytes(chunks), contentType: `multipart/form-data; boundary=${boundary}`, boundary };
}
