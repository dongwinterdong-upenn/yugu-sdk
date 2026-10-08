// YuguClient: REST calls with idempotency and retries, WebSocket sessions, recorders, lifecycle.
import {
  CORE_TYPES, COMPAT_CORE_TYPES, DEFAULT_BASE_URL, DEFAULT_WS_BASE_URL, USER_AGENT,
  PATH_EVALUATE, PATH_TTS, PATH_REPORT, PATH_WS_NATIVE,
} from './constants.js';
import {
  asYuguError, fromWarningCode, localError, RequestCancelledException,
} from './errors.js';
import { Logger, EventHub, isLogLevel, maskAppKey } from './logger.js';
import { createAuth, restAuthHeaders } from './signer.js';
import { generateIdempotencyKey, validateIdempotencyKey } from './idempotency.js';
import {
  DEFAULT_RETRY_POLICY, DEFAULT_RECONNECT_POLICY, normalizeRetryPolicy, normalizeReconnectPolicy,
} from './retry.js';
import { executeCall } from './http.js';
import { buildMultipart } from './multipart.js';
import { buildJsonBody } from './json.js';
import { loadAudio, loadImage } from './audio.js';
import { analyzeContainer, applyPrecheckMode, isPrecheckMode } from './precheck.js';
import {
  parseEvalResult, parseJsonBody, isReplayedHeader, unwrapEnvelope, parseTtsData,
} from './result.js';
import { linkSignals } from './abort.js';
import { StreamSession } from './session.js';
import { YuguRecorder } from './recorder.js';
import { trimTrailingSlash, isPlainObject } from './util.js';

const TAG = 'YuguSDK:client';

const EVAL_CONFIG_FIELDS = [
  'language', 'includeReport', 'includeStandardAudio', 'includeAsrText', 'slack', 'scale', 'precision',
  'agegroup', 'toneWeight', 'refPinyin', 'phonemeOutput', 'taskType', 'paragraphNeedWordScore',
];

const BUFFER_POLICIES = ['REPLAY', 'DROP', 'FAIL'];

function globalWx() {
  return typeof wx !== 'undefined' ? wx : null; // eslint-disable-line no-undef
}

function positive(v, name, def) {
  if (v === undefined || v === null) return def;
  if (typeof v !== 'number' || !isFinite(v) || v <= 0) throw localError(90010, name + ' must be a positive number of milliseconds');
  return v;
}

function nonNegative(v, name, def) {
  if (v === undefined || v === null) return def;
  if (typeof v !== 'number' || !isFinite(v) || v < 0) throw localError(90010, name + ' must be zero or a positive number');
  return v;
}

function oneOf(v, list, name, def) {
  if (v === undefined || v === null) return def;
  if (list.indexOf(v) < 0) throw localError(90010, name + ' must be one of ' + list.join(', '));
  return v;
}

function normalizeClientOptions(o) {
  const baseUrl = trimTrailingSlash(o.baseUrl || DEFAULT_BASE_URL);
  if (!/^https?:\/\/[^/]+/i.test(baseUrl)) throw localError(90010, 'baseUrl must start with https:// or http://');
  const wsBaseUrl = trimTrailingSlash(o.wsBaseUrl || DEFAULT_WS_BASE_URL);
  if (!/^wss?:\/\/[^/]+/i.test(wsBaseUrl)) throw localError(90010, 'wsBaseUrl must start with wss:// or ws://');
  if (o.logLevel !== undefined && !isLogLevel(o.logLevel)) throw localError(90010, 'logLevel must be OFF, ERROR, WARN, INFO or DEBUG');
  if (o.audioPrecheck !== undefined && !isPrecheckMode(o.audioPrecheck)) throw localError(90010, 'audioPrecheck must be OFF, WARN or REJECT');
  if (o.logger !== undefined && typeof o.logger !== 'function') throw localError(90010, 'logger must be a function (level, tag, message, error)');
  if (o.random !== undefined && typeof o.random !== 'function') throw localError(90010, 'random must be a function returning [0, 1)');
  return Object.freeze({
    baseUrl,
    wsBaseUrl,
    connectTimeoutMs: positive(o.connectTimeoutMs, 'connectTimeoutMs', 10000),
    readTimeoutMs: positive(o.readTimeoutMs, 'readTimeoutMs', 120000),
    totalTimeoutMs: positive(o.totalTimeoutMs, 'totalTimeoutMs', 300000),
    retry: normalizeRetryPolicy(o.retry, DEFAULT_RETRY_POLICY),
    autoIdempotencyKey: o.autoIdempotencyKey !== false,
    logLevel: o.logLevel || 'WARN',
    audioPrecheck: o.audioPrecheck || 'WARN',
    strictAudio: o.strictAudio === true,
    userAgent: typeof o.userAgent === 'string' && o.userAgent ? o.userAgent : USER_AGENT,
    reconnect: normalizeReconnectPolicy(o.reconnect, DEFAULT_RECONNECT_POLICY),
    audioBufferPolicy: oneOf(o.audioBufferPolicy, BUFFER_POLICIES, 'audioBufferPolicy', 'REPLAY'),
    heartbeatIntervalMs: nonNegative(o.heartbeatIntervalMs, 'heartbeatIntervalMs', 15000),
    heartbeatTimeoutMs: positive(o.heartbeatTimeoutMs, 'heartbeatTimeoutMs', 30000),
    resultTimeoutMs: positive(o.resultTimeoutMs, 'resultTimeoutMs', 300000),
    replayBufferLimitBytes: positive(o.replayBufferLimitBytes, 'replayBufferLimitBytes', 10 * 1024 * 1024),
    random: o.random || Math.random,
  });
}

function normalizeRequestOptions(options) {
  const o = options || {};
  if (typeof o !== 'object') throw localError(90010, 'request options must be an object');
  if (o.audioPrecheck !== undefined && !isPrecheckMode(o.audioPrecheck)) throw localError(90010, 'audioPrecheck must be OFF, WARN or REJECT');
  if (o.signal !== undefined && o.signal !== null && typeof o.signal !== 'object') throw localError(90010, 'signal must be an AbortSignal like object');
  return {
    idempotencyKey: o.idempotencyKey,
    timeoutMs: positive(o.timeoutMs, 'timeoutMs', undefined),
    totalTimeoutMs: positive(o.totalTimeoutMs, 'totalTimeoutMs', undefined),
    retry: o.retry,
    signal: o.signal || null,
    audioPrecheck: o.audioPrecheck,
    strictAudio: o.strictAudio,
  };
}

function copyDefined(target, source, keys) {
  for (let i = 0; i < keys.length; i++) {
    const v = source[keys[i]];
    if (v !== undefined && v !== null) target[keys[i]] = v;
  }
  return target;
}

function mergeExtra(target, extra, what) {
  if (extra === undefined || extra === null) return target;
  if (!isPlainObject(extra)) throw localError(90010, what + ' must be a plain object');
  const keys = Object.keys(extra);
  for (let i = 0; i < keys.length; i++) {
    if (extra[keys[i]] !== undefined && extra[keys[i]] !== null && target[keys[i]] === undefined) target[keys[i]] = extra[keys[i]];
  }
  return target;
}

function requireText(v, name) {
  if (typeof v !== 'string' || !v) throw localError(90010, name + ' is required');
  return v;
}

function checkCoreType(coreType, list, name) {
  if (list.indexOf(coreType) < 0) throw localError(90010, name + ' must be one of ' + list.join(', ') + ', got ' + String(coreType));
}

function evaluateConfig(p) {
  checkCoreType(p.coreType, CORE_TYPES, 'coreType');
  const config = { coreType: p.coreType, referenceText: requireText(p.referenceText, 'referenceText') };
  copyDefined(config, p, EVAL_CONFIG_FIELDS);
  return mergeExtra(config, p.extra, 'extra');
}

function compatFields(coreType, p) {
  checkCoreType(coreType, COMPAT_CORE_TYPES, 'coreType');
  const refText = p.refText !== undefined ? p.refText : p.referenceText;
  const fields = {};
  if (refText !== undefined && refText !== null && refText !== '') fields.refText = String(refText);
  copyDefined(fields, p, ['language', 'refPinyin']);
  mergeExtra(fields, p.fields, 'fields');
  if (coreType === 'pinyin' && !fields.refPinyin) throw localError(90010, 'refPinyin is required for coreType pinyin');
  if (!fields.refText && !fields.text) throw localError(90010, 'refText is required');
  const out = {};
  Object.keys(fields).forEach((k) => {
    const v = fields[k];
    if (v === undefined || v === null || v === '') return;
    if (typeof v === 'object') throw localError(90010, 'compat field ' + k + ' must be a string, number or boolean');
    out[k] = String(v);
  });
  return out;
}

export class YuguClient {
  /**
   * options: {auth: {token} | {appKey, secretKey}, baseUrl, wsBaseUrl, connectTimeoutMs, readTimeoutMs,
   * totalTimeoutMs, retry, autoIdempotencyKey, logLevel, logger, eventListener, audioPrecheck, strictAudio,
   * userAgent, reconnect, audioBufferPolicy, heartbeatIntervalMs, heartbeatTimeoutMs, resultTimeoutMs,
   * replayBufferLimitBytes, wx, random}. See types/index.d.ts for the meaning of each option.
   */
  constructor(options) {
    if (!options || typeof options !== 'object') throw localError(90010, 'YuguClient options are required');
    const wxApi = options.wx || globalWx();
    if (!wxApi || typeof wxApi.request !== 'function') {
      throw localError(90010, 'the wx API is not available; run inside a WeChat mini program or pass options.wx');
    }
    this._wx = wxApi;
    this._auth = createAuth(options);
    this._config = normalizeClientOptions(options);
    this._logger = new Logger(this._config.logLevel, options.logger, this._auth.secrets);
    this._events = new EventHub(options.eventListener, this._logger);
    this._closed = false;
    this._inflight = new Set();
    this._sessions = new Set();
    this._recorders = new Set();
    this._logger.debug(TAG, 'client ready base=' + this._config.baseUrl + ' auth=' +
      (this._auth.mode === 'token' ? 'token' : 'appKey ' + maskAppKey(this._auth.appKey)));
  }

  /** Native whole-file evaluation, POST /api/v1/evaluate. */
  evaluate(params, options) {
    return this._run('evaluate', options, async (ro, signal, cancelError) => {
      const p = params || {};
      const config = evaluateConfig(p);
      const audioInput = p.audio !== undefined && p.audio !== null ? p.audio : p.audioPath;
      if (audioInput === undefined || audioInput === null || audioInput === '') throw localError(90010, 'audio is required');
      const key = this._resolveKey(ro);
      const audio = await loadAudio(audioInput, this._wx, { format: p.audioFormat, sampleRate: p.sampleRate, numberOfChannels: p.numberOfChannels });
      const localWarnings = this._precheck(audio, ro.audioPrecheck, key, 'evaluate');
      const configText = JSON.stringify(config);
      const parts = [
        { name: 'config', contentType: 'application/json; charset=utf-8', data: configText },
        { name: 'audio', filename: audio.filename, contentType: audio.contentType, data: audio.bytes },
      ];
      if (p.image !== undefined && p.image !== null) {
        const img = await loadImage(p.image, this._wx);
        parts.push({ name: 'image', filename: img.filename, contentType: img.contentType, data: img.bytes });
      }
      const mp = buildMultipart(parts);
      // The config part has no filename, so the platform treats it as the form parameter `config`
      // and the signed set is exactly {config: <the JSON text of that part>} (DESIGN 5.2).
      const signSet = { config: configText };
      return this._execute({
        op: 'evaluate', method: 'POST', path: PATH_EVALUATE, key, ro, signal, cancelError,
        build: () => ({ header: this._headers({ 'content-type': mp.contentType }, signSet, key), data: mp.body }),
        parse: (res, attempt) => this._evalResult(res, attempt, key, localWarnings, ro),
      });
    });
  }

  /** Shengtong compatible whole-file evaluation, POST /{coreType}. Needs appKey and secretKey auth. */
  evaluateCompat(coreType, params, options) {
    return this._run('evaluateCompat', options, async (ro, signal, cancelError) => {
      const p = params || {};
      if (this._auth.mode !== 'sign') {
        // The compat interface requires X-App-Key (CONTRACT 3.2); a token alone is answered with 401.
        throw localError(90010, 'evaluateCompat needs appKey and secretKey auth: the compat interface requires X-App-Key');
      }
      const fields = compatFields(coreType, p);
      const audioInput = p.audio !== undefined && p.audio !== null ? p.audio : p.audioPath;
      if (audioInput === undefined || audioInput === null || audioInput === '') throw localError(90010, 'audio is required');
      const key = this._resolveKey(ro);
      const audio = await loadAudio(audioInput, this._wx, { format: p.audioFormat, sampleRate: p.sampleRate, numberOfChannels: p.numberOfChannels });
      const localWarnings = this._precheck(audio, ro.audioPrecheck, key, 'evaluateCompat');
      const parts = Object.keys(fields).map((k) => ({ name: k, data: fields[k] }));
      parts.push({ name: 'audio', filename: audio.filename, contentType: audio.contentType, data: audio.bytes });
      const mp = buildMultipart(parts);
      return this._execute({
        op: 'evaluateCompat', method: 'POST', path: '/' + coreType, key, ro, signal, cancelError,
        build: () => ({ header: this._headers({ 'content-type': mp.contentType }, fields, key), data: mp.body }),
        parse: (res, attempt) => this._evalResult(res, attempt, key, localWarnings, ro),
      });
    });
  }

  /** Speech synthesis, POST /api/v1/tts/generate. */
  tts(params, options) {
    return this._run('tts', options, async (ro, signal, cancelError) => {
      const p = params || {};
      const language = p.language || 'zh-CN';
      const body = {
        text: requireText(p.text, 'text'),
        language,
        voice: p.voice || (/^zh/i.test(language) ? 'xiaoyan' : 'female'),
        format: p.format || 'mp3',
        speed: p.speed === undefined || p.speed === null ? 50 : p.speed,
        pitch: p.pitch === undefined || p.pitch === null ? 50 : p.pitch,
        volume: p.volume === undefined || p.volume === null ? 50 : p.volume,
      };
      if (p.style !== undefined && p.style !== null) body.style = p.style;
      mergeExtra(body, p.extra, 'extra');
      const key = this._resolveKey(ro);
      const json = buildJsonBody(body);
      return this._execute({
        op: 'tts', method: 'POST', path: PATH_TTS, key, ro, signal, cancelError,
        build: () => ({ header: this._headers({ 'content-type': 'application/json; charset=utf-8' }, json.signSet, key), data: json.text }),
        parse: (res, attempt) => {
          const env = parseJsonBody(res.text, { httpStatus: res.statusCode, idempotencyKey: key });
          const data = unwrapEnvelope(env, { httpStatus: res.statusCode, idempotencyKey: key, rawBody: res.text });
          const out = parseTtsData(data, this._config.baseUrl);
          out.idempotencyKey = key;
          out.replayed = isReplayedHeader(res.header);
          out.attempts = attempt;
          out.raw = env;
          return out;
        },
      });
    });
  }

  /** Report of an evaluation record, GET /api/v1/report/{recordId}. Naturally idempotent. */
  getReport(recordId, options) {
    return this._run('getReport', options, async (ro, signal, cancelError) => {
      const id = requireText(recordId, 'recordId');
      const path = PATH_REPORT + encodeURIComponent(id);
      return this._execute({
        op: 'getReport', method: 'GET', path, key: null, naturallyIdempotent: true, ro, signal, cancelError,
        build: () => ({ header: this._headers({}, {}, null), data: undefined }),
        parse: (res) => {
          const env = parseJsonBody(res.text, { httpStatus: res.statusCode });
          return unwrapEnvelope(env, { httpStatus: res.statusCode, rawBody: res.text });
        },
      });
    });
  }

  /** Native streaming evaluation over WS /api/v1/ws/evaluate. Connects on the next microtask. */
  streamEvaluate(params, listener, options) {
    this._ensureOpen('streamEvaluate');
    const p = params || {};
    const config = evaluateConfig(p);
    const frame = { cmd: 'start' };
    Object.keys(config).forEach((k) => {
      frame[k] = config[k];
    });
    return this._openSession('native', PATH_WS_NATIVE, frame, listener, options, p.sampleRate);
  }

  /** Shengtong compatible streaming evaluation over WS /{coreType}. Connects on the next microtask. */
  streamEvaluateCompat(coreType, params, listener, options) {
    this._ensureOpen('streamEvaluateCompat');
    const p = params || {};
    checkCoreType(coreType, COMPAT_CORE_TYPES, 'coreType');
    const refText = p.refText !== undefined ? p.refText : p.referenceText !== undefined ? p.referenceText : p.text;
    const frame = { refText: requireText(refText, 'refText') };
    copyDefined(frame, p, ['language', 'refPinyin']);
    if (p.realtimeFeedback !== undefined && p.realtimeFeedback !== null) frame.realtime_feedback = !!p.realtimeFeedback;
    mergeExtra(frame, p.fields, 'fields');
    if (coreType === 'pinyin' && !frame.refPinyin) throw localError(90010, 'refPinyin is required for coreType pinyin');
    return this._openSession('compat', '/' + coreType, frame, listener, options, p.sampleRate);
  }

  /** Recorder bound to this client; released by client.close(). */
  createRecorder(options) {
    this._ensureOpen('createRecorder');
    const rec = new YuguRecorder(options, {
      wx: this._wx,
      logger: this._logger,
      onDispose: (r) => this._recorders.delete(r),
    });
    this._recorders.add(rec);
    return rec;
  }

  /**
   * Closes the client: cancels open sessions (onClosed still fires), aborts running requests with
   * IllegalSessionStateException 90004 and releases recorders created by createRecorder. Idempotent.
   */
  close() {
    if (this._closed) return;
    this._closed = true;
    const inflight = Array.from(this._inflight);
    this._inflight.clear();
    for (let i = 0; i < inflight.length; i++) inflight[i].abort('closed');
    const sessions = Array.from(this._sessions);
    this._sessions.clear();
    for (let i = 0; i < sessions.length; i++) sessions[i].cancel();
    const recorders = Array.from(this._recorders);
    this._recorders.clear();
    for (let i = 0; i < recorders.length; i++) recorders[i].release();
    this._logger.debug(TAG, 'client closed');
  }

  isClosed() {
    return this._closed;
  }

  // ------------------------------------------------------------------ internals

  _ensureOpen(op) {
    if (this._closed) throw localError(90004, 'client is closed', { op });
  }

  _resolveKey(ro) {
    if (ro.idempotencyKey !== undefined && ro.idempotencyKey !== null) return validateIdempotencyKey(ro.idempotencyKey);
    if (this._config.autoIdempotencyKey) return generateIdempotencyKey();
    return null;
  }

  _headers(extra, signSet, key) {
    const h = {};
    Object.keys(extra).forEach((k) => {
      h[k] = extra[k];
    });
    const auth = restAuthHeaders(this._auth, signSet);
    Object.keys(auth).forEach((k) => {
      h[k] = auth[k];
    });
    // wx.request cannot set User-Agent, so the SDK identifies itself with X-Yugu-SDK.
    h['X-Yugu-SDK'] = this._config.userAgent;
    if (key) h['Idempotency-Key'] = key;
    return h;
  }

  _precheck(audio, mode, key, op) {
    const m = mode || this._config.audioPrecheck;
    if (m === 'OFF') return [];
    const report = analyzeContainer(audio.bytes, audio.format);
    const warnings = applyPrecheckMode(report.warnings, m, { idempotencyKey: key, op });
    if (warnings.length) {
      this._logger.info(TAG, op + ': audio precheck warnings ' + warnings.map((w) => w.code).join(','));
    }
    return warnings;
  }

  _evalResult(res, attempt, key, localWarnings, ro) {
    const json = parseJsonBody(res.text, { httpStatus: res.statusCode, idempotencyKey: key });
    const r = parseEvalResult(json);
    r.idempotencyKey = key;
    r.replayed = r.replayed || isReplayedHeader(res.header);
    r.localWarnings = localWarnings.slice();
    r.attempts = attempt;
    const strict = ro.strictAudio !== undefined ? ro.strictAudio === true : this._config.strictAudio;
    if (strict) {
      for (let i = 0; i < r.warnings.length; i++) {
        if (r.warnings[i].code === 1001) {
          throw fromWarningCode(1001, { idempotencyKey: key, recordId: r.recordId, httpStatus: res.statusCode });
        }
      }
    }
    return r;
  }

  _run(op, options, body) {
    if (this._closed) return Promise.reject(localError(90004, 'client is closed', { op }));
    let ro;
    try {
      ro = normalizeRequestOptions(options);
    } catch (e) {
      return Promise.reject(decorate(e, op));
    }
    const link = linkSignals(ro.signal, null);
    this._inflight.add(link);
    const cancelError = () => (this._closed
      ? localError(90004, 'client closed while the call was running', { op })
      : new RequestCancelledException('call cancelled by the caller', { code: 90003, op }));
    const done = () => {
      link.dispose();
      this._inflight.delete(link);
    };
    let p;
    try {
      p = Promise.resolve(body(ro, link.signal, cancelError));
    } catch (e) {
      p = Promise.reject(e);
    }
    return p.then((v) => {
      done();
      return v;
    }, (e) => {
      done();
      throw decorate(e, op);
    });
  }

  _execute(s) {
    const policy = normalizeRetryPolicy(s.ro.retry, this._config.retry);
    return executeCall({ wx: this._wx, logger: this._logger, events: this._events, random: this._config.random }, {
      op: s.op,
      method: s.method,
      path: s.path,
      url: this._config.baseUrl + s.path,
      key: s.key,
      retryAllowed: s.naturallyIdempotent === true || !!s.key,
      policy,
      attemptTimeoutMs: s.ro.timeoutMs || this._config.readTimeoutMs,
      totalTimeoutMs: s.ro.totalTimeoutMs || this._config.totalTimeoutMs,
      signal: s.signal,
      cancelError: s.cancelError,
      build: s.build,
      parse: s.parse,
    });
  }

  _openSession(mode, path, frame, listener, options, sampleRate) {
    if (!listener || typeof listener !== 'object' || typeof listener.onResult !== 'function' || typeof listener.onError !== 'function') {
      throw localError(90010, 'a listener with onResult and onError is required');
    }
    const o = options || {};
    if (typeof o !== 'object') throw localError(90010, 'stream options must be an object');
    const c = this._config;
    if (o.audioPrecheck !== undefined && !isPrecheckMode(o.audioPrecheck)) throw localError(90010, 'audioPrecheck must be OFF, WARN or REJECT');
    if (o.query !== undefined && !isPlainObject(o.query)) throw localError(90010, 'query must be a plain object');
    const key = this._resolveKey({ idempotencyKey: o.idempotencyKey });
    const cfg = {
      mode,
      path,
      firstFrame: frame,
      idempotencyKey: key,
      reconnect: normalizeReconnectPolicy(o.reconnect, c.reconnect),
      bufferPolicy: oneOf(o.audioBufferPolicy, BUFFER_POLICIES, 'audioBufferPolicy', c.audioBufferPolicy),
      heartbeatIntervalMs: nonNegative(o.heartbeatIntervalMs, 'heartbeatIntervalMs', c.heartbeatIntervalMs),
      heartbeatTimeoutMs: positive(o.heartbeatTimeoutMs, 'heartbeatTimeoutMs', c.heartbeatTimeoutMs),
      resultTimeoutMs: positive(o.resultTimeoutMs, 'resultTimeoutMs', c.resultTimeoutMs),
      connectTimeoutMs: positive(o.connectTimeoutMs, 'connectTimeoutMs', c.connectTimeoutMs),
      replayBufferLimitBytes: positive(o.replayBufferLimitBytes, 'replayBufferLimitBytes', c.replayBufferLimitBytes),
      audioPrecheck: o.audioPrecheck || c.audioPrecheck,
      sampleRate: positive(sampleRate, 'sampleRate', 16000),
      query: o.query || null,
    };
    const ctx = {
      wx: this._wx,
      logger: this._logger,
      events: this._events,
      auth: this._auth,
      wsBaseUrl: c.wsBaseUrl,
      userAgent: c.userAgent,
      random: c.random,
      onClosed: (s) => this._sessions.delete(s),
    };
    const session = new StreamSession(ctx, cfg, listener);
    this._sessions.add(session);
    return session;
  }
}

function decorate(e, op) {
  const err = asYuguError(e);
  if (!err.op) err.op = op;
  return err;
}
