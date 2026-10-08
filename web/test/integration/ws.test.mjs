// V-04 against tools/mock-server with the ws client: server kill, close 1011, silent server,
// REPLAY with one result, DROP, FAIL, exhausted reconnects. Every test asserts a terminal
// callback within a bound (no silent hang).
import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import WebSocket from 'ws';
import { YuguClient } from '../../src/client.js';
import {
  InvalidParameterException,
  NetworkException,
  PermissionException,
  RequestTimeoutException,
  ServerException,
} from '../../src/errors.js';
import { assertGuarantees, frames, kinds, pcmOf, readAudio, recordingListener, sleep, states, waitFor } from '../helpers/common.mjs';
import { MOCK_CREDS, startMock } from '../helpers/mock-server.mjs';

const PCM = pcmOf(readAudio('zh_short.wav'));
const FRAMES = frames(PCM);
const NATIVE_CFG = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' };
const NATIVE = '/api/v1/ws/evaluate';
const BOUND_MS = 15000;
let mock;

before(async () => {
  mock = await startMock({ processingMs: 50, env: { MOCK_IDEM_WAIT_MS: '4000' } });
});
after(async () => {
  await mock.stop();
});
beforeEach(async () => {
  await mock.reset();
});

function client(extra = {}) {
  return new YuguClient({
    baseUrl: mock.base,
    ...MOCK_CREDS,
    WebSocket,
    logLevel: 'OFF',
    reconnect: { initialDelayMs: 100, maxDelayMs: 400 },
    ...extra,
  });
}

/** Finished within the bound, or the test fails: no silent hang. */
async function finished(listener, bound = BOUND_MS) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`no terminal callback within ${bound} ms: ${kinds(listener.events)}`)), bound);
  });
  try {
    return await Promise.race([listener.done, timeout]);
  } finally {
    clearTimeout(timer);
  }
}

function sendAll(session, list = FRAMES) {
  for (const f of list) session.sendAudio(f);
}

async function sendPaced(session, list, ms) {
  for (const f of list) {
    session.sendAudio(f);
    await sleep(ms);
  }
}

const result = (ev) => ev.find((e) => e[0] === 'result')[1];
const error = (ev) => ev.find((e) => e[0] === 'error')[1];

describe('baseline sessions', () => {
  test('native and compat sessions complete, one bill each, compat progress frames', async () => {
    const c = client();
    const l = recordingListener();
    const s = c.streamEvaluate(NATIVE_CFG, l);
    sendAll(s);
    s.end();
    const ev = await finished(l);
    assertGuarantees(assert, ev);
    assert.deepEqual(states(ev), ['CONNECTING', 'CONNECTED', 'STARTED', 'ENDING', 'COMPLETED', 'CLOSED']);
    assert.equal(result(ev).overall, 93.7);
    assert.equal(result(ev).replayed, false);
    const l2 = recordingListener();
    const s2 = c.streamEvaluateCompat('sent.eval.cn', { refText: '今天天气很好', language: 'zh-CN', realtime_feedback: true }, l2);
    await waitFor(() => s2.getState() === 'STARTED', 5000);
    await sendPaced(s2, FRAMES, 0);
    s2.end();
    const ev2 = await finished(l2);
    assertGuarantees(assert, ev2);
    assert.ok(ev2.some((e) => e[0] === 'partial' && e[1] > 0), 'progress frames');
    assert.equal(result(ev2).mode, 'compat');
    const bill = await mock.billing();
    assert.equal(bill.billed, 2);
    assert.deepEqual(bill.records.map((r) => r.bytes), [PCM.length, PCM.length]);
    assert.equal(bill.byKey[s.idempotencyKey], 1);
    await c.close();
  });
});

describe('V-04 server kill, close 1011, silent server (REPLAY)', () => {
  test('server kills the connection: reconnect, replay, one result, events in order, billed once', async () => {
    await mock.faults([{ match: NATIVE, fault: 'ws-kill-after:20' }]);
    const c = client();
    const l = recordingListener();
    const s = c.streamEvaluate(NATIVE_CFG, l);
    sendAll(s);
    s.end();
    const ev = await finished(l);
    assertGuarantees(assert, ev);
    const st = states(ev);
    assert.deepEqual(st.slice(st.indexOf('RECONNECTING')), ['RECONNECTING', 'CONNECTING', 'CONNECTED', 'STARTED', 'ENDING', 'COMPLETED', 'CLOSED']);
    const order = kinds(ev).filter((k) => k !== 'state');
    assert.deepEqual(order, ['connected', 'started', 'reconnecting', 'reconnected', 'result', 'closed']);
    const rc = ev.find((e) => e[0] === 'reconnecting');
    assert.equal(rc[1], 1);
    assert.ok(rc[3] instanceof NetworkException);
    const rd = ev.find((e) => e[0] === 'reconnected');
    assert.deepEqual(rd[2], { droppedBytes: 0, replayedBytes: PCM.length });
    const bill = await mock.billing();
    assert.equal(bill.billed, 1);
    assert.equal(bill.records[0].bytes, PCM.length, 'the new server session got every byte');
    assert.equal(bill.records[0].idemKey, s.idempotencyKey);
    const wsLog = (await mock.log()).filter((e) => e.method === 'WS');
    assert.equal(wsLog.length, 2);
    assert.ok(wsLog.every((e) => e.idempotencyKey === s.idempotencyKey), 'same key on the reconnect handshake');
  });

  test('server closes with 1011: reconnect and one result', async () => {
    await mock.faults([{ match: NATIVE, fault: 'ws-close-after:5' }]);
    const l = recordingListener();
    const s = client().streamEvaluate(NATIVE_CFG, l);
    sendAll(s);
    s.end();
    const ev = await finished(l);
    assertGuarantees(assert, ev);
    const cause = ev.find((e) => e[0] === 'reconnecting')[3];
    assert.match(cause.message, /1011/);
    assert.equal(result(ev).overall, 93.7);
    assert.equal((await mock.billing()).billed, 1);
  });

  test('silent server after end: result timeout 90007, reconnect, one result', async () => {
    await mock.faults([{ match: NATIVE, fault: 'ws-silent:3' }]);
    const l = recordingListener();
    const s = client({ resultTimeoutMs: 800 }).streamEvaluate(NATIVE_CFG, l);
    sendAll(s);
    s.end();
    const ev = await finished(l);
    assertGuarantees(assert, ev);
    const cause = ev.find((e) => e[0] === 'reconnecting')[3];
    assert.ok(cause instanceof RequestTimeoutException);
    assert.equal(cause.code, 90007);
    assert.equal((await mock.billing()).billed, 1);
  });

  test('silent server while streaming: heartbeat timeout, reconnect, one result', async () => {
    await mock.faults([{ match: NATIVE, fault: 'ws-silent:2' }]);
    const l = recordingListener();
    const s = client({ heartbeat: true, heartbeatIntervalMs: 100, heartbeatTimeoutMs: 300 }).streamEvaluate(NATIVE_CFG, l);
    await waitFor(() => s.getState() === 'STARTED', 5000);
    s.sendAudio(FRAMES[0]);
    await waitFor(() => s.getState() === 'RECONNECTING', 5000, 'heartbeat timeout');
    const cause = l.events.find((e) => e[0] === 'reconnecting')[3];
    assert.equal(cause.code, 90002);
    sendAll(s, FRAMES.slice(1));
    s.end();
    const ev = await finished(l);
    assertGuarantees(assert, ev);
    assert.equal((await mock.billing()).records[0].bytes, PCM.length);
  });

  test('result computed but lost: the reconnect with the same key gets the replayed result, billed once', async () => {
    await mock.faults([{ match: NATIVE, fault: 'ws-delay-result:1500' }]);
    const l = recordingListener();
    const s = client({ resultTimeoutMs: 400 }).streamEvaluate(NATIVE_CFG, l);
    sendAll(s);
    s.end();
    const ev = await finished(l);
    assertGuarantees(assert, ev);
    assert.equal(ev.find((e) => e[0] === 'reconnecting')[3].code, 90007);
    const r = result(ev);
    assert.equal(r.replayed, true, 'the platform replayed the first evaluation');
    const bill = await mock.billing();
    assert.equal(bill.billed, 1, 'reconnect with the same key does not double bill');
    assert.equal(bill.records[0].recordId, r.recordId);
  });

  test('retryable server error frame reconnects; terminal error frame fails', async () => {
    await mock.faults([{ match: NATIVE, fault: 'ws-error:50200' }]);
    const l = recordingListener();
    const s = client().streamEvaluate(NATIVE_CFG, l);
    sendAll(s);
    s.end();
    const ev = await finished(l);
    assertGuarantees(assert, ev);
    assert.ok(ev.find((e) => e[0] === 'reconnecting')[3] instanceof ServerException);
    assert.equal(result(ev).overall, 93.7);
    await mock.faults([{ match: NATIVE, fault: 'ws-error:40001' }]);
    const l2 = recordingListener();
    const s2 = client().streamEvaluate(NATIVE_CFG, l2);
    sendAll(s2);
    s2.end();
    const ev2 = await finished(l2);
    assertGuarantees(assert, ev2);
    assert.ok(error(ev2) instanceof InvalidParameterException);
    assert.equal(ev2.filter((e) => e[0] === 'reconnecting').length, 0);
  });
});

describe('V-04 buffer policies and failure semantics', () => {
  test('FAIL: transport failure goes to onError, no reconnect, nothing billed', async () => {
    await mock.faults([{ match: NATIVE, fault: 'ws-kill-after:10' }]);
    const l = recordingListener();
    const s = client({ audioBufferPolicy: 'FAIL' }).streamEvaluate(NATIVE_CFG, l);
    sendAll(s);
    s.end();
    const ev = await finished(l);
    assertGuarantees(assert, ev);
    assert.deepEqual(states(ev).slice(-2), ['FAILED', 'CLOSED']);
    assert.ok(error(ev) instanceof NetworkException);
    assert.equal(error(ev).code, 90001);
    assert.equal(ev.filter((e) => e[0] === 'reconnecting').length, 0);
    assert.equal((await mock.billing()).billed, 0);
    assert.equal((await mock.log()).filter((e) => e.method === 'WS').length, 1);
  });

  test('DROP: audio across the drop is discarded, the new session scores only later audio', async () => {
    await mock.faults([{ match: NATIVE, fault: 'ws-kill-after:30' }]);
    const l = recordingListener();
    const s = client({ audioBufferPolicy: 'DROP' }).streamEvaluate(NATIVE_CFG, l);
    await waitFor(() => s.getState() === 'STARTED', 5000);
    await sendPaced(s, FRAMES, 4);
    await waitFor(() => s.getState() === 'STARTED', 5000);
    sendAll(s, FRAMES.slice(0, 40));
    s.end();
    const ev = await finished(l);
    assertGuarantees(assert, ev);
    const rd = ev.find((e) => e[0] === 'reconnected');
    assert.ok(rd[2].droppedBytes > 0);
    const stats = s.getStats();
    const bill = await mock.billing();
    assert.equal(bill.billed, 1);
    assert.equal(bill.records[0].bytes, stats.acceptedBytes - stats.droppedBytes);
  });

  test('reconnects exhausted after the default 8 consecutive attempts: onError 90006 with the cause', async () => {
    await mock.faults([{ match: NATIVE, fault: 'ws-kill-after:5' }, ...Array.from({ length: 8 }, () => ({ match: NATIVE, fault: 'ws-refuse' }))]);
    const attempts = [];
    const l = recordingListener();
    const s = client({ eventListener: { onReconnect: (...a) => attempts.push(a) } }).streamEvaluate(NATIVE_CFG, l);
    sendAll(s);
    s.end();
    const ev = await finished(l);
    assertGuarantees(assert, ev);
    const err = error(ev);
    assert.ok(err instanceof NetworkException);
    assert.equal(err.code, 90006);
    assert.ok(err.cause instanceof ServerException);
    assert.equal(err.cause.httpStatus, 503);
    assert.deepEqual(ev.filter((e) => e[0] === 'reconnecting').map((e) => e[1]), [1, 2, 3, 4, 5, 6, 7, 8]);
    assert.deepEqual(attempts.map((a) => [a[1], a[2]]), [1, 2, 3, 4, 5, 6, 7, 8].map((n) => [n, false]));
    assert.equal((await mock.billing()).billed, 0);
  });

  test('128 KB frame limit: large audio chunks are split, an oversized start frame fails without reconnect loops', async () => {
    const l = recordingListener();
    const s = client().streamEvaluate(NATIVE_CFG, l);
    await waitFor(() => s.getState() === 'STARTED', 5000);
    const big = new Uint8Array(200 * 1024);
    for (let i = 0; i < big.length; i++) big[i] = PCM[i % PCM.length];
    assert.equal(s.sendAudio(big), true);
    s.end();
    const ev = await finished(l);
    assertGuarantees(assert, ev);
    assert.equal(result(ev).overall, 93.7);
    assert.equal((await mock.billing()).records[0].bytes, big.length);
  });

  test('an oversized start frame is closed with 1009 and fails without reconnect loops', async () => {
    // Own mock instance: the shared mock process exits on an oversized frame (unhandled ws error).
    const own = await startMock();
    try {
      const l = recordingListener();
      const c = new YuguClient({ baseUrl: own.base, ...MOCK_CREDS, WebSocket, logLevel: 'OFF', reconnect: { initialDelayMs: 100 } });
      c.streamEvaluate({ ...NATIVE_CFG, referenceText: '今'.repeat(50000) }, l);
      const ev = await finished(l);
      assertGuarantees(assert, ev);
      const err = error(ev);
      assert.equal(err.code, 90005);
      assert.match(err.message, /1009/);
      assert.equal(ev.find((e) => e[0] === 'closed')[1], 1009);
      assert.equal(ev.filter((e) => e[0] === 'reconnecting').length, 0, 'a reconnect cannot fix an oversized frame');
    } finally {
      await own.stop();
    }
  });

  test('bad signature on the handshake: PermissionException, no reconnect', async () => {
    const c = new YuguClient({ baseUrl: mock.base, appKey: 'mock-app-key', secretKey: 'wrong', WebSocket, logLevel: 'OFF' });
    const l = recordingListener();
    c.streamEvaluate(NATIVE_CFG, l);
    const ev = await finished(l);
    assertGuarantees(assert, ev);
    assert.ok(error(ev) instanceof PermissionException);
    assert.equal(error(ev).httpStatus, 403);
    assert.equal(ev.filter((e) => e[0] === 'reconnecting').length, 0);
  });

  test('cancel during streaming: no result or error, onClosed last, nothing billed', async () => {
    const l = recordingListener();
    const s = client().streamEvaluate(NATIVE_CFG, l);
    await waitFor(() => s.getState() === 'STARTED', 5000);
    sendAll(s, FRAMES.slice(0, 10));
    s.cancel();
    const ev = await finished(l);
    assertGuarantees(assert, ev, { cancelled: true });
    await sleep(100);
    assert.equal((await mock.billing()).billed, 0);
  });
});

describe('heartbeat against the platform mock', () => {
  test('native probe gets pong; the client then keeps the heartbeat on compat sessions too', async () => {
    const c = client({ heartbeatIntervalMs: 50 });
    const l = recordingListener();
    const s = c.streamEvaluate(NATIVE_CFG, l);
    await waitFor(() => s.getState() === 'STARTED', 5000);
    await waitFor(() => c._pingSupport === 'supported', 3000, 'pong');
    assert.equal(s.getStats().heartbeat, true);
    sendAll(s);
    s.end();
    assertGuarantees(assert, await finished(l));
    const l2 = recordingListener();
    const s2 = c.streamEvaluateCompat('sent.eval.cn', { refText: '今天天气很好' }, l2);
    await waitFor(() => s2.getState() === 'STARTED', 5000);
    assert.equal(s2.getStats().heartbeat, true);
    await sleep(200);
    sendAll(s2);
    s2.end();
    const ev2 = await finished(l2);
    assertGuarantees(assert, ev2);
    assert.equal((await mock.billing()).records[1].bytes, PCM.length, 'pings did not reset the compat session');
    await c.close();
  });
});
