// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// Audio input handling (C-06), WAV parsing and encoding, and the local precheck (C-04, DESIGN 2.9).

import { LOCAL_TABLE } from './error-table.js';
import { AudioQualityException, localError } from './errors.js';
import { isBlobLike, readBlob, toBytes } from './util.js';

/** Precheck modes. */
export const AudioPrecheck = Object.freeze({ OFF: 'OFF', WARN: 'WARN', REJECT: 'REJECT' });

/** Upload limit of one REST evaluation, same as the platform (50 MB). */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

/** Audio limit of one streaming round, same as the platform (10 MB). */
export const MAX_STREAM_BYTES = 10 * 1024 * 1024;

const MIN_DURATION_MS = 1000;
const MAX_DURATION_MS = 300000;
const SILENT_PEAK = 200;
const SILENT_RMS = 30;
const LOW_VOLUME_DBFS = -45;

/** Codes that REJECT mode turns into an exception. 90104 stays a warning. */
const REJECTING = new Set([90101, 90102, 90103, 90105]);

function ascii(bytes, off, len) {
  let s = '';
  for (let i = 0; i < len && off + i < bytes.length; i++) s += String.fromCharCode(bytes[off + i]);
  return s;
}

/** Guess the container from magic bytes. */
export function sniffAudioContainer(bytes) {
  if (bytes.length >= 12 && ascii(bytes, 0, 4) === 'RIFF' && ascii(bytes, 8, 4) === 'WAVE') return 'wav';
  if (bytes.length >= 3 && ascii(bytes, 0, 3) === 'ID3') return 'mp3';
  if (bytes.length >= 2 && bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0) return 'mp3';
  if (bytes.length >= 4 && ascii(bytes, 0, 4) === 'OggS') return 'ogg';
  if (bytes.length >= 4 && ascii(bytes, 0, 4) === 'fLaC') return 'flac';
  if (bytes.length >= 8 && ascii(bytes, 4, 4) === 'ftyp') return 'mp4';
  if (bytes.length >= 4 && bytes[0] === 0x1a && bytes[1] === 0x45 && bytes[2] === 0xdf && bytes[3] === 0xa3) return 'webm';
  return 'unknown';
}

const CONTAINER_INFO = {
  wav: { ext: 'wav', type: 'audio/wav' },
  mp3: { ext: 'mp3', type: 'audio/mpeg' },
  ogg: { ext: 'ogg', type: 'audio/ogg' },
  flac: { ext: 'flac', type: 'audio/flac' },
  mp4: { ext: 'm4a', type: 'audio/mp4' },
  webm: { ext: 'webm', type: 'audio/webm' },
  unknown: { ext: 'wav', type: 'application/octet-stream' },
};

/**
 * Parse a RIFF WAVE header by walking its chunks (LIST and other chunks before data are skipped).
 * Returns null when the bytes are not RIFF WAVE.
 */
export function parseWav(input) {
  const bytes = toBytes(input);
  if (!bytes || sniffAudioContainer(bytes) !== 'wav') return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const info = {
    audioFormat: 0,
    channels: 0,
    sampleRate: 0,
    byteRate: 0,
    blockAlign: 0,
    bitsPerSample: 0,
    dataOffset: -1,
    dataLength: 0,
    isPcm16: false,
  };
  let off = 12;
  let haveFmt = false;
  while (off + 8 <= bytes.length) {
    const id = ascii(bytes, off, 4);
    const size = view.getUint32(off + 4, true);
    const body = off + 8;
    if (id === 'fmt ' && body + 16 <= bytes.length) {
      info.audioFormat = view.getUint16(body, true);
      info.channels = view.getUint16(body + 2, true);
      info.sampleRate = view.getUint32(body + 4, true);
      info.byteRate = view.getUint32(body + 8, true);
      info.blockAlign = view.getUint16(body + 12, true);
      info.bitsPerSample = view.getUint16(body + 14, true);
      // WAVE_FORMAT_EXTENSIBLE: the sub format GUID starts with the real format tag.
      if (info.audioFormat === 0xfffe && size >= 40 && body + 26 <= bytes.length) {
        info.audioFormat = view.getUint16(body + 24, true);
      }
      haveFmt = true;
    } else if (id === 'data') {
      info.dataOffset = body;
      const avail = bytes.length - body;
      // 0 and 0xFFFFFFFF are written by streaming encoders that never patched the header.
      info.dataLength = size === 0 || size === 0xffffffff || size > avail ? avail : size;
      break;
    }
    off = body + size + (size % 2);
  }
  if (!haveFmt) return Object.assign(info, { valid: false });
  info.isPcm16 = info.audioFormat === 1 && info.bitsPerSample === 16;
  info.valid = info.dataOffset >= 0;
  return info;
}

/** Running peak and RMS over little endian 16-bit samples, fed in chunks of any length. */
export class PcmStats {
  constructor() {
    this.bytes = 0;
    this.samples = 0;
    this.peak = 0;
    this.sumSquares = 0;
    this._carry = -1;
  }

  add(chunk) {
    const b = toBytes(chunk);
    if (!b || b.length === 0) return;
    this.bytes += b.length;
    let i = 0;
    if (this._carry >= 0) {
      this._sample((b[0] << 8) | this._carry);
      this._carry = -1;
      i = 1;
    }
    for (; i + 1 < b.length; i += 2) this._sample((b[i + 1] << 8) | b[i]);
    if (i < b.length) this._carry = b[i];
  }

  _sample(u16) {
    const s = u16 >= 0x8000 ? u16 - 0x10000 : u16;
    const a = s < 0 ? -s : s;
    if (a > this.peak) this.peak = a;
    this.sumSquares += s * s;
    this.samples += 1;
  }

  get rms() {
    return this.samples ? Math.sqrt(this.sumSquares / this.samples) : 0;
  }
}

/** Peak and RMS of PCM16 samples inside bytes[offset, offset + length). */
export function analyzePcm16(bytes, offset, length) {
  const st = new PcmStats();
  st.add(bytes.subarray(offset, offset + length));
  return { peak: st.peak, rms: st.rms, samples: st.samples };
}

function rmsToDbfs(rms) {
  if (rms <= 0) return -Infinity;
  return 20 * Math.log10(rms / 32768);
}

/** A local warning object {code, name, message}. */
export function localWarning(code) {
  const e = LOCAL_TABLE[code];
  return Object.freeze({ code, name: e ? e.name : String(code), message: e ? e.message : '' });
}

function levelWarnings(peak, rms, warnings) {
  if (peak < SILENT_PEAK && rms < SILENT_RMS) warnings.push(localWarning(90103));
  else if (rmsToDbfs(rms) < LOW_VOLUME_DBFS) warnings.push(localWarning(90104));
}

function durationWarnings(durationMs, size, maxBytes, warnings) {
  if (durationMs != null && durationMs < MIN_DURATION_MS) warnings.push(localWarning(90101));
  if ((durationMs != null && durationMs > MAX_DURATION_MS) || size > maxBytes) warnings.push(localWarning(90102));
}

/**
 * Local precheck of evaluate input (DESIGN 2.9).
 * WAV and raw PCM (format "pcm") get every check, other containers only the size check.
 * The size limit is maxBytes, by default the 50 MB REST upload limit.
 * Returns the analysis and the list of warnings. Does not throw; see applyPrecheck.
 */
export function precheckAudio(input, options) {
  const opts = options || {};
  const bytes = toBytes(input);
  if (!bytes) throw localError(90010, 'precheckAudio 需要 ArrayBuffer 或 Uint8Array');
  const maxBytes = typeof opts.maxBytes === 'number' && opts.maxBytes > 0 ? opts.maxBytes : MAX_UPLOAD_BYTES;
  const warnings = [];
  const report = {
    container: opts.format === 'pcm' ? 'pcm' : sniffAudioContainer(bytes),
    size: bytes.length,
    durationMs: null,
    sampleRate: null,
    channels: null,
    bitsPerSample: null,
    peak: null,
    rms: null,
    rmsDbfs: null,
    warnings,
  };
  if (report.container === 'pcm') {
    const sampleRate = opts.sampleRate || 16000;
    const channels = opts.channels || 1;
    report.sampleRate = sampleRate;
    report.channels = channels;
    report.bitsPerSample = 16;
    report.durationMs = (bytes.length / (2 * channels * sampleRate)) * 1000;
    durationWarnings(report.durationMs, bytes.length, maxBytes, warnings);
    const a = analyzePcm16(bytes, 0, bytes.length);
    Object.assign(report, { peak: a.peak, rms: a.rms, rmsDbfs: rmsToDbfs(a.rms) });
    levelWarnings(a.peak, a.rms, warnings);
    return report;
  }
  if (report.container !== 'wav') {
    if (bytes.length > maxBytes) warnings.push(localWarning(90102));
    return report;
  }
  const wav = parseWav(bytes);
  report.sampleRate = wav.sampleRate || null;
  report.channels = wav.channels || null;
  report.bitsPerSample = wav.bitsPerSample || null;
  if (!wav.valid || !wav.isPcm16 || wav.sampleRate < 16000) {
    warnings.push(localWarning(90105));
    const byteRate = wav.byteRate || wav.sampleRate * wav.blockAlign;
    if (wav.valid && byteRate > 0) report.durationMs = (wav.dataLength / byteRate) * 1000;
    durationWarnings(report.durationMs, bytes.length, maxBytes, warnings);
    return report;
  }
  const byteRate = wav.byteRate || wav.sampleRate * wav.channels * 2;
  report.durationMs = (wav.dataLength / byteRate) * 1000;
  durationWarnings(report.durationMs, bytes.length, maxBytes, warnings);
  const a = analyzePcm16(bytes, wav.dataOffset, wav.dataLength);
  Object.assign(report, { peak: a.peak, rms: a.rms, rmsDbfs: rmsToDbfs(a.rms) });
  levelWarnings(a.peak, a.rms, warnings);
  return report;
}

/** Precheck of streamed PCM16 accumulated in a PcmStats (no format check, 10 MB per round). */
export function precheckPcmStats(stats, sampleRate, maxBytes) {
  const warnings = [];
  const durationMs = (stats.bytes / (2 * (sampleRate || 16000))) * 1000;
  durationWarnings(durationMs, stats.bytes, maxBytes || MAX_STREAM_BYTES, warnings);
  levelWarnings(stats.peak, stats.rms, warnings);
  return { durationMs, size: stats.bytes, peak: stats.peak, rms: stats.rms, rmsDbfs: rmsToDbfs(stats.rms), warnings };
}

/**
 * Applies the precheck mode to a list of warnings.
 * OFF returns []. WARN returns the warnings. REJECT throws AudioQualityException for the first
 * rejecting code (90101, 90102, 90103, 90105) and returns the rest.
 */
export function applyPrecheck(warnings, mode) {
  if (mode === AudioPrecheck.OFF) return [];
  if (mode === AudioPrecheck.REJECT) {
    const bad = warnings.find((w) => REJECTING.has(w.code));
    if (bad) {
      throw new AudioQualityException(`音频预检未通过：${bad.message}`, {
        code: bad.code,
        localWarnings: warnings.slice(),
      });
    }
  }
  return warnings.slice();
}

/** True for the precheck modes. */
export function isPrecheckMode(m) {
  return m === AudioPrecheck.OFF || m === AudioPrecheck.WARN || m === AudioPrecheck.REJECT;
}

/** Float32 samples in [-1, 1] to Int16 (clamped). */
export function floatToInt16(float32) {
  const out = new Int16Array(float32.length);
  for (let i = 0; i < float32.length; i++) {
    let s = float32[i];
    if (s > 1) s = 1;
    else if (s < -1) s = -1;
    out[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
  }
  return out;
}

/** PCM16 mono (or interleaved) samples to a WAV file as bytes. */
export function encodeWavBytes(pcm, sampleRate, channels) {
  const ch = channels || 1;
  const rate = sampleRate || 16000;
  let samples = pcm;
  if (!(pcm instanceof Int16Array)) {
    const b = toBytes(pcm);
    if (!b) throw localError(90010, 'encodeWav 需要 Int16Array、ArrayBuffer 或 Uint8Array');
    samples = new Int16Array(b.slice(0, b.length - (b.length % 2)).buffer);
  }
  const dataSize = samples.length * 2;
  const buffer = new ArrayBuffer(44 + dataSize);
  const v = new DataView(buffer);
  const w = (o, s) => {
    for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i));
  };
  w(0, 'RIFF');
  v.setUint32(4, 36 + dataSize, true);
  w(8, 'WAVE');
  w(12, 'fmt ');
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true);
  v.setUint16(22, ch, true);
  v.setUint32(24, rate, true);
  v.setUint32(28, rate * ch * 2, true);
  v.setUint16(32, ch * 2, true);
  v.setUint16(34, 16, true);
  w(36, 'data');
  v.setUint32(40, dataSize, true);
  for (let i = 0, o = 44; i < samples.length; i++, o += 2) v.setInt16(o, samples[i], true);
  return new Uint8Array(buffer);
}

/** PCM16 samples to a WAV Blob (audio/wav). */
export function encodeWav(pcm, sampleRate, channels) {
  return new Blob([encodeWavBytes(pcm, sampleRate, channels)], { type: 'audio/wav' });
}

/**
 * Normalize caller audio (Blob, File, ArrayBuffer, any ArrayBufferView) to bytes plus a filename
 * and a content type. format "pcm" wraps raw PCM16 into a WAV container first.
 */
export async function resolveAudioInput(input, options, what) {
  const opts = options || {};
  const label = what || 'audio';
  let bytes;
  let name = null;
  let type = null;
  if (isBlobLike(input)) {
    bytes = new Uint8Array(await readBlob(input));
    if (typeof input.name === 'string' && input.name) name = input.name;
    if (typeof input.type === 'string' && input.type) type = input.type;
  } else {
    bytes = toBytes(input);
    if (!bytes) throw localError(90010, `${label} 必须是 Blob、File、ArrayBuffer 或 Uint8Array`);
  }
  if (bytes.length === 0) throw localError(90010, `${label} 为空`);
  if (opts.audioFormat === 'pcm') {
    bytes = encodeWavBytes(bytes, opts.sampleRate || 16000, 1);
    name = null;
    type = null;
  }
  const container = sniffAudioContainer(bytes);
  const info = CONTAINER_INFO[container];
  return {
    bytes,
    container,
    filename: opts.filename || name || `${label}.${info.ext}`,
    contentType: opts.contentType || type || info.type,
  };
}
