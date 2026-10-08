// Error model (DESIGN 2.4) and the single retry decision isRetryable (DESIGN 2.3).
import { ERROR_TABLE, WARNING_TABLE, LOCAL_TABLE, HTTP_FALLBACK, RETRYABLE_HTTP } from './error-table.js';
import { toInt, truncate, headerValue } from './util.js';
import { parseRetryAfter } from './retry-after.js';

export const ErrorCategory = Object.freeze({
  NETWORK: 'NETWORK',
  TIMEOUT: 'TIMEOUT',
  AUTH: 'AUTH',
  PERMISSION: 'PERMISSION',
  INVALID_PARAM: 'INVALID_PARAM',
  NOT_FOUND: 'NOT_FOUND',
  CONFLICT: 'CONFLICT',
  RATE_LIMIT: 'RATE_LIMIT',
  QUOTA: 'QUOTA',
  SERVER: 'SERVER',
  UPSTREAM: 'UPSTREAM',
  AUDIO: 'AUDIO',
  STATE: 'STATE',
  CANCELLED: 'CANCELLED',
  PROTOCOL: 'PROTOCOL',
  UNKNOWN: 'UNKNOWN',
});

/** Audio quality warning codes found in `result.warning` and top level `warnings`. */
export const WarningCode = Object.freeze({
  NO_VALID_AUDIO: 1001,
  VOLUME_TOO_LOW: 1002,
  VOLUME_TOO_HIGH: 1003,
  AUDIO_NOISY: 1004,
  AUDIO_INCOMPLETE: 1005,
  SCORER_DEGRADED: 1009,
});

const RAW_BODY_LIMIT = 4096;
const LOCAL_RETRYABLE = [90001, 90002, 90007];

// Code namespaces (DESIGN 2.4): 1004 and 1005 are both platform error codes and audio warning codes,
// so a code is only meaningful together with the table it came from.
const NS = Object.freeze({ ERRORS: 'errors', WARNINGS: 'warnings', LOCAL: 'local', UNKNOWN: 'unknown' });

function inferNamespace(code) {
  if (!code) return NS.UNKNOWN;
  if (LOCAL_TABLE[code]) return NS.LOCAL;
  if (ERROR_TABLE[code]) return NS.ERRORS;
  if (WARNING_TABLE[code]) return NS.WARNINGS;
  return NS.UNKNOWN;
}

function tableOf(ns) {
  return ns === NS.LOCAL ? LOCAL_TABLE : ns === NS.ERRORS ? ERROR_TABLE : ns === NS.WARNINGS ? WARNING_TABLE : null;
}

/**
 * The retry rules of DESIGN 2.3, first match wins:
 * 1. local codes: 90001, 90002, 90007 retryable, every other local code not;
 * 2. a known platform or warning code: the `retryable` flag of spec/errors.json;
 * 3. no code or an unknown code: HTTP 408, 425, 429, 500, 502, 503, 504.
 */
function retryableFor(code, httpStatus, namespace) {
  const ns = namespace || inferNamespace(code);
  const table = tableOf(ns);
  const entry = code && table ? table[code] : null;
  if (entry && ns === NS.LOCAL) return LOCAL_RETRYABLE.indexOf(code) >= 0;
  if (entry) return entry.retryable;
  return RETRYABLE_HTTP.indexOf(httpStatus) >= 0;
}

/**
 * Whether an error is worth retrying. Used by the REST retry loop, by WebSocket reconnect decisions
 * and exposed to callers.
 */
export function isRetryable(error) {
  if (!error || typeof error !== 'object') return false;
  const code = toInt(error.code);
  const status = toInt(error.httpStatus);
  if (!code && !status) return false;
  return retryableFor(code, status, typeof error.codeNamespace === 'string' ? error.codeNamespace : undefined);
}

export class YuguError extends Error {
  constructor(message, init) {
    super(message);
    const o = init || {};
    // Keeps instanceof working when an app transpiles the bundle to ES5.
    if (Object.setPrototypeOf && new.target) Object.setPrototypeOf(this, new.target.prototype);
    this.name = 'YuguError';
    this.category = o.category || ErrorCategory.UNKNOWN;
    this.code = toInt(o.code);
    this.httpStatus = toInt(o.httpStatus);
    // errors, warnings, local or unknown: the table the code belongs to.
    Object.defineProperty(this, 'codeNamespace', {
      value: o.namespace || inferNamespace(this.code),
      enumerable: false,
      writable: false,
    });
    this.retryable = retryableFor(this.code, this.httpStatus, this.codeNamespace);
    this.idempotencyKey = o.idempotencyKey || null;
    this.recordId = o.recordId || null;
    this.attempts = toInt(o.attempts);
    this.rawBody = typeof o.rawBody === 'string' ? truncate(o.rawBody, RAW_BODY_LIMIT) : null;
    this.op = o.op || null;
    if (o.retryAfterMs !== undefined) this.retryAfterMs = o.retryAfterMs;
    if (o.warnings !== undefined) this.warnings = o.warnings;
    if (o.cause !== undefined) this.cause = o.cause;
  }

  toJSON() {
    return {
      name: this.name,
      message: this.message,
      category: this.category,
      code: this.code,
      httpStatus: this.httpStatus,
      retryable: this.retryable,
      idempotencyKey: this.idempotencyKey,
      recordId: this.recordId,
      attempts: this.attempts,
      op: this.op,
    };
  }
}

export class NetworkException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.NETWORK));
    this.name = 'NetworkException';
  }
}

export class RequestTimeoutException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.TIMEOUT));
    this.name = 'RequestTimeoutException';
  }
}

export class AuthException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.AUTH));
    this.name = 'AuthException';
  }
}

export class PermissionException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.PERMISSION));
    this.name = 'PermissionException';
  }
}

export class InvalidParameterException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.INVALID_PARAM));
    this.name = 'InvalidParameterException';
  }
}

export class NotFoundException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.NOT_FOUND));
    this.name = 'NotFoundException';
  }
}

export class ConflictException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.CONFLICT));
    this.name = 'ConflictException';
  }
}

export class RateLimitException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.RATE_LIMIT));
    this.name = 'RateLimitException';
  }
}

export class QuotaExceededException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.QUOTA));
    this.name = 'QuotaExceededException';
  }
}

/** SERVER and UPSTREAM categories. */
export class ServerException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.SERVER));
    this.name = 'ServerException';
  }
}

export class AudioQualityException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.AUDIO));
    this.name = 'AudioQualityException';
  }
}

export class IllegalSessionStateException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.STATE));
    this.name = 'IllegalSessionStateException';
  }
}

export class RequestCancelledException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.CANCELLED));
    this.name = 'RequestCancelledException';
  }
}

export class ProtocolViolationException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.PROTOCOL));
    this.name = 'ProtocolViolationException';
  }
}

function withCategory(init, fallback) {
  const o = {};
  const src = init || {};
  const keys = Object.keys(src);
  for (let i = 0; i < keys.length; i++) o[keys[i]] = src[keys[i]];
  if (!o.category) o.category = fallback;
  return o;
}

const CLASS_BY_CATEGORY = {
  NETWORK: NetworkException,
  TIMEOUT: RequestTimeoutException,
  AUTH: AuthException,
  PERMISSION: PermissionException,
  INVALID_PARAM: InvalidParameterException,
  NOT_FOUND: NotFoundException,
  CONFLICT: ConflictException,
  RATE_LIMIT: RateLimitException,
  QUOTA: QuotaExceededException,
  SERVER: ServerException,
  UPSTREAM: ServerException,
  AUDIO: AudioQualityException,
  STATE: IllegalSessionStateException,
  CANCELLED: RequestCancelledException,
  PROTOCOL: ProtocolViolationException,
};

/** Builds the typed error class that belongs to a category. UNKNOWN gives the base YuguError. */
export function createError(category, message, init) {
  const C = CLASS_BY_CATEGORY[category] || YuguError;
  const o = withCategory(init, category || ErrorCategory.UNKNOWN);
  o.category = category || ErrorCategory.UNKNOWN;
  return new C(message, o);
}

/**
 * Typed error for a code of spec/errors.json: local codes (9xxxx) from the local table, other codes
 * from the platform error table, so 1004 and 1005 mean USER_DISABLED and USER_LOCKED here. Codes that
 * exist only as warnings (1001 to 1003, 1009) still build their AudioQualityException; use
 * fromWarningCode for result warnings.
 */
export function fromCode(code, init) {
  const o = init || {};
  const c = toInt(code);
  let ns = o.namespace;
  if (!ns) {
    if (LOCAL_TABLE[c]) ns = NS.LOCAL;
    else if (ERROR_TABLE[c]) ns = NS.ERRORS;
    else if (WARNING_TABLE[c]) ns = NS.WARNINGS;
    else ns = NS.UNKNOWN;
  }
  const table = tableOf(ns);
  const entry = table ? table[c] : null;
  const data = withCategory(o, null);
  data.code = c;
  data.namespace = entry ? ns : NS.UNKNOWN;
  if (!entry) {
    const category = ns === NS.WARNINGS ? ErrorCategory.AUDIO : o.httpStatus ? httpCategory(o.httpStatus) : ErrorCategory.UNKNOWN;
    return createError(category, o.message || 'error code ' + c, data);
  }
  return createError(entry.category, o.message || entry.message, data);
}

/** Typed error for an audio quality warning code (1001 to 1005, 1009), from the warning table. */
export function fromWarningCode(code, init) {
  const o = withCategory(init, null);
  o.namespace = NS.WARNINGS;
  return fromCode(code, o);
}

/** Local SDK error by code (9xxxx), message defaults to the table text. */
export function localError(code, message, init) {
  const o = withCategory(init, null);
  if (message) o.message = message;
  o.namespace = NS.LOCAL;
  return fromCode(code, o);
}

/**
 * Category of an HTTP status without a known body code: httpFallback of spec/errors.json, then by
 * class: other 4xx INVALID_PARAM, other 5xx SERVER, anything else UNKNOWN (DESIGN 2.4).
 */
function httpCategory(status) {
  if (HTTP_FALLBACK[status]) return HTTP_FALLBACK[status];
  if (status >= 400 && status < 500) return ErrorCategory.INVALID_PARAM;
  if (status >= 500 && status < 600) return ErrorCategory.SERVER;
  return ErrorCategory.UNKNOWN;
}

function detailText(detail) {
  if (typeof detail === 'string') return detail;
  if (Array.isArray(detail)) {
    const msgs = [];
    for (let i = 0; i < detail.length; i++) {
      const d = detail[i];
      if (d && typeof d === 'object' && d.msg) msgs.push(String(d.msg));
      else if (typeof d === 'string') msgs.push(d);
    }
    return msgs.join('; ') || null;
  }
  return null;
}

function parseJsonSafe(text) {
  if (typeof text !== 'string' || !text) return null;
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

/**
 * Maps a non 2xx HTTP response. Order: body `code` through the table, then the compat engine
 * detail pattern `[2001] ...`, then the HTTP status fallback.
 * Body shapes: platform `{code, message, timestamp}`, FastAPI `{detail: "..."}` or `{detail: [...]}`.
 */
export function fromHttpResponse(status, bodyText, init) {
  const o = init || {};
  const raw = typeof bodyText === 'string' ? bodyText : '';
  const json = parseJsonSafe(raw);
  const base = withCategory(o, null);
  delete base.headers;
  base.httpStatus = status;
  base.rawBody = raw;
  const retryAfter = parseRetryAfter(headerValue(o.headers, 'Retry-After'));
  if (retryAfter !== undefined) base.retryAfterMs = retryAfter;
  const obj = json && typeof json === 'object' && !Array.isArray(json) ? json : null;
  if (obj && obj.recordId && !base.recordId) base.recordId = String(obj.recordId);
  const detail = obj ? detailText(obj.detail) : null;
  const message = (obj && (obj.message || obj.msg)) || detail || 'HTTP ' + status;
  const code = obj ? toInt(obj.code) : 0;
  if (code) {
    base.message = String(message);
    if (ERROR_TABLE[code]) {
      base.namespace = NS.ERRORS;
      return fromCode(code, base);
    }
    base.code = code;
    base.namespace = NS.UNKNOWN;
    return createError(httpCategory(status), String(message), base);
  }
  if (obj && typeof obj.detail === 'string') {
    const m = /^\s*\[(\d+)\]/.exec(obj.detail);
    if (m) {
      const c = parseInt(m[1], 10);
      if (c >= 2001 && c <= 2003) {
        base.message = obj.detail;
        base.code = c;
        base.namespace = NS.ERRORS;
        return createError(ErrorCategory.AUTH, obj.detail, base);
      }
      if (ERROR_TABLE[c]) {
        base.message = obj.detail;
        base.namespace = NS.ERRORS;
        return fromCode(c, base);
      }
    }
  }
  base.code = 0;
  base.namespace = NS.UNKNOWN;
  return createError(httpCategory(status), String(message), base);
}

/**
 * Maps a WebSocket `{"event":"error","code":N,"message":"..."}` frame. A frame without code is a
 * server side rejection of the session (for example "no audio") and maps to SERVER, not retryable.
 */
export function fromWsErrorFrame(frame, init) {
  const o = withCategory(init, null);
  const code = frame ? toInt(frame.code) : 0;
  const message = (frame && frame.message) || 'server error frame';
  o.message = String(message);
  o.rawBody = safeStringify(frame);
  if (code && ERROR_TABLE[code]) {
    o.namespace = NS.ERRORS;
    return fromCode(code, o);
  }
  o.code = code;
  o.namespace = NS.UNKNOWN;
  return createError(ErrorCategory.SERVER, String(message), o);
}

/** Maps a wx API fail callback `{errMsg, errno}`. */
export function fromWxFail(err, what, init) {
  const msg = String((err && (err.errMsg || err.message)) || what + ' failed');
  const o = withCategory(init, null);
  o.cause = err;
  if (/time\s?out|timed out|超时/i.test(msg)) return localError(90002, msg, o);
  if (/abort/i.test(msg)) return localError(90003, msg, o);
  if (/certificate|\bcert\b/i.test(msg)) return localError(90011, msg, o);
  if (/domain list|合法域名/i.test(msg)) {
    return localError(90001, msg + ' (add the domain to the request and socket legal domains in the mini program console)', o);
  }
  return localError(90001, msg, o);
}

/** Wraps anything thrown into a YuguError, keeping YuguErrors as they are. */
export function asYuguError(e) {
  if (e instanceof YuguError) return e;
  if (e && typeof e === 'object' && typeof e.category === 'string' && typeof e.code === 'number') return e;
  const message = e && e.message ? String(e.message) : String(e);
  return new YuguError(message, { category: ErrorCategory.UNKNOWN, cause: e });
}

function safeStringify(v) {
  try {
    return JSON.stringify(v);
  } catch (e) {
    return null;
  }
}

/** Namespace style access, mirroring YuguErrors of the Java and Kotlin SDKs. */
export const YuguErrors = Object.freeze({
  fromCode,
  fromWarningCode,
  fromHttpResponse,
  isRetryable,
});
