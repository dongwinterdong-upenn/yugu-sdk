// YuguRecorder over the fake RecorderManager (B-05): states, microphone release, listener removal,
// no handler build-up over 100 create and release cycles, pipe into a session.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createWxMock } from '../helpers/wx-mock.mjs';
import { scriptedWx, scriptedServer } from '../helpers/scripted-wx.mjs';
import { wavPcm, waitFor, sleep, recordingListener } from '../helpers/common.mjs';
import {
  YuguClient, YuguRecorder, RecorderState, IllegalSessionStateException, PermissionException, RequestCancelledException,
  InvalidParameterException,
} from '../../src/index.js';

const AUTH = { appKey: 'mock-app-key', secretKey: 'mock-secret-key' };
const PCM = wavPcm('audio/zh_short.wav');

function recorderListener() {
  const events = [];
  const l = {};
  for (const n of ['onStateChanged', 'onStart', 'onPause', 'onResume', 'onStop', 'onFrame', 'onError', 'onInterruptionBegin', 'onInterruptionEnd']) {
    l[n] = (...a) => events.push([n, ...a]);
  }
  return { l, events, names: () => events.map((e) => e[0]) };
}

test('records 16000 Hz mono PCM with frame callbacks and stops into a temp file', async () => {
  const wx = createWxMock({ frameIntervalMs: 1 });
  const rec = new YuguRecorder({}, { wx });
  const rl = recorderListener();
  rec.setListener(rl.l);
  assert.equal(rec.getState(), RecorderState.IDLE);
  rec.start();
  assert.equal(rec.getState(), 'RECORDING');
  const fake = wx.getRecorderManager();
  assert.deepEqual(fake.lastOptions, { duration: 300000, sampleRate: 16000, numberOfChannels: 1, format: 'PCM', frameSize: 1 });
  assert.equal(fake.micOpen, true);
  await waitFor(() => rl.events.filter((e) => e[0] === 'onFrame').length >= 5);
  const result = await rec.stop();
  assert.equal(rec.getState(), 'STOPPED');
  assert.equal(fake.micOpen, false, 'stop releases the microphone');
  assert.equal(result.format, 'pcm');
  assert.equal(result.sampleRate, 16000);
  assert.equal(result.numberOfChannels, 1);
  assert.equal(fs.statSync(result.tempFilePath).size, result.fileSize);
  const frames = rl.events.filter((e) => e[0] === 'onFrame');
  assert.equal(frames.reduce((n, f) => n + f[1].byteLength, 0), result.fileSize);
  assert.equal(frames.at(-1)[2], true, 'last frame flagged');
  assert.deepEqual(await rec.stop(), result, 'stop after STOPPED resolves the last result');
  assert.ok(rl.names().includes('onStart') && rl.names().includes('onStop'));
  rec.release();
  wx.__mock.cleanup();
});

test('pause, resume, interruption, and state errors', async () => {
  const wx = createWxMock({ frameIntervalMs: 2 });
  const rec = new YuguRecorder({ frameSize: 2, duration: 60000, audioSource: 'auto' }, { wx });
  const rl = recorderListener();
  rec.setListener(rl.l);
  const is90009 = (e) => e instanceof IllegalSessionStateException && e.code === 90009;
  assert.throws(() => rec.pause(), is90009);
  assert.throws(() => rec.resume(), is90009);
  await assert.rejects(rec.stop(), is90009);
  rec.start();
  assert.throws(() => rec.start(), is90009);
  rec.pause();
  assert.equal(rec.getState(), 'PAUSED');
  assert.throws(() => rec.pause(), is90009);
  rec.resume();
  assert.equal(rec.getState(), 'RECORDING');
  wx.getRecorderManager().interrupt();
  await waitFor(() => rec.getState() === 'PAUSED');
  assert.ok(rl.names().includes('onInterruptionBegin'));
  rec.resume();
  await rec.stop();
  assert.equal(wx.getRecorderManager().lastOptions.frameSize, 2);
  assert.equal(wx.getRecorderManager().lastOptions.audioSource, 'auto');
  rec.start();
  await rec.stop();
  rec.release();
  wx.__mock.cleanup();
});

test('release is idempotent, releases a busy microphone and rejects pending stops', async () => {
  const wx = createWxMock({ frameIntervalMs: 5 });
  const rec = new YuguRecorder({}, { wx });
  const rl = recorderListener();
  rec.setListener(rl.l);
  rec.start();
  await sleep(10);
  const pending = rec.stop();
  rec.release();
  rec.release();
  await assert.rejects(pending, (e) => e instanceof RequestCancelledException && e.code === 90003);
  assert.equal(rec.getState(), 'RELEASED');
  await waitFor(() => wx.getRecorderManager().micOpen === false);
  const before = rl.events.length;
  await sleep(30);
  assert.equal(rl.events.length, before, 'no callbacks after release');
  assert.throws(() => rec.start(), (e) => e.code === 90009);
  assert.throws(() => rec.pipeTo({ sendAudio() {} }), (e) => e.code === 90009);
  const idle = new YuguRecorder({}, { wx });
  idle.release();
  wx.__mock.cleanup();
});

test('errors release the microphone: permission denied and a failure while recording', async () => {
  const wx = createWxMock({ frameIntervalMs: 2 });
  const fake = wx.getRecorderManager();
  fake.denyPermission = true;
  const rec = new YuguRecorder({}, { wx });
  const rl = recorderListener();
  rec.setListener(rl.l);
  rec.start();
  await waitFor(() => rl.names().includes('onError'));
  const err = rl.events.find((e) => e[0] === 'onError')[1];
  assert.ok(err instanceof PermissionException);
  assert.equal(err.code, 90201);
  assert.equal(rec.getState(), 'STOPPED');
  assert.equal(fake.micOpen, false);
  fake.denyPermission = false;
  rec.start();
  await sleep(10);
  const pending = rec.stop();
  fake.injectError('operateRecorder:fail audio device error');
  const e2 = await pending.catch((e) => e);
  assert.equal(e2.code, 90203);
  assert.equal(fake.micOpen, false);
  rec.start();
  await sleep(5);
  fake.injectError('operateRecorder:fail recorder is busy');
  await waitFor(() => rl.events.filter((e) => e[0] === 'onError').length === 3);
  assert.equal(rl.events.filter((e) => e[0] === 'onError')[2][1].code, 90202);
  assert.equal(rec.getState(), 'STOPPED');
  rec.release();
  wx.__mock.cleanup();
});

test('listener removal stops callbacks; the platform manager gets its handlers once', async () => {
  const wx = createWxMock({ frameIntervalMs: 1 });
  const rec = new YuguRecorder({}, { wx });
  const rl = recorderListener();
  rec.setListener(rl.l);
  rec.removeListener();
  rec.start();
  await rec.stop();
  assert.equal(rl.events.length, 0);
  rec.setListener(rl.l);
  rec.setListener(null);
  rec.release();
  const fake = wx.getRecorderManager();
  for (const n of ['start', 'pause', 'resume', 'stop', 'error', 'frameRecorded', 'interruptionBegin', 'interruptionEnd']) {
    assert.equal(fake.registrations[n], 1, n);
  }
  wx.__mock.cleanup();
});

test('B-05: 100 create, use and release cycles leave no microphone, handlers or timers behind', async () => {
  const wx = createWxMock({ frameIntervalMs: 0 });
  const timersBefore = process.getActiveResourcesInfo().filter((r) => r === 'Timeout').length;
  for (let i = 0; i < 100; i++) {
    const client = new YuguClient({ wx, auth: AUTH });
    const rec = client.createRecorder();
    rec.start();
    if (i % 2 === 0) await rec.stop();
    client.close();
    client.close();
    assert.equal(rec.getState(), 'RELEASED');
  }
  const fake = wx.getRecorderManager();
  await waitFor(() => fake.micOpen === false);
  for (const n of ['start', 'stop', 'error', 'frameRecorded']) assert.equal(fake.registrations[n], 1, n);
  await sleep(20);
  const timersAfter = process.getActiveResourcesInfo().filter((r) => r === 'Timeout').length;
  assert.ok(timersAfter <= timersBefore + 1, `timers ${timersBefore} -> ${timersAfter}`);
  const heap = process.memoryUsage().heapUsed;
  for (let i = 0; i < 100; i++) {
    const client = new YuguClient({ wx, auth: AUTH });
    client.createRecorder().start();
    client.close();
  }
  await waitFor(() => fake.micOpen === false);
  if (global.gc) global.gc();
  assert.ok(process.memoryUsage().heapUsed - heap < 30 * 1024 * 1024);
  wx.__mock.cleanup();
});

test('a stop() that races the duration limit keeps the recording and reports no error', async () => {
  const wx = createWxMock({ frameIntervalMs: 1 });
  const rec = new YuguRecorder({}, { wx });
  const rl = recorderListener();
  rec.setListener(rl.l);
  rec.start();
  // the platform stopped on its own (here: end of the source, like the duration limit)
  await waitFor(() => !wx.getRecorderManager().isRecording());
  const result = await rec.stop();
  assert.ok(result.tempFilePath);
  assert.equal(result.fileSize, PCM.length);
  await sleep(20);
  assert.equal(rl.events.filter((e) => e[0] === 'onError').length, 0);
  assert.equal(rec.getState(), 'STOPPED');
  rec.start();
  await sleep(5);
  assert.equal(rec.getState(), 'RECORDING');
  await rec.stop();
  rec.release();
  wx.__mock.cleanup();
});

test('events of a released recording never reach the next recording', async () => {
  const wx = createWxMock({ frameIntervalMs: 1 });
  const a = new YuguRecorder({}, { wx });
  a.start();
  await sleep(10);
  a.release();
  const b = new YuguRecorder({}, { wx });
  const rl = recorderListener();
  b.setListener(rl.l);
  b.start();
  await sleep(25);
  assert.equal(b.getState(), 'RECORDING', 'the stale stop of the first recording was drained');
  const result = await b.stop();
  const frames = rl.events.filter((e) => e[0] === 'onFrame');
  assert.equal(frames.reduce((n, f) => n + f[1].byteLength, 0), result.fileSize, 'only frames of the second recording');
  assert.deepEqual(rl.names().filter((n) => n === 'onStop' || n === 'onError'), ['onStop']);
  b.release();
  wx.__mock.cleanup();
});

test('one microphone: a second recorder cannot start while the first records', async () => {
  const wx = createWxMock({ frameIntervalMs: 2 });
  const a = new YuguRecorder({}, { wx });
  const b = new YuguRecorder({}, { wx });
  a.start();
  assert.throws(() => b.start(), (e) => e.code === 90202);
  await a.stop();
  b.start();
  await b.stop();
  a.release();
  b.release();
  assert.throws(() => new YuguRecorder({}, { wx: {} }), InvalidParameterException);
  wx.__mock.cleanup();
});

test('pipeTo: frames re-chunked to 640 bytes, end() on stop, the recorder stops when the session ends', async () => {
  const wx = createWxMock({ frameIntervalMs: 1 });
  const rec = new YuguRecorder({ frameSize: 1 }, { wx });
  const sent = [];
  const observers = [];
  const fakeSession = {
    sendAudio: (c) => sent.push(c.length),
    end: () => sent.push('end'),
    cancel: () => sent.push('cancel'),
    _addObserver: (fn) => observers.push(fn),
  };
  rec.pipeTo(fakeSession);
  rec.start();
  await waitFor(() => sent.length > 5);
  await rec.stop();
  assert.equal(sent.at(-1), 'end');
  const sizes = sent.filter((x) => typeof x === 'number');
  assert.ok(sizes.slice(0, -1).every((n) => n === 640));
  const total = sizes.reduce((a, b) => a + b, 0);
  assert.equal(total, wx.getRecorderManager().stopCount && fs.statSync((await rec.stop()).tempFilePath).size);
  // the session closing while recording stops the recorder
  sent.length = 0;
  rec.start();
  await sleep(5);
  observers.at(-1)('state', 'STARTED', 'FAILED');
  observers.at(-1)('closed', 1006, 'gone');
  await waitFor(() => rec.getState() === 'STOPPED');
  // a recorder error cancels the piped session
  const s2 = { sendAudio() {}, end() {}, cancel: () => sent.push('cancel2') };
  rec.pipeTo(s2, { endOnStop: false, frameBytes: 320 });
  rec.start();
  await sleep(5);
  wx.getRecorderManager().injectError('operateRecorder:fail broken');
  await waitFor(() => sent.includes('cancel2'));
  assert.throws(() => rec.pipeTo(null), InvalidParameterException);
  rec.unpipe();
  rec.release();
  wx.__mock.cleanup();
});

test('a real session fed by the recorder completes with all recorded audio', async () => {
  const wx = scriptedWx([], { frameIntervalMs: 1 });
  wx.__scripted.setServer((task) => scriptedServer(task, {}));
  const client = new YuguClient({ wx, auth: AUTH, wsBaseUrl: 'wss://ws.test' });
  const rec = client.createRecorder();
  const rl = recordingListener();
  const session = client.streamEvaluate({ coreType: 'sentence', referenceText: '今天天气很好' }, rl.listener);
  rec.pipeTo(session);
  rec.start();
  await waitFor(() => wx.getRecorderManager().isRecording() === false, 5000, 'source exhausted');
  await rl.done;
  assert.equal(rl.count('onResult'), 1);
  assert.equal(wx.__scripted.sockets[0].server.state.audio, PCM.length);
  assert.equal(rec.getState(), 'STOPPED');
  client.close();
  assert.equal(rec.getState(), 'RELEASED');
});
