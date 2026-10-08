// V-04 (a), acceptance 6.4: the client loses the network for 10 seconds in the middle of a
// streaming evaluation. The wx mock drops every socket and refuses new ones while offline.
// - With DEFAULT settings (ReconnectPolicy maxAttempts 8: waits 0.5, 1, 2, 4, 4, 4, 4, 4 s, at least
//   16.45 s with the jitter) the session rides out the outage, replays the audio and delivers one
//   result.
// - A smaller budget that ends during the outage reports RECONNECT_EXHAUSTED through onError
//   within the budget: documented behaviour, never a silent hang.
import { describe, test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startMockServer, MOCK_APP_KEY, MOCK_SECRET } from '../helpers/mock-server.mjs';
import { createWxMock } from '../helpers/wx-mock.mjs';
import { recordingListener, wavPcm, sleep, waitFor } from '../helpers/common.mjs';
import { YuguClient } from '../../src/index.js';

const PCM = wavPcm('audio/zh_short.wav');
const NATIVE = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' };
const OUTAGE_MS = 10000;

let mock;
before(async () => {
  mock = await startMockServer({ processingMs: 30 });
});
after(() => mock.stop());

function setup(opts) {
  const wx = createWxMock();
  const client = new YuguClient({
    wx, auth: { appKey: MOCK_APP_KEY, secretKey: MOCK_SECRET }, baseUrl: mock.baseUrl, wsBaseUrl: mock.wsBaseUrl, ...opts,
  });
  return { wx, client };
}

async function paced(session, bytes, everyMs) {
  for (let i = 0; i < bytes.length; i += 640) {
    session.sendAudio(bytes.subarray(i, i + 640));
    await sleep(everyMs);
  }
}

describe('client network drop of 10 seconds', { concurrency: 2 }, () => {
  test('default settings ride out the outage and deliver one result for all audio', async () => {
    const { wx, client } = setup({});
    const rl = recordingListener();
    const s = client.streamEvaluate(NATIVE, rl.listener);
    await waitFor(() => s.getState() === 'STARTED');
    await paced(s, PCM.subarray(0, 19200), 10);
    const offAt = Date.now();
    wx.__mock.setOffline(true);
    await paced(s, PCM.subarray(19200), 20); // the speaker keeps talking during the outage
    s.end();
    await sleep(OUTAGE_MS - (Date.now() - offAt));
    wx.__mock.setOffline(false);
    await rl.done;
    const reconnecting = rl.events.filter((e) => e.name === 'onReconnecting');
    const attempts = reconnecting.map((e) => e.args[0]);
    assert.deepEqual(attempts, attempts.map((_, i) => i + 1), 'consecutive attempts');
    assert.ok(attempts.length >= 4 && attempts.length <= 8, String(attempts.length));
    const back = rl.first('onReconnected');
    assert.equal(back.args[0], attempts.length);
    assert.ok(Date.now() - offAt >= OUTAGE_MS);
    assert.ok(Date.now() - offAt < OUTAGE_MS + 6000, 'reconnected within one backoff step after the outage');
    assert.equal(back.args[1].replayedBytes, PCM.length);
    assert.equal(rl.count('onResult'), 1);
    assert.equal(rl.count('onError'), 0);
    const names = rl.names().filter((n) => ['onReconnecting', 'onReconnected', 'onResult', 'onClosed'].includes(n));
    assert.deepEqual(names, [...attempts.map(() => 'onReconnecting'), 'onReconnected', 'onResult', 'onClosed']);
    const bill = (await mock.billing()).records.filter((r) => r.idemKey === s.idempotencyKey);
    assert.equal(bill.length, 1);
    assert.equal(bill[0].bytes, PCM.length);
    client.close();
    wx.__mock.cleanup();
  });

  test('a reconnect budget shorter than the outage ends in RECONNECT_EXHAUSTED within the budget', async () => {
    const { wx, client } = setup({ reconnect: { maxAttempts: 3 } });
    const rl = recordingListener();
    const s = client.streamEvaluate(NATIVE, rl.listener);
    await waitFor(() => s.getState() === 'STARTED');
    await paced(s, PCM.subarray(0, 12800), 5);
    const offAt = Date.now();
    wx.__mock.setOffline(true);
    await rl.done;
    const elapsed = Date.now() - offAt;
    // 3 attempts after 500, 1000 and 2000 ms, each plus or minus 30 percent
    assert.ok(elapsed >= 2400 && elapsed < 5500, String(elapsed));
    const err = rl.first('onError').args[0];
    assert.equal(err.code, 90006);
    assert.equal(err.category, 'NETWORK');
    assert.equal(err.idempotencyKey, s.idempotencyKey);
    assert.equal(rl.count('onReconnecting'), 3);
    assert.equal(rl.count('onReconnected'), 0);
    assert.equal(rl.names().at(-1), 'onClosed');
    assert.equal(s.getState(), 'CLOSED');
    wx.__mock.setOffline(false);
    client.close();
    wx.__mock.cleanup();
  });
});
