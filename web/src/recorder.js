// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// Browser recorder (B-05): getUserMedia, AudioWorklet with ScriptProcessor fallback, 16 kHz mono
// PCM16, WAV export, 640-byte frames that can stream straight into a YuguStreamSession.

import { encodeWav, encodeWavBytes, floatToInt16 } from './audio.js';
import { localError, YuguError } from './errors.js';
import { getGlobal } from './util.js';

/** Recorder states. */
export const RecorderState = Object.freeze({
  IDLE: 'IDLE',
  RECORDING: 'RECORDING',
  PAUSED: 'PAUSED',
  STOPPED: 'STOPPED',
  RELEASED: 'RELEASED',
});

const R = RecorderState;
const PROCESSOR_NAME = 'yugu-pcm-processor';
const RESUME_WAIT_MS = 300;

/** Source of the AudioWorklet processor (also shipped as dist/yugu-pcm-worklet.js). */
export const PCM_WORKLET_SOURCE = `class YuguPcmProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this._buf = new Float32Array(1024);
    this._n = 0;
    this.port.onmessage = (e) => {
      if (e.data === 'flush') {
        this._flush();
        this.port.postMessage('flushed');
      }
    };
  }
  _flush() {
    if (this._n > 0) {
      const out = this._buf.slice(0, this._n);
      this._n = 0;
      this.port.postMessage(out, [out.buffer]);
    }
  }
  process(inputs) {
    const input = inputs[0];
    if (input && input.length) {
      const chs = input.length;
      const len = input[0].length;
      for (let i = 0; i < len; i++) {
        let s = 0;
        for (let c = 0; c < chs; c++) s += input[c][i];
        this._buf[this._n++] = s / chs;
        if (this._n === this._buf.length) this._flush();
      }
    }
    return true;
  }
}
registerProcessor('${PROCESSOR_NAME}', YuguPcmProcessor);
`;

let workletUrlCache = null;

/**
 * Streaming resampler: box low-pass (when downsampling by 2 or more) plus linear interpolation,
 * continuous across chunks.
 */
export class StreamResampler {
  constructor(inRate, outRate) {
    this.ratio = inRate / outRate;
    this.pos = 1;
    this.prev = 0;
    this.width = this.ratio >= 2 ? Math.floor(this.ratio) : 1;
    this.hist = new Float32Array(Math.max(0, this.width - 1));
  }

  process(input) {
    if (this.ratio === 1) return Float32Array.from(input);
    const x = this.width > 1 ? this._lowpass(input) : input;
    const n = x.length;
    if (n === 0) return new Float32Array(0);
    const out = [];
    let t = this.pos;
    while (t < n) {
      const i = Math.floor(t);
      const frac = t - i;
      const a = i === 0 ? this.prev : x[i - 1];
      const b = x[i];
      out.push(a + (b - a) * frac);
      t += this.ratio;
    }
    this.pos = t - n;
    this.prev = x[n - 1];
    return Float32Array.from(out);
  }

  _lowpass(input) {
    const w = this.width;
    const h = this.hist;
    const out = new Float32Array(input.length);
    for (let k = 0; k < input.length; k++) {
      let s = input[k];
      for (let j = 1; j < w; j++) {
        const idx = k - j;
        s += idx >= 0 ? input[idx] : h[h.length + idx];
      }
      out[k] = s / w;
    }
    const keep = w - 1;
    if (keep > 0) {
      const merged = new Float32Array(h.length + input.length);
      merged.set(h, 0);
      merged.set(input, h.length);
      this.hist = merged.slice(merged.length - keep);
    }
    return out;
  }
}

function mapMediaError(e) {
  const name = e && e.name;
  const detail = e && e.message ? e.message : String(e);
  if (name === 'NotAllowedError' || name === 'SecurityError' || name === 'PermissionDeniedError') {
    return localError(90201, detail, { cause: e });
  }
  if (
    name === 'NotFoundError' ||
    name === 'DevicesNotFoundError' ||
    name === 'NotReadableError' ||
    name === 'TrackStartError' ||
    name === 'OverconstrainedError' ||
    name === 'AbortError'
  ) {
    return localError(90202, detail, { cause: e });
  }
  return localError(90203, detail, { cause: e });
}

/**
 * Resume an AudioContext without blocking: without a user gesture the resume() promise stays
 * pending until one happens (autoplay policy). Audio flows as soon as the context runs.
 */
async function resumeContext(ctx) {
  if (!ctx || typeof ctx.resume !== 'function') return;
  let timer = null;
  await Promise.race([
    Promise.resolve()
      .then(() => ctx.resume())
      .catch(() => undefined),
    new Promise((r) => {
      timer = setTimeout(r, RESUME_WAIT_MS);
    }),
  ]);
  clearTimeout(timer);
}

function stopTracks(stream) {
  if (!stream || typeof stream.getTracks !== 'function') return;
  for (const t of stream.getTracks()) {
    try {
      t.stop();
    } catch (_) {
      /* ignore */
    }
  }
}

/**
 * Microphone recorder producing 16 kHz, 16-bit, mono PCM.
 */
export class YuguRecorder {
  constructor(options) {
    const o = options == null ? {} : options;
    if (typeof o !== 'object') throw localError(90010, 'YuguRecorder 选项必须是对象');
    this._targetRate = o.targetSampleRate == null ? 16000 : o.targetSampleRate;
    if (!Number.isInteger(this._targetRate) || this._targetRate < 8000 || this._targetRate > 48000) {
      throw localError(90010, 'targetSampleRate 必须是 8000 到 48000 之间的整数');
    }
    this._frameBytes = o.frameBytes == null ? 640 : o.frameBytes;
    if (!Number.isInteger(this._frameBytes) || this._frameBytes < 2 || this._frameBytes % 2 !== 0) {
      throw localError(90010, 'frameBytes 必须是正偶数');
    }
    this._constraints = o.constraints || {
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    };
    this._useWorklet = o.useAudioWorklet !== false;
    this._workletUrl = typeof o.workletModuleUrl === 'string' && o.workletModuleUrl ? o.workletModuleUrl : null;
    this._envOverride = o.environment || null;
    this._listener =
      o.listener !== undefined
        ? o.listener
        : { onFrame: o.onFrame, onLevel: o.onLevel, onError: o.onError, onStateChanged: o.onStateChanged };
    this._state = R.IDLE;
    this._starting = null;
    this._releasePromise = null;
    this._stream = null;
    this._ctx = null;
    this._source = null;
    this._node = null;
    this._sink = null;
    this._usingWorklet = false;
    this._session = null;
    this._chunks = [];
    this._samples = 0;
    this._frame = new Int16Array(this._frameBytes / 2);
    this._frameFill = 0;
    this._resampler = null;
    this._flushResolve = null;
    this._onTrackEnded = () => this._trackEnded();
  }

  /** True when getUserMedia and AudioContext exist (secure context required in browsers). */
  static isSupported() {
    const env = defaultEnvironment();
    return !!(env.mediaDevices && typeof env.mediaDevices.getUserMedia === 'function' && env.AudioContext);
  }

  /** IDLE, RECORDING, PAUSED, STOPPED or RELEASED. */
  getState() {
    return this._state;
  }

  /** Replace or remove (null) the listener {onStateChanged, onFrame, onLevel, onError}. */
  setListener(listener) {
    if (listener !== null && typeof listener !== 'object') throw localError(90010, 'listener 必须是对象或 null');
    if (this._state === R.RELEASED) return;
    this._listener = listener;
  }

  /** True when the AudioWorklet path is in use, false for ScriptProcessor. */
  isUsingAudioWorklet() {
    return this._usingWorklet;
  }

  /** Recorded duration in milliseconds. */
  getDurationMs() {
    return (this._samples / this._targetRate) * 1000;
  }

  /**
   * Start recording. Must follow a user gesture in browsers.
   * options.session: a YuguStreamSession that receives every 640-byte frame through sendAudio.
   */
  start(options) {
    if (this._state === R.RELEASED) return Promise.reject(localError(90009, '录音器已释放'));
    if (this._state === R.RECORDING) return Promise.resolve();
    if (this._state === R.PAUSED) return Promise.reject(localError(90009, '录音器已暂停，请调用 resume()'));
    if (this._starting) return this._starting;
    const session = options && options.session ? options.session : null;
    if (session && typeof session.sendAudio !== 'function') {
      return Promise.reject(localError(90010, 'session 必须是 YuguStreamSession'));
    }
    this._starting = this._doStart(session).finally(() => {
      this._starting = null;
    });
    return this._starting;
  }

  /** Pause: frames are discarded until resume(). The microphone stays open. */
  async pause() {
    if (this._state === R.PAUSED) return;
    if (this._state !== R.RECORDING) throw localError(90009, `${this._state} 状态不能暂停`);
    this._setState(R.PAUSED);
    try {
      if (this._ctx && typeof this._ctx.suspend === 'function') await this._ctx.suspend();
    } catch (_) {
      /* frames are dropped by state anyway */
    }
  }

  /** Resume after pause(). */
  async resume() {
    if (this._state === R.RECORDING) return;
    if (this._state !== R.PAUSED) throw localError(90009, `${this._state} 状态不能恢复`);
    if (this._ctx) await resumeContext(this._ctx);
    if (this._state === R.PAUSED) this._setState(R.RECORDING);
  }

  /**
   * Stop: flushes the last partial frame (zero padded to 640 bytes), releases the microphone and
   * the AudioContext. Recorded audio stays available for exportWav(). No-op when not recording.
   */
  async stop() {
    if (this._starting) {
      try {
        await this._starting;
      } catch (_) {
        return;
      }
    }
    if (this._state !== R.RECORDING && this._state !== R.PAUSED) return;
    if (this._state === R.RECORDING) await this._flushWorklet();
    if (this._frameFill > 0 && this._state === R.RECORDING) {
      this._frame.fill(0, this._frameFill);
      this._frameFill = this._frame.length;
      this._deliverFrame();
    }
    this._frameFill = 0;
    await this._teardown();
    if (this._state === R.RECORDING || this._state === R.PAUSED) this._setState(R.STOPPED);
  }

  /** stop() then exportWav(). */
  async stopAndGetWav() {
    await this.stop();
    return this.exportWav();
  }

  /** Recorded audio as a 16 kHz mono 16-bit WAV Blob. */
  exportWav() {
    return encodeWav(this._pcm(), this._targetRate, 1);
  }

  /** Recorded audio as WAV bytes. */
  exportWavBytes() {
    return encodeWavBytes(this._pcm(), this._targetRate, 1);
  }

  /**
   * Release everything: stops the microphone tracks, closes the AudioContext, drops the recorded
   * audio and the listener. Idempotent, never throws.
   */
  release() {
    if (this._releasePromise) return this._releasePromise;
    this._setState(R.RELEASED);
    this._listener = null;
    this._session = null;
    this._releasePromise = this._teardown()
      .catch(() => undefined)
      .then(() => {
        this._chunks = [];
        this._samples = 0;
        this._frameFill = 0;
      });
    return this._releasePromise;
  }

  // ------------------------------------------------------------------ internal

  _env() {
    const d = defaultEnvironment();
    const o = this._envOverride || {};
    return {
      mediaDevices: o.mediaDevices || d.mediaDevices,
      AudioContext: o.AudioContext || d.AudioContext,
      AudioWorkletNode: o.AudioWorkletNode || d.AudioWorkletNode,
      createObjectURL: o.createObjectURL || d.createObjectURL,
    };
  }

  async _doStart(session) {
    const env = this._env();
    if (!env.mediaDevices || typeof env.mediaDevices.getUserMedia !== 'function' || !env.AudioContext) {
      throw localError(90202, '当前环境不支持录音，需要 https 或 localhost 安全上下文，以及 getUserMedia 与 AudioContext');
    }
    let stream;
    try {
      stream = await env.mediaDevices.getUserMedia(this._constraints);
    } catch (e) {
      throw mapMediaError(e);
    }
    if (this._state === R.RELEASED) {
      stopTracks(stream);
      throw localError(90009, '录音器已释放');
    }
    this._stream = stream;
    try {
      const ctx = new env.AudioContext();
      this._ctx = ctx;
      if (ctx.state === 'suspended') await resumeContext(ctx);
      this._source = ctx.createMediaStreamSource(stream);
      this._resampler = new StreamResampler(ctx.sampleRate, this._targetRate);
      this._chunks = [];
      this._samples = 0;
      this._frameFill = 0;
      const ok = this._useWorklet && (await this._initWorklet(env));
      if (!ok) this._initScriptProcessor();
    } catch (e) {
      await this._teardown();
      throw e instanceof YuguError ? e : localError(90203, `录音初始化失败：${e && e.message}`, { cause: e });
    }
    if (this._state === R.RELEASED) {
      await this._teardown();
      throw localError(90009, '录音器已释放');
    }
    for (const t of typeof stream.getTracks === 'function' ? stream.getTracks() : []) {
      if (typeof t.addEventListener === 'function') t.addEventListener('ended', this._onTrackEnded);
    }
    this._session = session;
    this._setState(R.RECORDING);
  }

  async _initWorklet(env) {
    const ctx = this._ctx;
    if (!ctx.audioWorklet || typeof ctx.audioWorklet.addModule !== 'function' || !env.AudioWorkletNode) return false;
    try {
      let url = this._workletUrl;
      if (!url) {
        if (!workletUrlCache) {
          workletUrlCache = env.createObjectURL(new Blob([PCM_WORKLET_SOURCE], { type: 'application/javascript' }));
        }
        url = workletUrlCache;
      }
      await ctx.audioWorklet.addModule(url);
      const node = new env.AudioWorkletNode(ctx, PROCESSOR_NAME, {
        numberOfInputs: 1,
        numberOfOutputs: 1,
        channelCount: 1,
      });
      node.port.onmessage = (ev) => this._onWorkletMessage(ev.data);
      this._source.connect(node);
      this._connectSink(node);
      this._node = node;
      this._usingWorklet = true;
      return true;
    } catch (_) {
      this._node = null;
      this._usingWorklet = false;
      return false;
    }
  }

  _initScriptProcessor() {
    const ctx = this._ctx;
    const node = ctx.createScriptProcessor(4096, 1, 1);
    node.onaudioprocess = (ev) => {
      const input = ev.inputBuffer.getChannelData(0);
      this._onPcm(new Float32Array(input));
    };
    this._source.connect(node);
    this._connectSink(node);
    this._node = node;
    this._usingWorklet = false;
  }

  _connectSink(node) {
    // Keep the graph pulled without making a sound.
    const sink = this._ctx.createGain();
    sink.gain.value = 0;
    node.connect(sink);
    sink.connect(this._ctx.destination);
    this._sink = sink;
  }

  _onWorkletMessage(data) {
    if (data === 'flushed') {
      if (this._flushResolve) this._flushResolve();
      return;
    }
    this._onPcm(data);
  }

  _flushWorklet() {
    const node = this._node;
    if (!this._usingWorklet || !node || !node.port) return Promise.resolve();
    return new Promise((resolve) => {
      const timer = setTimeout(done, 100);
      const self = this;
      function done() {
        clearTimeout(timer);
        self._flushResolve = null;
        resolve();
      }
      this._flushResolve = done;
      try {
        node.port.postMessage('flush');
      } catch (_) {
        done();
      }
    });
  }

  _onPcm(float32) {
    if (this._state !== R.RECORDING || !float32 || !float32.length) return;
    try {
      let sum = 0;
      for (let i = 0; i < float32.length; i++) sum += float32[i] * float32[i];
      this._call('onLevel', [Math.sqrt(sum / float32.length)]);
      const i16 = floatToInt16(this._resampler.process(float32));
      if (!i16.length) return;
      this._chunks.push(i16);
      this._samples += i16.length;
      let off = 0;
      while (off < i16.length) {
        const n = Math.min(this._frame.length - this._frameFill, i16.length - off);
        this._frame.set(i16.subarray(off, off + n), this._frameFill);
        this._frameFill += n;
        off += n;
        if (this._frameFill === this._frame.length) this._deliverFrame();
      }
    } catch (e) {
      this._fault(localError(90203, `录音数据处理失败：${e && e.message}`, { cause: e }));
    }
  }

  _deliverFrame() {
    const bytes = new Uint8Array(this._frame.buffer.slice(0, this._frameBytes));
    this._frameFill = 0;
    this._call('onFrame', [bytes]);
    if (this._session) {
      try {
        this._session.sendAudio(bytes);
      } catch (_) {
        /* the session reports its own errors */
      }
    }
  }

  _trackEnded() {
    if (this._state !== R.RECORDING && this._state !== R.PAUSED) return;
    this._fault(localError(90202, '麦克风已断开或被其他程序占用'));
  }

  _fault(err) {
    this._teardown().catch(() => undefined);
    if (this._state === R.RECORDING || this._state === R.PAUSED) this._setState(R.STOPPED);
    this._call('onError', [err]);
  }

  async _teardown() {
    const node = this._node;
    const source = this._source;
    const sink = this._sink;
    const stream = this._stream;
    const ctx = this._ctx;
    this._node = null;
    this._source = null;
    this._sink = null;
    this._stream = null;
    this._ctx = null;
    this._usingWorklet = false;
    if (this._flushResolve) this._flushResolve();
    for (const n of [source, node, sink]) {
      if (!n) continue;
      try {
        n.disconnect();
      } catch (_) {
        /* ignore */
      }
    }
    if (node) {
      try {
        if (node.port) node.port.onmessage = null;
        if ('onaudioprocess' in node) node.onaudioprocess = null;
      } catch (_) {
        /* ignore */
      }
    }
    if (stream) {
      for (const t of typeof stream.getTracks === 'function' ? stream.getTracks() : []) {
        if (typeof t.removeEventListener === 'function') t.removeEventListener('ended', this._onTrackEnded);
      }
      stopTracks(stream);
    }
    if (ctx && ctx.state !== 'closed' && typeof ctx.close === 'function') {
      try {
        await ctx.close();
      } catch (_) {
        /* ignore */
      }
    }
  }

  _pcm() {
    const out = new Int16Array(this._samples);
    let o = 0;
    for (const c of this._chunks) {
      out.set(c, o);
      o += c.length;
    }
    return out;
  }

  _setState(next) {
    const prev = this._state;
    if (prev === next) return;
    this._state = next;
    this._call('onStateChanged', [prev, next]);
  }

  _call(name, args) {
    const l = this._listener;
    if (!l) return;
    const fn = l[name];
    if (typeof fn !== 'function') return;
    try {
      fn.apply(l, args);
    } catch (_) {
      /* listener errors never break recording */
    }
  }
}

function defaultEnvironment() {
  const g = getGlobal();
  const nav = g.navigator;
  return {
    mediaDevices: nav && nav.mediaDevices ? nav.mediaDevices : null,
    AudioContext: g.AudioContext || g.webkitAudioContext || null,
    AudioWorkletNode: g.AudioWorkletNode || null,
    createObjectURL: g.URL && typeof g.URL.createObjectURL === 'function' ? (b) => g.URL.createObjectURL(b) : null,
  };
}
