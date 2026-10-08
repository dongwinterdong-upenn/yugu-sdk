// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// Typed errors on top of the generated tables in error-table.js (DESIGN 2.3 and 2.4).

import { ERROR_TABLE, WARNING_TABLE, LOCAL_TABLE, HTTP_FALLBACK, RETRYABLE_HTTP } from './error-table.js';
import { getHeader, parseRetryAfter } from './headers.js';
import { truncate } from './util.js';

/** Error categories, same names as spec/errors.json. */
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

/** Audio quality warning codes found in evaluation results (warnings section of errors.json). */
export const WarningCode = Object.freeze(
  Object.values(WARNING_TABLE).reduce((acc, e) => {
    acc[e.name] = e.code;
    return acc;
  }, {}),
);

/** rawBody keeps at most this many characters. */
export const RAW_BODY_LIMIT = 4096;

const ENGINE_DETAIL = /^\s*\[(\d{1,6})\]\s*([\s\S]*)$/;

function toInt(v, def) {
  const n = Number(v);
  return Number.isFinite(n) ? Math.trunc(n) : def;
}

/**
 * Retry decision shared by the retry loop, WebSocket reconnects and callers (DESIGN 2.3).
 * 1. local codes: the local table flag (90001, 90002, 90007 are retryable)
 * 2. known business code: the flag from errors.json
 * 3. otherwise: HTTP 408, 425, 429, 500, 502, 503, 504
 */
export function retryableFor(code, httpStatus) {
  const c = toInt(code, 0);
  if (c !== 0) {
    if (LOCAL_TABLE[c]) return LOCAL_TABLE[c].retryable;
    if (ERROR_TABLE[c]) return ERROR_TABLE[c].retryable;
    if (WARNING_TABLE[c]) return WARNING_TABLE[c].retryable;
  }
  return RETRYABLE_HTTP.includes(toInt(httpStatus, 0));
}

/**
 * Category of an HTTP status without a known code: the httpFallback table, then by class
 * (other 4xx INVALID_PARAM, other 5xx SERVER), anything else UNKNOWN (DESIGN 2.4).
 */
export function httpCategory(httpStatus) {
  const s = toInt(httpStatus, 0);
  if (HTTP_FALLBACK[s]) return HTTP_FALLBACK[s];
  if (s >= 400 && s < 500) return ErrorCategory.INVALID_PARAM;
  if (s >= 500 && s < 600) return ErrorCategory.SERVER;
  return ErrorCategory.UNKNOWN;
}

/**
 * Category for a code and HTTP status, same lookup order as fromCode. Codes are looked up by
 * namespace: local 9xxxx codes in the local table, server codes in the error table; codes found
 * only among the warnings (1001 to 1003, 1009) are AUDIO.
 */
export function categoryFor(code, httpStatus) {
  const c = toInt(code, 0);
  if (c !== 0) {
    const e = LOCAL_TABLE[c] || ERROR_TABLE[c] || WARNING_TABLE[c];
    if (e) return e.category;
  }
  return httpCategory(httpStatus);
}

/** Base error of the SDK. Every error the SDK throws or reports is a YuguError. */
export class YuguError extends Error {
  constructor(message, init) {
    const i = init || {};
    super(message == null ? '' : String(message));
    Object.setPrototypeOf(this, new.target.prototype);
    this.name = 'YuguError';
    this.category = i.category || ErrorCategory.UNKNOWN;
    this.code = toInt(i.code, 0);
    this.httpStatus = toInt(i.httpStatus, 0);
    this.retryable = typeof i.retryable === 'boolean' ? i.retryable : retryableFor(this.code, this.httpStatus);
    this.idempotencyKey = i.idempotencyKey == null ? null : String(i.idempotencyKey);
    this.recordId = i.recordId == null ? null : String(i.recordId);
    this.attempts = toInt(i.attempts, 0);
    this.rawBody = i.rawBody == null ? null : truncate(String(i.rawBody), RAW_BODY_LIMIT);
    this.retryAfterMs = i.retryAfterMs == null ? null : toInt(i.retryAfterMs, null);
    this.traceId = i.traceId == null ? null : String(i.traceId);
    this.localWarnings = Array.isArray(i.localWarnings) ? i.localWarnings : [];
    if (i.cause !== undefined) this.cause = i.cause;
  }

  /** Plain object for logs and reports. */
  toJSON() {
    return {
      name: this.name,
      category: this.category,
      code: this.code,
      httpStatus: this.httpStatus,
      message: this.message,
      retryable: this.retryable,
      idempotencyKey: this.idempotencyKey,
      recordId: this.recordId,
      attempts: this.attempts,
      retryAfterMs: this.retryAfterMs,
      traceId: this.traceId,
      rawBody: this.rawBody,
    };
  }
}

const withCategory = (init, category) => Object.assign({}, init, { category: (init && init.category) || category });

/** Connection failed, was reset, TLS failed or reconnects were exhausted. Category NETWORK. */
export class NetworkException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.NETWORK));
    this.name = 'NetworkException';
  }
}

/** Connect, read, heartbeat or result timeout. Category TIMEOUT. */
export class RequestTimeoutException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.TIMEOUT));
    this.name = 'RequestTimeoutException';
  }
}

/** Missing or invalid credentials, bad signature, expired token. Category AUTH. */
export class AuthException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.AUTH));
    this.name = 'AuthException';
  }
}

/** Credentials valid but not allowed, also microphone permission denied. Category PERMISSION. */
export class PermissionException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.PERMISSION));
    this.name = 'PermissionException';
  }
}

/** Invalid request parameters, local or server side. Category INVALID_PARAM. */
export class InvalidParameterException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.INVALID_PARAM));
    this.name = 'InvalidParameterException';
  }
}

/** Resource not found. Category NOT_FOUND. */
export class NotFoundException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.NOT_FOUND));
    this.name = 'NotFoundException';
  }
}

/** Idempotency conflicts and other 409 conflicts. Category CONFLICT. */
export class ConflictException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.CONFLICT));
    this.name = 'ConflictException';
  }
}

/** Rate or concurrency limit. Category RATE_LIMIT. */
export class RateLimitException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.RATE_LIMIT));
    this.name = 'RateLimitException';
  }
}

/** Quota, balance or daily limit exhausted. Category QUOTA. */
export class QuotaExceededException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.QUOTA));
    this.name = 'QuotaExceededException';
  }
}

/** Server or upstream failure. Category SERVER or UPSTREAM. */
export class ServerException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.SERVER));
    this.name = 'ServerException';
  }
}

/** Audio quality problem, from local precheck or server warning codes. Category AUDIO. */
export class AudioQualityException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.AUDIO));
    this.name = 'AudioQualityException';
  }
}

/** Operation not allowed in the current state, client closed, recorder unavailable. Category STATE. */
export class IllegalSessionStateException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.STATE));
    this.name = 'IllegalSessionStateException';
  }
}

/** The caller cancelled the call (AbortSignal). Category CANCELLED. */
export class RequestCancelledException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.CANCELLED));
    this.name = 'RequestCancelledException';
  }
}

/** Response or frame could not be parsed, or the server broke the protocol. Category PROTOCOL. */
export class ProtocolViolationException extends YuguError {
  constructor(message, init) {
    super(message, withCategory(init, ErrorCategory.PROTOCOL));
    this.name = 'ProtocolViolationException';
  }
}

const CATEGORY_CLASS = {
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
  UNKNOWN: YuguError,
};

/** Error class used for a category. */
export function classForCategory(category) {
  return CATEGORY_CLASS[category] || YuguError;
}

/** isRetryable(error): one decision for retries, reconnects and callers. */
export function isRetryable(error) {
  if (!(error instanceof YuguError)) return false;
  return retryableFor(error.code, error.httpStatus);
}

function build(category, message, init) {
  const Cls = classForCategory(category);
  return new Cls(message, Object.assign({}, init, { category }));
}

/**
 * Typed error from a code in an error context (error body, error frame, local error).
 * Lookup by namespace: 9xxxx in the local table, server codes in the error table, so 1004 and
 * 1005 are USER_DISABLED and USER_LOCKED here. Codes that exist only as warnings (1001 to 1003,
 * 1009) give AudioQualityException. Unknown codes fall back to the HTTP status category.
 * Use fromWarning for codes taken from result.warning.
 */
function fromCode(code, init) {
  const i = init || {};
  const c = toInt(code, 0);
  const entry = c !== 0 ? LOCAL_TABLE[c] || ERROR_TABLE[c] || WARNING_TABLE[c] || null : null;
  const category = entry ? entry.category : httpCategory(i.httpStatus);
  const message = i.message || (entry ? entry.message : c !== 0 ? `错误码 ${c}` : '未知错误');
  return build(category, message, Object.assign({}, i, { code: c, message: undefined }));
}

/** AudioQualityException from a warning code (1001 to 1009) or a local precheck code (90101 to 90105). */
function fromWarning(code, init) {
  const i = init || {};
  const c = toInt(code, 0);
  const entry = WARNING_TABLE[c] || LOCAL_TABLE[c] || null;
  const message = i.message || (entry ? entry.message : `音频质量警告 ${c}`);
  return new AudioQualityException(message, Object.assign({}, i, { code: c, category: ErrorCategory.AUDIO }));
}

/** Extracts {code, message} from the error body shapes the platform produces. */
export function parseErrorBody(bodyText) {
  let code = 0;
  let message = '';
  let json = null;
  if (bodyText) {
    try {
      json = JSON.parse(bodyText);
    } catch (_) {
      json = null;
    }
  }
  if (json && typeof json === 'object' && !Array.isArray(json)) {
    const c = toInt(json.code, 0);
    if (c !== 0) code = c;
    if (typeof json.message === 'string') message = json.message;
    else if (typeof json.msg === 'string') message = json.msg;
    else if (typeof json.error === 'string') message = json.error;
    const d = json.detail;
    if (typeof d === 'string') {
      if (!message) message = d;
    } else if (Array.isArray(d)) {
      if (!message) {
        message = d
          .map((x) => (x && typeof x === 'object' ? x.msg || x.message || JSON.stringify(x) : String(x)))
          .join('；');
      }
    } else if (d && typeof d === 'object') {
      if (!message) message = typeof d.message === 'string' ? d.message : JSON.stringify(d);
    }
  }
  if (code === 0 && message) {
    const m = ENGINE_DETAIL.exec(message);
    if (m) {
      code = toInt(m[1], 0);
      message = m[2].trim() || message;
    }
  }
  return { code, message, json };
}

/**
 * Typed error from an HTTP error response.
 * Mapping order: body code through the table, engine detail pattern [2001], HTTP status fallback.
 */
function fromHttp(httpStatus, bodyText, init) {
  const i = init || {};
  const status = toInt(httpStatus, 0);
  const parsed = parseErrorBody(bodyText);
  const headers = i.headers;
  const retryAfterMs = i.retryAfterMs != null ? i.retryAfterMs : parseRetryAfter(getHeader(headers, 'retry-after'));
  const traceId = i.traceId != null ? i.traceId : getHeader(headers, 'x-trace-id');
  const entry = parsed.code !== 0 ? ERROR_TABLE[parsed.code] || null : null;
  const message = parsed.message || (entry ? entry.message : `HTTP ${status}`);
  const rest = Object.assign({}, i);
  delete rest.headers;
  return fromCode(
    parsed.code,
    Object.assign(rest, {
      httpStatus: status,
      message,
      rawBody: bodyText == null ? null : String(bodyText),
      retryAfterMs,
      traceId,
    }),
  );
}

/** Typed error from a WebSocket error frame {"event":"error","code":N,"message":"..."}. */
function fromWsFrame(frame, init) {
  const i = init || {};
  const f = frame && typeof frame === 'object' ? frame : {};
  let code = toInt(f.code, 0);
  let message = typeof f.message === 'string' ? f.message : '';
  if (code === 0 && message) {
    const m = ENGINE_DETAIL.exec(message);
    if (m) {
      code = toInt(m[1], 0);
      message = m[2].trim() || message;
    }
  }
  let raw = null;
  try {
    raw = JSON.stringify(f);
  } catch (_) {
    raw = null;
  }
  return fromCode(code, Object.assign({}, i, { message: message || '实时评测服务返回错误', rawBody: raw }));
}

/** Factory helpers. */
export const YuguErrors = Object.freeze({
  fromCode,
  fromWarning,
  fromHttp,
  fromWsFrame,
  parseErrorBody,
  categoryFor,
  httpCategory,
  classForCategory,
  isRetryable,
});

/** Short text used in retry logs, for example "HTTP 503 code=50200". */
export function describeError(err) {
  if (!(err instanceof YuguError)) return String(err && err.message ? err.message : err);
  if (err.httpStatus) return `HTTP ${err.httpStatus}${err.code ? ` code=${err.code}` : ''}`;
  return `${err.name} code=${err.code}`;
}

/** Local error helper. */
export function localError(code, detail, init) {
  const entry = LOCAL_TABLE[code];
  const base = entry ? entry.message : `错误码 ${code}`;
  return fromCode(code, Object.assign({}, init, { message: detail ? `${base}：${detail}` : base }));
}
