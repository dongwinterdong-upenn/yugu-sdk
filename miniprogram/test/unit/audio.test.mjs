// External audio (DESIGN 2.10, C-06): ArrayBuffer, typed arrays, tempFilePath and recording results.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createWxMock } from '../helpers/wx-mock.mjs';
import { fixtureBytes, toArrayBuffer, wavPcm } from '../helpers/common.mjs';
import { detectFormat, formatFromPath, parseWav, pcmToWav, loadAudio, loadImage, readFile } from '../../src/audio.js';
import { InvalidParameterException } from '../../src/errors.js';

const wx = createWxMock();
test.after(() => wx.__mock.cleanup());

test('container detection from magic bytes and extensions', () => {
  const b = (...x) => new Uint8Array(x);
  assert.equal(detectFormat(fixtureBytes('audio/zh_short.wav')), 'wav');
  assert.equal(detectFormat(Buffer.from('ID3\x04\x00')), 'mp3');
  assert.equal(detectFormat(b(0xff, 0xfb, 0x90, 0x00)), 'mp3');
  assert.equal(detectFormat(b(0xff, 0xf1, 0x50, 0x80)), 'aac');
  assert.equal(detectFormat(Buffer.from('OggS\x00\x02')), 'ogg');
  assert.equal(detectFormat(Buffer.from('fLaC\x00')), 'flac');
  assert.equal(detectFormat(Buffer.from('#!AMR\n')), 'amr');
  assert.equal(detectFormat(Buffer.from('\x00\x00\x00\x20ftypM4A ', 'latin1')), 'm4a');
  assert.equal(detectFormat(b(0x1a, 0x45, 0xdf, 0xa3)), 'webm');
  assert.equal(detectFormat(b(1, 2, 3, 4)), null);
  assert.equal(formatFromPath('wxfile://tmp_ab12.PCM'), 'pcm');
  assert.equal(formatFromPath('http://tmp/rec.mp3?x=1'), 'mp3');
  assert.equal(formatFromPath('/a/b/c'), null);
  assert.equal(formatFromPath(undefined), null);
});

test('WAV parsing walks every chunk', () => {
  const w = parseWav(fixtureBytes('audio/zh_short.wav'));
  assert.deepEqual({ ...w }, { ok: true, audioFormat: 1, channels: 1, sampleRate: 16000, bitsPerSample: 16, blockAlign: 2, dataOffset: 78, dataLength: 61440 });
  assert.equal(parseWav(new Uint8Array(4)), null);
  assert.equal(parseWav(Buffer.from('RIFF\x00\x00\x00\x00WAVE', 'latin1')).ok, false);
  const streaming = Buffer.from(pcmToWav(new Uint8Array(100), 16000, 1, 16));
  streaming.writeUInt32LE(0xffffffff, 40);
  assert.equal(parseWav(streaming).dataLength, 100);
});

test('PCM is wrapped in a 44 byte WAV header', () => {
  const pcm = new Uint8Array([1, 0, 2, 0]);
  const wav = Buffer.from(pcmToWav(pcm, 16000, 1, 16));
  assert.equal(wav.length, 48);
  assert.equal(wav.toString('ascii', 0, 4), 'RIFF');
  assert.equal(wav.readUInt32LE(4), 40);
  assert.equal(wav.toString('ascii', 8, 16), 'WAVEfmt ');
  assert.equal(wav.readUInt16LE(20), 1);
  assert.equal(wav.readUInt16LE(22), 1);
  assert.equal(wav.readUInt32LE(24), 16000);
  assert.equal(wav.readUInt32LE(28), 32000);
  assert.equal(wav.readUInt16LE(32), 2);
  assert.equal(wav.readUInt16LE(34), 16);
  assert.equal(wav.toString('ascii', 36, 40), 'data');
  assert.equal(wav.readUInt32LE(40), 4);
  assert.deepEqual([...wav.subarray(44)], [1, 0, 2, 0]);
  assert.equal(pcmToWav(pcm).length, 48);
});

test('audio from ArrayBuffer, typed array views and tempFilePath', async () => {
  const bytes = fixtureBytes('audio/zh_short.wav');
  const a = await loadAudio(toArrayBuffer(bytes), wx);
  assert.equal(a.format, 'wav');
  assert.equal(a.filename, 'audio.wav');
  assert.equal(a.contentType, 'audio/wav');
  assert.equal(a.bytes.length, bytes.length);
  const padded = Buffer.concat([Buffer.from('xxxx'), bytes]);
  const view = new Uint8Array(padded.buffer, padded.byteOffset + 4, bytes.length);
  assert.equal((await loadAudio(view, wx)).bytes.length, bytes.length);
  const file = wx.__mock.writeTemp('in.wav', bytes);
  const f = await loadAudio(file, wx);
  assert.equal(f.wav.sampleRate, 16000);
  const g = await loadAudio({ filePath: file }, wx);
  assert.equal(g.bytes.length, bytes.length);
});

test('raw PCM from a recording result, a .pcm path or a format hint is wrapped as WAV', async () => {
  const pcm = wavPcm('audio/zh_short.wav');
  const file = wx.__mock.writeTemp('recording.pcm', pcm);
  for (const input of [{ tempFilePath: file, format: 'pcm', sampleRate: 16000, numberOfChannels: 1 }, file, { data: toArrayBuffer(pcm), format: 'PCM' }]) {
    const a = await loadAudio(input, wx);
    assert.equal(a.format, 'wav');
    assert.equal(a.sourceFormat, 'pcm');
    assert.equal(a.bytes.length, pcm.length + 44);
    assert.equal(a.wav.dataLength, pcm.length);
  }
  const hinted = await loadAudio(toArrayBuffer(pcm), wx, { format: 'pcm', sampleRate: 22050 });
  assert.equal(hinted.wav.sampleRate, 22050);
  // a real WAV labelled pcm stays as it is
  const wav = await loadAudio(fixtureBytes('audio/zh_short.wav'), wx, { format: 'pcm' });
  assert.equal(wav.sourceFormat, 'wav');
});

test('other containers pass through with their content type', async () => {
  const mp3 = await loadAudio(Buffer.from('ID3\x04\x00\x00\x00\x00'), wx);
  assert.deepEqual([mp3.format, mp3.filename, mp3.contentType], ['mp3', 'audio.mp3', 'audio/mpeg']);
  const unknown = await loadAudio(new Uint8Array([1, 2, 3]), wx);
  assert.deepEqual([unknown.format, unknown.filename, unknown.contentType], ['unknown', 'audio.bin', 'application/octet-stream']);
  const m4a = await loadAudio(new Uint8Array([1, 2, 3]), wx, { format: 'm4a' });
  assert.deepEqual([m4a.filename, m4a.contentType], ['audio.m4a', 'audio/mp4']);
});

test('missing, empty and unsupported audio inputs are InvalidParameterException 90010', async () => {
  const is90010 = (e) => e instanceof InvalidParameterException && e.code === 90010;
  await assert.rejects(loadAudio('/no/such/file.wav', wx), (e) => is90010(e) && /cannot read file/.test(e.message));
  await assert.rejects(loadAudio(new ArrayBuffer(0), wx), is90010);
  await assert.rejects(loadAudio(42, wx), is90010);
  await assert.rejects(loadAudio({ data: 'text' }, wx), is90010);
  await assert.rejects(loadAudio({}, wx), is90010);
  await assert.rejects(readFile({ getFileSystemManager() { throw new Error('x'); } }, 'a'), is90010);
  await assert.rejects(readFile({ getFileSystemManager: () => ({ readFile: (o) => o.success({ data: 'str' }) }) }, 'a'), is90010);
});

test('images for open questions with pictures', async () => {
  const cases = [
    [[0xff, 0xd8, 0xff, 0xe0], 'image.jpg', 'image/jpeg'],
    [[0x89, 0x50, 0x4e, 0x47], 'image.png', 'image/png'],
    [[...Buffer.from('GIF89a')], 'image.gif', 'image/gif'],
    [[...Buffer.from('RIFF0000WEBPVP8 ')], 'image.webp', 'image/webp'],
    [[...Buffer.from('BM00')], 'image.bmp', 'image/bmp'],
    [[1, 2, 3], 'image.bin', 'application/octet-stream'],
  ];
  for (const [bytes, name, type] of cases) {
    const img = await loadImage(new Uint8Array(bytes), wx);
    assert.deepEqual([img.filename, img.contentType], [name, type]);
  }
});
