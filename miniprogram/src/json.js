// JSON bodies whose signed values equal the literal text that is sent (DESIGN 5.2, JSON-body calls).
import { localError } from './errors.js';

/** Plain decimal text of a finite number, never in exponent form. */
export function formatPlainNumber(n) {
  if (typeof n !== 'number' || !isFinite(n)) throw localError(90010, 'numbers must be finite');
  if (n === 0) return '0';
  const s = String(n);
  if (s.indexOf('e') < 0) return s;
  const m = /^(-?)(\d)(?:\.(\d+))?e([+-]\d+)$/.exec(s);
  if (!m) return s;
  const sign = m[1];
  const digits = m[2] + (m[3] || '');
  const point = 1 + parseInt(m[4], 10);
  if (point <= 0) return sign + '0.' + repeat('0', -point) + digits;
  if (point >= digits.length) return sign + digits + repeat('0', point - digits.length);
  return sign + digits.slice(0, point) + '.' + digits.slice(point);
}

function repeat(ch, n) {
  let s = '';
  for (let i = 0; i < n; i++) s += ch;
  return s;
}

/** Text of a top level scalar as it is signed: strings as is, numbers plain decimal, booleans true or false. */
export function scalarText(v) {
  if (typeof v === 'string') return v;
  if (typeof v === 'number') return formatPlainNumber(v);
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  return null;
}

function jsonValue(v) {
  if (typeof v === 'number') return formatPlainNumber(v);
  return JSON.stringify(v);
}

/**
 * Serializes a flat object to JSON text and collects the signed set: every top level non null
 * scalar. Undefined and null fields are not sent. Nested objects and arrays are sent but not signed.
 */
export function buildJsonBody(obj) {
  const parts = [];
  const signSet = {};
  const keys = Object.keys(obj);
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i];
    const v = obj[k];
    if (v === undefined || v === null) continue;
    parts.push(JSON.stringify(k) + ':' + jsonValue(v));
    const t = scalarText(v);
    if (t !== null) signSet[k] = t;
  }
  return { text: '{' + parts.join(',') + '}', signSet };
}
