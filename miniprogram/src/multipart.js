// multipart/form-data assembled by hand as one ArrayBuffer (DESIGN 5.1 and 5.2).
//
// wx.uploadFile cannot set a part Content-Type, so in 1.x the `config` part reached the platform as
// text/plain and native evaluate failed with HTTP 415. The body built here is sent with
// wx.request({method: 'POST', header: {'content-type': 'multipart/form-data; boundary=...'}, data}).
import { utf8Encode } from './codec.js';
import { localError } from './errors.js';
import { concatBytes, exactArrayBuffer, toUint8, randomBytes } from './util.js';

const CRLF = new Uint8Array([13, 10]);
const BOUNDARY_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';

export function generateBoundary() {
  const b = randomBytes(24);
  let s = '----YuguFormBoundary';
  for (let i = 0; i < b.length; i++) s += BOUNDARY_ALPHABET[b[i] % BOUNDARY_ALPHABET.length];
  return s;
}

function checkToken(v, what) {
  if (typeof v !== 'string' || !v || /["\r\n]/.test(v)) {
    throw localError(90010, 'multipart ' + what + ' must be a non empty string without quotes or line breaks');
  }
}

/**
 * parts: [{name, data: string | ArrayBuffer | ArrayBufferView, filename?, contentType?}]
 * A part without filename is a form parameter on the platform; a part with filename is a file.
 * Returns {body: ArrayBuffer, bytes: Uint8Array, boundary, contentType}.
 */
export function buildMultipart(parts, boundary) {
  const b = boundary || generateBoundary();
  checkToken(b, 'boundary');
  const chunks = [];
  for (let i = 0; i < parts.length; i++) {
    const p = parts[i];
    checkToken(p.name, 'part name');
    let head = '--' + b + '\r\nContent-Disposition: form-data; name="' + p.name + '"';
    if (p.filename !== undefined && p.filename !== null) {
      checkToken(p.filename, 'filename');
      head += '; filename="' + p.filename + '"';
    }
    head += '\r\n';
    if (p.contentType) head += 'Content-Type: ' + p.contentType + '\r\n';
    head += '\r\n';
    const data = typeof p.data === 'string' ? utf8Encode(p.data) : toUint8(p.data);
    if (!data) throw localError(90010, 'multipart part ' + p.name + ' has no data');
    chunks.push(utf8Encode(head), data, CRLF);
  }
  chunks.push(utf8Encode('--' + b + '--\r\n'));
  const bytes = concatBytes(chunks);
  return {
    body: exactArrayBuffer(bytes),
    bytes,
    boundary: b,
    contentType: 'multipart/form-data; boundary=' + b,
  };
}
