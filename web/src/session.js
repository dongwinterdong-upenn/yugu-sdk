// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// Streaming evaluation session over WebSocket (DESIGN 2.5): state machine, reconnect with
// REPLAY, DROP or FAIL buffering, application heartbeat, result timeout and listener guarantees.

import { applyPrecheck, AudioPrecheck, PcmStats, precheckPcmStats } from './audio.js';
import { describeError, InvalidParameterException, isRetryable, localError, YuguError, YuguErrors } from './errors.js';
import { callHook, redactUrl } from './logger.js';
import { computeBackoffDelay } from './retry.js';
import { normalizeEvalResult } from './result.js';
import { bytesToBase64, randomBytes, toBytes, toHex, truncate, utf8Decode } from './util.js';

/** Session states. */
export const SessionState = Object.freeze({
  IDLE: 'IDLE',
  CONNECTING: 'CONNECTING',
  CONNECTED: 'CONNECTED',
  STARTED: 'STARTED',
  ENDING: 'ENDING',
  RECONNECTING: 'RECONNECTING',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
  CANCELLED: 'CANCELLED',
  CLOSED: 'CLOSED',
});

/** What happens to audio across a reconnect. */
export const AudioBufferPolicy = Object.freeze({ REPLAY: 'REPLAY', DROP: 'DROP', FAIL: 'FAIL' });

/** Heartbeat modes: "auto" pings only once the server is known to answer pong. */
export const HeartbeatMode = Object.freeze({ AUTO: 'auto', ON: true, OFF: false });

/** Replay buffer limit, same as the server per-session audio limit. */
export const DEFAULT_MAX_REPLAY_BYTES = 10 * 1024 * 1024;

/** Largest audio payload per WebSocket frame: 1 s of 16 kHz PCM16, under the 128 KB frame limit. */
const MAX_AUDIO_FRAME_BYTES = 32000;

/**
 * maxAttempts counts consecutive failures and resets after a successful reconnect. A server that
 * accepts and then always drops the session would loop forever, so a session makes at most
 * TOTAL_RECONNECT_FACTOR times maxAttempts reconnect attempts in total.
 */
const TOTAL_RECONNECT_FACTOR = 3;

/** Close codes that a reconnect cannot fix: protocol error, bad data, policy, message too big. */
const TERMINAL_CLOSE_CODES = new Set([1002, 1003, 1007, 1008, 1009, 1010]);

const S = SessionState;
const TERMINAL = new Set([S.COMPLETED, S.FAILED, S.CANCELLED, S.CLOSED]);
const CLOSE_GRACE_MS = 1000;
const WS_CLOSED = 3;
const NOOP = () => {};

const HANDLER = {
  connected: 'onConnected',
  started: 'onStarted',
  partial: 'onPartial',
  reconnecting: 'onReconnecting',
  reconnected: 'onReconnected',
  warning: 'onWarning',
  result: 'onResult',
  error: 'onError',
  closed: 'onClosed',
};

/** Marker that lets only YuguClient construct sessions. */
export const SESSION_CONTEXT = Symbol('yugu.sessionContext');

let sessionSeq = 0;

function neutralize(ws) {
  try {
    ws.onopen = NOOP;
    ws.onmessage = NOOP;
    ws.onclose = NOOP;
    ws.onerror = NOOP; // never null: the ws package throws on an unhandled error event
  } catch (_) {
    /* ignore */
  }
}

function safeClose(ws, code, reason) {
  try {
    if (ws.readyState !== WS_CLOSED) ws.close(code, reason);
  } catch (_) {
    /* already closed or closing */
  }
}

/**
 * One streaming evaluation. Created by YuguClient.streamEvaluate or streamEvaluateCompat.
 */
export class YuguStreamSession {
  constructor(ctx, spec) {
    if (!ctx || ctx[SESSION_CONTEXT] !== true) {
      throw new TypeError('YuguStreamSession 只能由 YuguClient.streamEvaluate 或 streamEvaluateCompat 创建');
    }
    sessionSeq += 1;
    this._ctx = ctx;
    this._id = `ws-${sessionSeq.toString(36)}-${toHex(randomBytes(3))}`;
    this._mode = spec.mode;
    this._coreType = spec.coreType;
    this._path = spec.path;
    this._params = spec.params;
    this._listener = spec.listener;
    this._opt = spec.options;
    this._key = spec.options.idempotencyKey;

    this._state = S.IDLE;
    this._announced = S.IDLE;
    this._queue = [];
    this._draining = false;

    this._ws = null;
    this._gen = 0;
    this._finalGen = -1;
    this._attempt = 0;
    this._totalReconnects = 0;
    this._connections = 0;
    this._reconnectPhase = false;
    this._everConnected = false;
    this._everStarted = false;
    this._endRequested = false;
    this._sentOnConn = 0;
    this._pingsSent = 0;
    this._pingSentAt = null;
    this._hbDisabled = false;

    this._replay = [];
    this._replayBytes = 0;
    this._overflow = false;
    this._pending = [];
    this._acceptedBytes = 0;
    this._droppedBytes = 0;
    this._droppedSinceLast = 0;
    this._stats = new PcmStats();
    this._localWarnings = [];
    this._warnedLate = false;

    this._timers = { connect: null, heartbeat: null, result: null, reconnect: null, close: null };
    this._result = null;
    this._error = null;
    this._closeCode = null;
    this._closeReason = '';
    this._closedDone = false;
    this._waiters = [];
    this._closedWaiters = [];
    this._abortHandler = null;
  }

  /** Session id used in logs and EventListener callbacks. */
  get id() {
    return this._id;
  }

  /** Idempotency key sent on the handshake and in the start or parameter frame. */
  get idempotencyKey() {
    return this._key;
  }

  /** "native" or "compat". */
  get mode() {
    return this._mode;
  }

  /** coreType of the session. */
  get coreType() {
    return this._coreType;
  }

  /** Current state: always the state announced by the last onStateChanged. */
  getState() {
    return this._announced;
  }

  /** True until the session reached COMPLETED, FAILED, CANCELLED or CLOSED. */
  isActive() {
    return !TERMINAL.has(this._announced);
  }

  /** Replace or remove (null) the listener. */
  setListener(listener) {
    if (listener !== null && (typeof listener !== 'object' || listener === undefined)) {
      throw localError(90010, 'listener 必须是对象或 null');
    }
    this._listener = listener;
  }

  /** Counters for diagnostics. */
  getStats() {
    return {
      state: this._announced,
      reconnectAttempts: this._totalReconnects,
      acceptedBytes: this._acceptedBytes,
      replayBufferBytes: this._replayBytes,
      droppedBytes: this._droppedBytes,
      replayOverflow: this._overflow,
      heartbeat: this._heartbeatWanted(),
    };
  }

  /**
   * Queue or send PCM16 mono audio (640 bytes per 20 ms frame recommended).
   * Returns true when the bytes are part of the evaluation, false when they were discarded
   * (after end, after the session ended, or while reconnecting with DROP).
   */
  sendAudio(chunk, options) {
    const input = toBytes(chunk);
    if (!input) throw localError(90010, 'sendAudio 需要 ArrayBuffer 或 Uint8Array');
    if (TERMINAL.has(this._state) || this._state === S.ENDING || this._endRequested) {
      if (!this._warnedLate) {
        this._warnedLate = true;
        this._ctx.logger.warn('ws', `${this._id} end 或结束之后的音频已丢弃`);
      }
      return false;
    }
    if (input.length === 0) return true;
    const item = { bytes: input.slice(), base64: !!(options && options.base64) };
    const len = item.bytes.length;
    this._stats.add(item.bytes);
    this._acceptedBytes += len;
    const policy = this._opt.audioBufferPolicy;
    if (this._state === S.STARTED) {
      if (policy === AudioBufferPolicy.REPLAY) this._pushReplay(item);
      this._sendAudioFrame(item);
      return true;
    }
    if (this._reconnectPhase) {
      if (policy === AudioBufferPolicy.REPLAY) {
        this._pushReplay(item);
        return true;
      }
      this._droppedBytes += len;
      this._droppedSinceLast += len;
      return false;
    }
    if (policy === AudioBufferPolicy.REPLAY) this._pushReplay(item);
    else this._pending.push(item);
    return true;
  }

  /**
   * Finish the audio. Runs the precheck on the accumulated audio, then sends {"cmd":"end"} as soon
   * as the session is STARTED. Idempotent.
   */
  end() {
    if (TERMINAL.has(this._state) || this._endRequested) return;
    this._endRequested = true;
    const mode = this._opt.audioPrecheck;
    if (mode !== AudioPrecheck.OFF) {
      const pc = precheckPcmStats(this._stats, this._opt.sampleRate);
      let warnings;
      try {
        warnings = applyPrecheck(pc.warnings, mode);
      } catch (e) {
        this._fail(e);
        return;
      }
      this._localWarnings = warnings;
      for (const w of warnings) {
        this._ctx.logger.warn('precheck', `${this._id} ${w.code} ${w.message}`);
        this._emit('warning', w);
      }
    }
    if (this._state === S.STARTED) this._sendEnd();
  }

  /** Cancel: no onResult or onError follows, onClosed comes last. Idempotent. */
  cancel() {
    if (TERMINAL.has(this._state)) return;
    this._clearTimers();
    this._setState(S.CANCELLED);
    this._rejectWaiters(localError(90003, '实时评测会话已取消', { idempotencyKey: this._key }));
    this._beginClose(1000, 'cancelled');
  }

  /** Close: cancels an active session, finishes a terminal one at once. Idempotent. */
  close() {
    if (!TERMINAL.has(this._state)) {
      this.cancel();
      return;
    }
    if (!this._closedDone) this._finalize(this._closeCode != null ? this._closeCode : 1000, this._closeReason || 'closed');
  }

  /** Promise of the final result; rejects with the session error or RequestCancelledException. */
  waitForResult() {
    if (this._result) return Promise.resolve(this._result);
    if (this._error) return Promise.reject(this._error);
    if (this._state === S.CANCELLED || (this._state === S.CLOSED && !this._result)) {
      return Promise.reject(localError(90003, '实时评测会话已取消', { idempotencyKey: this._key }));
    }
    return new Promise((resolve, reject) => this._waiters.push({ resolve, reject }));
  }

  // ------------------------------------------------------------------ internal: lifecycle

  _start() {
    const sig = this._opt.signal;
    if (sig) {
      if (sig.aborted) {
        Promise.resolve().then(() => this.cancel());
        return;
      }
      this._abortHandler = () => this.cancel();
      sig.addEventListener('abort', this._abortHandler, { once: true });
    }
    Promise.resolve()
      .then(() => {
        if (this._state === S.IDLE) return this._connect();
        return undefined;
      })
      .catch((e) => this._internalError(e));
  }

  _whenClosed() {
    if (this._closedDone) return Promise.resolve();
    return new Promise((resolve) => this._closedWaiters.push(resolve));
  }

  async _connect() {
    if (TERMINAL.has(this._state)) return;
    this._timers.reconnect = null;
    this._setState(S.CONNECTING);
    const gen = ++this._gen;
    this._connections += 1;
    this._pingsSent = 0;
    this._pingSentAt = null;
    this._sentOnConn = 0;
    this._timers.connect = setTimeout(() => {
      this._timers.connect = null;
      this._onTransportFailure(gen, localError(90002, `${this._opt.connectTimeoutMs} ms 内未完成连接与会话开始`));
    }, this._opt.connectTimeoutMs);
    let url;
    try {
      url = await this._ctx.buildUrl(this._path, this._key, this._opt.query);
    } catch (e) {
      if (gen === this._gen && !TERMINAL.has(this._state)) this._fail(e instanceof YuguError ? e : localError(90010, String(e && e.message), { cause: e }));
      return;
    }
    if (gen !== this._gen || TERMINAL.has(this._state)) return;
    this._ctx.logger.info('ws', `${this._id} connect ${redactUrl(url)}${this._attempt ? ` reconnect ${this._attempt}` : ''}`);
    let ws;
    try {
      ws = new this._ctx.WebSocket(url);
    } catch (e) {
      this._onTransportFailure(gen, localError(90001, `WebSocket 创建失败：${redactUrl(e && e.message)}`, { cause: e }));
      return;
    }
    this._ws = ws;
    try {
      ws.binaryType = 'arraybuffer';
    } catch (_) {
      /* some implementations make it read-only */
    }
    ws.onopen = () => this._guard(() => {
      if (gen === this._gen) this._ctx.logger.debug('ws', `${this._id} socket open`);
    });
    ws.onmessage = (ev) => this._guard(() => this._onMessage(gen, ev));
    ws.onerror = (ev) => this._guard(() => this._onSocketError(gen, ev));
    ws.onclose = (ev) => this._guard(() => this._onSocketClose(gen, ev));
  }

  _guard(fn) {
    try {
      fn();
    } catch (e) {
      this._internalError(e);
    }
  }

  _internalError(e) {
    this._ctx.logger.error('ws', `${this._id} internal error`, e);
    if (!TERMINAL.has(this._state)) this._fail(localError(90005, `SDK 内部错误：${e && e.message}`, { cause: e }));
  }

  // ------------------------------------------------------------------ internal: frames

  _onMessage(gen, ev) {
    if (gen !== this._gen || TERMINAL.has(this._state)) return;
    this._pingSentAt = null; // any inbound frame proves the connection is alive
    const data = ev ? ev.data : undefined;
    let text = null;
    if (typeof data === 'string') text = data;
    else {
      const b = toBytes(data);
      if (b) {
        try {
          text = utf8Decode(b);
        } catch (_) {
          text = null;
        }
      }
    }
    if (text == null) {
      this._ctx.logger.debug('ws', `${this._id} binary frame ignored`);
      return;
    }
    let f;
    try {
      f = JSON.parse(text);
    } catch (_) {
      this._ctx.logger.warn('ws', `${this._id} non JSON frame ignored: ${truncate(text, 120)}`);
      return;
    }
    if (!f || typeof f !== 'object' || Array.isArray(f)) return;
    this._handleFrame(gen, f, text);
  }

  _handleFrame(gen, f, text) {
    const ev = typeof f.event === 'string' ? f.event : null;
    if (ev === 'pong') {
      this._ctx.setPingSupport('supported');
      this._updateHeartbeat();
      return;
    }
    if (ev === 'connected') {
      this._onConnectedFrame();
      return;
    }
    if (ev === 'started') {
      this._onStartedFrame();
      return;
    }
    if (ev === 'error') {
      this._onErrorFrame(f);
      return;
    }
    if (ev === 'blocked' || f.blocked === true) {
      this._fail(
        new InvalidParameterException(typeof f.message === 'string' && f.message ? f.message : '参考文本未通过内容安全审查', {
          rawBody: text,
        }),
      );
      return;
    }
    if (ev === 'result' || Number(f.eof) === 1) {
      this._onResultFrame(f);
      return;
    }
    if (f.eof != null && Number(f.eof) === 0) {
      const bytes = f.result && f.result.bytes != null && Number.isFinite(Number(f.result.bytes)) ? Number(f.result.bytes) : null;
      this._emit('partial', { bytes, raw: f });
      return;
    }
    this._ctx.logger.debug('ws', `${this._id} frame ignored: ${truncate(text, 120)}`);
  }

  _onConnectedFrame() {
    if (this._state !== S.CONNECTING) return;
    this._setState(S.CONNECTED);
    if (!this._everConnected) {
      this._everConnected = true;
      this._emit('connected');
    }
    const frame = Object.assign({}, this._params);
    if (this._mode === 'native') frame.cmd = 'start';
    if (this._key) frame.idempotencyKey = this._key;
    this._sendJson(frame);
    this._updateHeartbeat();
  }

  _onStartedFrame() {
    if (this._state !== S.CONNECTED) return; // duplicate started frames are ignored
    this._clearTimer('connect');
    this._setState(S.STARTED);
    const wasReconnect = this._reconnectPhase;
    this._reconnectPhase = false;
    if (!this._everStarted) {
      this._everStarted = true;
      this._emit('started');
    }
    let replayed = 0;
    if (this._opt.audioBufferPolicy === AudioBufferPolicy.REPLAY) {
      for (const item of this._replay) {
        this._sendAudioFrame(item);
        replayed += item.bytes.length;
      }
    } else {
      const pend = this._pending;
      this._pending = [];
      for (const item of pend) this._sendAudioFrame(item);
    }
    if (wasReconnect) {
      const info = { droppedBytes: this._droppedSinceLast, replayedBytes: replayed };
      this._droppedSinceLast = 0;
      this._ctx.logger.info('ws', `${this._id} reconnected after attempt ${this._attempt}, replayed ${replayed} bytes`);
      const attempt = this._attempt;
      this._attempt = 0; // consecutive failures start again after a successful reconnect
      this._emit('reconnected', attempt, info);
      callHook(this._ctx.events, 'onReconnect', [this._id, attempt, true], this._ctx.logger);
    }
    if (this._endRequested && this._state === S.STARTED) this._sendEnd();
    else this._updateHeartbeat();
  }

  _onErrorFrame(f) {
    const code = Number(f.code) || 0;
    if (code === 0 && f.message === 'unknown cmd') {
      // A server without heartbeat support answers {"cmd":"ping"} like this. Never terminal.
      if (this._pingsSent > 0) {
        this._hbDisabled = true;
        this._pingSentAt = null;
        this._ctx.setPingSupport('unsupported');
        this._ctx.logger.info('ws', `${this._id} server does not support {"cmd":"ping"}; heartbeat off`);
        this._updateHeartbeat();
      } else {
        this._ctx.logger.warn('ws', `${this._id} unexpected "unknown cmd" frame ignored`);
      }
      return;
    }
    const err = YuguErrors.fromWsFrame(f, { idempotencyKey: this._key });
    if (isRetryable(err)) {
      this._ctx.logger.warn('ws', `${this._id} retryable server error: ${describeError(err)} ${err.message}`);
      this._retireSocket(1000, 'retryable server error');
      this._handleFailure(err);
      return;
    }
    this._fail(err);
  }

  _onResultFrame(f) {
    if (!(this._state === S.CONNECTED || this._state === S.STARTED || this._state === S.ENDING)) return;
    this._clearTimers();
    const result = normalizeEvalResult(f, {
      mode: this._mode,
      coreType: this._coreType,
      idempotencyKey: this._key,
      replayed: f.replayed === true,
      localWarnings: this._localWarnings,
    });
    this._result = result;
    this._setState(S.COMPLETED);
    this._emit('result', result);
    for (const w of this._waiters.splice(0)) w.resolve(result);
    this._beginClose(1000, 'completed');
  }

  _onSocketError(gen, ev) {
    if (gen !== this._gen || TERMINAL.has(this._state)) return;
    const detail = redactUrl((ev && (ev.message || (ev.error && ev.error.message))) || '');
    const m = /Unexpected server response: (\d{3})/.exec(detail);
    let err;
    if (m) {
      const status = Number(m[1]);
      err = YuguErrors.fromHttp(status, '', { message: `WebSocket 握手被拒绝，HTTP ${status}`, idempotencyKey: this._key });
    } else {
      const code = ev && ev.error && (ev.error.code || (ev.error.cause && ev.error.cause.code));
      if (code && /CERT|SSL|TLS|SELF_SIGNED/i.test(String(code))) {
        err = localError(90011, String(code), { cause: ev.error });
      } else {
        err = localError(90001, `WebSocket 连接错误${detail ? `：${detail}` : ''}`, { cause: ev && ev.error ? ev.error : undefined });
      }
    }
    this._closeCode = 1006;
    this._closeReason = detail;
    this._onTransportFailure(gen, err);
  }

  _onSocketClose(gen, ev) {
    const code = ev && typeof ev.code === 'number' ? ev.code : 1006;
    let reason = ev && ev.reason != null ? ev.reason : '';
    if (typeof reason !== 'string') reason = String(reason);
    if (gen === this._finalGen) {
      this._finalize(code, reason);
      return;
    }
    if (gen !== this._gen || TERMINAL.has(this._state)) return;
    this._closeCode = code;
    this._closeReason = reason;
    let err;
    if (code === 1000) {
      err = localError(90005, '服务端正常关闭了连接，但没有返回终评结果');
    } else if (TERMINAL_CLOSE_CODES.has(code) || (code >= 4000 && code <= 4999)) {
      const why = code === 1009 ? '，单帧超过服务端上限 128 KB' : '';
      err = localError(90005, `服务端以 ${code} 关闭连接${why}${reason ? `：${reason}` : ''}`);
    } else {
      err = localError(90001, `连接异常关闭，code ${code}${reason ? ` ${reason}` : ''}`);
    }
    err.idempotencyKey = this._key;
    this._ws = null;
    this._onTransportFailure(gen, err);
  }

  // ------------------------------------------------------------------ internal: failure and reconnect

  _onTransportFailure(gen, err) {
    if (gen !== this._gen || TERMINAL.has(this._state)) return;
    this._ctx.logger.warn('ws', `${this._id} ${this._state}: ${describeError(err)} ${err.message}`);
    this._retireSocket(1000, 'transport failure');
    this._handleFailure(err);
  }

  _retireSocket(code, reason) {
    const ws = this._ws;
    this._ws = null;
    this._gen += 1;
    this._clearTimer('connect');
    this._clearTimer('result');
    this._stopHeartbeat();
    if (ws) {
      neutralize(ws);
      safeClose(ws, code, reason);
    }
  }

  _handleFailure(err) {
    if (TERMINAL.has(this._state)) return;
    if (!err.idempotencyKey && this._key) err.idempotencyKey = this._key;
    const policy = this._opt.reconnect;
    const buffer = this._opt.audioBufferPolicy;
    if (buffer === AudioBufferPolicy.DROP) {
      // DROP keeps nothing: audio the failed connection received and audio still queued is lost.
      let lost = this._sentOnConn;
      for (const item of this._pending) lost += item.bytes.length;
      this._pending = [];
      this._droppedSinceLast += lost;
      this._droppedBytes += lost;
    }
    this._sentOnConn = 0;
    if (this._reconnectPhase && this._attempt > 0) {
      callHook(this._ctx.events, 'onReconnect', [this._id, this._attempt, false], this._ctx.logger);
    }
    const allowed = policy.enabled && policy.maxAttempts > 0 && buffer !== AudioBufferPolicy.FAIL && this._key != null;
    if (!allowed || !isRetryable(err)) {
      if (allowed === false && this._key == null && policy.enabled) {
        this._ctx.logger.debug('ws', `${this._id} 未带幂等键，不重连`);
      }
      this._fail(err);
      return;
    }
    if (this._attempt >= policy.maxAttempts || this._totalReconnects >= policy.maxAttempts * TOTAL_RECONNECT_FACTOR) {
      this._fail(localError(90006, `连续重连 ${this._attempt} 次，累计重连 ${this._totalReconnects} 次，最后一次原因：${err.message}`, {
        cause: err,
        attempts: this._connections,
        idempotencyKey: this._key,
      }));
      return;
    }
    if (buffer === AudioBufferPolicy.REPLAY && this._overflow) {
      this._fail(localError(90008, `重放缓冲超过 ${this._opt.maxReplayBytes} 字节`, { cause: err, idempotencyKey: this._key }));
      return;
    }
    this._attempt += 1;
    this._totalReconnects += 1;
    this._reconnectPhase = true;
    const delay = computeBackoffDelay(policy, this._attempt, this._ctx.random);
    this._setState(S.RECONNECTING);
    this._emit('reconnecting', this._attempt, delay, err);
    this._timers.reconnect = setTimeout(() => {
      this._timers.reconnect = null;
      this._connect().catch((e) => this._internalError(e));
    }, delay);
  }

  _fail(err) {
    if (TERMINAL.has(this._state)) return;
    this._clearTimers();
    if (err instanceof YuguError) {
      if (!err.idempotencyKey && this._key) err.idempotencyKey = this._key;
      if (!err.attempts) err.attempts = Math.max(1, this._connections);
    }
    this._error = err;
    this._setState(S.FAILED);
    this._emit('error', err);
    this._rejectWaiters(err);
    this._beginClose(1000, 'failed');
  }

  _rejectWaiters(err) {
    for (const w of this._waiters.splice(0)) w.reject(err);
  }

  _beginClose(code, reason) {
    const ws = this._ws;
    if (!ws || ws.readyState === WS_CLOSED) {
      this._ws = null;
      this._finalize(this._closeCode != null ? this._closeCode : code, this._closeReason || reason);
      return;
    }
    this._finalGen = this._gen;
    const gen = this._gen;
    try {
      ws.onmessage = NOOP;
      ws.onerror = NOOP;
      ws.onclose = (ev) => this._guard(() => this._onSocketClose(gen, ev));
    } catch (_) {
      /* ignore */
    }
    safeClose(ws, code, reason);
    this._timers.close = setTimeout(() => {
      this._timers.close = null;
      this._finalize(code, reason);
    }, CLOSE_GRACE_MS);
  }

  _finalize(code, reason) {
    if (this._closedDone) return;
    this._closedDone = true;
    this._clearTimers();
    this._clearTimer('close');
    if (this._ws) {
      neutralize(this._ws);
      safeClose(this._ws, 1000, 'closed');
      this._ws = null;
    }
    this._finalGen = -1;
    if (this._opt.signal && this._abortHandler) {
      try {
        this._opt.signal.removeEventListener('abort', this._abortHandler);
      } catch (_) {
        /* ignore */
      }
    }
    this._replay = [];
    this._pending = [];
    this._setState(S.CLOSED);
    this._emit('closed', code, reason || '');
    this._ctx.unregister(this);
    for (const w of this._closedWaiters.splice(0)) w();
  }

  // ------------------------------------------------------------------ internal: sending

  _pushReplay(item) {
    if (this._overflow) return;
    if (this._replayBytes + item.bytes.length > this._opt.maxReplayBytes) {
      this._overflow = true;
      this._ctx.logger.warn('ws', `${this._id} replay buffer exceeds ${this._opt.maxReplayBytes} bytes; the next reconnect fails with 90008`);
      return;
    }
    this._replay.push(item);
    this._replayBytes += item.bytes.length;
  }

  _sendAudioFrame(item) {
    if (!this._ws) return;
    const gen = this._gen;
    try {
      const b = item.bytes;
      for (let off = 0; off < b.length; off += MAX_AUDIO_FRAME_BYTES) {
        const part = b.length <= MAX_AUDIO_FRAME_BYTES ? b : b.subarray(off, off + MAX_AUDIO_FRAME_BYTES);
        if (item.base64) this._ws.send(JSON.stringify({ cmd: 'audio', data: bytesToBase64(part) }));
        else this._ws.send(part);
        this._sentOnConn += part.length;
      }
    } catch (e) {
      this._onTransportFailure(gen, localError(90001, `发送音频失败：${e && e.message}`, { cause: e }));
    }
  }

  _sendJson(obj) {
    if (!this._ws) return;
    try {
      this._ws.send(JSON.stringify(obj));
    } catch (e) {
      this._onTransportFailure(this._gen, localError(90001, `发送帧失败：${e && e.message}`, { cause: e }));
    }
  }

  _sendEnd() {
    const gen = this._gen;
    this._sendJson({ cmd: 'end' });
    if (gen !== this._gen || this._state !== S.STARTED) return;
    this._setState(S.ENDING);
    this._stopHeartbeat();
    this._timers.result = setTimeout(() => {
      this._timers.result = null;
      this._onTransportFailure(gen, localError(90007, `end 之后 ${this._opt.resultTimeoutMs} ms 未收到终评结果`));
    }, this._opt.resultTimeoutMs);
  }

  // ------------------------------------------------------------------ internal: heartbeat

  _heartbeatWanted() {
    if (!(this._state === S.CONNECTED || this._state === S.STARTED)) return false;
    if (this._hbDisabled) return false;
    const mode = this._opt.heartbeat;
    if (mode === false) return false;
    if (mode === true) return true;
    const support = this._ctx.getPingSupport();
    if (support === 'supported') return true;
    if (support === 'unsupported') return false;
    // Unknown: probe only on native sessions in STARTED. A native server without ping support
    // answers "unknown cmd" and goes on; a compat server would take the ping as a parameter frame.
    return this._mode === 'native' && this._state === S.STARTED;
  }

  _updateHeartbeat() {
    const want = this._heartbeatWanted();
    if (want && !this._timers.heartbeat) {
      const gen = this._gen;
      this._pingSentAt = null;
      this._timers.heartbeat = setInterval(() => this._guard(() => this._heartbeatTick(gen)), this._opt.heartbeatIntervalMs);
    } else if (!want) {
      this._stopHeartbeat();
    }
  }

  _stopHeartbeat() {
    if (this._timers.heartbeat) {
      clearInterval(this._timers.heartbeat);
      this._timers.heartbeat = null;
    }
    this._pingSentAt = null;
  }

  _heartbeatTick(gen) {
    if (gen !== this._gen || !this._heartbeatWanted()) {
      this._stopHeartbeat();
      return;
    }
    const now = Date.now();
    if (this._pingSentAt != null && now - this._pingSentAt >= this._opt.heartbeatTimeoutMs) {
      this._onTransportFailure(gen, localError(90002, `心跳 ${this._opt.heartbeatTimeoutMs} ms 内没有应答`));
      return;
    }
    this._sendJson({ cmd: 'ping' });
    this._pingsSent += 1;
    if (this._pingSentAt == null) this._pingSentAt = now;
  }

  // ------------------------------------------------------------------ internal: state and events

  _setState(next) {
    const prev = this._state;
    if (prev === next) return;
    this._state = next;
    this._ctx.logger.debug('ws', `${this._id} ${prev} -> ${next}`);
    this._emit('stateChanged', prev, next);
  }

  _emit(kind, ...args) {
    this._queue.push([kind, args]);
    if (this._draining) return;
    this._draining = true;
    try {
      while (this._queue.length) {
        const [k, a] = this._queue.shift();
        this._deliver(k, a);
      }
    } finally {
      this._draining = false;
    }
  }

  _deliver(kind, args) {
    if (kind === 'stateChanged') {
      this._announced = args[1];
      this._call('onStateChanged', args);
      callHook(this._ctx.events, 'onSessionStateChanged', [this._id, args[0], args[1]], this._ctx.logger);
      return;
    }
    this._call(HANDLER[kind], args);
  }

  _call(name, args) {
    const l = this._listener;
    if (!l) return;
    const fn = l[name];
    if (typeof fn !== 'function') return;
    try {
      fn.apply(l, args);
    } catch (e) {
      this._ctx.logger.error('ws', `${this._id} listener.${name} threw`, e);
    }
  }

  _clearTimer(name) {
    const t = this._timers[name];
    if (t) {
      if (name === 'heartbeat') clearInterval(t);
      else clearTimeout(t);
      this._timers[name] = null;
    }
  }

  _clearTimers() {
    this._clearTimer('connect');
    this._clearTimer('result');
    this._clearTimer('reconnect');
    this._stopHeartbeat();
  }
}
