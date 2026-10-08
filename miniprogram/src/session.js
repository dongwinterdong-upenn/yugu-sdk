// WebSocket evaluation session over wx.connectSocket (DESIGN 2.5): state machine, listener
// guarantees, reconnect with REPLAY, DROP or FAIL audio buffering, application heartbeat and
// result timeout.
//
// Listener guarantees:
// - exactly one of onResult or onError per session, unless cancel() came first, then neither;
// - onClosed is always the last callback and is delivered once;
// - callbacks of one session never nest: a callback that calls back into the session gets the
//   resulting callbacks after it returns, in order;
// - getState() equals the state announced by the last onStateChanged.
//
// Heartbeat (mini programs cannot send WebSocket protocol pings):
// - on every connection the SDK sends one probe {"cmd":"ping"} right after "connected", before the
//   start or parameter frame, so a server that misreads it cannot lose audio;
// - the periodic heartbeat is armed only after the server answered that probe with {"event":"pong"};
//   then a ping goes out every heartbeatIntervalMs while CONNECTED or STARTED, never while ENDING,
//   and heartbeatTimeoutMs without any frame counts as a transport failure;
// - a server that answers the probe with {"event":"error","message":"unknown cmd"}, or answers the
//   start frame first, has no heartbeat support: heartbeat stays off for the rest of the session and
//   the session relies on the connect timeout, close detection and resultTimeoutMs.
import { PcmStats, analyzePcmStats, applyPrecheckMode } from './precheck.js';
import {
  asYuguError, fromWsErrorFrame, fromWxFail, isRetryable, localError, ProtocolViolationException,
} from './errors.js';
import { computeBackoffDelay } from './retry.js';
import { parseEvalResult } from './result.js';
import { wsAuthQuery } from './signer.js';
import { redactUrl } from './logger.js';
import { describeError } from './http.js';
import { MAX_AUDIO_FRAME_BYTES } from './constants.js';
import { copyBytes, encodeQuery, exactArrayBuffer, microtask, randomHex, toInt, toUint8, truncate } from './util.js';

const TAG = 'YuguSDK:ws';

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

export const AudioBufferPolicy = Object.freeze({ REPLAY: 'REPLAY', DROP: 'DROP', FAIL: 'FAIL' });

const ALLOWED = {
  IDLE: ['CONNECTING', 'FAILED', 'CANCELLED'],
  CONNECTING: ['CONNECTED', 'RECONNECTING', 'FAILED', 'CANCELLED'],
  CONNECTED: ['STARTED', 'RECONNECTING', 'FAILED', 'CANCELLED'],
  STARTED: ['ENDING', 'COMPLETED', 'RECONNECTING', 'FAILED', 'CANCELLED'],
  ENDING: ['COMPLETED', 'RECONNECTING', 'FAILED', 'CANCELLED'],
  RECONNECTING: ['CONNECTING', 'FAILED', 'CANCELLED'],
  COMPLETED: ['CLOSED'],
  FAILED: ['CLOSED'],
  CANCELLED: ['CLOSED'],
  CLOSED: [],
};

// Close codes a reconnect cannot fix: the server refused the session itself (DESIGN 2.5). 1000 before
// the final result is handled the same way.
const FATAL_CLOSE_CODES = [1002, 1003, 1007, 1008, 1009, 1010];

function isFatalCloseCode(code) {
  return code === 1000 || FATAL_CLOSE_CODES.indexOf(code) >= 0 || (code >= 4000 && code <= 4999);
}

function isActiveState(s) {
  return s !== 'COMPLETED' && s !== 'FAILED' && s !== 'CANCELLED' && s !== 'CLOSED';
}

let sessionSeq = 0;

export class StreamSession {
  /**
   * Created by YuguClient.streamEvaluate and streamEvaluateCompat; not meant to be constructed
   * directly. Connects on the next microtask.
   */
  constructor(ctx, cfg, listener) {
    this._ctx = ctx;
    this._cfg = cfg;
    sessionSeq += 1;
    this.id = 'ws-' + sessionSeq + '-' + randomHex(3);
    this.idempotencyKey = cfg.idempotencyKey || null;
    this.mode = cfg.mode;
    this._state = 'IDLE';
    this._announced = 'IDLE';
    this._listener = listener && typeof listener === 'object' ? listener : {};
    this._observers = [];
    this._queue = [];
    this._draining = false;
    this._closeQueued = false;
    this._cancelled = false;
    this._gen = 0;
    this._conn = null;
    this._timers = { connect: null, result: null, reconnect: null, hbInterval: null, hbWatchdog: null };
    this._hbDisabled = !(cfg.heartbeatIntervalMs > 0);
    this._hbConfirmed = false;
    this._hbStatus = this._hbDisabled ? 'disabled' : 'probing';
    this._probeOutstanding = false;
    this._everConnected = false;
    this._everStarted = false;
    this._inReconnect = false;
    this._reconnectAttempt = 0;
    this._totalReconnects = 0;
    // Every reconnect attempt of this session, successful or not. At most 3 x maxAttempts (DESIGN 2.5),
    // so a server that keeps accepting and then dropping the session cannot loop forever.
    this._totalAttempts = 0;
    this._lastClose = null;
    this._replay = [];
    this._replayBytes = 0;
    this._overflow = false;
    this._pending = [];
    this._droppedBytes = 0;
    this._totalDroppedBytes = 0;
    this._stats = new PcmStats();
    this._bytesAccepted = 0;
    this._endRequested = false;
    this._endSent = false;
    this._localWarnings = [];
    this._ignoredAudioLogged = false;
    microtask(() => this._connect());
  }

  /** Current state, equal to the state announced by the last onStateChanged. */
  getState() {
    return this._announced;
  }

  /** True until the session reaches COMPLETED, FAILED, CANCELLED or CLOSED. */
  isActive() {
    return isActiveState(this._announced);
  }

  getStats() {
    return {
      bytesAccepted: this._bytesAccepted,
      reconnects: this._totalReconnects,
      reconnectAttempts: this._totalAttempts,
      droppedBytes: this._totalDroppedBytes,
      replayBufferBytes: this._replayBytes,
      heartbeat: this._hbStatus,
    };
  }

  /** Replaces the listener. null removes it; the session keeps running without callbacks. */
  setListener(listener) {
    this._listener = listener && typeof listener === 'object' ? listener : {};
  }

  removeListener() {
    this.setListener(null);
  }

  /**
   * Queues or sends one chunk of 16 bit little-endian PCM (16000 Hz mono unless the session says
   * otherwise). Chunks above 32000 bytes are sent as several frames. Returns false and ignores the
   * chunk once end() was called or the session is over.
   */
  sendAudio(chunk) {
    const u8 = toUint8(chunk);
    if (!u8) throw localError(90010, 'sendAudio expects an ArrayBuffer or a typed array');
    if (u8.length > MAX_AUDIO_FRAME_BYTES) {
      // The platform closes frames above 128 KB with 1009; large chunks go out as several frames.
      let accepted = true;
      for (let off = 0; off < u8.length && accepted; off += MAX_AUDIO_FRAME_BYTES) {
        accepted = this.sendAudio(u8.subarray(off, off + MAX_AUDIO_FRAME_BYTES));
      }
      return accepted;
    }
    const s = this._state;
    if (!isActiveState(s) || this._endRequested) {
      if (!this._ignoredAudioLogged) {
        this._ignoredAudioLogged = true;
        this._ctx.logger.warn(TAG, this.id + ': sendAudio ignored after ' + (isActiveState(s) ? 'end()' : 'the session ended (' + s + ')'));
      }
      return false;
    }
    if (u8.length === 0) return true;
    this._stats.add(u8);
    this._bytesAccepted += u8.length;
    const policy = this._cfg.bufferPolicy;
    if (policy === 'REPLAY') {
      if (!this._overflow) {
        const copy = copyBytes(u8);
        this._replay.push(copy);
        this._replayBytes += copy.length;
        if (this._replayBytes > this._cfg.replayBufferLimitBytes) {
          this._overflow = true;
          this._replay = [];
          this._replayBytes = 0;
          this._ctx.logger.warn(TAG, this.id + ': replay buffer passed ' + this._cfg.replayBufferLimitBytes + ' bytes; a later reconnect fails with 90008');
          if (s !== 'STARTED') {
            this._fail(localError(90008, 'replay buffer overflow while waiting for the server session'));
            return false;
          }
          this._transmit(u8);
          return true;
        }
        if (s === 'STARTED') this._transmit(copy);
        return true;
      }
      if (s === 'STARTED') this._transmit(u8);
      return true;
    }
    if (s === 'STARTED') {
      this._transmit(u8);
      return true;
    }
    if (policy === 'DROP' && this._inReconnect) {
      this._droppedBytes += u8.length;
      this._totalDroppedBytes += u8.length;
      return true;
    }
    this._pending.push(copyBytes(u8));
    return true;
  }

  /**
   * Ends the audio. The end frame goes out now when STARTED, else right after the server session
   * starts. Runs the precheck on the accumulated audio first. Returns false when already ended or
   * when the session is over.
   */
  end() {
    if (!isActiveState(this._state) || this._endRequested) return false;
    this._endRequested = true;
    const mode = this._cfg.audioPrecheck;
    if (mode !== 'OFF') {
      const report = analyzePcmStats(this._stats, this._cfg.sampleRate, 1);
      let warnings;
      try {
        warnings = applyPrecheckMode(report.warnings, mode, { idempotencyKey: this.idempotencyKey, op: this._op() });
      } catch (e) {
        this._fail(e);
        return false;
      }
      this._localWarnings = warnings;
      for (let i = 0; i < warnings.length; i++) this._emit('onWarning', [warnings[i]]);
    }
    if (this._state === 'STARTED') this._sendEnd();
    return true;
  }

  /** Stops the session without onResult or onError; onClosed still follows. Idempotent. */
  cancel() {
    if (!isActiveState(this._state)) return;
    this._cancelled = true;
    this._clearTimer('reconnect');
    this._detachConnection('cancelled');
    this._batch(() => {
      this._setState('CANCELLED');
      this._finishClosed(1000, 'cancelled');
    });
  }

  /** Same as cancel() while active, no-op afterwards. Idempotent. */
  close() {
    this.cancel();
  }

  // ------------------------------------------------------------------ internals

  _op() {
    return this.mode === 'compat' ? 'streamEvaluateCompat' : 'streamEvaluate';
  }

  _addObserver(fn) {
    this._observers.push(fn);
  }

  _enqueue(fn) {
    this._queue.push(fn);
    if (!this._draining) this._drain();
  }

  _drain() {
    this._draining = true;
    try {
      while (this._queue.length) {
        const next = this._queue.shift();
        try {
          next();
        } catch (e) {
          this._ctx.logger.error(TAG, this.id + ': internal callback error', e);
        }
      }
    } finally {
      this._draining = false;
    }
  }

  // Queues the callbacks of one logical transition together, so a listener that calls back into the
  // session sees its own effects only after the whole transition was announced.
  _batch(fn) {
    if (this._draining) {
      fn();
      return;
    }
    this._draining = true;
    try {
      fn();
    } finally {
      this._draining = false;
    }
    this._drain();
  }

  _call(name, args) {
    const l = this._listener;
    const fn = l && l[name];
    if (typeof fn !== 'function') return;
    try {
      fn.apply(l, args);
    } catch (e) {
      this._ctx.logger.error(TAG, this.id + ': listener.' + name + ' threw', e);
    }
  }

  _emit(name, args) {
    if (this._closeQueued) return;
    this._enqueue(() => this._call(name, args));
  }

  _emitEvent(name, args) {
    const events = this._ctx.events;
    this._enqueue(() => events.emit.apply(events, [name].concat(args)));
  }

  _notify(event, a, b) {
    const list = this._observers.slice();
    this._enqueue(() => {
      for (let i = 0; i < list.length; i++) {
        try {
          list[i](event, a, b);
        } catch (e) {
          this._ctx.logger.error(TAG, this.id + ': observer threw', e);
        }
      }
    });
  }

  _setState(next) {
    const prev = this._state;
    if (prev === next) return;
    if ((ALLOWED[prev] || []).indexOf(next) < 0) {
      this._ctx.logger.error(TAG, this.id + ': unexpected transition ' + prev + ' -> ' + next);
    }
    this._state = next;
    const events = this._ctx.events;
    this._enqueue(() => {
      this._announced = next;
      this._call('onStateChanged', [prev, next]);
      events.emit('onSessionStateChanged', this.id, prev, next);
    });
    this._notify('state', prev, next);
  }

  _clearTimer(name) {
    const t = this._timers[name];
    if (t === null) return;
    if (name === 'hbInterval') clearInterval(t);
    else clearTimeout(t);
    this._timers[name] = null;
  }

  _connect() {
    const s = this._state;
    if (s !== 'IDLE' && s !== 'RECONNECTING') return;
    const gen = ++this._gen;
    const query = {};
    const extra = this._cfg.query || {};
    Object.keys(extra).forEach((k) => {
      query[k] = extra[k];
    });
    if (this.idempotencyKey) query.idempotencyKey = this.idempotencyKey;
    const q = wsAuthQuery(this._ctx.auth, query);
    const url = this._ctx.wsBaseUrl + this._cfg.path + '?' + encodeQuery(q);
    const conn = { gen, task: null, txAudioBytes: 0 };
    this._conn = conn;
    this._lastClose = null;
    this._setState('CONNECTING');
    if (gen !== this._gen) return;
    this._ctx.logger.debug(TAG, this.id + ': connect ' + redactUrl(url));
    const timeoutMs = this._cfg.connectTimeoutMs;
    this._timers.connect = setTimeout(() => {
      this._timers.connect = null;
      if (gen === this._gen && (this._state === 'CONNECTING' || this._state === 'CONNECTED')) {
        this._onTransportFailure(localError(90002, 'connect timeout: session not started within ' + timeoutMs + ' ms'));
      }
    }, timeoutMs);
    let task;
    try {
      task = this._ctx.wx.connectSocket({
        url,
        header: { 'X-Yugu-SDK': this._ctx.userAgent },
        timeout: timeoutMs,
        fail: (e) => {
          if (gen === this._gen) this._onTransportFailure(fromWxFail(e, 'connectSocket'));
        },
      });
    } catch (e) {
      this._onTransportFailure(fromWxFail(e, 'connectSocket'));
      return;
    }
    if (gen !== this._gen) return;
    if (!task) {
      this._onTransportFailure(localError(90001, 'wx.connectSocket returned no SocketTask'));
      return;
    }
    conn.task = task;
    task.onOpen(() => {
      if (gen === this._gen) this._ctx.logger.debug(TAG, this.id + ': socket open');
    });
    task.onMessage((m) => {
      if (gen === this._gen) this._onFrame(m ? m.data : undefined);
    });
    task.onError((e) => {
      if (gen === this._gen) this._onTransportFailure(fromWxFail(e, 'socket'));
    });
    task.onClose((c) => {
      if (gen === this._gen) this._onSocketClose(c && typeof c.code === 'number' ? c.code : 1006, (c && c.reason) || '');
    });
  }

  _send(conn, data) {
    if (!conn || conn !== this._conn || !conn.task) return;
    const gen = conn.gen;
    try {
      conn.task.send({
        data,
        fail: (e) => {
          if (gen === this._gen) this._onTransportFailure(fromWxFail(e, 'socket send'));
        },
      });
    } catch (e) {
      if (gen === this._gen) this._onTransportFailure(fromWxFail(e, 'socket send'));
    }
  }

  _sendJson(conn, obj) {
    this._send(conn, JSON.stringify(obj));
  }

  _transmit(u8) {
    const conn = this._conn;
    if (!conn) return;
    conn.txAudioBytes += u8.length;
    this._send(conn, exactArrayBuffer(u8));
  }

  _onFrame(data) {
    if (typeof data !== 'string') {
      this._ctx.logger.debug(TAG, this.id + ': binary frame from server ignored');
      return;
    }
    let f = null;
    try {
      f = JSON.parse(data);
    } catch (e) {
      f = null;
    }
    if (!f || typeof f !== 'object' || Array.isArray(f)) {
      this._fail(new ProtocolViolationException('server frame is not a JSON object', { code: 90005, rawBody: truncate(data, 4096) }));
      return;
    }
    if (this._timers.hbInterval !== null) this._armWatchdog();
    const ev = f.event;
    if (ev === 'pong') return this._onPong();
    if (ev === 'connected') return this._onConnectedFrame();
    if (ev === 'started') return this._onStartedFrame();
    if (ev === 'error') return this._onErrorFrame(f);
    if (ev === 'result' || (f.eof !== undefined && toInt(f.eof) === 1)) return this._onResultFrame(f);
    if (f.eof !== undefined && toInt(f.eof) === 0) return this._onPartialFrame(f);
    this._ctx.logger.debug(TAG, this.id + ': frame ignored: ' + (ev || 'no event'));
    return undefined;
  }

  _onConnectedFrame() {
    if (this._state !== 'CONNECTING') {
      this._ctx.logger.debug(TAG, this.id + ': duplicate connected frame ignored');
      return;
    }
    const conn = this._conn;
    this._batch(() => {
      this._setState('CONNECTED');
      if (!this._everConnected) {
        this._everConnected = true;
        this._emit('onConnected', []);
      }
    });
    if (this._conn !== conn) return;
    if (!this._hbDisabled) {
      this._probeOutstanding = true;
      this._sendJson(conn, { cmd: 'ping' });
    }
    if (this._conn !== conn) return;
    const frame = {};
    const first = this._cfg.firstFrame;
    Object.keys(first).forEach((k) => {
      frame[k] = first[k];
    });
    if (this.idempotencyKey) frame.idempotencyKey = this.idempotencyKey;
    this._sendJson(conn, frame);
  }

  _onStartedFrame() {
    if (this._state !== 'CONNECTED') {
      this._ctx.logger.debug(TAG, this.id + ': duplicate started frame ignored');
      return;
    }
    const conn = this._conn;
    this._clearTimer('connect');
    if (this._probeOutstanding) {
      this._probeOutstanding = false;
      this._disableHeartbeat('the server started the session without answering the ping probe');
    }
    // Audio queued before the start, or kept for replay, goes out before STARTED is announced, so
    // a listener calling end() from a callback can never get the end frame ahead of that audio.
    const replay = this._cfg.bufferPolicy === 'REPLAY';
    const list = replay ? this._replay.slice() : this._pending;
    if (!replay) this._pending = [];
    for (let i = 0; i < list.length; i++) {
      if (this._conn !== conn) return;
      this._transmit(list[i]);
    }
    if (this._conn !== conn) return;
    if (this._hbConfirmed) this._startHeartbeat();
    let reconnected = null;
    if (this._inReconnect) {
      reconnected = {
        attempt: this._reconnectAttempt,
        info: {
          droppedBytes: this._droppedBytes,
          totalDroppedBytes: this._totalDroppedBytes,
          replayedBytes: replay ? this._replayBytes : 0,
        },
      };
      this._inReconnect = false;
      this._reconnectAttempt = 0;
      this._droppedBytes = 0;
      this._totalReconnects += 1;
      this._ctx.logger.info(TAG, this.id + ': reconnected on attempt ' + reconnected.attempt);
    }
    this._batch(() => {
      this._setState('STARTED');
      if (reconnected) {
        this._emitEvent('onReconnect', [this.id, reconnected.attempt, true]);
        this._emit('onReconnected', [reconnected.attempt, reconnected.info]);
      } else if (!this._everStarted) {
        this._everStarted = true;
        this._emit('onStarted', []);
      }
    });
    if (this._endRequested && this._conn === conn && this._state === 'STARTED') this._sendEnd();
  }

  _sendEnd() {
    const conn = this._conn;
    this._endSent = true;
    this._stopHeartbeat();
    this._armResultTimer();
    this._sendJson(conn, { cmd: 'end' });
    if (this._conn !== conn) return;
    this._setState('ENDING');
  }

  _onPong() {
    this._probeOutstanding = false;
    if (this._hbDisabled || this._hbConfirmed) return;
    this._hbConfirmed = true;
    this._hbStatus = 'active';
    this._ctx.logger.debug(TAG, this.id + ': heartbeat confirmed by the server');
    if (this._state === 'CONNECTED' || this._state === 'STARTED') this._startHeartbeat();
  }

  _disableHeartbeat(reason) {
    if (this._hbDisabled) return;
    this._hbDisabled = true;
    this._hbConfirmed = false;
    this._hbStatus = 'disabled';
    this._stopHeartbeat();
    this._ctx.logger.info(TAG, this.id + ': heartbeat off for this session: ' + reason);
  }

  _startHeartbeat() {
    this._stopHeartbeat();
    const conn = this._conn;
    if (this._hbDisabled || !this._hbConfirmed || !conn) return;
    this._timers.hbInterval = setInterval(() => {
      if (this._conn !== conn) return;
      if (this._state !== 'CONNECTED' && this._state !== 'STARTED') return;
      this._sendJson(conn, { cmd: 'ping' });
    }, this._cfg.heartbeatIntervalMs);
    this._armWatchdog();
  }

  _armWatchdog() {
    this._clearTimer('hbWatchdog');
    const conn = this._conn;
    if (!conn) return;
    const ms = this._cfg.heartbeatTimeoutMs;
    this._timers.hbWatchdog = setTimeout(() => {
      this._timers.hbWatchdog = null;
      if (this._conn !== conn) return;
      if (this._state !== 'CONNECTED' && this._state !== 'STARTED') return;
      this._onTransportFailure(localError(90002, 'heartbeat timeout: no frame from the server for ' + ms + ' ms'));
    }, ms);
  }

  _stopHeartbeat() {
    this._clearTimer('hbInterval');
    this._clearTimer('hbWatchdog');
  }

  _armResultTimer() {
    this._clearTimer('result');
    const conn = this._conn;
    if (!conn) return;
    const ms = this._cfg.resultTimeoutMs;
    this._timers.result = setTimeout(() => {
      this._timers.result = null;
      if (this._conn !== conn || this._state !== 'ENDING') return;
      this._onTransportFailure(localError(90007, 'no final result within ' + ms + ' ms after end()'));
    }, ms);
  }

  _onErrorFrame(f) {
    const code = toInt(f.code);
    if (!code && this._probeOutstanding && /unknown cmd/i.test(String(f.message || ''))) {
      this._probeOutstanding = false;
      this._disableHeartbeat('the server does not know {"cmd":"ping"}');
      return;
    }
    const err = fromWsErrorFrame(f, { idempotencyKey: this.idempotencyKey, op: this._op() });
    if (isRetryable(err)) this._onTransportFailure(err);
    else this._fail(err);
  }

  _onResultFrame(f) {
    if (this._state !== 'STARTED' && this._state !== 'ENDING') {
      this._ctx.logger.debug(TAG, this.id + ': result frame ignored in ' + this._state);
      return;
    }
    const result = parseEvalResult(f);
    result.idempotencyKey = this.idempotencyKey;
    result.replayed = f.replayed === true;
    result.localWarnings = this._localWarnings.slice();
    result.attempts = this._totalReconnects + 1;
    this._detachConnection('completed');
    this._batch(() => {
      this._setState('COMPLETED');
      if (!this._cancelled) this._emit('onResult', [result]);
      this._finishClosed(1000, 'completed');
    });
  }

  _onPartialFrame(f) {
    if (this._state !== 'STARTED' && this._state !== 'ENDING') return;
    const bytes = f.result && typeof f.result === 'object' ? toInt(f.result.bytes) : 0;
    this._emit('onPartial', [{ bytes, raw: f }]);
  }

  _onSocketClose(code, reason) {
    this._lastClose = { code, reason };
    if (!isActiveState(this._state) || this._state === 'RECONNECTING') return;
    if (isFatalCloseCode(code)) {
      const what = code === 1000 ? 'the server closed the session before the final result' : 'the server closed the session with code ' + code;
      this._fail(new ProtocolViolationException(what + (reason ? ' (' + reason + ')' : ''), { code: 90005 }));
      return;
    }
    this._onTransportFailure(localError(90001, 'connection closed with code ' + code + (reason ? ' (' + reason + ')' : '')));
  }

  _onTransportFailure(error) {
    const s = this._state;
    if (!isActiveState(s) || s === 'RECONNECTING' || s === 'IDLE') return;
    const err = asYuguError(error);
    if (!err.idempotencyKey) err.idempotencyKey = this.idempotencyKey;
    if (!err.op) err.op = this._op();
    const conn = this._conn;
    if (this._inReconnect) this._emitEvent('onReconnect', [this.id, this._reconnectAttempt, false]);
    this._detachConnection('reconnect');
    const policy = this._cfg.bufferPolicy;
    const rp = this._cfg.reconnect;
    if (policy === 'FAIL' || !rp.enabled) {
      this._fail(err);
      return;
    }
    if (policy === 'DROP' && this._endSent) {
      this._fail(err);
      return;
    }
    if (policy === 'REPLAY' && this._overflow) {
      this._fail(localError(90008, 'cannot reconnect: the replay buffer overflowed (' + err.message + ')', { cause: err }));
      return;
    }
    if (this._reconnectAttempt >= rp.maxAttempts) {
      this._fail(localError(90006, 'reconnect attempts exhausted (' + rp.maxAttempts + '): ' + err.message, { cause: err }));
      return;
    }
    if (this._totalAttempts >= 3 * rp.maxAttempts) {
      this._fail(localError(90006, 'reconnect limit of this session reached (' + 3 * rp.maxAttempts + ' in total): ' + err.message, { cause: err }));
      return;
    }
    if (!this._inReconnect) {
      this._inReconnect = true;
      if (policy === 'DROP' && conn) {
        this._droppedBytes += conn.txAudioBytes;
        this._totalDroppedBytes += conn.txAudioBytes;
      }
    }
    this._reconnectAttempt += 1;
    this._totalAttempts += 1;
    const attempt = this._reconnectAttempt;
    const delay = computeBackoffDelay(attempt, rp, this._ctx.random);
    this._ctx.logger.warn(TAG, this.id + ': reconnect ' + attempt + '/' + rp.maxAttempts + ' in ' + delay + ' ms: ' + describeError(err));
    this._timers.reconnect = setTimeout(() => {
      this._timers.reconnect = null;
      if (this._state === 'RECONNECTING') this._connect();
    }, delay);
    this._batch(() => {
      this._setState('RECONNECTING');
      this._emit('onReconnecting', [attempt, delay, err]);
    });
  }

  _detachConnection(reason) {
    this._gen += 1;
    const conn = this._conn;
    this._conn = null;
    this._clearTimer('connect');
    this._clearTimer('result');
    this._stopHeartbeat();
    this._probeOutstanding = false;
    this._hbConfirmed = false;
    if (conn && conn.task) {
      try {
        conn.task.close({ code: 1000, reason: reason || '' });
      } catch (e) {
        // the socket may already be gone
      }
    }
  }

  _fail(error) {
    if (!isActiveState(this._state)) return;
    const err = asYuguError(error);
    if (!err.idempotencyKey) err.idempotencyKey = this.idempotencyKey;
    if (!err.op) err.op = this._op();
    const lastClose = this._lastClose;
    this._clearTimer('reconnect');
    this._detachConnection('failed');
    this._ctx.logger.info(TAG, this.id + ' failed: ' + describeError(err) + ' ' + err.message);
    let code = 1000;
    if (lastClose && lastClose.code) code = lastClose.code;
    else if (err.category === 'NETWORK' || err.category === 'TIMEOUT') code = 1006;
    this._batch(() => {
      this._setState('FAILED');
      if (!this._cancelled) this._emit('onError', [err]);
      this._finishClosed(code, err.message);
    });
  }

  _finishClosed(code, reason) {
    this._setState('CLOSED');
    this._emit('onClosed', [code, reason]);
    this._closeQueued = true;
    this._notify('closed', code, reason);
    this._replay = [];
    this._replayBytes = 0;
    this._pending = [];
    if (typeof this._ctx.onClosed === 'function') this._ctx.onClosed(this);
  }
}
