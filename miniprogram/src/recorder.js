// Recorder over wx.getRecorderManager() (DESIGN 2.6): 16000 Hz mono 16 bit PCM, frame callbacks,
// states IDLE, RECORDING, PAUSED, STOPPED, RELEASED, idempotent release().
//
// wx.getRecorderManager() returns one global manager and has no off* methods. The SDK registers its
// handlers on a manager exactly once and routes events to the YuguRecorder that currently owns the
// microphone; listeners of a YuguRecorder are plain fields that setListener(null) clears. Creating
// and releasing recorders therefore never piles up handlers on the global manager.
//
// Platform events carry no recording id, so two rules keep events of an older recording away from a
// newer one:
// - when the SDK stops a recording it no longer follows (release, recorder error), the platform
//   answers that stop with exactly one stop or error event; until it arrives the hub drops the frames
//   of that recording, then drops the answer itself;
// - after start() the recorder ignores stop, pause and resume events until the platform reports the
//   start of the new recording. Frames and errors always count, so no audio of the new recording is
//   lost and a failed start is reported.
import { fromWxFail, localError, RequestCancelledException } from './errors.js';
import { concatBytes, copyBytes, toUint8 } from './util.js';

const TAG = 'YuguSDK:recorder';

export const RecorderState = Object.freeze({
  IDLE: 'IDLE',
  RECORDING: 'RECORDING',
  PAUSED: 'PAUSED',
  STOPPED: 'STOPPED',
  RELEASED: 'RELEASED',
});

const DEFAULTS = Object.freeze({
  sampleRate: 16000,
  numberOfChannels: 1,
  format: 'PCM',
  frameSize: 1,
  duration: 300000,
  stopTimeoutMs: 5000,
  startTimeoutMs: 1500,
});

const hubs = typeof WeakMap === 'function' ? new WeakMap() : null;
const fallbackHubs = [];

function hubFor(manager) {
  let hub = hubs ? hubs.get(manager) : null;
  if (!hub && !hubs) {
    for (let i = 0; i < fallbackHubs.length; i++) if (fallbackHubs[i].manager === manager) hub = fallbackHubs[i];
  }
  if (hub) return hub;
  hub = { manager, owner: null, drain: 0 };
  const route = (name) => (res) => {
    if (hub.drain > 0) {
      if (name === 'frame') return;
      if (name === 'stop' || name === 'error') {
        hub.drain -= 1;
        return;
      }
      if (name === 'start') hub.drain = 0;
    }
    const owner = hub.owner;
    if (owner) owner._onManagerEvent(name, res);
  };
  const map = [
    ['onStart', 'start'], ['onPause', 'pause'], ['onResume', 'resume'], ['onStop', 'stop'],
    ['onError', 'error'], ['onFrameRecorded', 'frame'],
    ['onInterruptionBegin', 'interruptionBegin'], ['onInterruptionEnd', 'interruptionEnd'],
  ];
  for (let i = 0; i < map.length; i++) {
    if (typeof manager[map[i][0]] === 'function') manager[map[i][0]](route(map[i][1]));
  }
  if (hubs) hubs.set(manager, hub);
  else fallbackHubs.push(hub);
  return hub;
}

function recorderError(res) {
  const msg = String((res && res.errMsg) || 'recorder error');
  if (/auth|permission|deny|denied|not authorized|授权|权限/i.test(msg)) return localError(90201, msg, { cause: res });
  if (/busy|occupied|in use|is recording|is running|interrupt|占用/i.test(msg)) return localError(90202, msg, { cause: res });
  return localError(90203, msg, { cause: res });
}

export class YuguRecorder {
  /**
   * options: {sampleRate 16000, numberOfChannels 1, format 'PCM', frameSize 1 (KB per frame callback),
   *           duration 300000 ms, audioSource, stopTimeoutMs 5000}
   * deps: {wx, logger} (YuguClient.createRecorder fills them; standalone use takes the global wx)
   */
  constructor(options, deps) {
    const o = options || {};
    const d = deps || {};
    const wxApi = d.wx || (typeof wx !== 'undefined' ? wx : null); // eslint-disable-line no-undef
    if (!wxApi || typeof wxApi.getRecorderManager !== 'function') {
      throw localError(90010, 'wx.getRecorderManager is not available; run inside a WeChat mini program or pass deps.wx');
    }
    this._wx = wxApi;
    this._logger = d.logger || null;
    this._onDispose = typeof d.onDispose === 'function' ? d.onDispose : null;
    this._options = {
      sampleRate: o.sampleRate || DEFAULTS.sampleRate,
      numberOfChannels: o.numberOfChannels || DEFAULTS.numberOfChannels,
      format: o.format || DEFAULTS.format,
      frameSize: o.frameSize === undefined ? DEFAULTS.frameSize : o.frameSize,
      duration: o.duration || DEFAULTS.duration,
      audioSource: o.audioSource,
      stopTimeoutMs: o.stopTimeoutMs || DEFAULTS.stopTimeoutMs,
    };
    this._state = RecorderState.IDLE;
    this._listener = null;
    this._pipe = null;
    this._stopWaiters = [];
    this._stopping = false;
    this._stopTimer = null;
    this._awaitingStart = false;
    this._startTimer = null;
    this._lastResult = null;
    this._manager = null;
    this._hub = null;
  }

  getState() {
    return this._state;
  }

  /** Replaces the listener; null removes it. */
  setListener(listener) {
    this._listener = listener && typeof listener === 'object' ? listener : null;
  }

  removeListener() {
    this._listener = null;
  }

  /** Starts recording. Throws IllegalSessionStateException when recording, stopping or released. */
  start() {
    if (this._state === RecorderState.RELEASED) throw localError(90009, 'recorder is released');
    if (this._holdsMicrophone()) throw localError(90009, 'recorder is already recording');
    if (!this._manager) this._manager = this._wx.getRecorderManager();
    const hub = hubFor(this._manager);
    const owner = hub.owner;
    if (owner && owner !== this && owner._holdsMicrophone()) {
      throw localError(90202, 'the microphone is used by another YuguRecorder');
    }
    hub.owner = this;
    this._hub = hub;
    const opts = this._options;
    const config = {
      duration: opts.duration,
      sampleRate: opts.sampleRate,
      numberOfChannels: opts.numberOfChannels,
      format: opts.format,
    };
    if (opts.frameSize) config.frameSize = opts.frameSize;
    if (opts.audioSource) config.audioSource = opts.audioSource;
    this._awaitingStart = true;
    this._clearTimer('_startTimer');
    // A platform that never reports the start must not block the stop event for long.
    this._startTimer = setTimeout(() => {
      this._startTimer = null;
      this._awaitingStart = false;
    }, DEFAULTS.startTimeoutMs);
    this._setState(RecorderState.RECORDING);
    try {
      this._manager.start(config);
    } catch (e) {
      this._handleError(fromWxFail(e, 'recorder start'), 'sdk');
    }
  }

  pause() {
    if (this._state !== RecorderState.RECORDING || this._stopping) throw localError(90009, 'pause() needs a recording in progress');
    this._manager.pause();
    this._setState(RecorderState.PAUSED);
  }

  resume() {
    if (this._state !== RecorderState.PAUSED || this._stopping) throw localError(90009, 'resume() needs a paused recording');
    this._manager.resume();
    this._setState(RecorderState.RECORDING);
  }

  /**
   * Stops recording and releases the microphone. Resolves with
   * {tempFilePath, duration, fileSize, format, sampleRate, numberOfChannels}, a value that
   * YuguClient.evaluate accepts as `audio`. A second call while stopping returns the same result;
   * after STOPPED it resolves the last result.
   */
  stop() {
    if (this._state === RecorderState.STOPPED && !this._stopping && this._lastResult) return Promise.resolve(this._lastResult);
    if (!this._holdsMicrophone()) return Promise.reject(localError(90009, 'stop() needs a recording in progress'));
    const p = new Promise((resolve, reject) => this._stopWaiters.push({ resolve, reject }));
    if (!this._stopping) {
      this._stopping = true;
      this._stopTimer = setTimeout(() => {
        this._stopTimer = null;
        if (this._stopping) this._handleError(localError(90203, 'recorder did not report stop within ' + this._options.stopTimeoutMs + ' ms'), 'sdk');
      }, this._options.stopTimeoutMs);
      try {
        this._manager.stop();
      } catch (e) {
        this._handleError(fromWxFail(e, 'recorder stop'), 'sdk');
      }
    }
    return p;
  }

  /**
   * Releases the recorder: stops a running recording (microphone released), drops the listener and
   * any pipe. Idempotent; every other method throws afterwards.
   */
  release() {
    if (this._state === RecorderState.RELEASED) return;
    const recording = this._state === RecorderState.RECORDING || this._state === RecorderState.PAUSED;
    const stopping = this._stopping;
    this.unpipe();
    this._listener = null;
    this._clearTimer('_stopTimer');
    this._clearTimer('_startTimer');
    this._stopping = false;
    this._awaitingStart = false;
    const waiters = this._stopWaiters;
    this._stopWaiters = [];
    this._state = RecorderState.RELEASED;
    if (this._hub && this._hub.owner === this) this._hub.owner = null;
    if (stopping && this._hub) this._hub.drain += 1;
    else if (recording) this._abandonPlatformRecording();
    for (let i = 0; i < waiters.length; i++) {
      waiters[i].reject(new RequestCancelledException('recorder released before it stopped', { code: 90003 }));
    }
    if (this._onDispose) this._onDispose(this);
  }

  /**
   * Streams frames into a StreamSession: frames are re-chunked to frameBytes (640 bytes, 20 ms at
   * 16000 Hz) and sent with sendAudio; stop() flushes the rest and calls session.end() when endOnStop.
   * A recorder error cancels the session; a session that ends while recording stops the recorder.
   */
  pipeTo(session, options) {
    if (!session || typeof session.sendAudio !== 'function') throw localError(90010, 'pipeTo expects a StreamSession');
    if (this._state === RecorderState.RELEASED) throw localError(90009, 'recorder is released');
    const o = options || {};
    this.unpipe();
    const pipe = {
      session,
      endOnStop: o.endOnStop !== false,
      frameBytes: o.frameBytes > 0 ? Math.floor(o.frameBytes) : 640,
      carry: null,
      active: true,
    };
    this._pipe = pipe;
    if (typeof session._addObserver === 'function') {
      session._addObserver((event) => {
        if (event !== 'closed' || !pipe.active) return;
        pipe.active = false;
        if (this._pipe === pipe) this._pipe = null;
        if (this._state === RecorderState.RECORDING || this._state === RecorderState.PAUSED) {
          this.stop().catch(() => {});
        }
      });
    }
    return session;
  }

  unpipe() {
    if (this._pipe) this._pipe.active = false;
    this._pipe = null;
  }

  // ------------------------------------------------------------------ internals

  _holdsMicrophone() {
    return this._state === RecorderState.RECORDING || this._state === RecorderState.PAUSED || this._stopping;
  }

  _clearTimer(name) {
    if (this[name]) {
      clearTimeout(this[name]);
      this[name] = null;
    }
  }

  // Stops a platform recording the SDK no longer follows; the hub drops the answer.
  _abandonPlatformRecording() {
    if (!this._manager || !this._hub) return;
    this._hub.drain += 1;
    try {
      this._manager.stop();
    } catch (e) {
      this._hub.drain -= 1;
    }
  }

  _call(name, args) {
    const l = this._listener;
    if (!l || typeof l[name] !== 'function') return;
    try {
      l[name].apply(l, args);
    } catch (e) {
      if (this._logger) this._logger.error(TAG, 'listener.' + name + ' threw', e);
    }
  }

  _setState(next) {
    const prev = this._state;
    if (prev === next) return;
    this._state = next;
    this._call('onStateChanged', [prev, next]);
  }

  _debug(message) {
    if (this._logger) this._logger.debug(TAG, message);
  }

  _onManagerEvent(name, res) {
    if (this._state === RecorderState.RELEASED) return;
    if (!this._holdsMicrophone()) {
      // Events after the end of a recording, for example the error that answers a stop() which came
      // after the platform had already stopped at the duration limit; the recording result stands.
      this._debug(name + ' ignored, no recording in progress' + (res && res.errMsg ? ': ' + res.errMsg : ''));
      return;
    }
    if (this._awaitingStart && (name === 'stop' || name === 'pause' || name === 'resume')) {
      this._debug(name + ' of an earlier recording ignored');
      return;
    }
    if (name === 'start') {
      this._awaitingStart = false;
      this._clearTimer('_startTimer');
      this._call('onStart', []);
    } else if (name === 'pause') {
      if (this._state === RecorderState.RECORDING) this._setState(RecorderState.PAUSED);
      this._call('onPause', []);
    } else if (name === 'resume') {
      if (this._state === RecorderState.PAUSED) this._setState(RecorderState.RECORDING);
      this._call('onResume', []);
    } else if (name === 'frame') {
      this._onFrame(res);
    } else if (name === 'stop') {
      this._onStop(res);
    } else if (name === 'error') {
      this._handleError(recorderError(res), 'platform');
    } else if (name === 'interruptionBegin') {
      this._call('onInterruptionBegin', []);
    } else if (name === 'interruptionEnd') {
      this._call('onInterruptionEnd', []);
    }
  }

  _onFrame(res) {
    const frame = res && res.frameBuffer;
    const u8 = toUint8(frame);
    if (!u8) return;
    const isLast = !!(res && res.isLastFrame);
    this._call('onFrame', [frame, isLast]);
    const pipe = this._pipe;
    if (!pipe || !pipe.active || !u8.length) return;
    const data = pipe.carry ? concatBytes([pipe.carry, u8]) : u8;
    pipe.carry = null;
    const n = pipe.frameBytes;
    let off = 0;
    for (; off + n <= data.length; off += n) this._pipeSend(pipe, data.subarray(off, off + n));
    if (off < data.length) pipe.carry = copyBytes(data.subarray(off));
  }

  _pipeSend(pipe, chunk) {
    try {
      pipe.session.sendAudio(chunk);
    } catch (e) {
      if (this._logger) this._logger.warn(TAG, 'pipe sendAudio failed: ' + (e && e.message));
    }
  }

  _onStop(res) {
    const result = {
      tempFilePath: res && res.tempFilePath ? res.tempFilePath : null,
      duration: res && typeof res.duration === 'number' ? res.duration : 0,
      fileSize: res && typeof res.fileSize === 'number' ? res.fileSize : 0,
      format: String(this._options.format).toLowerCase(),
      sampleRate: this._options.sampleRate,
      numberOfChannels: this._options.numberOfChannels,
    };
    this._lastResult = result;
    this._clearTimer('_stopTimer');
    this._stopping = false;
    const waiters = this._stopWaiters;
    this._stopWaiters = [];
    this._setState(RecorderState.STOPPED);
    const pipe = this._pipe;
    if (pipe && pipe.active) {
      if (pipe.carry && pipe.carry.length) this._pipeSend(pipe, pipe.carry);
      pipe.carry = null;
      if (pipe.endOnStop) {
        try {
          pipe.session.end();
        } catch (e) {
          if (this._logger) this._logger.warn(TAG, 'pipe end failed: ' + (e && e.message));
        }
      }
    }
    for (let i = 0; i < waiters.length; i++) waiters[i].resolve(result);
    this._call('onStop', [result]);
  }

  _handleError(err, source) {
    if (this._state === RecorderState.RELEASED) return;
    const wasActive = this._holdsMicrophone();
    const wasStopping = this._stopping;
    this._clearTimer('_stopTimer');
    this._clearTimer('_startTimer');
    this._stopping = false;
    this._awaitingStart = false;
    // Every error path releases the microphone. A platform error during a recording may or may not
    // have stopped the platform recorder, so the SDK stops it and the hub drops the answer. An error
    // while stopping is the answer to that stop.
    if (wasActive && !wasStopping && source === 'platform') this._abandonPlatformRecording();
    const waiters = this._stopWaiters;
    this._stopWaiters = [];
    if (wasActive) this._setState(RecorderState.STOPPED);
    if (this._logger) this._logger.warn(TAG, 'recorder error ' + err.code + ': ' + err.message);
    const pipe = this._pipe;
    if (pipe && pipe.active) {
      pipe.active = false;
      this._pipe = null;
      try {
        if (typeof pipe.session.cancel === 'function') pipe.session.cancel();
      } catch (e) {
        // the session may already be closed
      }
    }
    for (let i = 0; i < waiters.length; i++) waiters[i].reject(err);
    this._call('onError', [err]);
  }
}
