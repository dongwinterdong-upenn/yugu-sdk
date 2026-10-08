// Local audio precheck (DESIGN 2.9, C-04).
import test from 'node:test';
import assert from 'node:assert/strict';
import { fixtureBytes, wavPcm } from '../helpers/common.mjs';
import {
  precheckAudio, analyzeContainer, analyzePcmStats, applyPrecheckMode, PcmStats, precheckFacts, AudioPrecheckMode,
} from '../../src/precheck.js';
import { pcmToWav } from '../../src/audio.js';
import { AudioQualityException, InvalidParameterException } from '../../src/errors.js';

const codes = (r) => r.warnings.map((w) => w.code);

function sineWav(seconds, { rate = 16000, amp = 8000, bits = 16, format = 1, channels = 1 } = {}) {
  const n = Math.round(seconds * rate);
  const pcm = Buffer.alloc(n * 2 * channels);
  for (let i = 0; i < n; i++) for (let c = 0; c < channels; c++) pcm.writeInt16LE(Math.round(amp * Math.sin(i / 7)), (i * channels + c) * 2);
  const wav = Buffer.from(pcmToWav(pcm, rate, channels, 16));
  if (bits !== 16) wav.writeUInt16LE(bits, 34);
  if (format !== 1) wav.writeUInt16LE(format, 20);
  return wav;
}

test('fixtures: clean speech passes, silence and low volume are flagged', () => {
  for (const f of ['zh_short.wav', 'en_apple.wav', 'en_abc.wav', 'en_fox.wav', 'zh_para.wav', 'g9_zh_selfintro.wav']) {
    const r = precheckAudio(fixtureBytes('audio/' + f));
    assert.deepEqual(codes(r), [], f);
    assert.equal(r.sampleRate, 16000);
    assert.equal(r.channels, 1);
    assert.equal(r.bitsPerSample, 16);
  }
  const zh = precheckAudio(fixtureBytes('audio/zh_short.wav'));
  assert.equal(zh.durationMs, 1920);
  assert.equal(zh.format, 'wav');
  assert.ok(zh.rmsDbfs > -20 && zh.peak > 30000);
  assert.deepEqual(codes(precheckAudio(fixtureBytes('audio/silent.wav'))), [90103]);
  const low = precheckAudio(fixtureBytes('audio/low_volume.wav'));
  assert.deepEqual(codes(low), [90104]);
  assert.ok(low.rmsDbfs < -45);
  assert.equal(precheckAudio(fixtureBytes('audio/silent.wav')).rmsDbfs, -Infinity);
});

test('duration, size and format rules', () => {
  assert.deepEqual(codes(precheckAudio(sineWav(0.5))), [90101]);
  assert.deepEqual(codes(precheckAudio(sineWav(1.0))), []);
  assert.deepEqual(codes(precheckAudio(sineWav(301))), [90102]);
  assert.deepEqual(codes(precheckAudio(sineWav(2, { rate: 8000 }))), [90105]);
  assert.deepEqual(codes(precheckAudio(sineWav(2, { bits: 8 }))), [90105]);
  assert.deepEqual(codes(precheckAudio(sineWav(2, { format: 3 }))), [90105]);
  assert.deepEqual(codes(precheckAudio(sineWav(2, { channels: 2 }))), []);
  const ext = sineWav(2);
  ext.writeUInt16LE(0xfffe, 20);
  assert.deepEqual(codes(precheckAudio(ext)), [90105], 'extensible header without SubFormat is not PCM');
  // uploads: 50 MB, the platform's request body limit
  const eleven = Buffer.alloc(11 * 1024 * 1024);
  eleven.write('ID3', 0);
  assert.deepEqual(codes(analyzeContainer(eleven, null)), [], '11 MB is fine for an upload');
  const big = Buffer.alloc(50 * 1024 * 1024 + 1);
  big.write('ID3', 0);
  const mp3 = analyzeContainer(big, null);
  assert.equal(mp3.format, 'mp3');
  assert.deepEqual(codes(mp3), [90102]);
  assert.deepEqual(codes(precheckAudio(eleven, { stream: true })), [90102], 'one streaming round is limited to 10 MB');
  assert.deepEqual(codes(analyzeContainer(Buffer.from('ID3 small'), null)), []);
  const broken = Buffer.from('RIFF\x10\x00\x00\x00WAVEjunk\x00\x00\x00\x00', 'latin1');
  assert.deepEqual(codes(precheckAudio(broken)), [90105]);
});

test('raw PCM and streaming statistics', () => {
  const pcm = wavPcm('audio/zh_short.wav');
  const r = precheckAudio(pcm, { format: 'pcm', sampleRate: 16000 });
  assert.equal(r.format, 'pcm');
  assert.equal(r.durationMs, 1920);
  assert.deepEqual(codes(r), []);
  const once = new PcmStats();
  once.add(pcm);
  const chunked = new PcmStats();
  for (let i = 0; i < pcm.length; i += 333) chunked.add(pcm.subarray(i, i + 333));
  assert.equal(chunked.samples, once.samples);
  assert.equal(chunked.peak, once.peak);
  assert.equal(chunked.sumSquares, once.sumSquares);
  chunked.add(new Uint8Array(0));
  assert.deepEqual(codes(analyzePcmStats(new PcmStats(), 16000)), [90101]);
  assert.deepEqual(codes(analyzePcmStats(once, 8000)), [90105]);
  // a streaming round above 10 MB (48 kHz stereo keeps the duration under 300 s)
  const round = new PcmStats();
  round.add(new Uint8Array(11 * 1024 * 1024).fill(7));
  assert.deepEqual(codes(analyzePcmStats(round, 48000, 2)), [90102]);
  assert.deepEqual(codes(analyzePcmStats(round, 48000, 2, 50 * 1024 * 1024)), []);
  const pcm11 = new Uint8Array(11 * 1024 * 1024).fill(7);
  assert.deepEqual(codes(precheckAudio(pcm11, { format: 'pcm', sampleRate: 48000, numberOfChannels: 2 })), [], 'PCM for an upload');
  assert.deepEqual(codes(precheckAudio(pcm11, { format: 'pcm', sampleRate: 48000, numberOfChannels: 2, stream: true })), [90102]);
  assert.throws(() => precheckAudio('nope'), InvalidParameterException);
});

test('modes: WARN returns warnings, REJECT throws for 90101, 90102, 90103, 90105 only, OFF ignores', () => {
  assert.deepEqual(Object.values(AudioPrecheckMode), ['OFF', 'WARN', 'REJECT']);
  const silent = precheckAudio(fixtureBytes('audio/silent.wav')).warnings;
  assert.deepEqual(applyPrecheckMode(silent, 'WARN').map((w) => w.code), [90103]);
  assert.deepEqual(applyPrecheckMode(silent, 'OFF'), []);
  assert.throws(() => applyPrecheckMode(silent, 'REJECT', { idempotencyKey: 'k', op: 'evaluate' }), (e) => {
    assert.ok(e instanceof AudioQualityException);
    assert.equal(e.code, 90103);
    assert.equal(e.idempotencyKey, 'k');
    assert.equal(e.warnings.length, 1);
    return true;
  });
  const low = precheckAudio(fixtureBytes('audio/low_volume.wav')).warnings;
  assert.deepEqual(applyPrecheckMode(low, 'REJECT').map((w) => w.code), [90104]);
  const facts = precheckFacts({ sizeBytes: 11 * 1024 * 1024, maxBytes: 10 * 1024 * 1024, durationSec: 0.2, formatOk: false, sampleRate: 8000 });
  assert.deepEqual(facts.map((w) => w.code), [90101, 90102, 90105]);
  assert.deepEqual(precheckFacts({ sizeBytes: 11 * 1024 * 1024 }).map((w) => w.code), [], 'the default limit is the 50 MB upload limit');
  assert.match(precheckFacts({ sizeBytes: 60 * 1024 * 1024 })[0].message, /50 MB/);
});
