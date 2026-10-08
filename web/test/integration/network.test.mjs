// A-03 acceptance scenarios (6.4) through a TCP proxy in front of the mock platform:
// client network down for 10 s with default settings, a silent drop found by the heartbeat,
// a network switch (every socket reset), and a network that never comes back. Events in order,
// one result, billed once, no silent hang.
import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import WebSocket from 'ws';
import { YuguClient } from '../../src/client.js';
import { NetworkException, RequestTimeoutException } from '../../src/errors.js';
import { assertGuarantees, frames, kinds, pcmOf, readAudio, recordingListener, sleep, states, waitFor } from '../helpers/common.mjs';
import { MOCK_CREDS, startMock } from '../helpers/mock-server.mjs';
import { startProxy } from '../helpers/proxy.mjs';

const PCM = pcmOf(readAudio('zh_short.wav'));
const FRAMES = frames(PCM);
const CFG = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' };
let mock;
let proxy;

before(async () => {
  mock = await startMock();
});
after(async () => {
  await mock.stop();
});
beforeEach(async () => {
  await mock.reset();
});

function client(extra = {}) {
  return new YuguClient({
    baseUrl: `http://127.0.0.1:${proxy.port}`,
    ...MOCK_CREDS,
    WebSocket,
    logLevel: 'OFF',
    ...extra,
  });
}

async function within(promise, ms, what) {
  let t;
  try {
    return await Promise.race([promise, new Promise((_, rej) => (t = setTimeout(() => rej(new Error(`${what}: no terminal callback within ${ms} ms`)), ms)))]);
  } finally {
    clearTimeout(t);
  }
}

describe('network drop and network switch (V-04, acceptance 6.4)', () => {
  test('client network down for 10 s with DEFAULT settings: reconnects until it is back, one result, billed once', async () => {
    proxy = await startProxy(mock.port);
    try {
      const l = recordingListener();
      const attempts = [];
      const c = client({ eventListener: { onReconnect: (_id, n, ok) => attempts.push([n, ok]) } });
      const s = c.streamEvaluate(CFG, l);
      await waitFor(() => s.getState() === 'STARTED', 5000);
      const half = Math.floor(FRAMES.length / 2);
      for (const f of FRAMES.slice(0, half)) s.sendAudio(f);
      await sleep(200);
      // The network goes away: open sockets die and every new connection is refused for 10 s.
      const lostAt = Date.now();
      proxy.setOffline(true);
      // The speaker finishes reading during the outage; REPLAY keeps the audio.
      for (const f of FRAMES.slice(half)) {
        assert.equal(s.sendAudio(f), true);
        await sleep(40);
      }
      s.end();
      await sleep(Math.max(0, 10000 - (Date.now() - lostAt)));
      proxy.setOffline(false);
      const backAt = Date.now();
      const ev = await within(l.done, 30000, 'network down 10 s');
      assertGuarantees(assert, ev);
      const reconnecting = ev.filter((e) => e[0] === 'reconnecting');
      assert.ok(reconnecting.length >= 3, `${reconnecting.length} attempts during the outage`);
      assert.ok(reconnecting.length <= 8, 'within the default budget of 8 consecutive attempts');
      assert.deepEqual(reconnecting.map((e) => e[1]), reconnecting.map((_, i) => i + 1));
      assert.ok(reconnecting[0][3] instanceof NetworkException);
      assert.equal(ev.filter((e) => e[0] === 'reconnected').length, 1);
      assert.equal(ev.filter((e) => e[0] === 'result').length, 1);
      assert.equal(ev.filter((e) => e[0] === 'error').length, 0);
      assert.deepEqual(attempts.at(-1), [reconnecting.length, true]);
      assert.ok(attempts.slice(0, -1).every((a) => a[1] === false));
      const order = kinds(ev).filter((k) => k !== 'state');
      assert.deepEqual(order.slice(0, 2), ['connected', 'started']);
      assert.deepEqual(order.slice(-3), ['reconnected', 'result', 'closed']);
      assert.equal(states(ev).at(-1), 'CLOSED');
      assert.ok(Date.now() - backAt < 8000, 'result shortly after the network is back');
      const bill = await mock.billing();
      assert.equal(bill.billed, 1);
      assert.equal(bill.records[0].bytes, PCM.length, 'every byte replayed after the outage');
      await c.close();
    } finally {
      await proxy.close();
    }
  });

  test('silent drop (packets lost, no reset): the heartbeat finds it and the session recovers', async () => {
    proxy = await startProxy(mock.port);
    try {
      const l = recordingListener();
      const c = client({ heartbeat: true, heartbeatIntervalMs: 300, heartbeatTimeoutMs: 600, connectTimeoutMs: 1000 });
      const s = c.streamEvaluate(CFG, l);
      await waitFor(() => s.getState() === 'STARTED', 5000);
      for (const f of FRAMES.slice(0, 30)) s.sendAudio(f);
      await sleep(100);
      const lostAt = Date.now();
      proxy.setBlackhole(true);
      await waitFor(() => s.getState() === 'RECONNECTING', 5000, 'silent drop detected');
      const detectedAfter = Date.now() - lostAt;
      for (const f of FRAMES.slice(30)) s.sendAudio(f);
      await sleep(Math.max(0, 4000 - (Date.now() - lostAt)));
      proxy.setBlackhole(false);
      await waitFor(() => s.getState() === 'STARTED', 15000, 'reconnected');
      s.end();
      const ev = await within(l.done, 15000, 'silent drop');
      assertGuarantees(assert, ev);
      assert.ok(detectedAfter < 2500, `detected after ${detectedAfter} ms`);
      assert.ok(ev.find((e) => e[0] === 'reconnecting')[3] instanceof RequestTimeoutException);
      const bill = await mock.billing();
      assert.equal(bill.billed, 1);
      assert.equal(bill.records[0].bytes, PCM.length);
      await c.close();
    } finally {
      proxy.setBlackhole(false);
      await proxy.close();
    }
  });

  test('network switch: every socket reset mid-stream, reconnect at once, one result', async () => {
    proxy = await startProxy(mock.port);
    try {
      const l = recordingListener();
      const c = client();
      const s = c.streamEvaluate(CFG, l);
      await waitFor(() => s.getState() === 'STARTED', 5000);
      for (const f of FRAMES.slice(0, 30)) s.sendAudio(f);
      await sleep(100);
      proxy.resetAll();
      await waitFor(() => s.getState() === 'RECONNECTING', 3000, 'reset detected');
      for (const f of FRAMES.slice(30)) s.sendAudio(f);
      s.end();
      const ev = await within(l.done, 15000, 'network switch');
      assertGuarantees(assert, ev);
      assert.ok(ev.find((e) => e[0] === 'reconnecting')[3] instanceof NetworkException);
      assert.equal(ev.find((e) => e[0] === 'reconnected')[2].replayedBytes, PCM.length);
      const bill = await mock.billing();
      assert.equal(bill.billed, 1);
      assert.equal(bill.records[0].bytes, PCM.length);
      await c.close();
    } finally {
      await proxy.close();
    }
  });

  test('network does not come back: reconnects exhausted, onError 90006, no hang', async () => {
    proxy = await startProxy(mock.port);
    try {
      const l = recordingListener();
      const c = client({ reconnect: { maxAttempts: 3, initialDelayMs: 100 } });
      const s = c.streamEvaluate(CFG, l);
      await waitFor(() => s.getState() === 'STARTED', 5000);
      for (const f of FRAMES.slice(0, 20)) s.sendAudio(f);
      proxy.setOffline(true);
      const ev = await within(l.done, 10000, 'lost network');
      assertGuarantees(assert, ev);
      const err = ev.find((e) => e[0] === 'error')[1];
      assert.equal(err.code, 90006);
      assert.ok(err.cause instanceof NetworkException);
      assert.deepEqual(states(ev).slice(-2), ['FAILED', 'CLOSED']);
      assert.equal((await mock.billing()).billed, 0);
      await c.close();
    } finally {
      proxy.setOffline(false);
      await proxy.close();
    }
  });
});
