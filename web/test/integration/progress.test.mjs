// Reading progress (2.1.0) against tools/mock-server with the ws client. The mock marks one unit per
// 8000 bytes read and matched, sends a progress frame per 16000 bytes and one more after end, adds the
// Shengtong part (recordId "", versions, incremental words) on the compat channel, and has the faults
// progress-mismatch:<i> and progress-off (tools/mock-server/README.md).
import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import WebSocket from 'ws';
import { YuguClient } from '../../src/client.js';
import { assertGuarantees, frames, kinds, pcmOf, readAudio, recordingListener, sleep, waitFor } from '../helpers/common.mjs';
import { MOCK_CREDS, startMock } from '../helpers/mock-server.mjs';

const CUSTOMER = '我是中国人，我是中国人。我们都是中国人。中华民族是一家人。';
const CUSTOMER_UNITS = [...'我是中国人我是中国人我们都是中国人中华民族是一家人'];
const ENGLISH = 'Nice to see you again. I am a student, and I like reading books every day.';
const ENGLISH_UNITS = ['Nice', 'to', 'see', 'you', 'again', 'I', 'am', 'a', 'student', 'and', 'I', 'like', 'reading', 'books', 'every', 'day'];
const ZH_PCM = pcmOf(readAudio('zh_progress.wav'));
const EN_PCM = pcmOf(readAudio('en_progress.wav'));
const NATIVE = '/api/v1/ws/evaluate';
const BOUND_MS = 15000;

// 145920 bytes of PCM: frames at 16000 * k bytes (k = 1 to 9) read 2k units, the frame after end
// reads floor(145920 / 8000) + 1 = 19 units, capped at the unit count.
const STEPS = [16000, 32000, 48000, 64000, 80000, 96000, 112000, 128000, 144000];
const expectedRead = (total) => [...STEPS.map((n) => Math.min(total, n / 8000)), Math.min(total, 19)];
const EXPECTED_BYTES = [...STEPS, 145920];

let mock;

before(async () => {
  mock = await startMock({ processingMs: 50 });
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

/** Opens a session on the customer sentence with reading progress requested. */
function open(channel, c, listener, options) {
  if (channel === 'native') {
    return c.streamEvaluate({ coreType: 'sentence', referenceText: CUSTOMER, language: 'zh-CN', realtimeFeedback: true }, listener, options);
  }
  return c.streamEvaluateCompat('sent.eval.cn', { refText: CUSTOMER, language: 'zh-CN', realtime_feedback: true }, listener, options);
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

async function sendPaced(session, list, ms) {
  for (const f of list) {
    session.sendAudio(f);
    await sleep(ms);
  }
}

/** Listener that records partials together with the session state at delivery. */
function progressListener() {
  const l = recordingListener();
  l.statesAtPartial = [];
  const record = l.onPartial;
  l.onPartial = (p) => {
    l.statesAtPartial.push(l.session.getState());
    record(p);
  };
  return l;
}

/** One round: waits for STARTED, sends the PCM in 640-byte frames with a pause, ends, waits for onClosed. */
async function round(start, pcm, paceMs = 1) {
  const l = progressListener();
  const s = start(l);
  l.session = s;
  await waitFor(() => s.getState() === 'STARTED', 5000);
  await sendPaced(s, frames(pcm), paceMs);
  s.end();
  const ev = await finished(l);
  assertGuarantees(assert, ev);
  return { ev, session: s, partials: l.partials, statesAtPartial: l.statesAtPartial, result: ev.find((e) => e[0] === 'result')[1] };
}

/** Fields agree with each other and with the reference text. */
function assertProgress(p, units) {
  assert.equal(p.hasProgress, true);
  assert.equal(p.totalUnits, units.length);
  assert.deepEqual(p.progress.map((w) => w.word), units);
  assert.equal(p.readUnits, p.progress.filter((w) => w.read).length);
  assert.equal(p.matchedUnits, p.progress.filter((w) => w.matched).length);
  p.progress.forEach((w, i) => assert.equal(w.read, i < p.readUnits, `unit ${i} of ${p.readUnits} read`));
}

function assertNeverBack(partials) {
  const prog = partials.filter((p) => p.hasProgress);
  for (let i = 1; i < prog.length; i++) {
    const [a, b] = [prog[i - 1], prog[i]];
    assert.ok(
      b.readUnits >= a.readUnits && b.matchedUnits >= a.matchedUnits,
      `progress went back: read ${a.readUnits} -> ${b.readUnits}, matched ${a.matchedUnits} -> ${b.matchedUnits}`,
    );
  }
}

/** Compat frames carry the Shengtong part in raw; its words together are the units read so far. */
function assertShengtongRaw(partials, units) {
  const concatenated = [];
  for (const p of partials) {
    assert.equal(p.raw.recordId, '');
    assert.equal(p.raw.result.kernel_version, '1.0.0');
    assert.equal('words' in p, false, 'the Shengtong words are not mapped');
    concatenated.push(...p.raw.result.words.map((w) => w.word));
  }
  const last = partials[partials.length - 1];
  assert.deepEqual(concatenated, units.slice(0, last.readUnits));
}

describe('reading progress on both channels', () => {
  test('compat: the customer sentence by character and an English sentence by word, the last frame while ENDING', async () => {
    const c = client();
    for (const [coreType, refText, units, pcm] of [
      ['sent.eval.cn', CUSTOMER, CUSTOMER_UNITS, ZH_PCM],
      ['sent.eval', ENGLISH, ENGLISH_UNITS, EN_PCM],
    ]) {
      await mock.reset();
      const r = await round((l) => c.streamEvaluateCompat(coreType, { refText, realtime_feedback: true }, l), pcm);
      assert.deepEqual(r.partials.map((p) => p.readUnits), expectedRead(units.length), coreType);
      assert.deepEqual(r.partials.map((p) => p.matchedUnits), expectedRead(units.length));
      assert.deepEqual(r.partials.map((p) => p.bytes), EXPECTED_BYTES);
      for (const p of r.partials) assertProgress(p, units);
      assertShengtongRaw(r.partials, units);
      assert.equal(r.statesAtPartial[0], 'STARTED', 'progress while the audio arrives');
      assert.equal(r.statesAtPartial[r.statesAtPartial.length - 1], 'ENDING', 'the frame after end, before the result');
      assert.equal(r.result.mode, 'compat');
      assert.equal(typeof r.result.overall, 'number');
      const bill = await mock.billing();
      assert.equal(bill.billed, 1);
      assert.equal(bill.records[0].bytes, pcm.length);
    }
    await c.close();
  });

  test('native: realtimeFeedback true gives the same progress without the Shengtong part; without it no partials', async () => {
    const c = client();
    const on = await round((l) => open('native', c, l), ZH_PCM);
    assert.deepEqual(on.partials.map((p) => p.readUnits), expectedRead(25));
    for (const p of on.partials) {
      assertProgress(p, CUSTOMER_UNITS);
      assert.deepEqual(Object.keys(p.raw), ['eof', 'result']);
      assert.equal('words' in p.raw.result, false);
    }
    assert.equal(on.statesAtPartial[on.statesAtPartial.length - 1], 'ENDING');
    assert.equal(on.result.mode, 'native');
    assert.equal(on.result.overall, 93.7);
    const off = await round((l) => c.streamEvaluate({ coreType: 'sentence', referenceText: CUSTOMER, language: 'zh-CN' }, l), ZH_PCM);
    assert.equal(off.partials.length, 0, 'no realtimeFeedback, no progress frames');
    assert.equal(off.result.overall, 93.7);
    const bill = await mock.billing();
    assert.deepEqual(bill.records.map((x) => x.bytes), [ZH_PCM.length, ZH_PCM.length]);
    await c.close();
  });

  test('progress-mismatch:<i>: unit i read but not matched, every partial delivered', async () => {
    const c = client();
    for (const [channel, bad] of [
      ['native', 3],
      ['compat', 0],
    ]) {
      const r = await round((l) => open(channel, c, l, { query: { mockFault: `progress-mismatch:${bad}` } }), ZH_PCM);
      assert.deepEqual(r.partials.map((p) => p.readUnits), expectedRead(25), channel);
      for (const p of r.partials) {
        assertProgress(p, CUSTOMER_UNITS);
        assert.equal(p.matchedUnits, p.readUnits > bad ? p.readUnits - 1 : p.readUnits);
        if (p.readUnits > bad) assert.deepEqual(p.progress[bad], { word: CUSTOMER_UNITS[bad], read: true, matched: false });
      }
      if (channel === 'compat') {
        assertShengtongRaw(r.partials, CUSTOMER_UNITS);
        assert.equal(r.partials[0].raw.result.words[0].scores.skipped, 1, 'the Shengtong item of the mismatched unit');
      }
      assert.equal(typeof r.result.overall, 'number');
    }
    await c.close();
  });

  test('progress-off: partials carry only bytes, counts -1, progress empty, still delivered on both channels', async () => {
    const c = client();
    for (const channel of ['compat', 'native']) {
      const r = await round((l) => open(channel, c, l, { query: { mockFault: 'progress-off' } }), ZH_PCM);
      assert.deepEqual(r.partials.map((p) => p.bytes), EXPECTED_BYTES, channel);
      for (const p of r.partials) {
        assert.deepEqual([p.hasProgress, p.readUnits, p.matchedUnits, p.totalUnits, p.progress], [false, -1, -1, -1, []]);
        assert.deepEqual(p.raw, { eof: 0, result: { bytes: p.bytes } }, 'the 2.0.0 frame shape');
      }
      assert.equal(typeof r.result.overall, 'number');
    }
    await c.close();
  });
});

describe('reading progress across a reconnect', () => {
  test('REPLAY, server kill while reading: replayed progress behind the delivered one is dropped, nothing goes back, one result', async () => {
    for (const channel of ['compat', 'native']) {
      await mock.reset();
      // The parameter or start frame plus 120 audio frames (76800 bytes, progress up to 8 units)
      // reach the server, then it kills the connection. The reconnect has no fault.
      await mock.faults([{ match: channel === 'native' ? NATIVE : '/sent.eval.cn', fault: 'ws-kill-after:121' }]);
      const logs = [];
      const c = client({ logLevel: 'DEBUG', logger: (level, tag, message) => logs.push([level, message]) });
      const r = await round((l) => open(channel, c, l), ZH_PCM, 2);
      const order = kinds(r.ev).filter((k) => k !== 'state' && k !== 'partial');
      assert.deepEqual(order, ['connected', 'started', 'reconnecting', 'reconnected', 'result', 'closed'], channel);
      const beforeKill = kinds(r.ev).slice(0, kinds(r.ev).indexOf('reconnecting')).filter((k) => k === 'partial').length;
      assert.ok(beforeKill >= 2, `${channel}: progress before the kill (${beforeKill} partials)`);
      assert.ok(r.partials[beforeKill - 1].readUnits >= 4, `${channel}: delivered read ${r.partials[beforeKill - 1].readUnits} before the kill`);
      assertNeverBack(r.partials);
      for (const p of r.partials) assertProgress(p, CUSTOMER_UNITS);
      const dropped = logs.filter(([level, message]) => level === 'DEBUG' && /progress partial dropped/.test(message));
      assert.ok(dropped.length >= 1, `${channel}: the replay restarted the server progress from zero`);
      assert.equal(r.partials.length + dropped.length, beforeKill + expectedRead(25).length, 'every frame either delivered or dropped');
      assert.equal(r.partials[r.partials.length - 1].readUnits, 19, 'the frame after end of the new server session');
      const bill = await mock.billing();
      assert.equal(bill.billed, 1);
      assert.equal(bill.records[0].bytes, ZH_PCM.length, 'the new server session got every byte');
      await c.close();
    }
  });

  test('DROP, server kill while reading: the new server session starts from zero and its progress is delivered', async () => {
    for (const channel of ['compat', 'native']) {
      await mock.reset();
      await mock.faults([{ match: channel === 'native' ? NATIVE : '/sent.eval.cn', fault: 'ws-kill-after:121' }]);
      const logs = [];
      const c = client({ logLevel: 'DEBUG', logger: (level, tag, message) => logs.push([level, message]) });
      const l = progressListener();
      const s = open(channel, c, l, { audioBufferPolicy: 'DROP' });
      l.session = s;
      await waitFor(() => s.getState() === 'STARTED', 5000);
      const list = frames(ZH_PCM);
      let i = 0;
      // Paced until the kill is noticed, then the rest of the audio once the new session started,
      // so the new server session surely gets enough audio for progress frames.
      for (; i < list.length && s.getState() === 'STARTED'; i++) {
        s.sendAudio(list[i]);
        await sleep(2);
      }
      await waitFor(() => s.getState() === 'STARTED', 5000, 'reconnected');
      await sendPaced(s, list.slice(i), 1);
      s.end();
      const ev = await finished(l);
      assertGuarantees(assert, ev);
      const k = kinds(ev);
      const beforeKill = k.slice(0, k.indexOf('reconnecting')).filter((x) => x === 'partial').length;
      const before = l.partials.slice(0, beforeKill);
      const afterReconnect = l.partials.slice(beforeKill);
      assert.ok(before.length >= 2 && before[before.length - 1].readUnits >= 4, `${channel}: progress before the kill`);
      assert.ok(afterReconnect.length >= 2, `${channel}: progress of the new server session`);
      assert.equal(afterReconnect[0].readUnits, 2, 'the new session starts from zero: its first frame reads 2 units');
      assert.ok(afterReconnect[0].readUnits < before[before.length - 1].readUnits, 'delivered although behind the old session');
      assertNeverBack(afterReconnect);
      for (const p of l.partials) assertProgress(p, CUSTOMER_UNITS);
      if (channel === 'compat') assertShengtongRaw(afterReconnect, CUSTOMER_UNITS);
      assert.equal(logs.filter(([level, message]) => level === 'DEBUG' && /progress partial dropped/.test(message)).length, 0);
      const stats = s.getStats();
      assert.ok(stats.droppedBytes > 0);
      const bill = await mock.billing();
      assert.equal(bill.billed, 1);
      assert.equal(bill.records[0].bytes, stats.acceptedBytes - stats.droppedBytes, 'the new server session got only the later audio');
      const lastBytes = bill.records[0].bytes;
      assert.equal(afterReconnect[afterReconnect.length - 1].readUnits, Math.min(25, Math.floor(lastBytes / 8000) + 1), 'the frame after end');
      await c.close();
    }
  });
});
