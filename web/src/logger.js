// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// Logging with levels and redaction (DESIGN 2.7). Never logs secretKey, signature, token or audio.

/** Log levels, from quiet to verbose. */
export const LogLevel = Object.freeze({
  OFF: 'OFF',
  ERROR: 'ERROR',
  WARN: 'WARN',
  INFO: 'INFO',
  DEBUG: 'DEBUG',
});

const RANK = { OFF: 0, ERROR: 1, WARN: 2, INFO: 3, DEBUG: 4 };

/** True when level is one of LogLevel. */
export function isLogLevel(level) {
  return typeof level === 'string' && Object.prototype.hasOwnProperty.call(RANK, level);
}

/** Default sink: the platform console. */
export function consoleLogSink(level, tag, message, error) {
  const c = typeof console !== 'undefined' ? console : null;
  /* c8 ignore next */
  if (!c) return;
  const line = `[yugu-web-sdk] ${level} ${tag}: ${message}`;
  let fn;
  if (level === LogLevel.ERROR) fn = c.error;
  else if (level === LogLevel.WARN) fn = c.warn;
  else if (level === LogLevel.INFO) fn = c.info || c.log;
  else fn = c.debug || c.log;
  if (error != null) fn.call(c, line, error);
  else fn.call(c, line);
}

/** appKey as its first 4 characters plus ***. */
export function maskAppKey(appKey) {
  if (appKey == null || appKey === '') return '';
  return String(appKey).slice(0, 4) + '***';
}

const SECRET_QUERY = /([?&](?:signature|sig|token|access_token|secretKey)=)[^&#\s"']*/gi;
const APPKEY_QUERY = /([?&]appKey=)([^&#\s"']{0,4})[^&#\s"']*/gi;
const BEARER = /(Bearer\s+)[A-Za-z0-9\-._~+/]+=*/g;

/** Mask credentials inside a URL or any text that may contain one. */
export function redactUrl(text) {
  return String(text).replace(SECRET_QUERY, '$1***').replace(APPKEY_QUERY, '$1$2***').replace(BEARER, '$1***');
}

/** Copy of a header map with credentials masked. */
export function redactHeaders(headers) {
  const out = {};
  for (const [k, v] of Object.entries(headers || {})) {
    const lk = k.toLowerCase();
    if (lk === 'x-signature' || lk === 'authorization') out[k] = lk === 'authorization' ? 'Bearer ***' : '***';
    else if (lk === 'x-app-key') out[k] = maskAppKey(v);
    else out[k] = v;
  }
  return out;
}

/** Internal logger. Sink failures never reach the caller. */
export class Logger {
  constructor(level, sink, secrets) {
    this.level = level || LogLevel.WARN;
    this.sink = typeof sink === 'function' ? sink : consoleLogSink;
    this.secrets = (secrets || []).filter((s) => typeof s === 'string' && s.length >= 4);
  }

  enabled(level) {
    return RANK[level] > 0 && RANK[level] <= RANK[this.level];
  }

  scrub(message) {
    let m = redactUrl(message);
    for (const s of this.secrets) m = m.split(s).join('***');
    return m;
  }

  log(level, tag, message, error) {
    if (!this.enabled(level)) return;
    try {
      this.sink(level, tag, this.scrub(message), error == null ? null : error);
    } catch (_) {
      /* a failing sink must not break the SDK */
    }
  }

  error(tag, message, error) {
    this.log(LogLevel.ERROR, tag, message, error);
  }

  warn(tag, message, error) {
    this.log(LogLevel.WARN, tag, message, error);
  }

  info(tag, message, error) {
    this.log(LogLevel.INFO, tag, message, error);
  }

  debug(tag, message, error) {
    this.log(LogLevel.DEBUG, tag, message, error);
  }
}

/** Calls an optional hook on the metrics EventListener; failures are logged, never thrown. */
export function callHook(target, name, args, logger) {
  if (!target) return;
  const fn = target[name];
  if (typeof fn !== 'function') return;
  try {
    fn.apply(target, args);
  } catch (e) {
    if (logger) logger.error('event', `eventListener.${name} threw`, e);
  }
}
