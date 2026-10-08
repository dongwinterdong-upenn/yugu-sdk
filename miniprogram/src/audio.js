// Caller audio (DESIGN 2.10): ArrayBuffer, typed arrays, tempFilePath strings and recording results.
import { localError } from './errors.js';
import { toUint8, isBytesLike, isPlainObject } from './util.js';

const EXT_FORMAT = {
  wav: 'wav', wave: 'wav', mp3: 'mp3', pcm: 'pcm', raw: 'pcm', aac: 'aac', m4a: 'm4a', mp4: 'm4a',
  ogg: 'ogg', opus: 'ogg', amr: 'amr', webm: 'webm', flac: 'flac',
};

const CONTENT_TYPE = {
  wav: 'audio/wav', mp3: 'audio/mpeg', aac: 'audio/aac', m4a: 'audio/mp4', ogg: 'audio/ogg',
  amr: 'audio/amr', webm: 'audio/webm', flac: 'audio/flac',
};

const IMAGE_TYPE = { jpg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp' };

function ascii(b, off, len) {
  let s = '';
  for (let i = off; i < off + len && i < b.length; i++) s += String.fromCharCode(b[i]);
  return s;
}

function u16(b, off) {
  return b[off] | (b[off + 1] << 8);
}

function u32(b, off) {
  return (b[off] | (b[off + 1] << 8) | (b[off + 2] << 16) | (b[off + 3] << 24)) >>> 0;
}

/** Container format from magic bytes, or null when the bytes are not recognised (raw PCM included). */
export function detectFormat(b) {
  if (b.length >= 12 && ascii(b, 0, 4) === 'RIFF' && ascii(b, 8, 4) === 'WAVE') return 'wav';
  if (b.length >= 3 && ascii(b, 0, 3) === 'ID3') return 'mp3';
  if (b.length >= 4 && ascii(b, 0, 4) === 'OggS') return 'ogg';
  if (b.length >= 4 && ascii(b, 0, 4) === 'fLaC') return 'flac';
  if (b.length >= 5 && ascii(b, 0, 5) === '#!AMR') return 'amr';
  if (b.length >= 8 && ascii(b, 4, 4) === 'ftyp') return 'm4a';
  if (b.length >= 4 && b[0] === 0x1a && b[1] === 0x45 && b[2] === 0xdf && b[3] === 0xa3) return 'webm';
  if (b.length >= 2 && b[0] === 0xff && (b[1] & 0xf6) === 0xf0) return 'aac';
  if (b.length >= 2 && b[0] === 0xff && (b[1] & 0xe0) === 0xe0) return 'mp3';
  return null;
}

export function formatFromPath(path) {
  const m = /\.([a-z0-9]+)(?:[?#].*)?$/i.exec(String(path || ''));
  return m ? EXT_FORMAT[m[1].toLowerCase()] || null : null;
}

/**
 * Parses the RIFF chunks of a WAV file. Returns null when the bytes are not RIFF WAVE, or
 * {ok, audioFormat, channels, sampleRate, bitsPerSample, blockAlign, dataOffset, dataLength}.
 * ok is false when the fmt or data chunk is missing.
 */
export function parseWav(bytes) {
  const b = toUint8(bytes);
  if (!b || b.length < 12 || ascii(b, 0, 4) !== 'RIFF' || ascii(b, 8, 4) !== 'WAVE') return null;
  let off = 12;
  let fmt = null;
  let data = null;
  while (off + 8 <= b.length) {
    const id = ascii(b, off, 4);
    const size = u32(b, off + 4);
    const body = off + 8;
    if (id === 'fmt ' && size >= 16 && body + 16 <= b.length) {
      fmt = {
        audioFormat: u16(b, body),
        channels: u16(b, body + 2),
        sampleRate: u32(b, body + 4),
        blockAlign: u16(b, body + 12),
        bitsPerSample: u16(b, body + 14),
      };
      // WAVE_FORMAT_EXTENSIBLE: the real format tag starts the SubFormat GUID.
      if (fmt.audioFormat === 0xfffe && size >= 40 && body + 26 <= b.length) fmt.audioFormat = u16(b, body + 24);
    } else if (id === 'data') {
      const avail = b.length - body;
      data = { offset: body, length: size === 0 || size === 0xffffffff ? avail : Math.min(size, avail) };
      break;
    }
    off = body + size + (size & 1);
  }
  if (!fmt || !data) {
    return { ok: false, audioFormat: fmt ? fmt.audioFormat : 0, channels: fmt ? fmt.channels : 0,
      sampleRate: fmt ? fmt.sampleRate : 0, bitsPerSample: fmt ? fmt.bitsPerSample : 0, blockAlign: 0,
      dataOffset: data ? data.offset : 0, dataLength: data ? data.length : 0 };
  }
  return {
    ok: true,
    audioFormat: fmt.audioFormat,
    channels: fmt.channels,
    sampleRate: fmt.sampleRate,
    bitsPerSample: fmt.bitsPerSample,
    blockAlign: fmt.blockAlign,
    dataOffset: data.offset,
    dataLength: data.length,
  };
}

/** Wraps raw little-endian PCM in a 44 byte WAV header. */
export function pcmToWav(pcm, sampleRate, channels, bitsPerSample) {
  const data = toUint8(pcm);
  const sr = sampleRate || 16000;
  const ch = channels || 1;
  const bits = bitsPerSample || 16;
  const blockAlign = ch * (bits / 8);
  const out = new Uint8Array(44 + data.length);
  const dv = new DataView(out.buffer);
  const tag = (off, s) => {
    for (let i = 0; i < 4; i++) out[off + i] = s.charCodeAt(i);
  };
  tag(0, 'RIFF');
  dv.setUint32(4, 36 + data.length, true);
  tag(8, 'WAVE');
  tag(12, 'fmt ');
  dv.setUint32(16, 16, true);
  dv.setUint16(20, 1, true);
  dv.setUint16(22, ch, true);
  dv.setUint32(24, sr, true);
  dv.setUint32(28, sr * blockAlign, true);
  dv.setUint16(32, blockAlign, true);
  dv.setUint16(34, bits, true);
  tag(36, 'data');
  dv.setUint32(40, data.length, true);
  out.set(data, 44);
  return out;
}

/** Reads a local file (tempFilePath, wxfile:// or user data path) through wx.getFileSystemManager(). */
export function readFile(wxApi, filePath) {
  return new Promise((resolve, reject) => {
    let fsm;
    try {
      fsm = wxApi.getFileSystemManager();
    } catch (e) {
      reject(localError(90010, 'wx.getFileSystemManager is not available', { cause: e }));
      return;
    }
    fsm.readFile({
      filePath,
      success: (res) => {
        const u8 = toUint8(res && res.data);
        if (u8) resolve(u8);
        else reject(localError(90010, 'readFile returned no binary data for ' + filePath));
      },
      fail: (err) => {
        reject(localError(90010, 'cannot read file ' + filePath + ': ' + ((err && err.errMsg) || 'unknown error'), { cause: err }));
      },
    });
  });
}

function describe(input) {
  if (typeof input === 'string') return { path: input };
  if (isBytesLike(input)) return { data: input };
  if (isPlainObject(input)) {
    return {
      data: input.data,
      path: input.tempFilePath || input.filePath || input.path,
      format: input.format,
      sampleRate: input.sampleRate,
      numberOfChannels: input.numberOfChannels,
    };
  }
  return null;
}

async function loadBytes(input, wxApi, what) {
  const d = describe(input);
  if (!d) throw localError(90010, what + ' must be an ArrayBuffer, a typed array, a tempFilePath or {tempFilePath}');
  let bytes;
  if (d.data !== undefined && d.data !== null) {
    bytes = toUint8(d.data);
    if (!bytes) throw localError(90010, what + '.data must be an ArrayBuffer or a typed array');
  } else if (typeof d.path === 'string' && d.path) {
    bytes = await readFile(wxApi, d.path);
  } else {
    throw localError(90010, what + ' is required');
  }
  if (bytes.length === 0) throw localError(90010, what + ' is empty');
  return { bytes, d };
}

/**
 * Loads caller audio into bytes ready for the `audio` part.
 * hints: {format, sampleRate, numberOfChannels}. Raw PCM (format "pcm", a .pcm path or a recording
 * result of the SDK recorder) is wrapped in a WAV header, 16000 Hz mono 16 bit unless told otherwise.
 * Returns {bytes, format, sourceFormat, filename, contentType, wav}.
 */
export async function loadAudio(input, wxApi, hints) {
  const h = hints || {};
  const loaded = await loadBytes(input, wxApi, 'audio');
  const d = loaded.d;
  let bytes = loaded.bytes;
  const hint = String(h.format || d.format || '').toLowerCase() || null;
  const detected = detectFormat(bytes);
  let format = detected;
  if (!format && hint) format = EXT_FORMAT[hint] || hint;
  if (!format && d.path) format = formatFromPath(d.path);
  if (hint === 'pcm' && detected !== 'wav') format = 'pcm';
  if (!format) format = 'unknown';
  const sourceFormat = format;
  if (format === 'pcm') {
    bytes = pcmToWav(bytes, h.sampleRate || d.sampleRate || 16000, h.numberOfChannels || d.numberOfChannels || 1, 16);
    format = 'wav';
  }
  return {
    bytes,
    format,
    sourceFormat,
    filename: 'audio.' + (format === 'unknown' ? 'bin' : format),
    contentType: CONTENT_TYPE[format] || 'application/octet-stream',
    wav: format === 'wav' ? parseWav(bytes) : null,
  };
}

/** Loads the optional picture of open questions (coreType open, taskType picture). */
export async function loadImage(input, wxApi) {
  const loaded = await loadBytes(input, wxApi, 'image');
  const b = loaded.bytes;
  let ext = 'bin';
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) ext = 'jpg';
  else if (b.length >= 4 && b[0] === 0x89 && ascii(b, 1, 3) === 'PNG') ext = 'png';
  else if (b.length >= 4 && ascii(b, 0, 4) === 'GIF8') ext = 'gif';
  else if (b.length >= 12 && ascii(b, 0, 4) === 'RIFF' && ascii(b, 8, 4) === 'WEBP') ext = 'webp';
  else if (b.length >= 2 && ascii(b, 0, 2) === 'BM') ext = 'bmp';
  return { bytes: b, filename: 'image.' + ext, contentType: IMAGE_TYPE[ext] || 'application/octet-stream' };
}
