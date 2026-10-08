// Logging with levels and redaction (DESIGN 2.7). secretKey, signature, token and audio bytes are
// never logged; appKey is logged as its first 4 characters plus ***.

export const LogLevel = Object.freeze({
  OFF: 'OFF',
  ERROR: 'ERROR',
  WARN: 'WARN',
  INFO: 'INFO',
  DEBUG: 'DEBUG',
});

const RANK = { OFF: 0, ERROR: 1, WARN: 2, INFO: 3, DEBUG: 4 };

export function isLogLevel(v) {
  return typeof v === 'string' && Object.prototype.hasOwnProperty.call(RANK, v);
}

export function maskAppKey(appKey) {
  if (!appKey) return '';
  return String(appKey).slice(0, 4) + '***';
}

/** Redacts credential query parameters of a URL before it is logged. */
export function redactUrl(url) {
  return String(url).replace(/([?&])(signature|token|secretKey|appKey)=([^&#]*)/g, (all, sep, name, value) => {
    if (name === 'appKey') return sep + name + '=' + maskAppKey(decodeSafe(value));
    return sep + name + '=***';
  });
}

function decodeSafe(v) {
  try {
    return decodeURIComponent(v);
  } catch (e) {
    return v;
  }
}

function consoleSink(level, tag, message, error) {
  if (typeof console === 'undefined') return;
  const fn = level === 'ERROR' ? console.error : level === 'WARN' ? console.warn : level === 'INFO' ? console.info : console.log;
  const line = '[' + tag + '] ' + message;
  if (error !== undefined) fn.call(console, line, error);
  else fn.call(console, line);
}

export class Logger {
  /**
   * @param level   OFF, ERROR, WARN, INFO or DEBUG
   * @param sink    function (level, tag, message, error) or undefined for the console
   * @param secrets strings that must never appear in a log line (secretKey, token)
   */
  constructor(level, sink, secrets) {
    this.level = isLogLevel(level) ? level : LogLevel.WARN;
    this.sink = typeof sink === 'function' ? sink : consoleSink;
    this.secrets = (secrets || []).filter((s) => typeof s === 'string' && s.length >= 4);
  }

  enabled(level) {
    return RANK[level] > 0 && RANK[level] <= RANK[this.level];
  }

  scrub(text) {
    let s = String(text);
    for (let i = 0; i < this.secrets.length; i++) s = s.split(this.secrets[i]).join('***');
    return s;
  }

  log(level, tag, message, error) {
    if (!this.enabled(level)) return;
    try {
      this.sink(level, tag, this.scrub(message), error);
    } catch (e) {
      // A failing sink must never break the SDK.
    }
  }

  error(tag, message, error) {
    this.log('ERROR', tag, message, error);
  }

  warn(tag, message, error) {
    this.log('WARN', tag, message, error);
  }

  info(tag, message, error) {
    this.log('INFO', tag, message, error);
  }

  debug(tag, message, error) {
    this.log('DEBUG', tag, message, error);
  }
}

/** Calls optional EventListener hooks (DESIGN 2.7), isolating the SDK from exceptions they throw. */
export class EventHub {
  constructor(listener, logger) {
    this.listener = listener && typeof listener === 'object' ? listener : null;
    this.logger = logger;
  }

  emit(name) {
    const l = this.listener;
    if (!l || typeof l[name] !== 'function') return;
    const args = Array.prototype.slice.call(arguments, 1);
    try {
      l[name].apply(l, args);
    } catch (e) {
      if (this.logger) this.logger.error('YuguSDK', 'eventListener.' + name + ' threw', e);
    }
  }
}
