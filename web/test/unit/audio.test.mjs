import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  analyzePcm16,
  applyPrecheck,
  AudioPrecheck,
  encodeWav,
  encodeWavBytes,
  floatToInt16,
  isPrecheckMode,
  localWarning,
  MAX_STREAM_BYTES,
  MAX_UPLOAD_BYTES,
  parseWav,
  PcmStats,
  precheckAudio,
  precheckPcmStats,
  resolveAudioInput,
  sniffAudioContainer,
} from '../../src/audio.js';
import { AudioQualityException, InvalidParameterException } from '../../src/errors.js';
import { pcmOf, readAudio } from '../helpers/common.mjs';

const codes = (r) => r.warnings.map((w) => w.code);

function wavHeader({ format = 1, channels = 1, rate = 16000, bits = 16, dataBytes = 0, extensible = false }) {
  const fmtSize = extensible ? 40 : 16;
  const buf = new ArrayBuffer(12 + 8 + fmtSize + 8 + dataBytes);
  const v = new DataView(buf);
  const w = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  w(0, 'RIFF');
  v.setUint32(4, buf.byteLength - 8, true);
  w(8, 'WAVE');
  w(12, 'fmt ');
  v.setUint32(16, fmtSize, true);
  v.setUint16(20, extensible ? 0xfffe : format, true);
  v.setUint16(22, channels, true);
  v.setUint32(24, rate, true);
  v.setUint32(28, (rate * channels * bits) / 8, true);
  v.setUint16(32, (channels * bits) / 8, true);
  v.setUint16(34, bits, true);
  if (extensible) v.setUint16(20 + 24, format, true);
  w(20 + fmtSize, 'data');
  v.setUint32(24 + fmtSize, dataBytes, true);
  return new Uint8Array(buf);
}

function sineWav(seconds, amp = 8000, rate = 16000) {
  const n = Math.round(seconds * rate);
  const pcm = new Int16Array(n);
  for (let i = 0; i < n; i++) pcm[i] = Math.round(amp * Math.sin((2 * Math.PI * 300 * i) / rate));
  return encodeWavBytes(pcm, rate);
}

describe('WAV parsing', () => {
  test('fixtures with a LIST chunk before data', () => {
    for (const [name, seconds] of [['zh_short.wav', 1.92], ['en_apple.wav', 2.48], ['silent.wav', 2.5], ['low_volume.wav', 1.92]]) {
      const info = parseWav(readAudio(name));
      assert.equal(info.valid, true, name);
      assert.equal(info.isPcm16, true);
      assert.equal(info.sampleRate, 16000);
      assert.equal(info.channels, 1);
      assert.ok(info.dataOffset > 44, `${name}: data after the LIST chunk`);
      assert.equal(Math.round((info.dataLength / 32000) * 100) / 100, seconds);
    }
  });
  test('not WAV gives null; header without data chunk is invalid', () => {
    assert.equal(parseWav(new Uint8Array([1, 2, 3])), null);
    assert.equal(parseWav('nope'), null);
    const h = wavHeader({ dataBytes: 0 }).slice(0, 36);
    assert.equal(parseWav(h).valid, false);
    const noFmt = new Uint8Array([...new TextEncoder().encode('RIFF'), 4, 0, 0, 0, ...new TextEncoder().encode('WAVE')]);
    assert.equal(parseWav(noFmt).valid, false);
  });
  test('data size 0 or larger than the file is clamped to the bytes present', () => {
    const h = wavHeader({ dataBytes: 3200 });
    new DataView(h.buffer).setUint32(40, 0, true);
    assert.equal(parseWav(h).dataLength, 3200);
    new DataView(h.buffer).setUint32(40, 0xffffffff, true);
    assert.equal(parseWav(h).dataLength, 3200);
  });
  test('WAVE_FORMAT_EXTENSIBLE with PCM sub format', () => {
    const info = parseWav(wavHeader({ extensible: true, dataBytes: 320 }));
    assert.equal(info.audioFormat, 1);
    assert.equal(info.isPcm16, true);
  });
  test('container sniffing', () => {
    assert.equal(sniffAudioContainer(readAudio('zh_short.wav')), 'wav');
    assert.equal(sniffAudioContainer(new TextEncoder().encode('ID3\u0004')), 'mp3');
    assert.equal(sniffAudioContainer(new Uint8Array([0xff, 0xfb, 0x90, 0])), 'mp3');
    assert.equal(sniffAudioContainer(new TextEncoder().encode('OggS....')), 'ogg');
    assert.equal(sniffAudioContainer(new TextEncoder().encode('fLaC....')), 'flac');
    assert.equal(sniffAudioContainer(new TextEncoder().encode('....ftypM4A ')), 'mp4');
    assert.equal(sniffAudioContainer(new Uint8Array([0x1a, 0x45, 0xdf, 0xa3])), 'webm');
    assert.equal(sniffAudioContainer(new Uint8Array([0, 0, 0, 0])), 'unknown');
  });
});

describe('audio precheck (C-04, DESIGN 2.9)', () => {
  test('good fixtures have no warnings', () => {
    for (const name of ['zh_short.wav', 'en_apple.wav', 'en_abc.wav', 'en_fox.wav', 'zh_para.wav', 'g9_zh_selfintro.wav']) {
      const r = precheckAudio(readAudio(name));
      assert.deepEqual(codes(r), [], name);
      assert.equal(r.container, 'wav');
      assert.ok(r.durationMs > 1000);
    }
  });
  test('silent.wav is 90103 (peak < 200 and RMS < 30), not also 90104', () => {
    const r = precheckAudio(readAudio('silent.wav'));
    assert.deepEqual(codes(r), [90103]);
    assert.equal(r.peak, 0);
  });
  test('low_volume.wav is 90104 (RMS below -45 dBFS)', () => {
    const r = precheckAudio(readAudio('low_volume.wav'));
    assert.deepEqual(codes(r), [90104]);
    assert.ok(r.rmsDbfs < -45 && r.peak >= 200);
  });
  test('shorter than 1 s is 90101', () => {
    assert.deepEqual(codes(precheckAudio(sineWav(0.5))), [90101]);
    assert.deepEqual(codes(precheckAudio(sineWav(1.0))), []);
  });
  test('90102: longer than 300 s, REST upload larger than 50 MB', () => {
    assert.equal(MAX_UPLOAD_BYTES, 50 * 1024 * 1024);
    assert.equal(MAX_STREAM_BYTES, 10 * 1024 * 1024);
    assert.deepEqual(codes(precheckAudio(sineWav(301))), [90102]);
    // 11 MB of 48 kHz stereo PCM is 60 s: fine for a REST upload (the old 10 MB rule no longer applies).
    const elevenMb = wavHeader({ rate: 48000, channels: 2, dataBytes: 11 * 1024 * 1024 });
    for (let i = 46; i < elevenMb.length; i += 97) elevenMb[i] = 0x20;
    assert.deepEqual(codes(precheckAudio(elevenMb)), []);
    const mp3 = new Uint8Array(MAX_UPLOAD_BYTES + 1);
    mp3.set(new TextEncoder().encode('ID3'));
    assert.deepEqual(codes(precheckAudio(mp3)), [90102]);
    assert.deepEqual(codes(precheckAudio(mp3.subarray(0, 20 * 1024 * 1024))), []);
    assert.deepEqual(codes(precheckAudio(mp3.subarray(0, 2000), { maxBytes: 1000 })), [90102]);
  });
  test('unsupported WAV is 90105: 8-bit, float, 8 kHz', () => {
    assert.ok(codes(precheckAudio(wavHeader({ bits: 8, dataBytes: 16000 }))).includes(90105));
    assert.ok(codes(precheckAudio(wavHeader({ format: 3, bits: 32, dataBytes: 64000 * 2 }))).includes(90105));
    const r = precheckAudio(wavHeader({ rate: 8000, dataBytes: 16000 * 2 }));
    assert.deepEqual(codes(r), [90105]);
    assert.equal(r.durationMs, 2000);
    assert.deepEqual(codes(precheckAudio(wavHeader({ dataBytes: 0 }).slice(0, 36))), [90105]);
  });
  test('other containers only get the size check', () => {
    const mp3 = new Uint8Array(2000);
    mp3.set(new TextEncoder().encode('ID3'));
    const r = precheckAudio(mp3);
    assert.deepEqual(codes(r), []);
    assert.equal(r.durationMs, null);
  });
  test('raw PCM (format pcm) gets every check', () => {
    const pcm = pcmOf(readAudio('zh_short.wav'));
    const r = precheckAudio(pcm, { format: 'pcm' });
    assert.equal(r.container, 'pcm');
    assert.equal(Math.round(r.durationMs), 1920);
    assert.deepEqual(codes(r), []);
    assert.deepEqual(codes(precheckAudio(new Uint8Array(8000), { format: 'pcm' })), [90101, 90103]);
    assert.throws(() => precheckAudio('x'), InvalidParameterException);
  });
  test('modes: OFF drops, WARN keeps, REJECT throws for 90101, 90102, 90103, 90105 but not 90104', () => {
    const silent = precheckAudio(readAudio('silent.wav')).warnings;
    assert.deepEqual(applyPrecheck(silent, AudioPrecheck.OFF), []);
    assert.deepEqual(applyPrecheck(silent, AudioPrecheck.WARN).map((w) => w.code), [90103]);
    assert.throws(() => applyPrecheck(silent, AudioPrecheck.REJECT), (e) => e instanceof AudioQualityException && e.code === 90103 && e.localWarnings.length === 1);
    const low = precheckAudio(readAudio('low_volume.wav')).warnings;
    assert.deepEqual(applyPrecheck(low, AudioPrecheck.REJECT).map((w) => w.code), [90104]);
    for (const code of [90101, 90102, 90105]) {
      assert.throws(() => applyPrecheck([localWarning(code)], 'REJECT'), (e) => e.code === code);
    }
    assert.equal(isPrecheckMode('WARN'), true);
    assert.equal(isPrecheckMode('warn'), false);
  });
  test('local warning objects', () => {
    assert.deepEqual({ ...localWarning(90103) }, { code: 90103, name: 'AUDIO_SILENT', message: '音频全程静音' });
    assert.equal(localWarning(1).name, '1');
  });
});

describe('PCM statistics for streams', () => {
  test('odd chunk boundaries give the same peak and RMS', () => {
    const pcm = pcmOf(readAudio('zh_short.wav'));
    const whole = analyzePcm16(pcm, 0, pcm.length);
    const st = new PcmStats();
    for (let i = 0; i < pcm.length; i += 333) st.add(pcm.subarray(i, i + 333));
    assert.equal(st.peak, whole.peak);
    assert.equal(st.samples, whole.samples);
    assert.ok(Math.abs(st.rms - whole.rms) < 1e-9);
    st.add(new Uint8Array(0));
    st.add('ignored');
    assert.equal(new PcmStats().rms, 0);
  });
  test('stream precheck: a round larger than 10 MB is 90102', () => {
    const st = new PcmStats();
    st.add(new Uint8Array(16000));
    assert.deepEqual(precheckPcmStats(st, 16000).warnings.map((w) => w.code), [90101, 90103]);
    assert.equal(precheckPcmStats(st).durationMs, 500);
    const loud = new Uint8Array(MAX_STREAM_BYTES + 2).map((_, i) => (i % 2 ? 0x10 : 0));
    const big = new PcmStats();
    big.add(loud);
    // 10 MB at 48 kHz is about 109 s, so only the size rule fires.
    assert.deepEqual(precheckPcmStats(big, 48000).warnings.map((w) => w.code), [90102]);
    const ok = new PcmStats();
    ok.add(loud.subarray(0, MAX_STREAM_BYTES));
    assert.deepEqual(precheckPcmStats(ok, 48000).warnings.map((w) => w.code), []);
  });
});

describe('WAV encoding and caller audio', () => {
  test('encodeWav round trip', async () => {
    const pcm = floatToInt16(new Float32Array([0, 0.5, -0.5, 1.5, -1.5]));
    assert.deepEqual([...pcm], [0, 16383, -16384, 32767, -32768]);
    const bytes = encodeWavBytes(pcm, 16000);
    const info = parseWav(bytes);
    assert.equal(info.dataLength, 10);
    assert.equal(info.dataOffset, 44);
    const blob = encodeWav(pcm, 16000);
    assert.equal(blob.type, 'audio/wav');
    assert.deepEqual(new Uint8Array(await blob.arrayBuffer()), bytes);
    assert.equal(parseWav(encodeWavBytes(new Uint8Array([1, 0, 2]), 8000)).dataLength, 2);
    assert.throws(() => encodeWavBytes('x'), InvalidParameterException);
  });
  test('Blob, File, ArrayBuffer and typed arrays', async () => {
    const wav = readAudio('zh_short.wav');
    const viaBlob = await resolveAudioInput(new Blob([wav], { type: 'audio/x-wav' }));
    assert.equal(viaBlob.filename, 'audio.wav');
    assert.equal(viaBlob.contentType, 'audio/x-wav');
    const viaFile = await resolveAudioInput(new File([wav], 'me.wav'));
    assert.equal(viaFile.filename, 'me.wav');
    assert.equal(viaFile.contentType, 'audio/wav');
    const viaAb = await resolveAudioInput(wav.buffer.slice(wav.byteOffset, wav.byteOffset + wav.byteLength));
    assert.equal(viaAb.bytes.length, wav.length);
    const viaView = await resolveAudioInput(new DataView(wav.buffer, wav.byteOffset, wav.byteLength));
    assert.equal(viaView.container, 'wav');
    const mp3 = await resolveAudioInput(new Uint8Array([0x49, 0x44, 0x33, 4, 0]));
    assert.deepEqual([mp3.filename, mp3.contentType], ['audio.mp3', 'audio/mpeg']);
    const unknown = await resolveAudioInput(new Uint8Array([1, 2, 3]), { filename: 'x.bin', contentType: 'audio/x-custom' });
    assert.deepEqual([unknown.filename, unknown.contentType], ['x.bin', 'audio/x-custom']);
    const sab = new SharedArrayBuffer(4);
    assert.equal((await resolveAudioInput(sab)).bytes.length, 4);
  });
  test('Blob without arrayBuffer() (Chrome 73 to 75, Safari 13) is read through FileReader', async () => {
    const wav = readAudio('zh_short.wav');
    const savedFR = globalThis.FileReader;
    const savedAB = Blob.prototype.arrayBuffer;
    class FakeFileReader {
      readAsArrayBuffer(blob) {
        assert.equal(blob.size, wav.length);
        queueMicrotask(() => {
          this.result = wav.slice().buffer;
          this.onload();
        });
      }
    }
    globalThis.FileReader = FakeFileReader;
    const blob = new Blob([wav], { type: 'audio/wav' });
    Object.defineProperty(blob, 'arrayBuffer', { value: undefined });
    try {
      const r = await resolveAudioInput(blob);
      assert.equal(r.bytes.length, wav.length);
      assert.equal(r.container, 'wav');
      class Failing {
        readAsArrayBuffer() {
          this.error = new Error('read failed');
          queueMicrotask(() => this.onerror());
        }
      }
      globalThis.FileReader = Failing;
      await assert.rejects(resolveAudioInput(blob), /read failed/);
    } finally {
      if (savedFR) globalThis.FileReader = savedFR;
      else delete globalThis.FileReader;
      assert.equal(Blob.prototype.arrayBuffer, savedAB);
    }
  });

  test('raw PCM is wrapped into WAV', async () => {
    const pcm = pcmOf(readAudio('zh_short.wav'));
    const r = await resolveAudioInput(new File([pcm], 'a.pcm'), { audioFormat: 'pcm', sampleRate: 16000 });
    assert.equal(r.container, 'wav');
    assert.equal(r.filename, 'audio.wav');
    assert.equal(parseWav(r.bytes).dataLength, pcm.length);
  });
  test('bad and empty input', async () => {
    await assert.rejects(resolveAudioInput('a string'), (e) => e instanceof InvalidParameterException && /Blob/.test(e.message));
    await assert.rejects(resolveAudioInput(new Uint8Array(0)), (e) => e.code === 90010 && /为空/.test(e.message));
  });
});
