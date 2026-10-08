// Local audio precheck (DESIGN 2.9). Thresholds of 90103 equal the platform's silence guard, so a file
// rejected here is one the platform would have scored 0.
import { LOCAL_TABLE } from './error-table.js';
import { createError, localError } from './errors.js';
import { parseWav, detectFormat } from './audio.js';
import { toUint8 } from './util.js';
import { MAX_UPLOAD_BYTES, MAX_STREAM_BYTES, MAX_AUDIO_SECONDS, MIN_AUDIO_SECONDS } from './constants.js';

export const AudioPrecheckMode = Object.freeze({ OFF: 'OFF', WARN: 'WARN', REJECT: 'REJECT' });

const SILENT_PEAK = 200;
const SILENT_RMS = 30;
const LOW_VOLUME_DBFS = -45;
const REJECT_CODES = [90101, 90102, 90103, 90105];

export function isPrecheckMode(v) {
  return v === 'OFF' || v === 'WARN' || v === 'REJECT';
}

/** Running peak and RMS of little-endian signed 16 bit samples, fed chunk by chunk. */
export class PcmStats {
  constructor() {
    this.samples = 0;
    this.peak = 0;
    this.sumSquares = 0;
    this.bytes = 0;
    this._carry = -1;
  }

  add(chunk) {
    const u8 = toUint8(chunk);
    if (!u8 || !u8.length) return;
    const n = u8.length;
    this.bytes += n;
    let i = 0;
    if (this._carry >= 0) {
      this._acc((((u8[0] << 8) | this._carry) << 16) >> 16);
      this._carry = -1;
      i = 1;
    }
    let peak = this.peak;
    let sum = 0;
    let count = 0;
    for (; i + 1 < n; i += 2) {
      const s = (((u8[i + 1] << 8) | u8[i]) << 16) >> 16;
      const a = s < 0 ? -s : s;
      if (a > peak) peak = a;
      sum += s * s;
      count++;
    }
    this.peak = peak;
    this.sumSquares += sum;
    this.samples += count;
    if (i < n) this._carry = u8[i];
  }

  _acc(s) {
    const a = s < 0 ? -s : s;
    if (a > this.peak) this.peak = a;
    this.sumSquares += s * s;
    this.samples++;
  }

  rms() {
    return this.samples ? Math.sqrt(this.sumSquares / this.samples) : 0;
  }
}

function warning(code) {
  return { code, message: LOCAL_TABLE[code].message };
}

function dbfs(rms) {
  return rms > 0 ? 20 * Math.log10(rms / 32768) : -Infinity;
}

/**
 * Evaluates the precheck rules on facts about the audio.
 * facts: {sizeBytes, maxBytes, durationSec, pcm16: boolean, stats: PcmStats|null, formatOk: boolean, sampleRate}
 * maxBytes: 50 MB for an upload (the default), 10 MB for one streaming round.
 */
export function precheckFacts(facts) {
  const codes = [];
  const add = (c) => {
    if (codes.indexOf(c) < 0) codes.push(c);
  };
  const maxBytes = typeof facts.maxBytes === 'number' ? facts.maxBytes : MAX_UPLOAD_BYTES;
  if (facts.sizeBytes > maxBytes) add(90102);
  if (facts.formatOk === false) add(90105);
  if (typeof facts.sampleRate === 'number' && facts.sampleRate > 0 && facts.sampleRate < 16000) add(90105);
  if (typeof facts.durationSec === 'number') {
    if (facts.durationSec < MIN_AUDIO_SECONDS) add(90101);
    if (facts.durationSec > MAX_AUDIO_SECONDS) add(90102);
  }
  if (facts.pcm16 && facts.stats && facts.stats.samples > 0) {
    const rms = facts.stats.rms();
    if (facts.stats.peak < SILENT_PEAK && rms < SILENT_RMS) add(90103);
    else if (dbfs(rms) < LOW_VOLUME_DBFS) add(90104);
  }
  codes.sort((a, b) => a - b);
  return codes.map(warning);
}

function report(format, facts, extra) {
  const stats = facts.stats;
  const rms = stats && stats.samples ? stats.rms() : null;
  return {
    format,
    sizeBytes: facts.sizeBytes,
    durationMs: typeof facts.durationSec === 'number' ? Math.round(facts.durationSec * 1000) : null,
    sampleRate: extra.sampleRate || null,
    channels: extra.channels || null,
    bitsPerSample: extra.bitsPerSample || null,
    peak: stats && stats.samples ? stats.peak : null,
    rms,
    rmsDbfs: rms === null ? null : dbfs(rms),
    warnings: precheckFacts(facts),
  };
}

/** Precheck of a WAV file or other container held in memory; maxBytes defaults to the 50 MB upload limit. */
export function analyzeContainer(bytes, format, maxBytes) {
  const b = toUint8(bytes);
  const limit = typeof maxBytes === 'number' ? maxBytes : MAX_UPLOAD_BYTES;
  const fmt = format || detectFormat(b) || 'unknown';
  if (fmt !== 'wav') return report(fmt, { sizeBytes: b.length, maxBytes: limit }, {});
  const wav = parseWav(b);
  if (!wav || !wav.ok) return report('wav', { sizeBytes: b.length, maxBytes: limit, formatOk: false }, {});
  const pcm16 = wav.audioFormat === 1 && wav.bitsPerSample === 16;
  const bytesPerSecond = wav.sampleRate * wav.channels * (wav.bitsPerSample / 8);
  const facts = {
    sizeBytes: b.length,
    maxBytes: limit,
    durationSec: bytesPerSecond > 0 ? wav.dataLength / bytesPerSecond : 0,
    formatOk: pcm16,
    sampleRate: wav.sampleRate,
    pcm16,
    stats: null,
  };
  if (pcm16) {
    facts.stats = new PcmStats();
    facts.stats.add(b.subarray(wav.dataOffset, wav.dataOffset + wav.dataLength));
  }
  return report('wav', facts, wav);
}

/**
 * Precheck of raw 16 bit PCM described by running stats: one streaming round at end() (limit 10 MB),
 * or PCM that will be uploaded when maxBytes says so.
 */
export function analyzePcmStats(stats, sampleRate, channels, maxBytes) {
  const sr = sampleRate || 16000;
  const ch = channels || 1;
  const facts = {
    sizeBytes: stats.bytes,
    maxBytes: typeof maxBytes === 'number' ? maxBytes : MAX_STREAM_BYTES,
    durationSec: stats.bytes / (sr * ch * 2),
    formatOk: true,
    sampleRate: sr,
    pcm16: true,
    stats,
  };
  return report('pcm', facts, { sampleRate: sr, channels: ch, bitsPerSample: 16 });
}

/**
 * Public precheck helper. input: WAV bytes, or raw PCM with options.format "pcm". The size limit is
 * the upload limit of 50 MB, or 10 MB with options.stream for the audio of one streaming round.
 * Returns {format, sizeBytes, durationMs, sampleRate, channels, bitsPerSample, peak, rms, rmsDbfs, warnings}.
 */
export function precheckAudio(input, options) {
  const o = options || {};
  const b = toUint8(input);
  if (!b) throw localError(90010, 'precheckAudio expects an ArrayBuffer or a typed array');
  const maxBytes = o.stream === true ? MAX_STREAM_BYTES : MAX_UPLOAD_BYTES;
  if (String(o.format || '').toLowerCase() === 'pcm' && detectFormat(b) !== 'wav') {
    const stats = new PcmStats();
    stats.add(b);
    return analyzePcmStats(stats, o.sampleRate, o.numberOfChannels, maxBytes);
  }
  return analyzeContainer(b, null, maxBytes);
}

/**
 * Applies a precheck mode to warnings. OFF: none. WARN: all are returned as warnings. REJECT: throws
 * AudioQualityException for 90101, 90102, 90103 and 90105, 90104 stays a warning.
 */
export function applyPrecheckMode(warnings, mode, init) {
  if (mode === 'OFF') return [];
  if (mode === 'REJECT') {
    for (let i = 0; i < warnings.length; i++) {
      if (REJECT_CODES.indexOf(warnings[i].code) >= 0) {
        const o = {};
        const src = init || {};
        Object.keys(src).forEach((k) => {
          o[k] = src[k];
        });
        o.code = warnings[i].code;
        o.warnings = warnings.slice();
        throw createError('AUDIO', 'audio precheck rejected the audio: ' + warnings[i].message, o);
      }
    }
  }
  return warnings.slice();
}
