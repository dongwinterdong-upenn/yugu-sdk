// Reading progress (2.1.0, CONTRACT 5.5): parsing of the progress frames recorded from the platform,
// the native realtimeFeedback option, delivery while ENDING and the reconnect rule (REPLAY keeps the
// delivered progress from going back, DROP starts again at every successful reconnect).
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { YuguClient } from '../../src/client.js';
import { InvalidParameterException } from '../../src/errors.js';
import { normalizePartial } from '../../src/result.js';
import { createFakeWebSocket } from '../helpers/fake-ws.mjs';
import { assertGuarantees, kinds, readJson, recordingListener, sleep, waitFor } from '../helpers/common.mjs';

const CUSTOMER = '我是中国人，我是中国人。我们都是中国人。中华民族是一家人。';
const CUSTOMER_UNITS = [...'我是中国人我是中国人我们都是中国人中华民族是一家人'];
const ENGLISH = 'Nice to see you again. I am a student, and I like reading books every day.';
const ENGLISH_UNITS = ['Nice', 'to', 'see', 'you', 'again', 'I', 'am', 'a', 'student', 'and', 'I', 'like', 'reading', 'books', 'every', 'day'];
const NATIVE_CFG = { coreType: 'sentence', referenceText: CUSTOMER, language: 'zh-CN' };
const RESULT = { recordId: 'eval_p', eof: 1, result: { overall: 88 } };
const PARTIAL_KEYS = ['bytes', 'readUnits', 'matchedUnits', 'totalUnits', 'progress', 'hasProgress', 'raw'];
const SHENGTONG_ITEM_KEYS = ['charType', 'scores', 'word', 'word_parts'];

/** The four frame sequences recorded from the platform on 2026-10-12 (spec/fixtures/platform/README.md). */
const RECORDED = [
  { file: 'ws_compat_sent_eval_cn_progress_frames.json', mode: 'compat', coreType: 'sent.eval.cn', refText: CUSTOMER, units: CUSTOMER_UNITS },
  { file: 'ws_compat_sent_eval_cn_progress_misread_frames.json', mode: 'compat', coreType: 'sent.eval.cn', refText: CUSTOMER, units: CUSTOMER_UNITS },
  { file: 'ws_compat_sent_eval_progress_frames.json', mode: 'compat', coreType: 'sent.eval', refText: ENGLISH, units: ENGLISH_UNITS },
  { file: 'ws_native_sentence_progress_frames.json', mode: 'native', coreType: 'sentence', refText: CUSTOMER, units: CUSTOMER_UNITS },
];

const recordedFrames = (file) => readJson(`platform/${file}`).map((e) => e.frame);
const isProgressFrame = (f) => Number(f.eof) === 0 && f.event === undefined;

/**
 * Progress frame like the platform's: the first `read` units read, the first `matched` of them
 * matched. compat adds the Shengtong part (recordId "", versions, incremental words); its words
 * deliberately disagree with progress so a test fails if the SDK ever reads them.
 */
function progress(bytes, read, matched, { units = CUSTOMER_UNITS, compat = false } = {}) {
  const list = units.map((word, i) => ({ word, read: i < read, matched: i < matched }));
  const result = { bytes, readUnits: read, matchedUnits: matched, totalUnits: units.length, progress: list };
  if (!compat) return { eof: 0, result };
  const decoy = { charType: 0, scores: { pronunciation: 0, skipped: 1, tone: 0, overall: 0, overall_pron: 0 }, word: 'x', word_parts: [] };
  return { eof: 0, recordId: '', result: { kernel_version: '1.0.0', resource_version: '1.0.0', words: [decoy], ...result } };
}

function setup(extra = {}) {
  const fake = createFakeWebSocket();
  const logs = [];
  const client = new YuguClient({
    token: 'jwt-token',
    baseUrl: 'http://api.test',
    WebSocket: fake.FakeWebSocket,
    logLevel: 'DEBUG',
    logger: (level, tag, message) => logs.push({ level, tag, message }),
    random: () => 0.5,
    reconnect: { initialDelayMs: 2, maxDelayMs: 10 },
    audioPrecheck: 'OFF',
    ...extra,
  });
  const socket = (i) => waitFor(() => fake.sockets[i], 2000, `socket ${i}`);
  return { ...fake, client, logs, socket };
}

const progressOf = (partials) => partials.map((p) => (p.hasProgress ? [p.readUnits, p.matchedUnits] : ['bytes', p.bytes]));
const droppedLogs = (logs) => logs.filter((x) => x.level === 'DEBUG' && /progress partial dropped/.test(x.message));

describe('progress frames recorded from the platform', () => {
  test('every progress frame of the four recordings maps to the partial model from result.progress', () => {
    for (const fx of RECORDED) {
      const frames = recordedFrames(fx.file).filter(isProgressFrame);
      assert.ok(frames.length >= 19, `${fx.file}: ${frames.length} progress frames`);
      for (const f of frames) {
        const p = normalizePartial(f);
        assert.deepEqual(Object.keys(p), PARTIAL_KEYS);
        assert.equal(p.raw, f, 'raw is the platform frame');
        assert.equal(p.hasProgress, true);
        assert.equal(p.bytes, f.result.bytes);
        assert.equal(p.readUnits, f.result.readUnits);
        assert.equal(p.matchedUnits, f.result.matchedUnits);
        assert.equal(p.totalUnits, fx.units.length);
        assert.deepEqual(p.progress, f.result.progress);
        assert.deepEqual(p.progress.map((w) => w.word), fx.units, `${fx.file}: the units of the reference text in order`);
        assert.equal(p.readUnits, p.progress.filter((w) => w.read).length);
        assert.equal(p.matchedUnits, p.progress.filter((w) => w.matched).length);
        assert.ok(p.progress.every((w) => !w.matched || w.read), 'matched implies read');
        assert.ok(p.progress.every((w) => Object.isFrozen(w) && Object.keys(w).join() === 'word,read,matched'));
      }
      const last = normalizePartial(frames[frames.length - 1]);
      assert.equal(last.readUnits, fx.units.length, `${fx.file}: the whole text read at the end`);
    }
  });

  test('compat frames keep the Shengtong intermediate result in raw: recordId "", versions, incremental words', () => {
    for (const fx of RECORDED) {
      const frames = recordedFrames(fx.file).filter(isProgressFrame);
      if (fx.mode === 'native') {
        assert.ok(frames.every((f) => !('words' in f.result) && !('recordId' in f)), 'native frames have no Shengtong part');
        continue;
      }
      let reported = 0;
      const concatenated = [];
      for (const f of frames) {
        const p = normalizePartial(f);
        assert.equal(p.raw.recordId, '');
        assert.equal(typeof p.raw.result.kernel_version, 'string');
        assert.equal(typeof p.raw.result.resource_version, 'string');
        const items = p.raw.result.words;
        assert.equal(items.length, p.readUnits - reported, `${fx.file}: words cover the units read since the previous frame`);
        for (const [k, item] of items.entries()) {
          const unit = p.progress[reported + k];
          assert.deepEqual(Object.keys(item).sort(), SHENGTONG_ITEM_KEYS);
          assert.equal(item.word, unit.word);
          assert.equal(item.scores.skipped, unit.matched ? 0 : 1);
          assert.equal(item.scores.overall, unit.matched ? 100 : 0);
        }
        concatenated.push(...items.map((w) => w.word));
        reported = p.readUnits;
        assert.equal('words' in p, false, 'the partial does not map the Shengtong words');
      }
      assert.deepEqual(concatenated, fx.units, `${fx.file}: the words of all frames together are the read units`);
    }
  });

  test('misread and misrecognized units: read without matched', () => {
    const misread = recordedFrames('ws_compat_sent_eval_cn_progress_misread_frames.json').filter(isProgressFrame).map(normalizePartial);
    const last = misread[misread.length - 1];
    assert.deepEqual([last.readUnits, last.matchedUnits, last.totalUnits], [25, 24, 25]);
    const misses = last.progress.map((w, i) => (w.read && !w.matched ? i : -1)).filter((i) => i >= 0);
    assert.deepEqual(misses, [7], 'only the 中 of the second clause, read as 外');
    assert.deepEqual(last.progress[7], { word: '中', read: true, matched: false });
    const firstMiss = misread.find((p) => p.readUnits > 7);
    assert.deepEqual(firstMiss.progress[7], { word: '中', read: true, matched: false }, 'not matched from the first frame that passed it');
    // English: "Nice" was misrecognized and stays read without matched.
    const en = recordedFrames('ws_compat_sent_eval_progress_frames.json').filter(isProgressFrame).map(normalizePartial);
    const first = en.find((p) => p.readUnits > 0);
    assert.deepEqual([first.readUnits, first.matchedUnits], [3, 2]);
    assert.deepEqual(first.progress[0], { word: 'Nice', read: true, matched: false });
    const lastEn = en[en.length - 1];
    assert.deepEqual([lastEn.readUnits, lastEn.matchedUnits, lastEn.totalUnits], [16, 15, 16]);
    assert.deepEqual(lastEn.progress[0], { word: 'Nice', read: true, matched: false });
  });

  test('frames without result.progress: bytes as in 2.0.0, counts -1, empty progress, hasProgress false', () => {
    const f = { eof: 0, result: { bytes: 16000 } };
    assert.deepEqual(normalizePartial(f), { bytes: 16000, readUnits: -1, matchedUnits: -1, totalUnits: -1, progress: [], hasProgress: false, raw: f });
    assert.deepEqual(normalizePartial({ eof: 0 }), { bytes: null, readUnits: -1, matchedUnits: -1, totalUnits: -1, progress: [], hasProgress: false, raw: { eof: 0 } });
    assert.deepEqual(normalizePartial(null).raw, {});
    assert.equal(normalizePartial({ eof: 0, result: { bytes: 'x', progress: 'x' } }).hasProgress, false);
    assert.equal(normalizePartial({ eof: 0, result: [] }).bytes, null);
    // The per-unit state is never taken from result.words, neither the Shengtong items of the
    // compat channel nor a list shaped like progress.
    const onlyWords = normalizePartial({ eof: 0, result: { bytes: 96000, words: [{ word: '我', read: true, matched: true }] } });
    assert.deepEqual([onlyWords.hasProgress, onlyWords.progress], [false, []]);
    const both = normalizePartial(progress(48000, 2, 1, { compat: true }));
    assert.deepEqual(both.progress.slice(0, 3), [
      { word: '我', read: true, matched: true },
      { word: '是', read: true, matched: false },
      { word: '中', read: false, matched: false },
    ]);
  });

  test('odd values: numeric strings, non-integer counts, malformed progress items', () => {
    const p = normalizePartial({
      eof: 0,
      result: {
        bytes: '320',
        readUnits: '2',
        matchedUnits: 1.5,
        totalUnits: true,
        progress: [null, 'x', ['a'], { word: 7, read: 'true', matched: 1 }, { read: true, matched: true }],
      },
    });
    assert.equal(p.bytes, 320);
    assert.equal(p.readUnits, 2);
    assert.equal(p.matchedUnits, -1);
    assert.equal(p.totalUnits, -1);
    assert.equal(p.hasProgress, true);
    assert.deepEqual(p.progress, [
      { word: '7', read: false, matched: false },
      { word: '', read: true, matched: true },
    ]);
    const empty = normalizePartial({ eof: 0, result: { bytes: 0, readUnits: 0, matchedUnits: 0, totalUnits: 0, progress: [] } });
    assert.deepEqual([empty.hasProgress, empty.readUnits, empty.totalUnits, empty.progress.length], [true, 0, 0, 0]);
    assert.equal(normalizePartial({ eof: 0, result: { readUnits: '' } }).readUnits, -1);
  });

  test('each recording played through a session: every frame on onPartial in order, the last ones while ENDING', async () => {
    for (const fx of RECORDED) {
      const { client, socket } = setup();
      const l = recordingListener();
      const statesAtPartial = [];
      let s = null;
      const record = l.onPartial;
      l.onPartial = (p) => {
        statesAtPartial.push(s.getState());
        record(p);
      };
      s =
        fx.mode === 'native'
          ? client.streamEvaluate({ coreType: 'sentence', referenceText: fx.refText, language: 'zh-CN', realtimeFeedback: true }, l)
          : client.streamEvaluateCompat(fx.coreType, { refText: fx.refText, realtime_feedback: true }, l);
      const ws = await socket(0);
      const frames = recordedFrames(fx.file);
      const progressFrames = frames.filter(isProgressFrame);
      const final = frames[frames.length - 1];
      ws.accept();
      ws.serverSend(frames[0]);
      if (fx.mode === 'native') {
        assert.deepEqual(ws.json[0], { coreType: 'sentence', referenceText: fx.refText, language: 'zh-CN', realtimeFeedback: true, cmd: 'start', idempotencyKey: s.idempotencyKey });
      } else {
        assert.deepEqual(ws.json[0], { refText: fx.refText, realtime_feedback: true, idempotencyKey: s.idempotencyKey });
      }
      ws.serverSend(frames[1]);
      await waitFor(() => s.getState() === 'STARTED');
      // The last two progress frames of each recording arrived after the audio, so after end().
      const split = progressFrames.length - 2;
      for (const f of progressFrames.slice(0, split)) ws.serverSend(f);
      s.end();
      assert.equal(s.getState(), 'ENDING');
      for (const f of progressFrames.slice(split)) ws.serverSend(f);
      ws.serverSend(final);
      const ev = await l.done;
      assertGuarantees(assert, ev);
      assert.equal(l.partials.length, progressFrames.length, fx.file);
      assert.deepEqual(l.partials.map((p) => p.raw), progressFrames, `${fx.file}: delivered in order`);
      assert.deepEqual(l.partials.map((p) => p.readUnits), progressFrames.map((f) => f.result.readUnits));
      assert.deepEqual(statesAtPartial.slice(0, split), new Array(split).fill('STARTED'));
      assert.deepEqual(statesAtPartial.slice(split), ['ENDING', 'ENDING'], `${fx.file}: progress while ENDING`);
      const r = ev.find((e) => e[0] === 'result')[1];
      assert.equal(r.overall, final.result.overall);
      assert.equal(r.mode, fx.mode);
      assert.deepEqual(kinds(ev).filter((k) => k !== 'state').slice(-3), ['partial', 'result', 'closed'], 'the result comes after every partial');
    }
  });
});

describe('native realtimeFeedback option', () => {
  test('the start frame carries "realtimeFeedback": true only when the option is true; other frames are byte-identical to 2.0.0', async () => {
    for (const [extra, on] of [
      [{}, false],
      [{ realtimeFeedback: false }, false],
      [{ realtimeFeedback: null }, false],
      [{ realtimeFeedback: undefined }, false],
      [{ realtimeFeedback: true }, true],
    ]) {
      const { client, socket } = setup();
      const s = client.streamEvaluate({ ...NATIVE_CFG, ...extra }, { onResult() {}, onError() {} });
      const ws = await socket(0);
      ws.accept();
      ws.serverSend({ event: 'connected' });
      const expected = { ...NATIVE_CFG, ...(on ? { realtimeFeedback: true } : {}), cmd: 'start', idempotencyKey: s.idempotencyKey };
      assert.equal(ws.sent[0], JSON.stringify(expected), JSON.stringify(extra));
      s.cancel();
    }
  });

  test('a value other than a boolean is InvalidParameterException 90010 before any connection', async () => {
    const { client, sockets } = setup();
    for (const v of [1, 0, 'true', {}, []]) {
      assert.throws(
        () => client.streamEvaluate({ ...NATIVE_CFG, realtimeFeedback: v }, { onResult() {}, onError() {} }),
        (e) => e instanceof InvalidParameterException && e.code === 90010 && /realtimeFeedback/.test(e.message),
        JSON.stringify(v),
      );
    }
    await sleep(5);
    assert.equal(sockets.length, 0);
  });

  test('the option is kept on every reconnect', async () => {
    const { client, socket, serve } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate({ ...NATIVE_CFG, realtimeFeedback: true }, l);
    const ws0 = await socket(0);
    serve(ws0, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    ws0.fail('ECONNRESET');
    const ws1 = await socket(1);
    serve(ws1, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    assert.equal(ws1.sent[0], ws0.sent[0]);
    assert.equal(ws1.json[0].realtimeFeedback, true);
    s.end();
    assertGuarantees(assert, await l.done);
  });
});

describe('reconnect rule: delivered progress', () => {
  test('REPLAY: progress behind the last delivered one is dropped and logged at DEBUG; partials without progress still come', async () => {
    const { client, socket, serve, logs } = setup();
    const l = recordingListener();
    const s = client.streamEvaluateCompat('sent.eval.cn', { refText: CUSTOMER, realtime_feedback: true }, l);
    const ws0 = await socket(0);
    serve(ws0, { mode: 'compat', result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    s.sendAudio(new Uint8Array(640));
    const send = (ws, bytes, read, matched) => ws.serverSend(progress(bytes, read, matched, { compat: true }));
    send(ws0, 16000, 2, 2);
    send(ws0, 32000, 5, 4);
    ws0.fail('ECONNRESET');
    const ws1 = await socket(1);
    serve(ws1, { mode: 'compat', result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    assert.deepEqual(ws1.json[0], ws0.json[0], 'the same parameter frame with realtime_feedback');
    // The new server session computes the progress again from zero while the audio is replayed.
    send(ws1, 16000, 0, 0); // dropped
    ws1.serverSend({ eof: 0, result: { bytes: 16000 } }); // no progress: delivered
    send(ws1, 24000, 3, 3); // dropped, read 3 < 5
    send(ws1, 32000, 5, 3); // dropped, matched 3 < 4
    send(ws1, 40000, 5, 4); // delivered, equal counts
    send(ws1, 48000, 6, 5); // delivered
    send(ws1, 48000, 7, 4); // dropped, matched went back
    s.end();
    send(ws1, 48640, 8, 7); // delivered while ENDING
    const ev = await l.done;
    assertGuarantees(assert, ev);
    assert.deepEqual(progressOf(l.partials), [[2, 2], [5, 4], ['bytes', 16000], [5, 4], [6, 5], [8, 7]]);
    assert.ok(kinds(ev).indexOf('reconnected') < kinds(ev).indexOf('result'));
    const dropped = droppedLogs(logs);
    assert.equal(dropped.length, 4);
    assert.match(dropped[0].message, /read 0 matched 0 behind delivered read 5 matched 4/);
    assert.match(dropped[3].message, /read 7 matched 4 behind delivered read 6 matched 5/);
    assert.ok(dropped.every((x) => x.tag === 'ws' && x.message.startsWith(s.id)));
  });

  test('DROP: the memory starts again at every successful reconnect, the rule still holds within a server session', async () => {
    const { client, socket, serve, logs } = setup();
    const l = recordingListener();
    const s = client.streamEvaluate({ ...NATIVE_CFG, realtimeFeedback: true }, l, { audioBufferPolicy: 'DROP' });
    const ws0 = await socket(0);
    serve(ws0, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    ws0.serverSend(progress(64000, 8, 7));
    ws0.fail('ECONNRESET');
    const ws1 = await socket(1);
    serve(ws1, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    // The new server session hears only the audio after the reconnect.
    ws1.serverSend(progress(16000, 2, 2)); // delivered although behind 8 and 7
    ws1.serverSend(progress(16000, 1, 1)); // dropped, behind the new session's 2
    ws1.serverSend(progress(32000, 4, 3)); // delivered
    ws1.serverSend({ event: 'error', code: 50200, message: 'mock upstream error' }); // retryable: second reconnect
    const ws2 = await socket(2);
    serve(ws2, { result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    ws2.serverSend(progress(16000, 0, 0)); // delivered after the second reset
    s.end();
    ws2.serverSend(progress(17280, 1, 0)); // delivered while ENDING
    const ev = await l.done;
    assertGuarantees(assert, ev);
    assert.deepEqual(progressOf(l.partials), [[8, 7], [2, 2], [4, 3], [0, 0], [1, 0]]);
    assert.equal(kinds(ev).filter((k) => k === 'reconnected').length, 2);
    const dropped = droppedLogs(logs);
    assert.equal(dropped.length, 1);
    assert.match(dropped[0].message, /read 1 matched 1 behind delivered read 2 matched 2/);
  });

  test('REPLAY keeps the memory over a reconnect where DROP would reset it', async () => {
    for (const [policy, expected] of [
      ['REPLAY', [[6, 6]]],
      ['DROP', [[6, 6], [2, 2]]],
    ]) {
      const { client, socket, serve } = setup();
      const l = recordingListener();
      const s = client.streamEvaluate({ ...NATIVE_CFG, realtimeFeedback: true }, l, { audioBufferPolicy: policy });
      const ws0 = await socket(0);
      serve(ws0, { result: RESULT });
      await waitFor(() => s.getState() === 'STARTED');
      ws0.serverSend(progress(48000, 6, 6));
      ws0.fail('ECONNRESET');
      const ws1 = await socket(1);
      serve(ws1, { result: RESULT });
      await waitFor(() => s.getState() === 'STARTED');
      ws1.serverSend(progress(16000, 2, 2));
      s.end();
      assertGuarantees(assert, await l.done);
      assert.deepEqual(progressOf(l.partials), expected, policy);
    }
  });

  test('the memory belongs to one session: the next session on the same client starts from zero', async () => {
    const { client, socket, serve } = setup();
    const rounds = [
      [progress(16000, 9, 9), progress(32000, 10, 10)],
      [progress(16000, 0, 0), progress(32000, 2, 1)],
    ];
    for (let i = 0; i < rounds.length; i++) {
      const l = recordingListener();
      const s = client.streamEvaluate({ ...NATIVE_CFG, realtimeFeedback: true }, l);
      const ws = await socket(i);
      serve(ws, { result: RESULT });
      await waitFor(() => s.getState() === 'STARTED');
      for (const f of rounds[i]) ws.serverSend(f);
      s.end();
      assertGuarantees(assert, await l.done);
      assert.deepEqual(progressOf(l.partials), rounds[i].map((f) => [f.result.readUnits, f.result.matchedUnits]), `session ${i}`);
    }
  });

  test('partials without progress are delivered as before, also after progress and while ENDING', async () => {
    const { client, socket, serve } = setup();
    const l = recordingListener();
    const s = client.streamEvaluateCompat('sent.eval', { refText: ENGLISH, realtime_feedback: true }, l);
    const ws = await socket(0);
    serve(ws, { mode: 'compat', result: RESULT });
    await waitFor(() => s.getState() === 'STARTED');
    ws.serverSend({ eof: 0, result: { bytes: 16000 } });
    ws.serverSend(progress(32000, 4, 4, { units: ENGLISH_UNITS, compat: true }));
    ws.serverSend({ eof: 0, result: { bytes: 48000 } });
    ws.serverSend({ eof: '0', result: { bytes: 64000 } });
    s.end();
    ws.serverSend({ eof: 0, result: { bytes: 64000 } });
    const ev = await l.done;
    assertGuarantees(assert, ev);
    assert.deepEqual(progressOf(l.partials), [['bytes', 16000], [4, 4], ['bytes', 48000], ['bytes', 64000], ['bytes', 64000]]);
    assert.deepEqual(l.partials[1].progress.map((w) => w.word), ENGLISH_UNITS);
    for (const p of l.partials.filter((x) => !x.hasProgress)) {
      assert.deepEqual([p.readUnits, p.matchedUnits, p.totalUnits, p.progress], [-1, -1, -1, []]);
    }
  });
});
