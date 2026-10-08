// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// YuguClient: REST calls with idempotency and retry, streaming sessions, lifecycle (DESIGN 2).

import { applyPrecheck, AudioPrecheck, isPrecheckMode, precheckAudio, resolveAudioInput } from './audio.js';
import {
  describeError,
  isRetryable,
  localError,
  RequestCancelledException,
  YuguError,
  YuguErrors,
} from './errors.js';
import { getHeader } from './headers.js';
import { resolveIdempotencyKey } from './idempotency.js';
import { callHook, isLogLevel, Logger, LogLevel, redactHeaders } from './logger.js';
import { buildMultipart, CONFIG_PART_CONTENT_TYPE } from './multipart.js';
import { COMPAT_CORE_TYPES, normalizeEvalResult, normalizeTtsResult, resolveTtsUrl } from './result.js';
import {
  computeRetryDelay,
  DEFAULT_RECONNECT_POLICY,
  DEFAULT_RETRY_POLICY,
  normalizeReconnectPolicy,
  normalizeRetryPolicy,
} from './retry.js';
import { AudioBufferPolicy, DEFAULT_MAX_REPLAY_BYTES, SESSION_CONTEXT, YuguStreamSession } from './session.js';
import { buildSignPayload, importHmacKey, signPayloadWithKey } from './signer.js';
import {
  abortableDelay,
  isBlobLike,
  isBrowserLike,
  isFiniteNumber,
  isPlainObject,
  readBlob,
  randomBytes,
  stripTrailingSlash,
  toBytes,
  toExactArrayBuffer,
  toHex,
} from './util.js';
import { DEFAULT_BASE_URL, DEFAULT_USER_AGENT, DEFAULT_WS_BASE_URL } from './version.js';

/** Timeouts and limits used when the options leave them out. */
export const CLIENT_DEFAULTS = Object.freeze({
  baseUrl: DEFAULT_BASE_URL,
  wsBaseUrl: DEFAULT_WS_BASE_URL,
  connectTimeoutMs: 10000,
  readTimeoutMs: 120000,
  totalTimeoutMs: 300000,
  resultTimeoutMs: 300000,
  heartbeatIntervalMs: 15000,
  heartbeatTimeoutMs: 30000,
  maxReplayBytes: DEFAULT_MAX_REPLAY_BYTES,
  autoIdempotencyKey: true,
  logLevel: LogLevel.WARN,
  audioPrecheck: AudioPrecheck.WARN,
  audioBufferPolicy: AudioBufferPolicy.REPLAY,
  heartbeat: 'auto',
  userAgent: DEFAULT_USER_AGENT,
});

const RESERVED_QUERY = new Set(['appKey', 'timestamp', 'nonce', 'signature', 'token', 'idempotencyKey']);

function checkMs(value, name, min, max) {
  if (!isFiniteNumber(value) || value < min || value > max) {
    throw localError(90010, `${name} 必须是 ${min} 到 ${max} 之间的毫秒数`);
  }
  return value;
}

function optMs(value, fallback, name, min, max) {
  return value === undefined || value === null ? fallback : checkMs(value, name, min, max);
}

function optNumber(value, fallback, name, min, max) {
  if (value === undefined || value === null) return fallback;
  if (!isFiniteNumber(value) || value < min || value > max || !Number.isInteger(value)) {
    throw localError(90010, `${name} 必须是 ${min} 到 ${max} 之间的整数`);
  }
  return value;
}

function checkSignal(signal) {
  if (signal == null) return null;
  if (typeof signal !== 'object' || typeof signal.addEventListener !== 'function' || !('aborted' in signal)) {
    throw localError(90010, 'signal 必须是 AbortSignal');
  }
  return signal;
}

function checkHeartbeat(v) {
  if (v === 'auto' || v === true || v === false) return v;
  throw localError(90010, 'heartbeat 只能是 "auto"、true 或 false');
}

function checkBufferPolicy(v) {
  if (v === 'REPLAY' || v === 'DROP' || v === 'FAIL') return v;
  throw localError(90010, 'audioBufferPolicy 只能是 REPLAY、DROP 或 FAIL');
}

function checkPrecheck(v) {
  if (isPrecheckMode(v)) return v;
  throw localError(90010, 'audioPrecheck 只能是 OFF、WARN 或 REJECT');
}

function plainNumberText(n, name) {
  const s = String(n);
  if (!Number.isFinite(n) || /e/i.test(s)) throw localError(90010, `${name} 必须是普通十进制数字`);
  return s;
}

function sniffImage(bytes) {
  if (bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) return ['png', 'image/png'];
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return ['jpg', 'image/jpeg'];
  if (bytes.length >= 6 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46) return ['gif', 'image/gif'];
  if (bytes.length >= 12 && bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50) return ['webp', 'image/webp'];
  return ['png', 'application/octet-stream'];
}

/**
 * Client of the 优谷雅言 speech evaluation platform.
 */
export class YuguClient {
  #auth;
  #hmacKey = null;

  constructor(options) {
    const o = options == null ? {} : options;
    if (typeof o !== 'object' || Array.isArray(o)) throw localError(90010, 'options 必须是对象');

    const a = isPlainObject(o.auth) ? o.auth : {};
    const token = o.token != null ? o.token : a.token;
    const appKey = o.appKey != null ? o.appKey : a.appKey;
    const secretKey = o.secretKey != null ? o.secretKey : a.secretKey;
    const hasToken = token != null && token !== '';
    const hasKeys = (appKey != null && appKey !== '') || (secretKey != null && secretKey !== '');
    if (hasToken && hasKeys) throw localError(90010, 'token 与 appKey 加 secretKey 只能二选一');
    if (hasKeys) {
      if (typeof appKey !== 'string' || !appKey || typeof secretKey !== 'string' || !secretKey) {
        throw localError(90010, '签名鉴权需要同时提供 appKey 与 secretKey');
      }
      this.#auth = { kind: 'signature', appKey, secretKey };
    } else if (hasToken) {
      if (typeof token !== 'string' && typeof token !== 'function') throw localError(90010, 'token 必须是字符串或返回字符串的函数');
      this.#auth = { kind: 'token', token };
    } else {
      throw localError(90010, '缺少鉴权信息，需传入 token，或 appKey 加 secretKey');
    }

    const baseUrl = stripTrailingSlash(o.baseUrl != null ? o.baseUrl : CLIENT_DEFAULTS.baseUrl);
    if (!/^https?:\/\/[^/]+/i.test(baseUrl)) throw localError(90010, 'baseUrl 必须以 http:// 或 https:// 开头');
    let wsBaseUrl;
    if (o.wsBaseUrl != null) wsBaseUrl = stripTrailingSlash(o.wsBaseUrl);
    else if (o.baseUrl != null) wsBaseUrl = baseUrl.replace(/^http/i, 'ws');
    else wsBaseUrl = CLIENT_DEFAULTS.wsBaseUrl;
    if (!/^wss?:\/\/[^/]+/i.test(wsBaseUrl)) throw localError(90010, 'wsBaseUrl 必须以 ws:// 或 wss:// 开头');
    this._baseUrl = baseUrl;
    this._wsBaseUrl = wsBaseUrl;

    this._connectTimeoutMs = optMs(o.connectTimeoutMs, CLIENT_DEFAULTS.connectTimeoutMs, 'connectTimeoutMs', 1, 600000);
    this._readTimeoutMs = optMs(o.readTimeoutMs, CLIENT_DEFAULTS.readTimeoutMs, 'readTimeoutMs', 1, 3600000);
    this._totalTimeoutMs = optMs(o.totalTimeoutMs, CLIENT_DEFAULTS.totalTimeoutMs, 'totalTimeoutMs', 1, 3600000);
    this._resultTimeoutMs = optMs(o.resultTimeoutMs, CLIENT_DEFAULTS.resultTimeoutMs, 'resultTimeoutMs', 1, 3600000);
    this._heartbeatIntervalMs = optMs(o.heartbeatIntervalMs, CLIENT_DEFAULTS.heartbeatIntervalMs, 'heartbeatIntervalMs', 1, 600000);
    this._heartbeatTimeoutMs = optMs(o.heartbeatTimeoutMs, CLIENT_DEFAULTS.heartbeatTimeoutMs, 'heartbeatTimeoutMs', 1, 600000);
    this._maxReplayBytes = optNumber(o.maxReplayBytes, CLIENT_DEFAULTS.maxReplayBytes, 'maxReplayBytes', 0, 64 * 1024 * 1024);
    this._retry = normalizeRetryPolicy(o.retry, DEFAULT_RETRY_POLICY);
    this._reconnect = normalizeReconnectPolicy(o.reconnect, DEFAULT_RECONNECT_POLICY);
    this._bufferPolicy = checkBufferPolicy(o.audioBufferPolicy != null ? o.audioBufferPolicy : CLIENT_DEFAULTS.audioBufferPolicy);
    this._heartbeat = checkHeartbeat(o.heartbeat !== undefined ? o.heartbeat : CLIENT_DEFAULTS.heartbeat);
    this._autoKey = o.autoIdempotencyKey !== false;
    this._precheck = checkPrecheck(o.audioPrecheck != null ? o.audioPrecheck : CLIENT_DEFAULTS.audioPrecheck);
    this._strictAudio = o.strictAudio === true;

    const level = o.logLevel != null ? o.logLevel : CLIENT_DEFAULTS.logLevel;
    if (!isLogLevel(level)) throw localError(90010, 'logLevel 只能是 OFF、ERROR、WARN、INFO、DEBUG');
    if (o.logger != null && typeof o.logger !== 'function') throw localError(90010, 'logger 必须是函数 (level, tag, message, error)');
    const secrets = [this.#auth.secretKey, typeof this.#auth.token === 'string' ? this.#auth.token : null];
    this._logger = new Logger(level, o.logger, secrets);
    if (o.eventListener != null && typeof o.eventListener !== 'object') throw localError(90010, 'eventListener 必须是对象');
    this._events = o.eventListener || null;

    if (o.userAgent != null && (typeof o.userAgent !== 'string' || !/^[\x20-\x7E]{1,200}$/.test(o.userAgent))) {
      throw localError(90010, 'userAgent 必须是可见 ASCII 字符串');
    }
    this._userAgent = o.userAgent || CLIENT_DEFAULTS.userAgent;
    this._sdkHeader = isBrowserLike() ? 'X-Yugu-SDK' : 'User-Agent';

    if (o.fetch != null && typeof o.fetch !== 'function') throw localError(90010, 'fetch 必须是函数');
    this._fetch = o.fetch || (typeof fetch === 'function' ? (url, init) => fetch(url, init) : null);
    if (o.WebSocket != null && typeof o.WebSocket !== 'function') throw localError(90010, 'WebSocket 必须是构造函数');
    this._WebSocket = o.WebSocket || (typeof WebSocket === 'function' ? WebSocket : null);
    this._crypto = o.crypto || null;
    if (o.random != null && typeof o.random !== 'function') throw localError(90010, 'random 必须是函数');
    this._random = o.random || Math.random;

    this._closed = false;
    this._closePromise = null;
    this._closeController = new AbortController();
    this._sessions = new Set();
    this._pingSupport = 'unknown';
    this._ctx = null;
    this._logger.debug('client', `created: base ${baseUrl}, ws ${wsBaseUrl}, auth ${this.authMode}`);
  }

  /** REST base URL. */
  get baseUrl() {
    return this._baseUrl;
  }

  /** WebSocket base URL. */
  get wsBaseUrl() {
    return this._wsBaseUrl;
  }

  /** "signature" (appKey plus secretKey) or "token" (JWT). */
  get authMode() {
    return this.#auth.kind;
  }

  /** True with appKey plus secretKey. */
  get hasSigAuth() {
    return this.#auth.kind === 'signature';
  }

  /** True with a token. */
  get hasTokenAuth() {
    return this.#auth.kind === 'token';
  }

  /** True after close(). */
  isClosed() {
    return this._closed;
  }

  /**
   * Native whole-utterance evaluation, POST /api/v1/evaluate.
   * audio: Blob, File, ArrayBuffer or any ArrayBufferView (WAV or MP3; raw PCM with audioFormat "pcm").
   */
  async evaluate(audio, config, options) {
    this._ensureOpen();
    const opts = this._callOptions(options, true);
    if (!isPlainObject(config)) throw localError(90010, 'config 必须是对象');
    if (typeof config.coreType !== 'string' || !config.coreType) throw localError(90010, 'config.coreType 必填');
    if (typeof config.referenceText !== 'string') throw localError(90010, 'config.referenceText 必填');
    const input = await resolveAudioInput(audio, opts, 'audio');
    const localWarnings = this._runPrecheck(input.bytes, opts, 'evaluate');
    let configText;
    try {
      configText = JSON.stringify(config);
    } catch (e) {
      throw localError(90010, `config 无法序列化为 JSON：${e && e.message}`);
    }
    const parts = [
      { name: 'config', value: configText, contentType: CONFIG_PART_CONTENT_TYPE },
      { name: 'audio', data: input.bytes, filename: input.filename, contentType: input.contentType },
    ];
    if (opts.image != null) parts.push(await this._imagePart(opts.image));
    const mp = buildMultipart(parts, { crypto: this._crypto });
    const res = await this._execute(
      'evaluate',
      { method: 'POST', path: '/api/v1/evaluate', body: mp.body, contentType: mp.contentType, signParams: { config: configText } },
      opts,
    );
    const result = normalizeEvalResult(res.json, {
      mode: 'native',
      coreType: config.coreType,
      idempotencyKey: opts.idempotencyKey,
      replayed: res.replayed,
      localWarnings,
    });
    this._strictCheck(result, opts, res.attempts);
    return result;
  }

  /**
   * Shengtong compatible evaluation, POST /{coreType} with form fields (signed) and the audio file.
   */
  async evaluateCompat(coreType, params, audio, options) {
    this._ensureOpen();
    const opts = this._callOptions(options, true);
    this._checkCompatCoreType(coreType);
    if (this.#auth.kind !== 'signature') {
      // The platform rejects compat REST calls without X-App-Key (CONTRACT 3.2).
      throw localError(90010, '声通兼容整段评测需要 appKey 加 secretKey 签名鉴权，平台要求请求头 X-App-Key');
    }
    const fields = this._compatFields(params);
    const input = await resolveAudioInput(audio, opts, 'audio');
    const localWarnings = this._runPrecheck(input.bytes, opts, 'evaluateCompat');
    const parts = Object.keys(fields).map((name) => ({ name, value: fields[name] }));
    parts.push({ name: 'audio', data: input.bytes, filename: input.filename, contentType: input.contentType });
    const mp = buildMultipart(parts, { crypto: this._crypto });
    const res = await this._execute(
      'evaluateCompat',
      { method: 'POST', path: `/${coreType}`, body: mp.body, contentType: mp.contentType, signParams: fields },
      opts,
    );
    const result = normalizeEvalResult(res.json, {
      mode: 'compat',
      coreType,
      idempotencyKey: opts.idempotencyKey,
      replayed: res.replayed,
      localWarnings,
    });
    this._strictCheck(result, opts, res.attempts);
    return result;
  }

  /** Text to speech, POST /api/v1/tts/generate (JSON body, top-level scalars signed). */
  async tts(request, options) {
    this._ensureOpen();
    const opts = this._callOptions(options, true);
    if (!isPlainObject(request)) throw localError(90010, 'tts 请求必须是对象');
    if (typeof request.text !== 'string' || !request.text) throw localError(90010, 'text 必填');
    const body = Object.assign(
      { text: request.text, language: 'zh-CN', format: 'mp3', speed: 50, pitch: 50, volume: 50, style: null },
      request,
    );
    for (const k of Object.keys(body)) if (body[k] === undefined) delete body[k];
    const signParams = {};
    for (const [k, v] of Object.entries(body)) {
      if (v === null) continue;
      if (typeof v === 'number') signParams[k] = plainNumberText(v, k);
      else if (typeof v === 'boolean') signParams[k] = v ? 'true' : 'false';
      else if (typeof v === 'string') signParams[k] = v;
    }
    const res = await this._execute(
      'tts',
      {
        method: 'POST',
        path: '/api/v1/tts/generate',
        body: JSON.stringify(body),
        contentType: 'application/json; charset=utf-8',
        signParams,
        envelope: true,
      },
      opts,
    );
    return normalizeTtsResult(res.json, { baseUrl: this._baseUrl, idempotencyKey: opts.idempotencyKey, replayed: res.replayed });
  }

  /** Report of an evaluation, GET /api/v1/report/{recordId}. Retried without a key (naturally idempotent). */
  async getReport(recordId, options) {
    this._ensureOpen();
    const opts = this._callOptions(options, false);
    if (typeof recordId !== 'string' || !recordId) throw localError(90010, 'recordId 必填');
    const res = await this._execute(
      'getReport',
      { method: 'GET', path: `/api/v1/report/${encodeURIComponent(recordId)}`, signParams: {}, envelope: true, idempotent: true },
      opts,
    );
    return res.json.data != null ? res.json.data : res.json;
  }

  /** Native streaming evaluation over wss://.../api/v1/ws/evaluate. Returns the session at once. */
  streamEvaluate(config, listener, options) {
    this._ensureOpen();
    if (!isPlainObject(config)) throw localError(90010, 'config 必须是对象');
    if (typeof config.coreType !== 'string' || !config.coreType) throw localError(90010, 'config.coreType 必填');
    if (typeof config.referenceText !== 'string') throw localError(90010, 'config.referenceText 必填');
    this._checkListener(listener);
    const so = this._sessionOptions(options);
    const params = Object.assign({}, config);
    delete params.idempotencyKey;
    return this._openSession({ mode: 'native', coreType: config.coreType, path: '/api/v1/ws/evaluate', params, listener, options: so });
  }

  /** Shengtong compatible streaming evaluation over wss://.../{coreType}. Returns the session at once. */
  streamEvaluateCompat(coreType, params, listener, options) {
    this._ensureOpen();
    this._checkCompatCoreType(coreType);
    if (params != null && !isPlainObject(params)) throw localError(90010, 'params 必须是对象');
    this._checkListener(listener);
    const so = this._sessionOptions(options);
    const frame = Object.assign({}, params || {});
    delete frame.idempotencyKey;
    return this._openSession({ mode: 'compat', coreType, path: `/${coreType}`, params: frame, listener, options: so });
  }

  /** Playable URL of a TTS audioUrl. */
  resolveTtsUrl(audioUrl) {
    return resolveTtsUrl(this._baseUrl, audioUrl);
  }

  /** Playable URL of result.standardAudio.url. */
  resolveAudioUrl(url) {
    return resolveTtsUrl(this._baseUrl, url);
  }

  /**
   * Close the client: cancels open sessions, aborts calls in flight (they reject with 90004) and
   * rejects later calls with IllegalSessionStateException 90004. Idempotent; resolves when every
   * session reached CLOSED.
   */
  close() {
    if (this._closePromise) return this._closePromise;
    this._closed = true;
    this._logger.info('client', 'close');
    try {
      this._closeController.abort();
    } catch (_) {
      /* ignore */
    }
    const sessions = Array.from(this._sessions);
    for (const s of sessions) s.cancel();
    this._closePromise = Promise.all(sessions.map((s) => s._whenClosed())).then(() => undefined);
    return this._closePromise;
  }

  // ------------------------------------------------------------------ internal: options

  _ensureOpen() {
    if (this._closed) throw localError(90004, '客户端已调用 close()');
  }

  _callOptions(options, write) {
    const o = options == null ? {} : options;
    if (typeof o !== 'object' || Array.isArray(o)) throw localError(90010, '调用选项必须是对象');
    if (o.headers != null && !isPlainObject(o.headers)) throw localError(90010, 'headers 必须是对象');
    if (o.audioFormat != null && o.audioFormat !== 'auto' && o.audioFormat !== 'pcm') {
      throw localError(90010, 'audioFormat 只能是 auto 或 pcm');
    }
    return {
      idempotencyKey: write ? resolveIdempotencyKey(o.idempotencyKey, this._autoKey, this._crypto) : null,
      retry: normalizeRetryPolicy(o.retry, this._retry),
      timeoutMs: optMs(o.timeoutMs, this._readTimeoutMs, 'timeoutMs', 1, 3600000),
      totalTimeoutMs: optMs(o.totalTimeoutMs, this._totalTimeoutMs, 'totalTimeoutMs', 1, 3600000),
      signal: checkSignal(o.signal),
      headers: o.headers || null,
      audioPrecheck: o.audioPrecheck != null ? checkPrecheck(o.audioPrecheck) : this._precheck,
      strictAudio: o.strictAudio != null ? o.strictAudio === true : this._strictAudio,
      filename: typeof o.filename === 'string' && o.filename ? o.filename : undefined,
      contentType: typeof o.contentType === 'string' && o.contentType ? o.contentType : undefined,
      audioFormat: o.audioFormat || 'auto',
      sampleRate: optNumber(o.sampleRate, 16000, 'sampleRate', 8000, 192000),
      image: o.image != null ? o.image : null,
    };
  }

  _sessionOptions(options) {
    const o = options == null ? {} : options;
    if (typeof o !== 'object' || Array.isArray(o)) throw localError(90010, '会话选项必须是对象');
    let query = null;
    if (o.query != null) {
      if (!isPlainObject(o.query)) throw localError(90010, 'query 必须是对象');
      query = {};
      for (const [k, v] of Object.entries(o.query)) {
        if (RESERVED_QUERY.has(k)) throw localError(90010, `query 不能包含 ${k}`);
        if (v == null || v === '') continue;
        if (typeof v === 'object') throw localError(90010, `query.${k} 必须是字符串、数字或布尔值`);
        query[k] = String(v);
      }
    }
    return {
      idempotencyKey: resolveIdempotencyKey(o.idempotencyKey, this._autoKey, this._crypto),
      reconnect: normalizeReconnectPolicy(o.reconnect, this._reconnect),
      audioBufferPolicy: o.audioBufferPolicy != null ? checkBufferPolicy(o.audioBufferPolicy) : this._bufferPolicy,
      maxReplayBytes: optNumber(o.maxReplayBytes, this._maxReplayBytes, 'maxReplayBytes', 0, 64 * 1024 * 1024),
      heartbeat: o.heartbeat !== undefined ? checkHeartbeat(o.heartbeat) : this._heartbeat,
      heartbeatIntervalMs: optMs(o.heartbeatIntervalMs, this._heartbeatIntervalMs, 'heartbeatIntervalMs', 1, 600000),
      heartbeatTimeoutMs: optMs(o.heartbeatTimeoutMs, this._heartbeatTimeoutMs, 'heartbeatTimeoutMs', 1, 600000),
      connectTimeoutMs: optMs(o.connectTimeoutMs, this._connectTimeoutMs, 'connectTimeoutMs', 1, 600000),
      resultTimeoutMs: optMs(o.resultTimeoutMs, this._resultTimeoutMs, 'resultTimeoutMs', 1, 3600000),
      audioPrecheck: o.audioPrecheck != null ? checkPrecheck(o.audioPrecheck) : this._precheck,
      sampleRate: optNumber(o.sampleRate, 16000, 'sampleRate', 8000, 192000),
      query,
      signal: checkSignal(o.signal),
    };
  }

  _checkListener(listener) {
    if (!listener || typeof listener !== 'object') throw localError(90010, 'listener 必须是对象');
    if (typeof listener.onResult !== 'function' || typeof listener.onError !== 'function') {
      throw localError(90010, 'listener 必须提供 onResult 与 onError');
    }
  }

  _checkCompatCoreType(coreType) {
    if (!COMPAT_CORE_TYPES.includes(coreType)) {
      throw localError(90010, `不支持的声通兼容 coreType：${String(coreType)}，可选 ${COMPAT_CORE_TYPES.join('，')}`);
    }
  }

  _compatFields(params) {
    if (params != null && !isPlainObject(params)) throw localError(90010, 'params 必须是对象');
    const fields = {};
    for (const [k, v] of Object.entries(params || {})) {
      if (k === 'audio') throw localError(90010, 'params 不能包含 audio 字段');
      if (v === null || v === undefined || v === '') continue;
      if (typeof v === 'number') fields[k] = plainNumberText(v, k);
      else if (typeof v === 'object') fields[k] = JSON.stringify(v);
      else fields[k] = String(v);
    }
    return fields;
  }

  async _imagePart(image) {
    let bytes;
    let name = null;
    let type = null;
    if (isBlobLike(image)) {
      bytes = new Uint8Array(await readBlob(image));
      if (typeof image.name === 'string' && image.name) name = image.name;
      if (typeof image.type === 'string' && image.type) type = image.type;
    } else {
      bytes = toBytes(image);
      if (!bytes) throw localError(90010, 'image 必须是 Blob、File、ArrayBuffer 或 Uint8Array');
    }
    if (!bytes.length) throw localError(90010, 'image 为空');
    const [ext, sniffed] = sniffImage(bytes);
    return { name: 'image', data: bytes, filename: name || `image.${ext}`, contentType: type || sniffed };
  }

  _runPrecheck(bytes, opts, op) {
    if (opts.audioPrecheck === AudioPrecheck.OFF) return [];
    const report = precheckAudio(bytes);
    let warnings;
    try {
      warnings = applyPrecheck(report.warnings, opts.audioPrecheck);
    } catch (e) {
      if (e instanceof YuguError && opts.idempotencyKey) e.idempotencyKey = opts.idempotencyKey;
      this._logger.warn('precheck', `${op} rejected before upload: ${e.code} ${e.message}`);
      throw e;
    }
    for (const w of warnings) this._logger.warn('precheck', `${op}: ${w.code} ${w.message}`);
    return warnings;
  }

  _strictCheck(result, opts, attempts) {
    if (!opts.strictAudio) return;
    if (result.warnings.some((w) => w.code === 1001)) {
      throw YuguErrors.fromWarning(1001, {
        idempotencyKey: opts.idempotencyKey,
        recordId: result.recordId,
        attempts,
      });
    }
  }

  // ------------------------------------------------------------------ internal: auth

  async _sign(params) {
    if (!this.#hmacKey) {
      const p = importHmacKey(this.#auth.secretKey, this._crypto);
      this.#hmacKey = p;
      p.catch(() => {
        if (this.#hmacKey === p) this.#hmacKey = null;
      });
    }
    const key = await this.#hmacKey;
    return signPayloadWithKey(buildSignPayload(params), key, this._crypto);
  }

  async _token() {
    const t = this.#auth.token;
    const value = typeof t === 'function' ? await t() : t;
    if (typeof value !== 'string' || !value) throw localError(90010, 'token 提供函数返回了空值');
    return value;
  }

  async _authHeaders(signParams) {
    if (this.#auth.kind === 'signature') {
      const signature = await this._sign(signParams || {});
      return {
        'X-App-Key': this.#auth.appKey,
        'X-Timestamp': String(Math.floor(Date.now() / 1000)),
        'X-Nonce': toHex(randomBytes(8, this._crypto)),
        'X-Signature': signature,
      };
    }
    return { Authorization: `Bearer ${await this._token()}` };
  }

  async _wsUrl(path, idempotencyKey, extraQuery) {
    const q = {};
    if (extraQuery) Object.assign(q, extraQuery);
    if (idempotencyKey) q.idempotencyKey = idempotencyKey;
    if (this.#auth.kind === 'signature') {
      q.appKey = this.#auth.appKey;
      q.timestamp = String(Math.floor(Date.now() / 1000));
      q.nonce = toHex(randomBytes(8, this._crypto));
      q.signature = await this._sign(Object.assign({}, q));
    } else {
      q.token = await this._token();
    }
    return `${this._wsBaseUrl}${path}?${new URLSearchParams(q).toString()}`;
  }

  // ------------------------------------------------------------------ internal: sessions

  _sessionContext() {
    if (this._ctx) return this._ctx;
    const client = this;
    this._ctx = {
      [SESSION_CONTEXT]: true,
      logger: this._logger,
      events: this._events,
      random: this._random,
      WebSocket: this._WebSocket,
      buildUrl: (path, key, query) => client._wsUrl(path, key, query),
      getPingSupport: () => client._pingSupport,
      setPingSupport: (v) => {
        if (client._pingSupport !== v) {
          client._pingSupport = v;
          client._logger.info('ws', `server heartbeat support: ${v}`);
        }
      },
      unregister: (s) => client._sessions.delete(s),
    };
    return this._ctx;
  }

  _openSession(spec) {
    if (!this._WebSocket) {
      throw localError(90010, '缺少 WebSocket 实现。Node 中请安装 ws 包并传入 { WebSocket }');
    }
    const session = new YuguStreamSession(this._sessionContext(), spec);
    this._sessions.add(session);
    session._start();
    return session;
  }

  // ------------------------------------------------------------------ internal: HTTP

  async _execute(op, spec, opts) {
    if (!this._fetch) throw localError(90010, '缺少 fetch 实现，请传入 options.fetch');
    const policy = opts.retry;
    const key = spec.idempotent ? null : opts.idempotencyKey;
    const canRetry = spec.idempotent === true || key != null;
    if (!canRetry && policy.maxRetries > 0) this._logger.debug('retry', `${op}: no Idempotency-Key, write call is not retried`);
    const url = this._baseUrl + spec.path;
    const started = Date.now();
    const deadline = started + opts.totalTimeoutMs;
    const signals = [opts.signal, this._closeController.signal];
    let attempt = 0;
    for (;;) {
      attempt += 1;
      let err = null;
      let res = null;
      const remaining = deadline - Date.now();
      if (remaining <= 0) {
        err = localError(90002, `总超时 ${opts.totalTimeoutMs} ms 已到`);
      } else {
        callHook(this._events, 'onRequestStart', [op, spec.method, spec.path, attempt], this._logger);
        try {
          const headers = await this._headers(spec, key, opts);
          if (this._logger.enabled(LogLevel.DEBUG)) {
            this._logger.debug('http', `${spec.method} ${spec.path} attempt ${attempt} headers ${JSON.stringify(redactHeaders(headers))}`);
          } else {
            this._logger.info('http', `${spec.method} ${spec.path} attempt ${attempt}${key ? ` key=${key}` : ''}`);
          }
          res = await this._fetchOnce(url, spec, headers, Math.min(opts.timeoutMs, remaining), opts.signal);
        } catch (e) {
          err = e instanceof YuguError ? e : localError(90001, String(e && e.message), { cause: e });
        }
      }
      if (res) {
        if (res.status >= 200 && res.status < 300) {
          const parsed = this._parseSuccess(res, spec, key);
          if (!parsed.error) {
            const latency = Date.now() - started;
            this._logger.info('http', `${op} ${res.status} in ${latency} ms, attempts ${attempt}${parsed.replayed ? ', replayed' : ''}`);
            callHook(this._events, 'onRequestEnd', [op, res.status, latency, attempt, null], this._logger);
            return { json: parsed.json, replayed: parsed.replayed, attempts: attempt, status: res.status };
          }
          err = parsed.error;
        } else {
          err = YuguErrors.fromHttp(res.status, res.text, { headers: res.headers, idempotencyKey: key });
        }
      }
      err.attempts = attempt;
      if (key && !err.idempotencyKey) err.idempotencyKey = key;
      let stop =
        err instanceof RequestCancelledException ||
        err.code === 90004 ||
        !canRetry ||
        attempt > policy.maxRetries ||
        !isRetryable(err);
      let delay = 0;
      if (!stop) {
        delay = computeRetryDelay(policy, attempt, err.retryAfterMs, this._random);
        if (Date.now() + delay > deadline) {
          this._logger.debug('retry', `${op}: next retry would pass totalTimeoutMs, giving up`);
          stop = true;
        }
      }
      if (stop) {
        const latency = Date.now() - started;
        callHook(this._events, 'onRequestEnd', [op, err.httpStatus, latency, attempt, err], this._logger);
        const level = err instanceof RequestCancelledException ? 'info' : 'warn';
        this._logger[level]('http', `${op} failed after ${attempt} attempt(s): ${describeError(err)} ${err.message}`);
        throw err;
      }
      callHook(this._events, 'onRetry', [op, attempt, delay, err], this._logger);
      this._logger.warn('retry', `retry ${attempt}/${policy.maxRetries} in ${delay} ms: ${describeError(err)}`);
      try {
        await abortableDelay(delay, signals, (s) => this._abortError(s === opts.signal ? 'caller' : 'closed', key, attempt));
      } catch (e) {
        callHook(this._events, 'onRequestEnd', [op, 0, Date.now() - started, attempt, e], this._logger);
        throw e;
      }
    }
  }

  _abortError(reason, key, attempts, cause) {
    if (reason === 'caller') {
      return localError(90003, '调用方取消了请求', { idempotencyKey: key, attempts, cause });
    }
    return localError(90004, '客户端已关闭，请求被中止', { idempotencyKey: key, attempts, cause });
  }

  async _headers(spec, key, opts) {
    const h = {};
    if (opts.headers) for (const [k, v] of Object.entries(opts.headers)) h[k] = String(v);
    h.Accept = 'application/json';
    h[this._sdkHeader] = this._userAgent;
    if (spec.contentType) h['Content-Type'] = spec.contentType;
    if (key) h['Idempotency-Key'] = key;
    return Object.assign(h, await this._authHeaders(spec.signParams));
  }

  async _fetchOnce(url, spec, headers, timeoutMs, callerSignal) {
    const closeSignal = this._closeController.signal;
    if (callerSignal && callerSignal.aborted) throw this._abortError('caller');
    if (closeSignal.aborted) throw this._abortError('closed');
    const ctl = new AbortController();
    let reason = null;
    const onCaller = () => {
      reason = reason || 'caller';
      ctl.abort();
    };
    const onClose = () => {
      reason = reason || 'closed';
      ctl.abort();
    };
    if (callerSignal) callerSignal.addEventListener('abort', onCaller, { once: true });
    closeSignal.addEventListener('abort', onClose, { once: true });
    const timer = setTimeout(() => {
      reason = reason || 'timeout';
      ctl.abort();
    }, timeoutMs);
    try {
      let body;
      if (spec.body != null) body = typeof spec.body === 'string' ? spec.body : toExactArrayBuffer(spec.body);
      const res = await this._fetch(url, { method: spec.method, headers, body, signal: ctl.signal });
      const text = await res.text();
      return { status: res.status, headers: res.headers, text };
    } catch (e) {
      if (reason === 'timeout') throw localError(90002, `单次请求超时 ${timeoutMs} ms`, { cause: e });
      if (reason === 'caller' || reason === 'closed') throw this._abortError(reason, undefined, undefined, e);
      const code = e && ((e.cause && e.cause.code) || e.code);
      if (code && /CERT|SSL|TLS|SELF_SIGNED/i.test(String(code))) throw localError(90011, String(code), { cause: e });
      const detail = e && e.cause && e.cause.message ? `${e.message}：${e.cause.message}` : e && e.message;
      throw localError(90001, String(detail || e), { cause: e });
    } finally {
      clearTimeout(timer);
      if (callerSignal) callerSignal.removeEventListener('abort', onCaller);
      closeSignal.removeEventListener('abort', onClose);
    }
  }

  _parseSuccess(res, spec, key) {
    let json;
    try {
      json = JSON.parse(res.text);
    } catch (_) {
      json = undefined;
    }
    const traceId = getHeader(res.headers, 'x-trace-id');
    if (!json || typeof json !== 'object' || Array.isArray(json)) {
      return {
        error: localError(90005, `HTTP ${res.status} 响应不是 JSON 对象`, {
          httpStatus: res.status,
          rawBody: res.text,
          idempotencyKey: key,
          traceId,
        }),
      };
    }
    if (spec.envelope && json.code != null && Number(json.code) !== 0) {
      return {
        error: YuguErrors.fromCode(Number(json.code), {
          httpStatus: res.status,
          message: typeof json.message === 'string' ? json.message : undefined,
          rawBody: res.text,
          idempotencyKey: key,
          traceId,
        }),
      };
    }
    const replayed = /^true$/i.test(getHeader(res.headers, 'idempotency-replayed') || '');
    return { json, replayed };
  }
}
