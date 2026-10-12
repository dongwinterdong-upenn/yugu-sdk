// WebSocket sessions against tools/mock-server, wx.connectSocket backed by the ws client.
// V-04 (6.4): server kill, close 1011, silent server, network switch, refused handshake, error
// frames, result timeout; REPLAY delivers one result for all audio, DROP and FAIL follow their
// documented semantics, every session ends with a callback within a bound.
// 2.1.0 reading progress: both channels, mockFault progress-mismatch:<i> and progress-off, and a
// server kill in the middle of the round with REPLAY and with DROP.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { WebSocketServer } from 'ws';
import { startMockServer, MOCK_APP_KEY, MOCK_SECRET } from '../helpers/mock-server.mjs';
import { createWxMock } from '../helpers/wx-mock.mjs';
import { recordingListener, captureLogs, wavPcm, waitFor, sleep } from '../helpers/common.mjs';
import { YuguClient, RequestTimeoutException, NetworkException } from '../../src/index.js';

const PCM = wavPcm('audio/zh_short.wav');
const NATIVE = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' };
const WS_NATIVE = '/api/v1/ws/evaluate';

// Reading progress: the customer sentence and the English sentence of the recorded rounds.
const ZH_PROGRESS = wavPcm('audio/zh_progress.wav');
const EN_PROGRESS = wavPcm('audio/en_progress.wav');
const CUSTOMER = '我是中国人，我是中国人。我们都是中国人。中华民族是一家人。';
const CUSTOMER_UNITS = Array.from('我是中国人我是中国人我们都是中国人中华民族是一家人');
const EN_TEXT = 'Nice to see you again. I am a student, and I like reading books every day.';
const EN_UNITS = ['Nice', 'to', 'see', 'you', 'again', 'I', 'am', 'a', 'student', 'and', 'I', 'like', 'reading', 'books', 'every', 'day'];
const partials = (rl) => rl.events.filter((e) => e.name === 'onPartial').map((e) => e.args[0]);

/**
 * readUnits the mock reports for `bytes` of audio sent in 640 byte frames: one unit per 8000 bytes
 * after every 16000 bytes, then one more unit after end, at most `units`.
 */
function mockProgress(bytes, units) {
  const out = [];
  for (let b = 16000; b <= bytes; b += 16000) out.push(Math.min(units, Math.floor(b / 8000)));
  out.push(Math.min(units, Math.floor(bytes / 8000) + 1));
  return out;
}

let mock;
let wx;
test.before(async () => {
  mock = await startMockServer({ processingMs: 30 });
  wx = createWxMock({ frameIntervalMs: 1 });
});
test.after(async () => {
  await mock.stop();
  wx.__mock.cleanup();
});
test.beforeEach(() => mock.reset());

function client(opts = {}) {
  return new YuguClient({
    wx,
    auth: { appKey: MOCK_APP_KEY, secretKey: MOCK_SECRET },
    baseUrl: mock.baseUrl,
    wsBaseUrl: mock.wsBaseUrl,
    reconnect: { initialDelayMs: 50, jitter: 0 },
    connectTimeoutMs: 2000,
    ...opts,
  });
}

/** Streams PCM in 640 byte frames, one frame per tick, then calls end(). */
async function stream(session, bytes = PCM, everyMs = 1) {
  for (let i = 0; i < bytes.length; i += 640) {
    session.sendAudio(bytes.subarray(i, i + 640));
    if (everyMs) await sleep(everyMs);
  }
  session.end();
}

const wsBilling = async (key) => (await mock.billing()).records.filter((r) => r.idemKey === key);
const order = (rl) => rl.names().filter((n) => ['onReconnecting', 'onReconnected', 'onResult', 'onError', 'onClosed'].includes(n));

test('native and compat sessions complete against the platform mock', async () => {
  const c = client();
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await stream(s, PCM, 0);
  await rl.done;
  const r = rl.first('onResult').args[0];
  assert.equal(r.overall, 93.7);
  assert.equal(r.idempotencyKey, s.idempotencyKey);
  assert.equal(r.replayed, false);
  assert.equal(s.getStats().heartbeat, 'active', 'the mock answers the probe with pong');
  const bill = await wsBilling(s.idempotencyKey);
  assert.equal(bill.length, 1);
  assert.equal(bill[0].bytes, PCM.length);
  const rl2 = recordingListener();
  const s2 = c.streamEvaluateCompat('sent.eval.cn', { refText: '今天天气很好', realtimeFeedback: true }, rl2.listener);
  await stream(s2, PCM, 0);
  await rl2.done;
  assert.equal(rl2.first('onResult').args[0].overall, 94.6);
  assert.ok(rl2.count('onPartial') >= 3, 'progress frames of realtime_feedback');
  assert.ok(rl2.events.filter((e) => e.name === 'onPartial').every((e) => e.args[0].bytes > 0));
  const log = (await mock.log()).filter((e) => e.method === 'WS');
  assert.deepEqual(log.map((e) => e.idempotencyKey), [s.idempotencyKey, s2.idempotencyKey]);
  c.close();
});

test('V-04 server kill: REPLAY reconnects and delivers one result for all audio', async () => {
  const c = client();
  await mock.faults([{ match: WS_NATIVE, fault: 'ws-kill-after:20' }]);
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await stream(s);
  await rl.done;
  assert.deepEqual(order(rl), ['onReconnecting', 'onReconnected', 'onResult', 'onClosed']);
  assert.deepEqual(rl.states(), ['CONNECTING', 'CONNECTED', 'STARTED', 'RECONNECTING', 'CONNECTING', 'CONNECTED', 'STARTED', 'ENDING', 'COMPLETED', 'CLOSED']);
  const cause = rl.first('onReconnecting').args[2];
  assert.ok(cause instanceof NetworkException);
  assert.equal(rl.count('onResult'), 1);
  const bill = await wsBilling(s.idempotencyKey);
  assert.equal(bill.length, 1, 'billed once');
  assert.equal(bill[0].bytes, PCM.length, 'the server scored every audio byte');
  const ws = (await mock.log()).filter((e) => e.method === 'WS');
  assert.equal(ws.length, 2);
  assert.equal(ws[0].idempotencyKey, ws[1].idempotencyKey);
  c.close();
});

test('V-04 close 1011 and a refused handshake are reconnected', async () => {
  const c = client();
  await mock.faults([{ match: WS_NATIVE, fault: 'ws-close-after:10' }, { match: WS_NATIVE, fault: 'ws-refuse' }]);
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await stream(s);
  await rl.done;
  const attempts = rl.events.filter((e) => e.name === 'onReconnecting').map((e) => e.args[0]);
  assert.deepEqual(attempts, [1, 2], 'the refused handshake is a failed attempt');
  // the close frame or a send that already failed on the closing socket, whichever comes first
  assert.match(rl.first('onReconnecting').args[2].message, /1011|not connected/);
  assert.equal(rl.first('onReconnecting').args[2].code, 90001);
  assert.equal(rl.count('onReconnected'), 1);
  assert.equal(rl.first('onReconnected').args[0], 2);
  assert.equal(rl.count('onResult'), 1);
  assert.equal((await wsBilling(s.idempotencyKey))[0].bytes, PCM.length);
  c.close();
});

test('V-04 silent server: the heartbeat detects it and the session recovers', async () => {
  const c = client({ heartbeatIntervalMs: 50, heartbeatTimeoutMs: 300 });
  await mock.faults([{ match: WS_NATIVE, fault: 'ws-silent:6' }]);
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  const half = PCM.subarray(0, 32000);
  for (let i = 0; i < half.length; i += 640) s.sendAudio(half.subarray(i, i + 640));
  await waitFor(() => rl.count('onReconnecting') === 1, 3000, 'heartbeat timeout');
  const cause = rl.first('onReconnecting').args[2];
  assert.ok(cause instanceof RequestTimeoutException);
  assert.match(cause.message, /heartbeat timeout/);
  await stream(s, PCM.subarray(32000), 0);
  await rl.done;
  assert.equal(rl.count('onResult'), 1);
  assert.equal((await wsBilling(s.idempotencyKey))[0].bytes, PCM.length);
  c.close();
});

test('V-04 silent while ENDING: the result timeout reconnects and the platform replays the result', async () => {
  const c = client({ resultTimeoutMs: 800 });
  await mock.faults([{ match: WS_NATIVE, fault: 'ws-delay-result:1500' }]);
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await stream(s, PCM, 0);
  await rl.done;
  assert.equal(rl.first('onReconnecting').args[2].code, 90007);
  const r = rl.first('onResult').args[0];
  assert.equal(r.replayed, true, 'second connection got the replay of the first evaluation');
  await sleep(300);
  assert.equal((await wsBilling(s.idempotencyKey)).length, 1);
  c.close();
});

test('V-04 network switch: a reset socket is reconnected, no ping is sent while ENDING', async () => {
  const c = client({ heartbeatIntervalMs: 30 });
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  const before = wx.__mock.state.sockets.length;
  const half = PCM.subarray(0, 30720);
  for (let i = 0; i < half.length; i += 640) s.sendAudio(half.subarray(i, i + 640));
  await waitFor(() => s.getState() === 'STARTED');
  await sleep(80);
  wx.__mock.state.sockets[before].__reset();
  await waitFor(() => rl.count('onReconnected') === 1, 3000);
  await stream(s, PCM.subarray(30720), 0);
  await rl.done;
  assert.equal(rl.count('onResult'), 1);
  assert.equal((await wsBilling(s.idempotencyKey))[0].bytes, PCM.length);
  const second = wx.__mock.state.sockets[before + 1].sent;
  const endAt = second.indexOf('{"cmd":"end"}');
  assert.ok(endAt > 0);
  assert.equal(second.slice(endAt).filter((f) => f === '{"cmd":"ping"}').length, 0);
  c.close();
});

test('large audio chunks stay under the 128 KB frame limit of the platform', async () => {
  const c = client();
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  const big = Buffer.concat([PCM, PCM, PCM]); // 180 KB in one sendAudio call
  s.sendAudio(big);
  s.end();
  await rl.done;
  assert.equal(rl.count('onReconnecting'), 0, 'no 1009 close');
  assert.equal(rl.count('onResult'), 1);
  assert.equal((await wsBilling(s.idempotencyKey))[0].bytes, big.length);
  c.close();
});

test('a 1009 close of the platform ends the session with 90005 and no reconnect', async () => {
  const c = client();
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await waitFor(() => s.getState() === 'STARTED');
  // bypass the SDK's frame splitting: one 200 KB frame straight on the socket
  const task = wx.__mock.state.sockets.filter((t) => t.closeCode === null).at(-1);
  task.send({ data: new ArrayBuffer(200 * 1024) });
  await rl.done;
  const err = rl.first('onError').args[0];
  assert.equal(err.code, 90005);
  assert.match(err.message, /code 1009/);
  assert.equal(rl.count('onReconnecting'), 0);
  assert.equal(rl.first('onClosed').args[0], 1009);
  c.close();
});

test('a server that keeps accepting and then dropping the session: 90006 after 3 x maxAttempts reconnects', async () => {
  const c = client({ reconnect: { maxAttempts: 2, initialDelayMs: 20, jitter: 0 } });
  await mock.faults(Array.from({ length: 10 }, () => ({ match: WS_NATIVE, fault: 'ws-kill-after:3' })));
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  s.sendAudio(PCM.subarray(0, 6400));
  await rl.done;
  assert.equal(rl.count('onReconnected'), 6);
  const err = rl.first('onError').args[0];
  assert.equal(err.code, 90006);
  assert.match(err.message, /6 in total/);
  const ws = (await mock.log()).filter((e) => e.method === 'WS');
  assert.equal(ws.length, 7);
  assert.equal(new Set(ws.map((e) => e.idempotencyKey)).size, 1);
  assert.equal((await mock.billing()).billed, 0, 'never reached the final evaluation');
  c.close();
});

test('retryable error frames reconnect; other error frames fail at once', async () => {
  const c = client();
  await mock.faults([{ match: WS_NATIVE, fault: 'ws-error:50200' }]);
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await stream(s, PCM, 0);
  await rl.done;
  assert.equal(rl.first('onReconnecting').args[2].code, 50200);
  assert.equal(rl.count('onResult'), 1);
  await mock.faults([{ match: WS_NATIVE, fault: 'ws-error:40001' }]);
  const rl2 = recordingListener();
  const s2 = c.streamEvaluate(NATIVE, rl2.listener);
  await stream(s2, PCM, 0);
  await rl2.done;
  assert.equal(rl2.first('onError').args[0].code, 40001);
  assert.equal(rl2.count('onReconnecting'), 0);
  // a session ended without audio is rejected by the server
  const rl3 = recordingListener();
  const s3 = c.streamEvaluate(NATIVE, rl3.listener, { audioPrecheck: 'OFF' });
  s3.end();
  await rl3.done;
  assert.match(rl3.first('onError').args[0].message, /no audio/);
  c.close();
});

test('FAIL policy: a server kill goes to onError within a bound, no reconnect', async () => {
  const c = client({ audioBufferPolicy: 'FAIL' });
  await mock.faults([{ match: WS_NATIVE, fault: 'ws-kill-after:6' }]);
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  let killedAt = 0;
  const sending = (async () => {
    for (let i = 0; i < PCM.length && s.isActive(); i += 640) {
      s.sendAudio(PCM.subarray(i, i + 640));
      await sleep(2);
    }
  })();
  await waitFor(() => s.getState() === 'FAILED' || s.getState() === 'CLOSED', 3000);
  killedAt = Date.now();
  await rl.done;
  await sending;
  assert.ok(Date.now() - killedAt < 100);
  assert.deepEqual(order(rl), ['onError', 'onClosed']);
  const err = rl.first('onError').args[0];
  assert.equal(err.code, 90001);
  assert.equal(err.idempotencyKey, s.idempotencyKey);
  assert.equal(rl.first('onClosed').args[0], 1006);
  assert.equal((await mock.billing()).billed, 0);
  c.close();
});

test('DROP policy: the new session scores only audio sent after the reconnect', async () => {
  const c = client({ audioBufferPolicy: 'DROP' });
  await mock.faults([{ match: WS_NATIVE, fault: 'ws-kill-after:12' }]);
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  const first = PCM.subarray(0, 19200);
  for (let i = 0; i < first.length; i += 640) s.sendAudio(first.subarray(i, i + 640));
  await waitFor(() => rl.count('onReconnected') === 1, 3000);
  const info = rl.first('onReconnected').args[1];
  await stream(s, PCM.subarray(19200), 0);
  await rl.done;
  assert.equal(rl.count('onResult'), 1);
  const bill = await wsBilling(s.idempotencyKey);
  assert.equal(bill.length, 1);
  assert.equal(bill[0].bytes, PCM.length - 19200);
  assert.ok(info.droppedBytes > 0 && info.droppedBytes <= 19200);
  c.close();
});

test('reconnect exhausted: refused handshakes end in 90006 with the cause, within the backoff budget', async () => {
  const c = client({ reconnect: { maxAttempts: 2, initialDelayMs: 40, jitter: 0 } });
  await mock.faults([{ match: WS_NATIVE, fault: 'ws-refuse' }, { match: WS_NATIVE, fault: 'ws-refuse' }, { match: WS_NATIVE, fault: 'ws-refuse' }]);
  const rl = recordingListener();
  const t0 = Date.now();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  s.sendAudio(PCM.subarray(0, 640));
  await rl.done;
  assert.ok(Date.now() - t0 < 2000);
  const err = rl.first('onError').args[0];
  assert.equal(err.code, 90006);
  assert.equal(err.retryable, false);
  assert.ok(err.cause);
  assert.deepEqual(order(rl), ['onReconnecting', 'onReconnecting', 'onError', 'onClosed']);
  c.close();
});

test('reading progress on the compat channel: Chinese by character, English by word, the last progress while ENDING', async () => {
  const c = client();
  const rounds = [
    { coreType: 'sent.eval.cn', refText: CUSTOMER, units: CUSTOMER_UNITS, pcm: ZH_PROGRESS },
    { coreType: 'sent.eval', refText: EN_TEXT, units: EN_UNITS, pcm: EN_PROGRESS },
  ];
  for (const round of rounds) {
    let s;
    const seenIn = [];
    const rl = recordingListener({ onPartial: () => seenIn.push(s.getState()) });
    const before = wx.__mock.state.sockets.length;
    s = c.streamEvaluateCompat(round.coreType, { refText: round.refText, realtimeFeedback: true }, rl.listener);
    await waitFor(() => s.getState() === 'STARTED');
    await stream(s, round.pcm, 1);
    await rl.done;
    const got = partials(rl);
    assert.deepEqual(got.map((p) => p.readUnits), mockProgress(round.pcm.length, round.units.length), round.coreType);
    for (const p of got) {
      assert.equal(p.hasProgress, true);
      assert.equal(p.totalUnits, round.units.length);
      assert.deepEqual(p.progress.map((w) => w.word), round.units);
      assert.deepEqual(p.progress.map((w) => w.read), round.units.map((u, i) => i < p.readUnits));
      assert.equal(p.matchedUnits, p.readUnits);
      // compat frames keep their Shengtong style extras in raw
      assert.deepEqual([p.raw.eof, p.raw.recordId, Array.isArray(p.raw.result.words)], [0, '', true]);
    }
    assert.equal(seenIn[0], 'STARTED');
    assert.equal(seenIn.at(-1), 'ENDING', 'the recognition of the last audio arrives after end()');
    assert.ok(rl.names().lastIndexOf('onPartial') < rl.names().indexOf('onResult'), 'the final result is the last frame');
    assert.equal(rl.count('onResult'), 1);
    assert.equal(JSON.parse(wx.__mock.state.sockets[before].sent[1]).realtime_feedback, true);
  }
  c.close();
});

test('reading progress on the native channel: "realtimeFeedback": true in the start frame, nothing without it', async () => {
  const c = client();
  const startFrame = (index) => wx.__mock.state.sockets[index].sent.find((f) => typeof f === 'string' && f.startsWith('{"cmd":"start"'));
  const plainAt = wx.__mock.state.sockets.length;
  const plain = recordingListener();
  const s0 = c.streamEvaluate({ coreType: 'sentence', referenceText: CUSTOMER, language: 'zh-CN' }, plain.listener);
  await stream(s0, ZH_PROGRESS, 0);
  await plain.done;
  assert.equal(plain.count('onPartial'), 0, 'no realtime feedback, no partial frame');
  assert.equal(startFrame(plainAt), JSON.stringify({ cmd: 'start', coreType: 'sentence', referenceText: CUSTOMER, language: 'zh-CN', idempotencyKey: s0.idempotencyKey }));
  const at = wx.__mock.state.sockets.length;
  const rl = recordingListener();
  const s = c.streamEvaluate({ coreType: 'sentence', referenceText: CUSTOMER, language: 'zh-CN', realtimeFeedback: true }, rl.listener);
  await waitFor(() => s.getState() === 'STARTED');
  await stream(s, ZH_PROGRESS, 1);
  await rl.done;
  assert.equal(startFrame(at), JSON.stringify({ cmd: 'start', coreType: 'sentence', referenceText: CUSTOMER, language: 'zh-CN', realtimeFeedback: true, idempotencyKey: s.idempotencyKey }));
  const got = partials(rl);
  assert.deepEqual(got.map((p) => [p.readUnits, p.matchedUnits]), mockProgress(ZH_PROGRESS.length, 25).map((n) => [n, n]));
  assert.ok(got.every((p) => p.hasProgress && p.totalUnits === 25 && p.progress.map((w) => w.word).join('') === CUSTOMER_UNITS.join('')));
  assert.ok(got.every((p) => p.raw.result.words === undefined), 'native progress frames have no words');
  assert.equal(rl.first('onResult').args[0].overall, 93.7);
  c.close();
});

test('mockFault progress-mismatch:<i> and progress-off reach onPartial as the platform sends them', async () => {
  const c = client();
  const rl = recordingListener();
  const s = c.streamEvaluateCompat('sent.eval.cn', { refText: CUSTOMER, realtimeFeedback: true }, rl.listener, { query: { mockFault: 'progress-mismatch:3' } });
  await stream(s, ZH_PROGRESS, 0);
  await rl.done;
  const got = partials(rl);
  assert.deepEqual(got.map((p) => p.readUnits), mockProgress(ZH_PROGRESS.length, 25));
  for (const p of got) {
    assert.deepEqual(p.progress[3], { word: '国', read: p.readUnits > 3, matched: false }, 'unit 3 is never matched');
    assert.equal(p.matchedUnits, p.readUnits > 3 ? p.readUnits - 1 : p.readUnits);
    assert.equal(p.matchedUnits, p.progress.filter((w) => w.matched).length);
  }
  assert.equal(rl.count('onResult'), 1);
  // progress-off: the frames carry bytes only and are delivered as in 2.0.0
  const off = recordingListener();
  const s2 = c.streamEvaluate({ coreType: 'sentence', referenceText: CUSTOMER, language: 'zh-CN', realtimeFeedback: true }, off.listener, { query: { mockFault: 'progress-off' } });
  await stream(s2, ZH_PROGRESS, 0);
  await off.done;
  const bytesOnly = partials(off);
  const expectedBytes = [];
  for (let b = 16000; b <= ZH_PROGRESS.length; b += 16000) expectedBytes.push(b);
  assert.deepEqual(bytesOnly.map((p) => p.bytes), expectedBytes.concat([ZH_PROGRESS.length]));
  for (const p of bytesOnly) assert.deepEqual([p.hasProgress, p.readUnits, p.matchedUnits, p.totalUnits, p.progress, Object.keys(p.raw.result)], [false, -1, -1, -1, [], ['bytes']]);
  assert.equal(off.count('onResult'), 1);
  const ws = (await mock.log()).filter((e) => e.method === 'WS');
  assert.deepEqual(ws.map((e) => e.fault), ['progress-mismatch:3', 'progress-off'], 'the signed handshake query carried the fault');
  c.close();
});

test('reading progress across a server kill: the replayed round never moves the progress back, one result', async () => {
  const logs = captureLogs();
  const c = client({ logger: logs.sink, logLevel: 'DEBUG' });
  // inbound frames of the first connection: ping probe, parameter frame, 125 audio frames of 640 bytes
  // (80000 bytes, progress 2 to 10); the next audio frame kills the socket
  await mock.faults([{ match: '/sent.eval.cn', fault: 'ws-kill-after:128' }]);
  const rl = recordingListener();
  const s = c.streamEvaluateCompat('sent.eval.cn', { refText: CUSTOMER, realtimeFeedback: true }, rl.listener);
  await waitFor(() => s.getState() === 'STARTED');
  const head = ZH_PROGRESS.subarray(0, 80000);
  for (let i = 0; i < head.length; i += 640) s.sendAudio(head.subarray(i, i + 640));
  await waitFor(() => partials(rl).some((p) => p.readUnits === 10), 3000, 'progress of the first server session');
  await stream(s, ZH_PROGRESS.subarray(80000), 1);
  await rl.done;
  assert.deepEqual(order(rl), ['onReconnecting', 'onReconnected', 'onResult', 'onClosed']);
  // the second server session reports 2, 4, 6, 8 again while the audio is replayed: dropped
  assert.deepEqual(partials(rl).map((p) => p.readUnits), [2, 4, 6, 8, 10, 10, 12, 14, 16, 18, 19]);
  assert.ok(partials(rl).every((p) => p.matchedUnits === p.readUnits && p.hasProgress));
  const dropped = logs.lines.filter((l) => /progress partial dropped/.test(l.message));
  assert.deepEqual(dropped.map((l) => l.level), ['DEBUG', 'DEBUG', 'DEBUG', 'DEBUG']);
  const names = rl.names();
  assert.ok(names.indexOf('onReconnected') < names.lastIndexOf('onPartial'), 'progress continues after the reconnect');
  const bill = await wsBilling(s.idempotencyKey);
  assert.equal(bill.length, 1, 'billed once');
  assert.equal(bill[0].bytes, ZH_PROGRESS.length, 'the server scored every audio byte');
  const ws = (await mock.log()).filter((e) => e.method === 'WS');
  assert.deepEqual(ws.map((e) => e.fault), ['ws-kill-after:128', null]);
  c.close();
});

test('reading progress with DROP across a server kill: the progress starts again with the new server session', async () => {
  const logs = captureLogs();
  const c = client({ audioBufferPolicy: 'DROP', logger: logs.sink, logLevel: 'DEBUG' });
  await mock.faults([{ match: '/sent.eval.cn', fault: 'ws-kill-after:128' }]);
  const rl = recordingListener();
  const s = c.streamEvaluateCompat('sent.eval.cn', { refText: CUSTOMER, realtimeFeedback: true }, rl.listener);
  await waitFor(() => s.getState() === 'STARTED');
  const head = ZH_PROGRESS.subarray(0, 80000);
  for (let i = 0; i < head.length; i += 640) s.sendAudio(head.subarray(i, i + 640));
  await waitFor(() => partials(rl).some((p) => p.readUnits === 10), 3000, 'progress of the first server session');
  s.sendAudio(ZH_PROGRESS.subarray(80000, 80640)); // inbound frame 128: the server kills the socket
  await waitFor(() => rl.count('onReconnected') === 1, 3000, 'reconnect');
  const after = ZH_PROGRESS.subarray(80640);
  await stream(s, after, 1);
  await rl.done;
  // the first server session reported 2 to 10; the second one scores only the audio sent after the
  // reconnect, and its progress from 2 on is delivered because DROP forgot the delivered 10
  assert.deepEqual(partials(rl).map((p) => p.readUnits), [2, 4, 6, 8, 10].concat(mockProgress(after.length, 25)));
  assert.equal(logs.lines.filter((l) => /progress partial dropped/.test(l.message)).length, 0);
  assert.equal(logs.lines.filter((l) => l.level === 'DEBUG' && /delivered progress forgotten/.test(l.message)).length, 1);
  assert.equal(rl.first('onReconnected').args[1].droppedBytes, 80640);
  const bill = await wsBilling(s.idempotencyKey);
  assert.equal(bill.length, 1);
  assert.equal(bill[0].bytes, after.length, 'the new server session scored only the audio after the reconnect');
  c.close();
});

// Servers deployed before the heartbeat change: native answers {"cmd":"ping"} with
// {"event":"error","message":"unknown cmd"}; compat takes any JSON frame as a parameter frame.
function legacyServer(mode) {
  return new Promise((resolve) => {
    const server = http.createServer();
    const wss = new WebSocketServer({ server });
    const stats = { audio: 0, starts: 0, pings: 0, results: 0 };
    wss.on('connection', (ws) => {
      let audio = 0;
      ws.send(JSON.stringify(mode === 'native' ? { event: 'connected', message: 'stream channel ready' } : { event: 'connected', coreType: 'sent.eval.cn' }));
      ws.on('message', (data, isBinary) => {
        if (isBinary) {
          audio += data.length;
          return;
        }
        const j = JSON.parse(data.toString());
        if (j.cmd === 'ping') stats.pings += 1;
        if (j.cmd === 'end') {
          stats.audio = audio;
          stats.results += 1;
          const body = { recordId: 'eval_legacy', eof: 1, result: { overall: 77, words: [] } };
          ws.send(JSON.stringify(mode === 'native' ? { event: 'result', ...body } : body));
          return;
        }
        if (mode === 'native') {
          if (j.cmd === 'start') {
            stats.starts += 1;
            ws.send(JSON.stringify({ event: 'started' }));
          } else ws.send(JSON.stringify({ event: 'error', message: 'unknown cmd' }));
          return;
        }
        stats.starts += 1;
        audio = 0;
        ws.send(JSON.stringify({ event: 'started', coreType: 'sent.eval.cn' }));
      });
    });
    server.listen(0, '127.0.0.1', () => resolve({ url: 'ws://127.0.0.1:' + server.address().port, stats, close: () => new Promise((r) => { for (const ws of wss.clients) ws.terminate(); wss.close(); server.close(r); }) }));
  });
}

test('servers without heartbeat support keep working, the heartbeat turns itself off', async () => {
  for (const mode of ['native', 'compat']) {
    const legacy = await legacyServer(mode);
    const c = new YuguClient({ wx, auth: { token: 'legacy' }, wsBaseUrl: legacy.url, heartbeatIntervalMs: 20 });
    try {
      const rl = recordingListener();
      const s = mode === 'native' ? c.streamEvaluate(NATIVE, rl.listener) : c.streamEvaluateCompat('sent.eval.cn', { refText: '北京你好' }, rl.listener);
      await waitFor(() => s.getState() === 'STARTED');
      await sleep(100);
      await stream(s, PCM, 0);
      await rl.done;
      assert.equal(rl.first('onResult').args[0].overall, 77, mode);
      assert.equal(rl.count('onError'), 0, mode);
      assert.equal(legacy.stats.pings, 1, mode + ': only the probe, no periodic ping');
      assert.equal(legacy.stats.audio, PCM.length, mode + ': no audio lost');
      assert.equal(s.getStats().heartbeat, 'disabled');
    } finally {
      c.close();
      await legacy.close();
    }
  }
});
