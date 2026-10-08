import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { YuguClient } from '../../src/client.js';
import {
  AudioQualityException,
  ConflictException,
  InvalidParameterException,
  NetworkException,
  PermissionException,
  ProtocolViolationException,
  RequestTimeoutException,
  ServerException,
  YuguError,
} from '../../src/errors.js';
import { YuguStreamSession } from '../../src/session.js';
import { signHmacSha256 } from '../../src/signer.js';
import { createFakeWebSocket } from '../helpers/fake-ws.mjs';
import { assertGuarantees, kinds, readJson, recordingListener, sleep, states, waitFor } from '../helpers/common.mjs';

const NATIVE_CFG = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' };
const RESULT = { recordId: 'eval_abc', eof: 1, result: { overall: 91, fluency: 90 } };
const frame = (n, fill = n & 0xff) => new Uint8Array(640).fill(fill);

function setup(extra = {}) {
  const fake = createFakeWebSocket();
  const events = [];
  const client = new YuguClient({
    token: 'jwt-token',
    baseUrl: 'http://api.test',
    WebSocket: fake.FakeWebSocket,
    logLevel: 'OFF',
    random: () => 0.5,
    reconnect: { initialDelayMs: 2, maxDelayMs: 10 },
    connectTimeoutMs: 1000,
    audioPrecheck: 'OFF',
    eventListener: {
      onSessionStateChanged: (...a) => events.push(['sess', ...a]),
      onReconnect: (...a) => events.push(['reconnect', ...a]),
    },
    ...extra,
  });
  const socket = (i) => waitFor(() => fake.sockets[i], 2000, `socket ${i}`);
  return { ...fake, client, socket, metrics: events };
}

describe('session happy paths', () => {
  test('native: states, callbacks, frames, URL and getState consistency', async () => {
    const { client, socket, serve, metrics } = setup();
    const l = recordingListener();
    const seen = [];
    let s;
    l.onStateChanged = (a, b) => {
      l.events.push(['state', a, b]);
      seen.push([b, s.getState()]);
    };
    s = client.streamEvaluate(NATIVE_CFG, l);
    assert.equal(s.getState(), 'IDLE');
    assert.equal(s.isActive(), true);
    assert.equal(s.mode, 'native');
    assert.equal(s.coreType, 'sentence');
    assert.match(s.idempotencyKey, /^[0-9a-f]{32}$/);
    s.sendAudio(frame(1));
    s.sendAudio(frame(2).buffer);
    const ws = await socket(0);
    const url = new URL(ws.url);
    assert.equal(url.origin + url.pathname, 'ws://api.test/api/v1/ws/evaluate');
    assert.equal(url.searchParams.get('token'), 'jwt-token');
    assert.equal(url.searchParams.get('idempotencyKey'), s.idempotencyKey);
    serve(ws, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    s.sendAudio(new Int16Array(320).fill(3));
    s.end();
    s.end();
    const ev = await l.done;
    assert.deepEqual(states(ev), ['CONNECTING', 'CONNECTED', 'STARTED', 'ENDING', 'COMPLETED', 'CLOSED']);
    assert.deepEqual(kinds(ev).filter((k) => k !== 'state'), ['connected', 'started', 'result', 'closed']);
    assertGuarantees(assert, ev);
    for (const [announced, got] of seen) assert.equal(got, announced, 'getState equals the state of the last onStateChanged');
    const start = ws.json[0];
    assert.deepEqual(start, { ...NATIVE_CFG, cmd: 'start', idempotencyKey: s.idempotencyKey });
    assert.equal(ws.audio.length, 3);
    assert.deepEqual([...ws.audio[0].slice(0, 2)], [1, 1]);
    assert.deepEqual(ws.json[ws.json.length - 1], { cmd: 'end' });
    const result = ev.find((e) => e[0] === 'result')[1];
    assert.equal(result.overall, 91);
    assert.equal(result.idempotencyKey, s.idempotencyKey);
    assert.equal(result.mode, 'native');
    assert.equal(s.getState(), 'CLOSED');
    assert.equal(s.isActive(), false);
    assert.equal((await s.waitForResult()).overall, 91);
    assert.deepEqual(metrics.filter((m) => m[0] === 'sess').map((m) => m[3]), states(ev));
    assert.equal(ws.closeCalls[0][0], 1000);
  });

  test('compat: parameter frame without cmd, progress frames to onPartial, compat result', async () => {
    const { client, socket, serve } = setup();
    const l = recordingListener();
    const s = client.streamEvaluateCompat('sent.eval.cn', { refText: '北京你好', realtime_feedback: true, idempotencyKey: 'ignored' }, l);
    const ws = await socket(0);
    assert.equal(new URL(ws.url).pathname, '/sent.eval.cn');
    serve(ws, { mode: 'compat', result: { recordId: 'eval_c', eof: 1, result: { overall: 88 } } });
    await waitFor(() => s.getState() === 'STARTED');
    assert.deepEqual(ws.json[0], { refText: '北京你好', realtime_feedback: true, idempotencyKey: s.idempotencyKey });
    ws.serverSend({ eof: 0, result: { bytes: 16000 } });
    ws.serverSend({ eof: 0, result: {} });
    ws.serverSend({ event: 'started', coreType: 'sent.eval.cn' });
    s.end();
    const ev = await l.done;
    assert.deepEqual(ev.filter((e) => e[0] === 'partial').map((e) => e[1]), [16000, null]);
    const r = ev.find((e) => e[0] === 'result')[1];
    assert.equal(r.mode, 'compat');
    assert.equal(r.coreType, 'sent.eval.cn');
    assert.equal(r.overall, 88);
    assertGuarantees(assert, ev);
    assert.equal(states(ev).filter((x) => x === 'STARTED').length, 1, 'duplicate started frames are ignored');
  });

  test('end() before STARTED: audio flushed in order, then end, then ENDING', async () => {
    for (const policy of ['REPLAY', 'DROP', 'FAIL']) {
      const { client, socket, serve } = setup();
      const l = recordingListener();
      const s = client.streamEvaluate(NATIVE_CFG, l, { audioBufferPolicy: policy });
      for (let i = 1; i <= 5; i++) assert.equal(s.sendAudio(frame(i)), true);
      s.end();
      assert.equal(s.sendAudio(frame(9)), false, 'audio after end is discarded');
      serve(await socket(0), { result: RESULT });
      const ev = await l.done;
      const ws = await socket(0);
      assert.deepEqual(ws.audio.map((b) => b[0]), [1, 2, 3, 4, 5], policy);
      assert.deepEqual(ws.json.map((f) => f.cmd), ['start', 'end']);
      assertGuarantees(assert, ev);
    }
  });

  test('chunks larger than 32000 bytes are split under the 128 KB frame limit', async () => {
    const { client, socket, serve } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws = await socket(0);
    serve(ws, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    const big = new Uint8Array(100000).map((_, i) => i & 0xff);
    assert.equal(s.sendAudio(big), true);
    assert.deepEqual(ws.audio.map((b) => b.length), [32000, 32000, 32000, 4000]);
    assert.deepEqual(new Uint8Array(ws.audio.flatMap((b) => [...b])), big);
    s.sendAudio(new Uint8Array(40000), { base64: true });
    const audioFrames = ws.json.filter((f) => f.cmd === 'audio');
    assert.equal(audioFrames.length, 2);
    assert.ok(audioFrames.every((f) => f.data.length < 128 * 1024));
    s.end();
    assertGuarantees(assert, await l.done);
  });

  test('base64 audio frames, binary and non-JSON server frames are ignored', async () => {
    const { client, socket, serve } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws = await socket(0);
    serve(ws, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    s.sendAudio(new Uint8Array([1, 2, 3]), { base64: true });
    assert.deepEqual(ws.json[1], { cmd: 'audio', data: 'AQID' });
    ws.onmessage({ data: new ArrayBuffer(4) });
    ws.onmessage({ data: 'not json' });
    ws.onmessage({ data: '[1,2]' });
    ws.onmessage({ data: new TextEncoder().encode('{"event":"something-new"}') });
    ws.onmessage({ data: new Blob(['x']) });
    assert.equal(s.getState(), 'STARTED');
    s.end();
    const ev = await l.done;
    assertGuarantees(assert, ev);
    assert.throws(() => s.sendAudio('x'), InvalidParameterException);
  });
});

describe('reconnect and buffer policies (A-03)', () => {
  test('REPLAY: new server session with the same key, same start frame, every byte replayed, end re-sent', async () => {
    const { client, socket, serve, metrics } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws0 = await socket(0);
    serve(ws0, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    for (let i = 1; i <= 4; i++) s.sendAudio(frame(i));
    ws0.send = () => {};
    s.end();
    assert.equal(s.getState(), 'ENDING');
    ws0.fail('ECONNRESET');
    await waitFor(() => s.getState() !== 'ENDING');
    const ws1 = await socket(1);
    assert.equal(s.sendAudio(frame(7)), false, 'end was already called');
    serve(ws1, { result: { ...RESULT, replayed: true } });
    const ev = await l.done;
    assert.deepEqual(states(ev), ['CONNECTING', 'CONNECTED', 'STARTED', 'ENDING', 'RECONNECTING', 'CONNECTING', 'CONNECTED', 'STARTED', 'ENDING', 'COMPLETED', 'CLOSED']);
    const rc = ev.find((e) => e[0] === 'reconnecting');
    assert.equal(rc[1], 1);
    assert.ok(rc[3] instanceof NetworkException);
    assert.equal(rc[3].code, 90001);
    const rd = ev.find((e) => e[0] === 'reconnected');
    assert.deepEqual([rd[1], rd[2]], [1, { droppedBytes: 0, replayedBytes: 4 * 640 }]);
    assert.equal(new URL(ws1.url).searchParams.get('idempotencyKey'), s.idempotencyKey);
    assert.deepEqual(ws1.json[0], ws0.json[0], 'identical start frame');
    assert.deepEqual(ws1.audio.map((b) => b[0]), [1, 2, 3, 4]);
    assert.deepEqual(ws1.json[ws1.json.length - 1], { cmd: 'end' });
    assert.equal(kinds(ev).filter((k) => k === 'connected').length, 1, 'onConnected only for the first connection');
    assert.equal(kinds(ev).filter((k) => k === 'started').length, 1, 'onStarted only for the first start');
    const r = ev.find((e) => e[0] === 'result')[1];
    assert.equal(r.replayed, true);
    assertGuarantees(assert, ev);
    assert.deepEqual(metrics.filter((m) => m[0] === 'reconnect').map((m) => m.slice(2)), [[1, true]]);
    assert.equal(s.getStats().reconnectAttempts, 1);
  });

  test('DROP: audio of the failed connection and audio sent while reconnecting are discarded and counted', async () => {
    const { client, socket, serve } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l, { audioBufferPolicy: 'DROP' });
    const ws0 = await socket(0);
    serve(ws0, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    s.sendAudio(frame(1));
    s.sendAudio(frame(2));
    ws0.serverClose(1011, 'server error');
    await waitFor(() => s.getState() === 'RECONNECTING');
    assert.equal(s.sendAudio(frame(3)), false);
    const ws1 = await socket(1);
    assert.equal(s.sendAudio(frame(4)), false, 'still before STARTED of the new session');
    serve(ws1, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    assert.equal(s.sendAudio(frame(5)), true);
    s.end();
    const ev = await l.done;
    const rd = ev.find((e) => e[0] === 'reconnected');
    assert.deepEqual(rd[2], { droppedBytes: 4 * 640, replayedBytes: 0 });
    assert.deepEqual(ws1.audio.map((b) => b[0]), [5]);
    assert.equal(s.getStats().droppedBytes, 4 * 640);
    assertGuarantees(assert, ev);
  });

  test('FAIL: transport failure goes straight to FAILED and onError', async () => {
    const { client, socket, serve, sockets } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l, { audioBufferPolicy: 'FAIL' });
    const ws0 = await socket(0);
    serve(ws0);
    await waitFor(() => s.getState() === 'STARTED');
    ws0.fail('ECONNRESET');
    const ev = await l.done;
    assert.deepEqual(states(ev).slice(-2), ['FAILED', 'CLOSED']);
    const err = ev.find((e) => e[0] === 'error')[1];
    assert.ok(err instanceof NetworkException);
    assert.equal(err.idempotencyKey, s.idempotencyKey);
    assert.equal(sockets.length, 1, 'no reconnect');
    assertGuarantees(assert, ev);
    await assert.rejects(s.waitForResult(), NetworkException);
    assert.equal(ev.find((e) => e[0] === 'closed')[1], 1006);
  });

  test('reconnect disabled, or no idempotency key: no reconnect', async () => {
    for (const opts of [{ reconnect: false }, { reconnect: { maxAttempts: 0 } }]) {
      const { client, socket, serve, sockets } = setup();
      const l = recordingListener();
      const s = client.streamEvaluate(NATIVE_CFG, l, opts);
      serve(await socket(0));
      await waitFor(() => s.getState() === 'STARTED');
      sockets[0].fail();
      await l.done;
      assert.equal(sockets.length, 1);
    }
    const { client, socket, serve, sockets } = setup({ autoIdempotencyKey: false, logLevel: 'DEBUG', logger: () => {} });
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    assert.equal(s.idempotencyKey, null);
    serve(await socket(0));
    await waitFor(() => s.getState() === 'STARTED');
    assert.equal(new URL(sockets[0].url).searchParams.get('idempotencyKey'), null);
    assert.equal(sockets[0].json[0].idempotencyKey, undefined);
    sockets[0].fail();
    const ev = await l.done;
    assert.equal(sockets.length, 1, 'without a key a reconnect could bill twice');
    assertGuarantees(assert, ev);
  });

  test('exhausted reconnects end with 90006, cause kept, every failed attempt reported', async () => {
    const { client, socket, serve, sockets, metrics } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l, { reconnect: { maxAttempts: 3 } });
    serve(await socket(0));
    await waitFor(() => s.getState() === 'STARTED');
    sockets[0].fail();
    for (let i = 1; i <= 3; i++) (await socket(i)).fail('Unexpected server response: 503');
    const ev = await l.done;
    assert.equal(sockets.length, 4, 'initial connection plus 3 reconnects');
    assert.deepEqual(ev.filter((e) => e[0] === 'reconnecting').map((e) => e[1]), [1, 2, 3]);
    const err = ev.find((e) => e[0] === 'error')[1];
    assert.ok(err instanceof NetworkException);
    assert.equal(err.code, 90006);
    assert.equal(err.retryable, false);
    assert.ok(err.cause instanceof ServerException);
    assert.equal(err.cause.httpStatus, 503);
    assert.equal(err.attempts, 4);
    assert.deepEqual(metrics.filter((m) => m[0] === 'reconnect').map((m) => m.slice(2)), [[1, false], [2, false], [3, false]]);
    assertGuarantees(assert, ev);
  });

  test('default ReconnectPolicy: 8 consecutive attempts with waits 0.5, 1, 2, 4, 4, 4, 4, 4 s', async () => {
    const { client, socket, serve, sockets } = setup({ reconnect: { initialDelayMs: 2, maxDelayMs: 16 } });
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    serve(await socket(0));
    await waitFor(() => s.getState() === 'STARTED');
    sockets[0].fail();
    for (let i = 1; i <= 8; i++) (await socket(i)).fail('ECONNREFUSED');
    const ev = await l.done;
    assert.equal(sockets.length, 9, 'initial connection plus 8 attempts');
    assert.deepEqual(ev.filter((e) => e[0] === 'reconnecting').map((e) => [e[1], e[2]]), [[1, 2], [2, 4], [3, 8], [4, 16], [5, 16], [6, 16], [7, 16], [8, 16]]);
    assert.equal(ev.find((e) => e[0] === 'error')[1].code, 90006);
    const { DEFAULT_RECONNECT_POLICY, computeBackoffDelay } = await import('../../src/retry.js');
    const waits = [1, 2, 3, 4, 5, 6, 7, 8].map((n) => computeBackoffDelay(DEFAULT_RECONNECT_POLICY, n, () => 0.5));
    assert.deepEqual(waits, [500, 1000, 2000, 4000, 4000, 4000, 4000, 4000]);
    assert.equal(waits.reduce((a, b) => a + b, 0), 23500);
  });

  test('consecutive failures reset after a successful reconnect', async () => {
    const { client, socket, serve, sockets, metrics } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l, { reconnect: { maxAttempts: 2 } });
    serve(await socket(0));
    await waitFor(() => s.getState() === 'STARTED');
    sockets[0].fail();
    (await socket(1)).fail();
    serve(await socket(2), { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    sockets[2].fail();
    (await socket(3)).fail();
    serve(await socket(4), { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    s.end();
    const ev = await l.done;
    assertGuarantees(assert, ev);
    assert.deepEqual(ev.filter((e) => e[0] === 'reconnecting').map((e) => e[1]), [1, 2, 1, 2], 'numbering starts again after a success');
    assert.deepEqual(ev.filter((e) => e[0] === 'reconnected').map((e) => e[1]), [2, 2]);
    assert.deepEqual(metrics.filter((m) => m[0] === 'reconnect').map((m) => m.slice(2)), [[1, false], [2, true], [1, false], [2, true]]);
    assert.equal(s.getStats().reconnectAttempts, 4, 'stats count every attempt');
  });

  test('a server that always drops the started session ends after 3 times maxAttempts reconnects', async () => {
    const { client, socket, serve, sockets } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l, { reconnect: { maxAttempts: 2 } });
    for (let i = 0; i <= 6; i++) {
      serve(await socket(i));
      await waitFor(() => s.getState() === 'STARTED' || !s.isActive());
      if (!s.isActive()) break;
      sockets[i].fail();
    }
    const ev = await l.done;
    const err = ev.find((e) => e[0] === 'error')[1];
    assert.equal(err.code, 90006);
    assert.equal(ev.filter((e) => e[0] === 'reconnecting').length, 6);
    assert.equal(err.attempts, 7);
    assertGuarantees(assert, ev);
  });

  test('REPLAY overflow makes the next reconnect fail with 90008', async () => {
    const { client, socket, serve, sockets } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l, { maxReplayBytes: 1000 });
    serve(await socket(0));
    await waitFor(() => s.getState() === 'STARTED');
    s.sendAudio(frame(1));
    s.sendAudio(frame(2));
    assert.equal(s.getStats().replayOverflow, true);
    assert.equal(sockets[0].audio.length, 2, 'audio still goes out live');
    sockets[0].fail();
    const ev = await l.done;
    const err = ev.find((e) => e[0] === 'error')[1];
    assert.equal(err.code, 90008);
    assertGuarantees(assert, ev);
  });

  test('connect timeout and a constructor that throws trigger reconnects', async () => {
    const { client, socket, serve, sockets } = setup({ connectTimeoutMs: 30 });
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    await socket(0);
    const ws1 = await socket(1);
    const rc = l.events.find((e) => e[0] === 'reconnecting');
    assert.ok(rc[3] instanceof RequestTimeoutException);
    assert.equal(rc[3].code, 90002);
    serve(ws1, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    s.end();
    assertGuarantees(assert, await l.done);
    assert.equal(sockets.length, 2);

    let n = 0;
    const fake = createFakeWebSocket();
    class Flaky extends fake.FakeWebSocket {
      constructor(url) {
        n += 1;
        if (n === 1) throw new Error('blocked by policy');
        super(url);
      }
    }
    const c2 = new YuguClient({ token: 't', baseUrl: 'http://h', WebSocket: Flaky, logLevel: 'OFF', reconnect: { initialDelayMs: 1 } });
    const l2 = recordingListener();
    const s2 = c2.streamEvaluate(NATIVE_CFG, l2);
    const w = await waitFor(() => fake.sockets[0]);
    fake.serve(w, { result: RESULT });
    await waitFor(() => s2.getState() === 'STARTED');
    s2.end();
    const ev2 = await l2.done;
    assert.ok(ev2.find((e) => e[0] === 'reconnecting')[3].message.includes('blocked by policy'));
  });

  test('result timeout after end is retryable 90007 and reconnects', async () => {
    const { client, socket, serve } = setup({ resultTimeoutMs: 40 });
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws0 = await socket(0);
    serve(ws0);
    await waitFor(() => s.getState() === 'STARTED');
    s.sendAudio(frame(1));
    const realSend = ws0.send;
    ws0.send = (d) => {
      if (typeof d === 'string' && JSON.parse(d).cmd === 'end') return; // swallow: no result
      realSend(d);
    };
    s.end();
    const ws1 = await socket(1);
    const rc = l.events.find((e) => e[0] === 'reconnecting');
    assert.equal(rc[3].code, 90007);
    assert.ok(rc[3] instanceof RequestTimeoutException);
    serve(ws1, { result: RESULT });
    const ev = await l.done;
    assert.equal(ws1.audio.length, 1);
    assertGuarantees(assert, ev);
  });

  test('send failure is a transport failure', async () => {
    const { client, socket, serve } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws0 = await socket(0);
    serve(ws0);
    await waitFor(() => s.getState() === 'STARTED');
    ws0.send = () => {
      throw new Error('socket gone');
    };
    s.sendAudio(frame(1));
    const ws1 = await socket(1);
    serve(ws1, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    s.end();
    const ev = await l.done;
    assert.deepEqual(ws1.audio.map((b) => b[0]), [1], 'replayed on the new connection');
    assertGuarantees(assert, ev);
  });
});

describe('server frames and close codes', () => {
  test('retryable error frame reconnects, terminal error frame fails', async () => {
    const { client, socket, serve } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws0 = await socket(0);
    serve(ws0);
    await waitFor(() => s.getState() === 'STARTED');
    ws0.serverSend({ event: 'error', code: 50200, message: 'mock upstream error' });
    const ws1 = await socket(1);
    assert.ok(l.events.find((e) => e[0] === 'reconnecting')[3] instanceof ServerException);
    serve(ws1);
    await waitFor(() => s.getState() === 'STARTED');
    ws1.serverSend({ event: 'error', code: 40903, message: '幂等键已用于另一个不同的请求' });
    const ev = await l.done;
    const err = ev.find((e) => e[0] === 'error')[1];
    assert.ok(err instanceof ConflictException);
    assert.equal(err.code, 40903);
    assertGuarantees(assert, ev);
  });

  test('error frame without code is terminal; blocked frame is InvalidParameterException', async () => {
    for (const [f, check] of [
      [{ event: 'error', message: 'no audio/config' }, (e) => e.constructor === YuguError && e.category === 'UNKNOWN'],
      [{ event: 'blocked', blocked: true, category: 'politics', message: '参考文本未通过内容安全审查' }, (e) => e instanceof InvalidParameterException],
    ]) {
      const { client, socket, serve, sockets } = setup();
      const l = recordingListener();
      const s = client.streamEvaluate(NATIVE_CFG, l);
      serve(await socket(0));
      await waitFor(() => s.getState() === 'STARTED');
      sockets[0].serverSend(f);
      const ev = await l.done;
      assert.ok(check(ev.find((e) => e[0] === 'error')[1]));
      assert.equal(sockets.length, 1);
      assertGuarantees(assert, ev);
    }
  });

  test('close 1000 before a result, protocol close codes and 4xxx are terminal; 1001, 1006, 1011, 1012 reconnect', async () => {
    for (const [code, expectReconnect] of [[1000, false], [4403, false], [1002, false], [1003, false], [1007, false], [1008, false], [1009, false], [1010, false], [1001, true], [1011, true], [1012, true], [1006, true]]) {
      const { client, socket, serve, sockets } = setup();
      const l = recordingListener();
      const s = client.streamEvaluate(NATIVE_CFG, l);
      serve(await socket(0));
      await waitFor(() => s.getState() === 'STARTED');
      sockets[0].serverClose(code, 'bye');
      if (expectReconnect) {
        serve(await socket(1), { result: RESULT });
        await waitFor(() => s.getState() === 'STARTED');
        s.end();
      }
      const ev = await l.done;
      if (!expectReconnect) {
        const err = ev.find((e) => e[0] === 'error')[1];
        assert.ok(err instanceof ProtocolViolationException, `code ${code}`);
        assert.equal(ev.find((e) => e[0] === 'closed')[1], code);
      }
      assert.equal(sockets.length, expectReconnect ? 2 : 1, `close ${code}`);
      assertGuarantees(assert, ev);
    }
  });

  test('handshake rejected with 403 is PermissionException without reconnect; 503 reconnects', async () => {
    const { client, socket, sockets } = setup();
    const l = recordingListener();
    client.streamEvaluate(NATIVE_CFG, l);
    (await socket(0)).fail('Unexpected server response: 403');
    const ev = await l.done;
    const err = ev.find((e) => e[0] === 'error')[1];
    assert.ok(err instanceof PermissionException);
    assert.equal(err.httpStatus, 403);
    assert.equal(sockets.length, 1);
    const b = setup();
    const l2 = recordingListener();
    const s2 = b.client.streamEvaluate(NATIVE_CFG, l2);
    (await b.socket(0)).fail('Unexpected server response: 503');
    b.serve(await b.socket(1), { result: RESULT });
    await waitFor(() => s2.getState() === 'STARTED');
    s2.end();
    assertGuarantees(assert, await l2.done);
  });

  test('TLS failure on connect is not retried', async () => {
    const { client, socket, sockets } = setup();
    const l = recordingListener();
    client.streamEvaluate(NATIVE_CFG, l);
    const ws = await socket(0);
    ws.onerror({ message: 'certificate has expired', error: Object.assign(new Error('certificate has expired'), { code: 'CERT_HAS_EXPIRED' }) });
    const ev = await l.done;
    assert.equal(ev.find((e) => e[0] === 'error')[1].code, 90011);
    assert.equal(sockets.length, 1);
  });
});

describe('heartbeat (DESIGN 2.5): {"cmd":"ping"} only once the server is known to answer pong', () => {
  const HB = { heartbeatIntervalMs: 10, heartbeatTimeoutMs: 1000 };

  test('native auto: no ping while CONNECTED, probe in STARTED, pong enables heartbeat for the client', async () => {
    const { client, socket, serve, sockets } = setup(HB);
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws0 = await socket(0);
    ws0.accept();
    ws0.serverSend({ event: 'connected' });
    await sleep(40);
    assert.equal(ws0.pings, 0, 'no probe before STARTED');
    ws0.serverSend({ event: 'started' });
    await waitFor(() => ws0.pings >= 1, 1000, 'probe ping');
    ws0.serverSend({ event: 'pong', ts: 1 });
    await waitFor(() => ws0.pings >= 3, 1000, 'more pings');
    assert.equal(s.getStats().heartbeat, true);
    ws0.send = (d) => {
      if (typeof d === 'string' && JSON.parse(d).cmd === 'end') queueMicrotask(() => ws0.serverSend({ event: 'result', ...RESULT }));
    };
    s.end();
    await l.done;
    // A compat session on the same client now pings even in CONNECTED.
    const l2 = recordingListener();
    const s2 = client.streamEvaluateCompat('sent.eval.cn', { refText: 'x' }, l2);
    const ws1 = await socket(1);
    ws1.accept();
    ws1.serverSend({ event: 'connected', coreType: 'sent.eval.cn' });
    await waitFor(() => ws1.pings >= 2, 1000, 'compat pings after pong was seen');
    s2.cancel();
    await l2.done;
    assert.equal(sockets.length, 2);
  });

  test('native auto: "unknown cmd" answer turns the heartbeat off, the session goes on, compat never probes', async () => {
    const { client, socket, sockets } = setup(HB);
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws0 = await socket(0);
    ws0.accept();
    ws0.serverSend({ event: 'connected' });
    ws0.serverSend({ event: 'started' });
    await waitFor(() => ws0.pings >= 1);
    ws0.serverSend({ event: 'error', message: 'unknown cmd' });
    const pings = ws0.pings;
    await sleep(50);
    assert.ok(ws0.pings <= pings + 1, 'heartbeat stopped');
    assert.equal(s.getState(), 'STARTED');
    assert.equal(s.getStats().heartbeat, false);
    ws0.serverSend({ event: 'result', ...RESULT });
    const ev = await l.done;
    assert.equal(ev.find((e) => e[0] === 'result')[1].overall, 91);
    assertGuarantees(assert, ev);
    // Next native session does not probe again on this client.
    const l2 = recordingListener();
    const s2 = client.streamEvaluate(NATIVE_CFG, l2);
    const ws1 = await socket(1);
    ws1.accept();
    ws1.serverSend({ event: 'connected' });
    ws1.serverSend({ event: 'started' });
    await sleep(50);
    assert.equal(ws1.pings, 0);
    s2.cancel();
    await l2.done;
    assert.equal(sockets.length, 2);
  });

  test('compat auto with unknown support never pings; heartbeat:true pings; heartbeat:false never', async () => {
    const { client, socket } = setup(HB);
    const l = recordingListener();
    const s = client.streamEvaluateCompat('sent.eval.cn', { refText: 'x' }, l);
    const ws0 = await socket(0);
    ws0.accept();
    ws0.serverSend({ event: 'connected' });
    ws0.serverSend({ event: 'started' });
    await sleep(60);
    assert.equal(ws0.pings, 0, 'a ping would reset an old compat session');
    s.cancel();
    await l.done;
    const forced = recordingListener();
    const s2 = client.streamEvaluateCompat('sent.eval.cn', { refText: 'x' }, forced, { heartbeat: true });
    const ws1 = await socket(1);
    ws1.accept();
    ws1.serverSend({ event: 'connected' });
    await waitFor(() => ws1.pings >= 2);
    s2.cancel();
    await forced.done;
    const off = recordingListener();
    const s3 = client.streamEvaluate(NATIVE_CFG, off, { heartbeat: false });
    const ws2 = await socket(2);
    ws2.accept();
    ws2.serverSend({ event: 'connected' });
    ws2.serverSend({ event: 'started' });
    await sleep(60);
    assert.equal(ws2.pings, 0);
    s3.cancel();
    await off.done;
  });

  test('no ping while ENDING', async () => {
    const { client, socket } = setup({ ...HB, heartbeat: true });
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws0 = await socket(0);
    ws0.accept();
    ws0.serverSend({ event: 'connected' });
    ws0.serverSend({ event: 'started' });
    await waitFor(() => ws0.pings >= 1);
    s.end();
    const at = ws0.pings;
    await sleep(60);
    assert.equal(ws0.pings, at, 'heartbeat paused during ENDING');
    ws0.serverSend({ event: 'result', ...RESULT });
    assertGuarantees(assert, await l.done);
  });

  test('missing pong for heartbeatTimeoutMs is a transport failure and reconnects', async () => {
    const { client, socket, serve } = setup({ heartbeat: true, heartbeatIntervalMs: 10, heartbeatTimeoutMs: 40 });
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws0 = await socket(0);
    ws0.accept();
    ws0.serverSend({ event: 'connected' });
    ws0.serverSend({ event: 'started' });
    const ws1 = await socket(1);
    const cause = l.events.find((e) => e[0] === 'reconnecting')[3];
    assert.ok(cause instanceof RequestTimeoutException);
    assert.match(cause.message, /心跳/);
    serve(ws1, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    s.end();
    assertGuarantees(assert, await l.done);
  });

  test('unexpected "unknown cmd" without a ping is ignored', async () => {
    const { client, socket, serve } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws0 = await socket(0);
    serve(ws0, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    ws0.serverSend({ event: 'error', message: 'unknown cmd' });
    assert.equal(s.getState(), 'STARTED');
    s.end();
    assertGuarantees(assert, await l.done);
  });
});

describe('cancel, close and listener guarantees', () => {
  test('cancel in IDLE, CONNECTING, STARTED and RECONNECTING: no onResult or onError, onClosed last', async () => {
    for (const where of ['IDLE', 'CONNECTING', 'STARTED', 'RECONNECTING']) {
      const { client, socket, serve, sockets } = setup({ reconnect: { initialDelayMs: 200 } });
      const l = recordingListener();
      const s = client.streamEvaluate(NATIVE_CFG, l);
      if (where !== 'IDLE') {
        const ws = await socket(0);
        if (where === 'STARTED' || where === 'RECONNECTING') {
          serve(ws);
          await waitFor(() => s.getState() === 'STARTED');
        }
        if (where === 'RECONNECTING') {
          ws.fail();
          await waitFor(() => s.getState() === 'RECONNECTING');
        }
      }
      s.cancel();
      s.cancel();
      s.close();
      const ev = await l.done;
      assertGuarantees(assert, ev, { cancelled: true });
      assert.deepEqual(states(ev).slice(-2), ['CANCELLED', 'CLOSED'], where);
      await assert.rejects(s.waitForResult(), (e) => e.code === 90003);
      await sleep(5);
      assert.ok(sockets.length <= 1, `${where}: no new connection after cancel`);
    }
  });

  test('close() of a completed session finishes at once; client.close() cancels open sessions', async () => {
    const { client, socket, serve } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws0 = await socket(0);
    serve(ws0, { result: RESULT });
    ws0.close = () => {}; // never acknowledges the close
    await waitFor(() => s.getState() === 'STARTED');
    s.end();
    await waitFor(() => s.getState() === 'COMPLETED');
    s.close();
    assert.equal(s.getState(), 'CLOSED');
    assertGuarantees(assert, await l.done);

    const l2 = recordingListener();
    const s2 = client.streamEvaluate(NATIVE_CFG, l2);
    serve(await socket(1));
    await waitFor(() => s2.getState() === 'STARTED');
    await client.close();
    assert.equal(s2.getState(), 'CLOSED');
    assertGuarantees(assert, l2.events, { cancelled: true });
    assert.throws(() => client.streamEvaluateCompat('sent.eval.cn', {}, l2), (e) => e.code === 90004);
  });

  test('AbortSignal cancels the session; an aborted signal cancels before connecting', async () => {
    const { client, socket, serve, sockets } = setup();
    const ctl = new AbortController();
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l, { signal: ctl.signal });
    serve(await socket(0));
    await waitFor(() => s.getState() === 'STARTED');
    ctl.abort();
    assertGuarantees(assert, await l.done, { cancelled: true });
    const pre = new AbortController();
    pre.abort();
    const l2 = recordingListener();
    client.streamEvaluate(NATIVE_CFG, l2, { signal: pre.signal });
    assertGuarantees(assert, await l2.done, { cancelled: true });
    assert.equal(sockets.length, 1);
  });

  test('callbacks never run nested: cancel() inside onStarted is delivered after it returns', async () => {
    const { client, socket, serve } = setup();
    const l = recordingListener();
    let s;
    const order = [];
    l.onStarted = () => {
      order.push('started:begin');
      s.cancel();
      order.push(`started:end state=${s.getState()}`);
    };
    const orig = l.onStateChanged;
    l.onStateChanged = (a, b) => {
      order.push(`state ${b}`);
      orig(a, b);
    };
    s = client.streamEvaluate(NATIVE_CFG, l);
    serve(await socket(0));
    await l.done;
    assert.deepEqual(order.slice(-5), ['state STARTED', 'started:begin', 'started:end state=STARTED', 'state CANCELLED', 'state CLOSED']);
  });

  test('a throwing listener does not break the session; setListener(null) silences it', async () => {
    const errors = [];
    const { client, socket, serve } = setup({ logLevel: 'ERROR', logger: (...a) => errors.push(a) });
    const l = recordingListener();
    l.onConnected = () => {
      throw new Error('ui bug');
    };
    const s = client.streamEvaluate(NATIVE_CFG, l);
    serve(await socket(0), { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    s.end();
    assertGuarantees(assert, await l.done);
    assert.ok(errors.some((e) => /listener\.onConnected threw/.test(e[2])));
    const l2 = recordingListener();
    const s2 = client.streamEvaluate(NATIVE_CFG, l2);
    s2.setListener(null);
    s2.cancel();
    await sleep(5);
    assert.equal(l2.events.length, 0);
    assert.throws(() => s2.setListener(5), InvalidParameterException);
  });

  test('listener and options validation', () => {
    const { client } = setup();
    assert.throws(() => client.streamEvaluate(NATIVE_CFG, { onResult() {} }), (e) => e.code === 90010 && /onError/.test(e.message));
    assert.throws(() => client.streamEvaluate(NATIVE_CFG, null), InvalidParameterException);
    assert.throws(() => client.streamEvaluate({ referenceText: 'x' }, { onResult() {}, onError() {} }), InvalidParameterException);
    assert.throws(() => client.streamEvaluate({ coreType: 'word' }, { onResult() {}, onError() {} }), InvalidParameterException);
    assert.throws(() => client.streamEvaluate(null, { onResult() {}, onError() {} }), InvalidParameterException);
    const ok = { onResult() {}, onError() {} };
    assert.throws(() => client.streamEvaluateCompat('nope', {}, ok), InvalidParameterException);
    assert.throws(() => client.streamEvaluateCompat('sent.eval.cn', 'x', ok), InvalidParameterException);
    for (const o of [5, { query: { signature: 'x' } }, { query: 'x' }, { query: { a: {} } }, { heartbeat: 'on' }, { audioBufferPolicy: 'x' }, { idempotencyKey: '' }, { resultTimeoutMs: 0 }, { signal: 1 }]) {
      assert.throws(() => client.streamEvaluate(NATIVE_CFG, ok, o), InvalidParameterException, JSON.stringify(o));
    }
    assert.throws(() => new YuguStreamSession({}, {}), TypeError);
  });

  test('precheck at end(): REJECT fails locally without sending end; WARN reports onWarning and localWarnings', async () => {
    const { client, socket, serve } = setup({ audioPrecheck: 'REJECT' });
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws0 = await socket(0);
    serve(ws0, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    s.sendAudio(new Uint8Array(6400));
    s.end();
    const ev = await l.done;
    const err = ev.find((e) => e[0] === 'error')[1];
    assert.ok(err instanceof AudioQualityException);
    assert.equal(err.code, 90101);
    assert.equal(err.idempotencyKey, s.idempotencyKey);
    assert.ok(!ws0.json.some((f) => f.cmd === 'end'), 'nothing billed');
    const warn = setup();
    const l2 = recordingListener();
    const s2 = warn.client.streamEvaluate(NATIVE_CFG, l2, { audioPrecheck: 'WARN' });
    warn.serve(await warn.socket(0), { result: RESULT });
    await waitFor(() => s2.getState() === 'STARTED');
    s2.sendAudio(new Uint8Array(6400));
    s2.end();
    const ev2 = await l2.done;
    assert.deepEqual(ev2.filter((e) => e[0] === 'warning').map((e) => e[1]), [90101, 90103]);
    assert.deepEqual(ev2.find((e) => e[0] === 'result')[1].localWarnings.map((w) => w.code), [90101, 90103]);
  });

  test('signature mode: every query parameter except signature is signed, extra query included', async () => {
    const fake = createFakeWebSocket();
    const client = new YuguClient({ appKey: 'ak_test', secretKey: 'test_secret_key_123', baseUrl: 'https://h', WebSocket: fake.FakeWebSocket, logLevel: 'OFF' });
    const s = client.streamEvaluateCompat('sent.eval.cn', { refText: 'x' }, { onResult() {}, onError() {} }, { query: { mockFault: 'ws-kill-after:3', skip: null } });
    const ws = await waitFor(() => fake.sockets[0]);
    const q = Object.fromEntries(new URL(ws.url).searchParams);
    assert.deepEqual(Object.keys(q).sort(), ['appKey', 'idempotencyKey', 'mockFault', 'nonce', 'signature', 'timestamp']);
    assert.equal(new URL(ws.url).protocol, 'wss:');
    const { signature, ...rest } = q;
    assert.equal(signature, await signHmacSha256(rest, 'test_secret_key_123'));
    assert.match(q.nonce, /^[0-9a-f]{16}$/);
    s.cancel();
  });

  test('signing failure fails the session', async () => {
    const fake = createFakeWebSocket();
    const client = new YuguClient({ appKey: 'a', secretKey: 's', crypto: { getRandomValues: (x) => x }, WebSocket: fake.FakeWebSocket, logLevel: 'OFF' });
    const l = recordingListener();
    client.streamEvaluate(NATIVE_CFG, l);
    const ev = await l.done;
    assert.equal(ev.find((e) => e[0] === 'error')[1].code, 90010);
    assert.equal(fake.sockets.length, 0);
  });

  test('waitForResult resolves later and rejects on failure; stats', async () => {
    const { client, socket, serve } = setup();
    const s = client.streamEvaluate(NATIVE_CFG, { onResult() {}, onError() {} });
    const p = s.waitForResult();
    serve(await socket(0), { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    s.sendAudio(frame(1));
    s.end();
    assert.equal((await p).overall, 91);
    const st = s.getStats();
    assert.deepEqual(Object.keys(st), ['state', 'reconnectAttempts', 'acceptedBytes', 'replayBufferBytes', 'droppedBytes', 'replayOverflow', 'heartbeat']);
    assert.equal(st.acceptedBytes, 640);
    const s2 = client.streamEvaluate(NATIVE_CFG, { onResult() {}, onError() {} });
    const p2 = s2.waitForResult();
    (await socket(1)).fail('Unexpected server response: 401');
    await assert.rejects(p2, (e) => e.category === 'AUTH');
  });

  test('a real platform frame sequence with an "unknown cmd" reply (ws_native_sentence_frames.json)', async () => {
    const frames = readJson('platform/ws_native_sentence_frames.json').map((f) => f.frame);
    const { client, socket } = setup({ heartbeat: true, heartbeatIntervalMs: 5 });
    const l = recordingListener();
    const s = client.streamEvaluate(NATIVE_CFG, l);
    const ws = await socket(0);
    ws.accept();
    ws.serverSend(frames[0]);
    ws.serverSend(frames[1]);
    await waitFor(() => ws.pings >= 1);
    ws.serverSend(frames[2]);
    s.end();
    ws.serverSend(frames[3]);
    const ev = await l.done;
    assert.equal(ev.find((e) => e[0] === 'result')[1].overall, 93.7);
    assertGuarantees(assert, ev);
  });
});
