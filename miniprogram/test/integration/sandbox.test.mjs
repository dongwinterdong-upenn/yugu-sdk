// Sandbox end-to-end tests (SANDBOX.md). They run only when YUGU_SANDBOX_APPKEY and
// YUGU_SANDBOX_SECRET are set, for example in the nightly CI build, and are skipped otherwise.
// The sandbox allows 200 calls per key and day; this file makes 7.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createWxMock } from '../helpers/wx-mock.mjs';
import { fixtureBytes, toArrayBuffer, recordingListener, wavPcm } from '../helpers/common.mjs';
import { YuguClient, generateIdempotencyKey } from '../../src/index.js';

const APPKEY = process.env.YUGU_SANDBOX_APPKEY;
const SECRET = process.env.YUGU_SANDBOX_SECRET;
const BASE = process.env.YUGU_SANDBOX_BASE || 'https://open.shengzhiai.com';
const skip = !APPKEY || !SECRET ? 'YUGU_SANDBOX_APPKEY and YUGU_SANDBOX_SECRET are not set' : false;

const WAV = fixtureBytes('audio/zh_short.wav');
const SENTENCE = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' };

function client(wx) {
  return new YuguClient({ wx, auth: { appKey: APPKEY, secretKey: SECRET }, baseUrl: BASE, wsBaseUrl: BASE.replace(/^http/, 'ws') });
}

test('sandbox: REST evaluate, replay with the same key, compat, TTS and report', { skip }, async () => {
  const wx = createWxMock();
  const c = client(wx);
  try {
    const key = generateIdempotencyKey();
    const r = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }, { idempotencyKey: key });
    assert.equal(typeof r.overall, 'number');
    assert.match(r.recordId, /\w/);
    const again = await c.evaluate({ ...SENTENCE, audio: toArrayBuffer(WAV) }, { idempotencyKey: key });
    assert.equal(again.replayed, true);
    assert.equal(again.recordId, r.recordId);
    const compat = await c.evaluateCompat('sent.eval.cn', { refText: '今天天气很好', audio: toArrayBuffer(WAV) });
    assert.equal(typeof compat.overall, 'number');
    const tts = await c.tts({ text: '你好世界' });
    assert.match(tts.fullUrl, /^https?:\/\//);
    const report = await c.getReport(r.recordId);
    assert.ok(report && typeof report === 'object');
  } finally {
    c.close();
    wx.__mock.cleanup();
  }
});

test('sandbox: native and compat streaming evaluation', { skip }, async () => {
  const wx = createWxMock();
  const c = client(wx);
  try {
    const pcm = wavPcm('audio/zh_short.wav');
    for (const open of [(l) => c.streamEvaluate(SENTENCE, l), (l) => c.streamEvaluateCompat('sent.eval.cn', { refText: '今天天气很好', language: 'zh-CN' }, l)]) {
      const rl = recordingListener();
      const s = open(rl.listener);
      for (let i = 0; i < pcm.length; i += 640) s.sendAudio(pcm.subarray(i, i + 640));
      s.end();
      await rl.done;
      assert.equal(rl.count('onError'), 0, JSON.stringify(rl.first('onError') && rl.first('onError').args[0]));
      assert.equal(typeof rl.first('onResult').args[0].overall, 'number');
    }
  } finally {
    c.close();
    wx.__mock.cleanup();
  }
});
