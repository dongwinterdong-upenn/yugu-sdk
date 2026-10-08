// StreamSession state machine against scripted sockets (A-03, B-05): states, listener guarantees,
// heartbeat, timeouts, reconnect with REPLAY, DROP and FAIL.
import test from 'node:test';
import assert from 'node:assert/strict';
import { scriptedWx, scriptedServer } from '../helpers/scripted-wx.mjs';
import { recordingListener, captureLogs, wavPcm, sleep, waitFor, seeded } from '../helpers/common.mjs';
import {
  YuguClient, SessionState, AudioBufferPolicy, StreamSession, signParams, computeBackoffDelay, DEFAULT_RECONNECT_POLICY,
  InvalidParameterException, ServerException, ProtocolViolationException, AudioQualityException, NetworkException,
  RequestTimeoutException,
} from '../../src/index.js';

const AUTH = { appKey: 'mock-app-key', secretKey: 'mock-secret-key' };
const PCM = wavPcm('audio/zh_short.wav');
const NATIVE = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' };
const QUICK = { reconnect: { initialDelayMs: 10, maxDelayMs: 40, jitter: 0 }, connectTimeoutMs: 300, resultTimeoutMs: 300 };

function setup(server = {}, clientOpts = {}) {
  const wx = scriptedWx([]);
  wx.__scripted.setServer((task, i) => {
    const o = typeof server === 'function' ? server(i, task) : server;
    return o && o.custom ? o.custom : scriptedServer(task, o || {});
  });
  const logs = captureLogs();
  const c = new YuguClient({ wx, auth: AUTH, wsBaseUrl: 'wss://ws.test', logger: logs.sink, logLevel: 'DEBUG', heartbeatIntervalMs: 15000, ...QUICK, ...clientOpts });
  return { wx, c, sockets: wx.__scripted.sockets, logs };
}

function feed(session, bytes = PCM, frame = 640) {
  for (let i = 0; i < bytes.length; i += frame) session.sendAudio(bytes.subarray(i, i + frame));
}

const binaryTotal = (task) => task.sent.filter((s) => s.binary).reduce((n, s) => n + s.binary.length, 0);

test('happy path: states, callback order, frames and getState consistency', async () => {
  const { c, sockets, logs } = setup();
  const seen = [];
  let session;
  const rl = recordingListener({
    onStateChanged: (o, n) => seen.push([n, session.getState(), session.isActive()]),
    onResult: () => seen.push(['result', session.getState()]),
    onClosed: () => seen.push(['closed', session.getState()]),
  });
  session = c.streamEvaluate({ ...NATIVE, includeReport: true }, rl.listener);
  assert.equal(session.getState(), 'IDLE');
  assert.ok(session instanceof StreamSession);
  assert.equal(session.mode, 'native');
  assert.match(session.idempotencyKey, /^[0-9a-f]{32}$/);
  feed(session);
  session.end();
  await rl.done;
  assert.deepEqual(rl.names(), ['onStateChanged', 'onStateChanged', 'onConnected', 'onStateChanged', 'onStarted',
    'onStateChanged', 'onStateChanged', 'onResult', 'onStateChanged', 'onClosed']);
  assert.deepEqual(rl.states(), ['CONNECTING', 'CONNECTED', 'STARTED', 'ENDING', 'COMPLETED', 'CLOSED']);
  for (const [announced, viaGetter] of seen.filter((s) => s.length === 3)) assert.equal(viaGetter, announced);
  assert.deepEqual(seen.find((s) => s[0] === 'result'), ['result', 'COMPLETED']);
  assert.deepEqual(seen.at(-1), ['closed', 'CLOSED']);
  assert.equal(session.isActive(), false);
  const result = rl.first('onResult').args[0];
  assert.equal(result.overall, 88);
  assert.equal(result.idempotencyKey, session.idempotencyKey);
  assert.equal(result.replayed, false);
  assert.equal(result.attempts, 1);
  assert.deepEqual(rl.first('onClosed').args, [1000, 'completed']);
  const task = sockets[0];
  const cmds = task.cmds();
  assert.equal(cmds[0], 'ping', 'heartbeat probe before the start frame');
  assert.equal(cmds[1], 'start');
  assert.equal(cmds.at(-1), 'end');
  assert.equal(binaryTotal(task), PCM.length);
  const start = task.texts()[1];
  assert.deepEqual(start, { cmd: 'start', ...NATIVE, includeReport: true, idempotencyKey: session.idempotencyKey });
  const q = task.query();
  assert.equal(new URL(task.url).pathname, '/api/v1/ws/evaluate');
  assert.equal(q.idempotencyKey, session.idempotencyKey);
  const { signature, ...rest } = q;
  assert.equal(signature, signParams(rest, AUTH.secretKey));
  assert.equal(task.options.header['X-Yugu-SDK'], 'yugu-miniprogram-sdk/2.0.0');
  assert.deepEqual(task.closeArgs, { code: 1000, reason: 'completed' });
  assert.ok(!logs.lines.some((l) => l.level === 'ERROR'), logs.text());
});

test('compat session: parameter frame, partial frames, extra query, token auth', async () => {
  const { c, sockets } = setup({ mode: 'compat' }, { auth: { token: 'jwt-1' } });
  const rl = recordingListener();
  const s = c.streamEvaluateCompat('sent.eval.cn', { refText: '北京你好', language: 'zh-CN', realtimeFeedback: true, fields: { scale: 100 } }, rl.listener, { query: { trace: 't1' }, idempotencyKey: 'compat-key-1' });
  await waitFor(() => s.getState() === 'STARTED');
  sockets[0].frame({ eof: 0, result: { bytes: 16000 } });
  sockets[0].frame({ eof: '0', result: { bytes: 32000 } });
  feed(s, PCM.subarray(0, 3200));
  s.end();
  await rl.done;
  assert.deepEqual(rl.events.filter((e) => e.name === 'onPartial').map((e) => e.args[0].bytes), [16000, 32000]);
  assert.equal(rl.first('onResult').args[0].idempotencyKey, 'compat-key-1');
  const task = sockets[0];
  assert.equal(new URL(task.url).pathname, '/sent.eval.cn');
  assert.deepEqual(task.query(), { trace: 't1', idempotencyKey: 'compat-key-1', token: 'jwt-1' });
  assert.deepEqual(task.texts()[1], { refText: '北京你好', language: 'zh-CN', realtime_feedback: true, scale: 100, idempotencyKey: 'compat-key-1' });
  assert.equal(s.mode, 'compat');
});

test('audio and end() given before the server session starts are delivered in order', async () => {
  const { c, sockets } = setup();
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  feed(s);
  assert.equal(s.end(), true);
  assert.equal(s.end(), false, 'end is accepted once');
  await rl.done;
  const cmds = sockets[0].cmds();
  assert.equal(cmds.indexOf('end'), cmds.length - 1);
  assert.equal(binaryTotal(sockets[0]), PCM.length);
  assert.equal(rl.count('onResult'), 1);
});

test('end() called inside onStarted still sends queued audio first', async () => {
  const { c, sockets } = setup();
  let s;
  const rl = recordingListener({ onStarted: () => s.end() });
  s = c.streamEvaluate(NATIVE, rl.listener);
  feed(s, PCM.subarray(0, 6400));
  await rl.done;
  const cmds = sockets[0].cmds();
  assert.deepEqual(cmds.slice(-11), [...Array(10).fill('audio:640'), 'end']);
  assert.equal(rl.count('onResult'), 1);
});

test('cancel(): no terminal callback, onClosed last, idempotent, also from inside callbacks', async () => {
  const { c, sockets } = setup();
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  s.cancel();
  s.cancel();
  s.close();
  await rl.done;
  assert.deepEqual(rl.states(), ['CANCELLED', 'CLOSED']);
  assert.equal(rl.count('onResult') + rl.count('onError'), 0);
  assert.deepEqual(rl.first('onClosed').args, [1000, 'cancelled']);
  assert.equal(sockets.length, 0, 'cancelled before the connection started');
  assert.equal(s.sendAudio(new Uint8Array(2)), false);
  assert.equal(s.end(), false);
  let s2;
  const rl2 = recordingListener({ onStarted: () => s2.cancel() });
  s2 = c.streamEvaluate(NATIVE, rl2.listener);
  await rl2.done;
  assert.deepEqual(rl2.states(), ['CONNECTING', 'CONNECTED', 'STARTED', 'CANCELLED', 'CLOSED']);
  assert.equal(rl2.names().at(-1), 'onClosed');
  assert.equal(rl2.count('onResult') + rl2.count('onError'), 0);
  assert.deepEqual(sockets[0].closeArgs, { code: 1000, reason: 'cancelled' });
});

test('cancel() inside onReconnecting stops the pending reconnect', async () => {
  let s;
  const { c, sockets } = setup({}, { reconnect: { initialDelayMs: 30, jitter: 0 } });
  const rl = recordingListener({ onReconnecting: () => s.cancel() });
  s = c.streamEvaluate(NATIVE, rl.listener);
  await waitFor(() => s.getState() === 'STARTED');
  sockets[0].serverClose(1006, 'gone');
  await rl.done;
  await sleep(80);
  assert.equal(sockets.length, 1, 'no new connection after cancel');
  assert.deepEqual(rl.states().slice(-3), ['RECONNECTING', 'CANCELLED', 'CLOSED']);
});

test('listener exceptions are logged and do not break the session; setListener(null) silences it', async () => {
  const { c, logs } = setup();
  const thrower = {};
  for (const n of ['onStateChanged', 'onConnected', 'onStarted', 'onResult', 'onError', 'onClosed']) thrower[n] = () => { throw new Error('listener bug'); };
  const s = c.streamEvaluate(NATIVE, thrower);
  feed(s, PCM.subarray(0, 32000));
  s.end();
  await waitFor(() => s.getState() === 'CLOSED');
  assert.ok(logs.lines.filter((l) => l.level === 'ERROR' && /listener\.\w+ threw/.test(l.message)).length >= 5);
  const rl = recordingListener();
  const quiet = c.streamEvaluate(NATIVE, rl.listener);
  quiet.removeListener();
  feed(quiet, PCM.subarray(0, 32000));
  quiet.end();
  await waitFor(() => quiet.getState() === 'CLOSED');
  assert.equal(rl.events.length, 0);
  quiet.setListener(rl.listener);
});

test('heartbeat: pings only while CONNECTED or STARTED after a pong, none while ENDING', async () => {
  const { c, sockets } = setup({ resultDelayMs: 250 }, { heartbeatIntervalMs: 20, heartbeatTimeoutMs: 400, resultTimeoutMs: 2000 });
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await waitFor(() => s.getState() === 'STARTED');
  await sleep(120);
  assert.equal(s.getStats().heartbeat, 'active');
  const pingsBeforeEnd = sockets[0].cmds().filter((x) => x === 'ping').length;
  assert.ok(pingsBeforeEnd >= 3, String(pingsBeforeEnd));
  feed(s, PCM.subarray(0, 32000));
  s.end();
  await rl.done;
  const cmds = sockets[0].cmds();
  assert.equal(cmds.slice(cmds.indexOf('end')).filter((x) => x === 'ping').length, 0, 'no ping while ENDING');
  assert.equal(rl.count('onResult'), 1);
});

test('heartbeat timeout counts as a transport failure and reconnects', async () => {
  const { c, sockets } = setup((i) => (i === 0 ? { answerPing: 'pong', mute: true } : {}), { heartbeatIntervalMs: 20, heartbeatTimeoutMs: 80 });
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await waitFor(() => s.getState() === 'STARTED');
  sockets[0].server.onFrame = () => {}; // the first server goes silent after starting
  feed(s, PCM.subarray(0, 6400));
  await waitFor(() => rl.count('onReconnecting') === 1, 2000, 'reconnect');
  const cause = rl.first('onReconnecting').args[2];
  assert.ok(cause instanceof RequestTimeoutException);
  assert.match(cause.message, /heartbeat timeout/);
  await waitFor(() => s.getState() === 'STARTED' && sockets.length === 2);
  s.end();
  await rl.done;
  assert.equal(rl.count('onResult'), 1);
  assert.equal(binaryTotal(sockets[1]), 6400, 'REPLAY re-sends the audio');
});

test('servers without heartbeat support: unknown cmd, or ping taken as parameters', async () => {
  for (const [mode, answerPing] of [['native', 'unknown'], ['compat', 'restart']]) {
    const { c, sockets, logs } = setup({ mode, answerPing }, { heartbeatIntervalMs: 15 });
    const rl = recordingListener();
    const s = mode === 'native' ? c.streamEvaluate(NATIVE, rl.listener) : c.streamEvaluateCompat('sent.eval.cn', { refText: '北京你好' }, rl.listener);
    await waitFor(() => s.getState() === 'STARTED');
    await sleep(80);
    feed(s, PCM.subarray(0, 12800));
    s.end();
    await rl.done;
    assert.equal(rl.count('onResult'), 1, mode);
    assert.equal(rl.count('onError'), 0, mode);
    assert.equal(sockets[0].cmds().filter((x) => x === 'ping').length, 1, mode + ': only the probe');
    assert.equal(sockets[0].server.state.audio, 12800, mode + ': the server kept every audio byte');
    assert.equal(s.getStats().heartbeat, 'disabled');
    assert.match(logs.text(), /heartbeat off for this session/);
  }
});

test('heartbeatIntervalMs 0 sends no ping at all', async () => {
  const { c, sockets } = setup({}, { heartbeatIntervalMs: 0 });
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener, { heartbeatIntervalMs: 0 });
  feed(s, PCM.subarray(0, 3200));
  s.end();
  await rl.done;
  assert.ok(!sockets[0].cmds().includes('ping'));
});

test('connect timeout reconnects, and exhausted attempts end in 90006 with the cause', async () => {
  const events = [];
  const { c, sockets } = setup({ connected: false }, {
    connectTimeoutMs: 40, reconnect: { maxAttempts: 2, initialDelayMs: 5, jitter: 0 },
    eventListener: { onReconnect: (...a) => events.push(a) },
  });
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  const t0 = Date.now();
  await rl.done;
  assert.ok(Date.now() - t0 < 1000, 'terminal callback within a bound');
  assert.equal(sockets.length, 3);
  const err = rl.first('onError').args[0];
  assert.equal(err.code, 90006);
  assert.ok(err instanceof NetworkException);
  assert.ok(err.cause instanceof RequestTimeoutException);
  assert.equal(err.idempotencyKey, s.idempotencyKey);
  assert.deepEqual(events.map((e) => [e[1], e[2]]), [[1, false], [2, false]]);
  assert.deepEqual(rl.states(), ['CONNECTING', 'RECONNECTING', 'CONNECTING', 'RECONNECTING', 'CONNECTING', 'FAILED', 'CLOSED']);
  assert.deepEqual(rl.first('onClosed').args[0], 1006);
});

test('result timeout: REPLAY reconnects and re-sends audio and end; FAIL reports 90007', async () => {
  const { c, sockets } = setup((i) => (i === 0 ? { onEnd: 'none' } : { replayed: true }), { resultTimeoutMs: 60 });
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  feed(s, PCM.subarray(0, 9600));
  s.end();
  await rl.done;
  assert.equal(rl.first('onReconnecting').args[2].code, 90007);
  assert.equal(sockets.length, 2);
  const second = sockets[1].cmds();
  assert.deepEqual([second[0], second[1], second.at(-1)], ['ping', 'start', 'end']);
  assert.equal(binaryTotal(sockets[1]), 9600);
  assert.equal(sockets[1].texts()[1].idempotencyKey, s.idempotencyKey);
  assert.equal(sockets[1].query().idempotencyKey, s.idempotencyKey);
  const result = rl.first('onResult').args[0];
  assert.equal(result.replayed, true);
  assert.equal(result.attempts, 2);
  const { c: c2 } = setup({ onEnd: 'none' }, { resultTimeoutMs: 50, audioBufferPolicy: 'FAIL' });
  const rl2 = recordingListener();
  const s2 = c2.streamEvaluate(NATIVE, rl2.listener);
  feed(s2, PCM.subarray(0, 3200));
  s2.end();
  await rl2.done;
  assert.equal(rl2.first('onError').args[0].code, 90007);
  assert.equal(rl2.count('onReconnecting'), 0);
});

test('server error frames: retryable codes reconnect, others fail at once', async () => {
  const { c, sockets } = setup((i) => (i === 0 ? { onEnd: { event: 'error', code: 50200, message: 'upstream' } } : {}));
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  feed(s, PCM.subarray(0, 3200));
  s.end();
  await rl.done;
  assert.equal(rl.first('onReconnecting').args[2].code, 50200);
  assert.equal(rl.count('onResult'), 1);
  assert.equal(sockets.length, 2);
  for (const [frame, code] of [[{ event: 'error', code: 40001, message: 'refText missing' }, 40001], [{ event: 'error', message: 'no audio' }, 0]]) {
    const x = setup({ onEnd: frame });
    const r = recordingListener();
    const t = x.c.streamEvaluate(NATIVE, r.listener);
    t.sendAudio(new Uint8Array(640));
    t.end();
    await r.done;
    const err = r.first('onError').args[0];
    assert.equal(err.code, code);
    assert.equal(r.count('onReconnecting'), 0);
    if (code === 0) assert.ok(err instanceof ServerException);
  }
});

test('close codes: 1011 reconnects, 1000 before the result is a protocol error', async () => {
  const { c, sockets } = setup();
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await waitFor(() => s.getState() === 'STARTED');
  sockets[0].serverClose(1011, 'server error');
  await waitFor(() => s.getState() === 'STARTED' && sockets.length === 2);
  s.end();
  await rl.done;
  assert.match(rl.first('onReconnecting').args[2].message, /code 1011/);
  assert.equal(rl.count('onResult'), 1);
  const x = setup();
  const r2 = recordingListener();
  const s2 = x.c.streamEvaluate(NATIVE, r2.listener);
  await waitFor(() => s2.getState() === 'STARTED');
  x.sockets[0].serverClose(1000, 'bye');
  await r2.done;
  assert.ok(r2.first('onError').args[0] instanceof ProtocolViolationException);
  assert.deepEqual(r2.first('onClosed').args[0], 1000);
});

test('close codes a reconnect cannot fix end the session at once with 90005', async () => {
  for (const code of [1002, 1003, 1007, 1008, 1009, 1010, 4000, 4404, 4999]) {
    const { c, sockets } = setup();
    const rl = recordingListener();
    const s = c.streamEvaluate(NATIVE, rl.listener);
    await waitFor(() => s.getState() === 'STARTED');
    sockets[0].serverClose(code, 'refused');
    await rl.done;
    const err = rl.first('onError').args[0];
    assert.ok(err instanceof ProtocolViolationException, String(code));
    assert.equal(err.code, 90005);
    assert.match(err.message, new RegExp('code ' + code));
    assert.equal(rl.count('onReconnecting'), 0, String(code));
    assert.equal(rl.first('onClosed').args[0], code);
    assert.equal(sockets.length, 1);
  }
  for (const code of [1001, 1006, 1011, 1012, 1013, 3000]) {
    const { c, sockets } = setup();
    const rl = recordingListener();
    const s = c.streamEvaluate(NATIVE, rl.listener);
    await waitFor(() => s.getState() === 'STARTED');
    sockets[0].serverClose(code, 'transient');
    await waitFor(() => sockets.length === 2 && s.getState() === 'STARTED');
    s.end();
    await rl.done;
    assert.equal(rl.count('onReconnecting'), 1, String(code));
    assert.equal(rl.count('onResult'), 1, String(code));
  }
});

test('at most 3 x maxAttempts reconnects per session, even when every reconnect succeeds', async () => {
  const { c, sockets } = setup({}, { reconnect: { maxAttempts: 2, initialDelayMs: 5, jitter: 0 } });
  // a server that keeps accepting the session and then dropping it
  const rl = recordingListener({
    onStateChanged: (o, n) => {
      if (n === 'STARTED') {
        const task = sockets[sockets.length - 1];
        setTimeout(() => task.serverClose(1006, 'dropped'), 2);
      }
    },
  });
  const s = c.streamEvaluate(NATIVE, rl.listener);
  s.sendAudio(PCM.subarray(0, 640));
  await rl.done;
  assert.equal(rl.count('onReconnected'), 6, 'each reconnect succeeded, so the consecutive count kept restarting');
  assert.equal(sockets.length, 7, 'the first connection plus 3 x maxAttempts reconnects');
  const err = rl.first('onError').args[0];
  assert.equal(err.code, 90006);
  assert.match(err.message, /reconnect limit of this session reached \(6 in total\)/);
  assert.ok(err.cause instanceof NetworkException);
  assert.deepEqual(rl.events.filter((e) => e.name === 'onReconnecting').map((e) => e.args[0]), [1, 1, 1, 1, 1, 1]);
  assert.equal(s.getStats().reconnectAttempts, 6);
  assert.equal(s.getStats().reconnects, 6);
});

test('frames that are not JSON objects fail the session; binary frames are ignored', async () => {
  const { c, sockets } = setup();
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await waitFor(() => s.getState() === 'STARTED');
  sockets[0].binary(new ArrayBuffer(4));
  sockets[0].frame({ event: 'mystery' });
  sockets[0].frame({ eof: 1, result: { overall: 1 } });
  assert.equal(s.getState(), 'CLOSED', 'a result in STARTED completes the session');
  const x = setup();
  const r2 = recordingListener();
  const s2 = x.c.streamEvaluate(NATIVE, r2.listener);
  await waitFor(() => s2.getState() === 'STARTED');
  x.sockets[0].frame('not json');
  await r2.done;
  assert.equal(r2.first('onError').args[0].code, 90005);
});

test('exactly one terminal callback even when the server repeats itself', async () => {
  const { c, sockets } = setup();
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await waitFor(() => s.getState() === 'STARTED');
  s.end();
  await rl.done;
  sockets[0].frame({ event: 'result', eof: 1, result: { overall: 1 } });
  sockets[0].frame({ event: 'error', code: 50000 });
  sockets[0].serverClose(1006);
  await sleep(20);
  assert.equal(rl.count('onResult') + rl.count('onError'), 1);
  assert.equal(rl.names().at(-1), 'onClosed');
  assert.equal(rl.count('onClosed'), 1);
});

test('REPLAY: every audio byte of the round is replayed after a server kill; attempts reset after success', async () => {
  const policyLog = [];
  const { c, sockets } = setup({}, { random: seeded(3), reconnect: { initialDelayMs: 10, jitter: 0.3 }, eventListener: { onReconnect: (...a) => policyLog.push(a) } });
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  feed(s, PCM.subarray(0, 12800));
  await waitFor(() => s.getState() === 'STARTED' && binaryTotal(sockets[0]) === 12800);
  sockets[0].serverClose(1006, 'killed');
  feed(s, PCM.subarray(12800, 19200)); // sent while RECONNECTING, kept for replay
  await waitFor(() => sockets.length === 2 && s.getState() === 'STARTED');
  sockets[1].serverClose(1006, 'killed again');
  await waitFor(() => sockets.length === 3 && s.getState() === 'STARTED');
  feed(s, PCM.subarray(19200, 25600));
  s.end();
  await rl.done;
  assert.equal(binaryTotal(sockets[2]), 25600);
  assert.deepEqual(sockets[2].sent.filter((x) => x.binary).map((x) => x.binary.length).slice(0, 3), [640, 640, 640]);
  const reconnecting = rl.events.filter((e) => e.name === 'onReconnecting').map((e) => e.args[0]);
  const reconnected = rl.events.filter((e) => e.name === 'onReconnected').map((e) => e.args[0]);
  assert.deepEqual(reconnecting, [1, 1], 'the attempt counter restarts after a successful reconnect');
  assert.deepEqual(reconnected, [1, 1]);
  assert.equal(rl.events.filter((e) => e.name === 'onReconnected')[0].args[1].replayedBytes, 19200);
  const rnd = seeded(3);
  const p = { ...DEFAULT_RECONNECT_POLICY, initialDelayMs: 10 };
  assert.equal(rl.first('onReconnecting').args[1], computeBackoffDelay(1, p, rnd));
  assert.deepEqual(policyLog.map((e) => [e[1], e[2]]), [[1, true], [1, true]]);
  assert.equal(s.getStats().reconnects, 2);
  const order = rl.names().filter((n) => ['onReconnecting', 'onReconnected', 'onResult', 'onClosed'].includes(n));
  assert.deepEqual(order, ['onReconnecting', 'onReconnected', 'onReconnecting', 'onReconnected', 'onResult', 'onClosed']);
});

test('REPLAY buffer overflow: later reconnects fail with 90008', async () => {
  const { c, sockets } = setup({}, { replayBufferLimitBytes: 4000 });
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await waitFor(() => s.getState() === 'STARTED');
  feed(s, PCM.subarray(0, 6400));
  assert.equal(binaryTotal(sockets[0]), 6400, 'audio keeps flowing after the overflow');
  sockets[0].serverClose(1006);
  await rl.done;
  assert.equal(rl.first('onError').args[0].code, 90008);
  assert.equal(rl.count('onReconnecting'), 0);
  const x = setup({ connected: false }, { replayBufferLimitBytes: 4000 });
  const r2 = recordingListener();
  const s2 = x.c.streamEvaluate(NATIVE, r2.listener);
  feed(s2, PCM.subarray(0, 6400));
  await r2.done;
  assert.equal(r2.first('onError').args[0].code, 90008);
});

test('DROP: audio of the broken connection and of the reconnect window is dropped and counted', async () => {
  const { c, sockets } = setup({}, { audioBufferPolicy: AudioBufferPolicy.DROP });
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  feed(s, PCM.subarray(0, 6400)); // queued before the first start, delivered
  await waitFor(() => s.getState() === 'STARTED');
  feed(s, PCM.subarray(6400, 9600));
  sockets[0].serverClose(1006);
  feed(s, PCM.subarray(9600, 12800)); // dropped while reconnecting
  await waitFor(() => s.getState() === 'STARTED' && sockets.length === 2);
  feed(s, PCM.subarray(12800, 16000));
  s.end();
  await rl.done;
  const info = rl.first('onReconnected').args[1];
  assert.equal(info.droppedBytes, 12800);
  assert.equal(info.replayedBytes, 0);
  assert.equal(binaryTotal(sockets[1]), 3200);
  assert.equal(s.getStats().droppedBytes, 12800);
  assert.equal(rl.count('onResult'), 1);
  // a failure after end() cannot be repaired without the audio
  const x = setup({ onEnd: 'none' }, { audioBufferPolicy: 'DROP', resultTimeoutMs: 40 });
  const r2 = recordingListener();
  const s2 = x.c.streamEvaluate(NATIVE, r2.listener);
  feed(s2, PCM.subarray(0, 3200));
  s2.end();
  await r2.done;
  assert.equal(r2.first('onError').args[0].code, 90007);
  assert.equal(r2.count('onReconnecting'), 0);
});

test('FAIL policy and reconnect: false go straight to FAILED with the cause', async () => {
  for (const opts of [{ audioBufferPolicy: 'FAIL' }, { reconnect: false }]) {
    const { c, sockets } = setup({}, opts);
    const rl = recordingListener();
    const s = c.streamEvaluate(NATIVE, rl.listener);
    await waitFor(() => s.getState() === 'STARTED');
    const t0 = Date.now();
    sockets[0].error('connection reset');
    await rl.done;
    assert.ok(Date.now() - t0 < 200);
    const err = rl.first('onError').args[0];
    assert.ok(err instanceof NetworkException);
    assert.equal(err.code, 90001);
    assert.equal(rl.count('onReconnecting'), 0);
    assert.deepEqual(rl.states().slice(-2), ['FAILED', 'CLOSED']);
  }
});

test('precheck at end(): WARN reports onWarning and localWarnings, REJECT fails without sending end', async () => {
  const silence = new Uint8Array(32000 * 2);
  const { c, sockets } = setup();
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  feed(s, silence);
  s.end();
  await rl.done;
  assert.deepEqual(rl.events.filter((e) => e.name === 'onWarning').map((e) => e.args[0].code), [90103]);
  assert.deepEqual(rl.first('onResult').args[0].localWarnings.map((w) => w.code), [90103]);
  const x = setup({}, { audioPrecheck: 'REJECT' });
  const r2 = recordingListener();
  const s2 = x.c.streamEvaluate(NATIVE, r2.listener);
  await waitFor(() => s2.getState() === 'STARTED');
  feed(s2, PCM.subarray(0, 6400));
  assert.equal(s2.end(), false);
  await r2.done;
  const err = r2.first('onError').args[0];
  assert.ok(err instanceof AudioQualityException);
  assert.equal(err.code, 90101);
  assert.ok(!x.sockets[0].cmds().includes('end'), 'nothing billed: no end frame');
  assert.equal(sockets.length, 1);
  const off = setup({}, { audioPrecheck: 'OFF' });
  const r3 = recordingListener();
  const s3 = off.c.streamEvaluate(NATIVE, r3.listener);
  s3.end();
  await r3.done;
  assert.equal(r3.count('onWarning'), 0);
});

test('eventListener sees every state change; client.close() cancels open sessions', async () => {
  const changes = [];
  const { c } = setup({ connected: false }, { eventListener: { onSessionStateChanged: (...a) => changes.push(a) } });
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await waitFor(() => s.getState() === 'CONNECTING');
  c.close();
  await rl.done;
  assert.deepEqual(changes.map((x) => x[2]), ['CONNECTING', 'CANCELLED', 'CLOSED']);
  assert.ok(changes.every((x) => x[0] === s.id));
  assert.equal(rl.count('onResult') + rl.count('onError'), 0);
});

test('connectSocket throwing and failing sends are transport failures', async () => {
  const { c, sockets, wx } = setup();
  wx.__connectThrows = true;
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  await waitFor(() => s.getState() === 'STARTED' && sockets.length === 2);
  assert.match(rl.first('onReconnecting').args[2].message, /too many sockets/);
  sockets[1].failSends = true;
  feed(s, PCM.subarray(0, 1280));
  await waitFor(() => rl.count('onReconnecting') === 2);
  await waitFor(() => s.getState() === 'STARTED' && sockets.length === 3);
  s.end();
  await rl.done;
  assert.equal(binaryTotal(sockets[2]), 1280);
});

test('chunks above 32000 bytes go out as several frames, under the 128 KB frame limit', async () => {
  const { c, sockets } = setup();
  const rl = recordingListener();
  const s = c.streamEvaluate(NATIVE, rl.listener);
  const big = new Uint8Array(200 * 1024);
  for (let i = 0; i < big.length; i++) big[i] = (i * 7) & 0xff;
  assert.equal(s.sendAudio(big), true, 'queued before the start');
  await waitFor(() => s.getState() === 'STARTED');
  assert.equal(s.sendAudio(big.buffer), true, 'sent while STARTED');
  s.end();
  await rl.done;
  const frames = sockets[0].sent.filter((x) => x.binary).map((x) => x.binary);
  assert.ok(frames.every((f) => f.length <= 32000));
  assert.equal(frames.reduce((n, f) => n + f.length, 0), 2 * big.length);
  const joined = new Uint8Array(big.length);
  let off = 0;
  for (const f of frames) {
    if (off >= big.length) break;
    joined.set(f, off);
    off += f.length;
  }
  assert.deepEqual(joined, big, 'byte order kept');
  assert.equal(rl.count('onResult'), 1);
});

test('argument validation for sessions', () => {
  const { c } = setup();
  const L = { onResult() {}, onError() {} };
  const is90010 = (e) => e instanceof InvalidParameterException && e.code === 90010;
  assert.throws(() => c.streamEvaluate(NATIVE), is90010);
  assert.throws(() => c.streamEvaluate(NATIVE, { onResult() {} }), is90010);
  assert.throws(() => c.streamEvaluate({ ...NATIVE, coreType: 'x' }, L), is90010);
  assert.throws(() => c.streamEvaluate(NATIVE, L, 'x'), is90010);
  assert.throws(() => c.streamEvaluate(NATIVE, L, { audioPrecheck: 'NO' }), is90010);
  assert.throws(() => c.streamEvaluate(NATIVE, L, { query: [1] }), is90010);
  assert.throws(() => c.streamEvaluate(NATIVE, L, { audioBufferPolicy: 'KEEP' }), is90010);
  assert.throws(() => c.streamEvaluate(NATIVE, L, { idempotencyKey: 'bad key' }), is90010);
  assert.throws(() => c.streamEvaluateCompat('sent.eval.cn', {}, L), is90010);
  assert.throws(() => c.streamEvaluateCompat('pinyin', { refText: '重庆' }, L), is90010);
  assert.throws(() => c.streamEvaluateCompat('nope', { refText: 'a' }, L), is90010);
  const s = c.streamEvaluate(NATIVE, L);
  assert.throws(() => s.sendAudio('text'), is90010);
  s.cancel();
  assert.deepEqual(Object.keys(SessionState), ['IDLE', 'CONNECTING', 'CONNECTED', 'STARTED', 'ENDING', 'RECONNECTING', 'COMPLETED', 'FAILED', 'CANCELLED', 'CLOSED']);
});
