// Sandbox end-to-end tests (acceptance A-05-2, SANDBOX.md). They run only when
// YUGU_SANDBOX_APPKEY and YUGU_SANDBOX_SECRET are set, for example in the nightly CI build, and
// are skipped otherwise, so push builds never call the platform. YUGU_SANDBOX_BASE defaults to
// https://open.shengzhiai.com.
//
// Budget: 5 platform calls per run, one after another (the sandbox account runs at most 2
// evaluations at a time): native REST evaluate twice with one key (the replay does not count
// against the daily quota), compat REST, native streaming, compat streaming.
// The keys are never printed: the client logs nothing and diagnostics carry only scores and ids.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import WebSocket from 'ws';
import { YuguClient } from '../../src/client.js';
import { YuguError } from '../../src/errors.js';
import { generateIdempotencyKey } from '../../src/idempotency.js';
import { redactUrl } from '../../src/logger.js';
import { assertGuarantees, frames, pcmOf, readAudio, recordingListener, states } from '../helpers/common.mjs';

const APPKEY = process.env.YUGU_SANDBOX_APPKEY;
const SECRET = process.env.YUGU_SANDBOX_SECRET;
const BASE = (process.env.YUGU_SANDBOX_BASE || 'https://open.shengzhiai.com').replace(/\/+$/, '');
const skip = !APPKEY || !SECRET ? 'YUGU_SANDBOX_APPKEY and YUGU_SANDBOX_SECRET are not set; sandbox tests need sandbox keys (SANDBOX.md)' : false;

const WAV = readAudio('zh_short.wav');
const FRAMES = frames(pcmOf(WAV));
const SENTENCE = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' };
const COMPAT = { refText: '今天天气很好', language: 'zh-CN' };
const STREAM_BOUND_MS = 90000;

function client() {
  return new YuguClient({ baseUrl: BASE, appKey: APPKEY, secretKey: SECRET, WebSocket, logLevel: 'OFF', readTimeoutMs: 60000 });
}

/** Masks the credential values wherever they might appear in a message. */
function scrub(text) {
  let out = redactUrl(String(text));
  for (const secret of [APPKEY, SECRET]) if (secret) out = out.split(secret).join('***');
  return out;
}

/** Failure text without credentials: class, code, status and the server message only. */
function explain(e) {
  if (e instanceof YuguError) return scrub(`${e.name} code=${e.code} http=${e.httpStatus} ${e.message}`);
  return scrub(e && e.message ? e.message : e);
}

async function call(what, fn) {
  try {
    return await fn();
  } catch (e) {
    assert.fail(`${what} failed: ${explain(e)}`);
  }
  return undefined;
}

async function stream(open) {
  const l = recordingListener();
  const session = open(l);
  for (const f of FRAMES) session.sendAudio(f);
  session.end();
  let timer;
  const ev = await Promise.race([
    l.done,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`no terminal callback within ${STREAM_BOUND_MS} ms: ${states(l.events)}`)), STREAM_BOUND_MS);
    }),
  ]).finally(() => clearTimeout(timer));
  const err = ev.find((e) => e[0] === 'error');
  assert.equal(err, undefined, err ? `stream failed: ${explain(err[1])}` : '');
  assertGuarantees(assert, ev);
  const st = states(ev);
  assert.deepEqual(st.slice(0, 3), ['CONNECTING', 'CONNECTED', 'STARTED']);
  assert.deepEqual(st.slice(-3), ['ENDING', 'COMPLETED', 'CLOSED']);
  const order = ev.map((e) => e[0]).filter((k) => k !== 'state');
  assert.equal(order[0], 'connected');
  assert.equal(order[1], 'started');
  assert.deepEqual(order.slice(-2), ['result', 'closed'], 'onResult then onClosed last');
  const result = ev.find((e) => e[0] === 'result')[1];
  assert.equal(typeof result.overall, 'number', 'numeric overall');
  assert.equal(result.idempotencyKey, session.idempotencyKey);
  return result;
}

describe('sandbox end to end (A-05-2)', { skip }, () => {
  test('native REST evaluate twice with one idempotency key: the second is a replay', async (t) => {
    const c = client();
    try {
      const key = generateIdempotencyKey();
      const first = await call('evaluate', () => c.evaluate(WAV, SENTENCE, { idempotencyKey: key }));
      assert.equal(typeof first.overall, 'number', 'numeric overall');
      assert.match(first.recordId, /\S/);
      assert.equal(first.idempotencyKey, key);
      const again = await call('evaluate replay', () => c.evaluate(WAV, SENTENCE, { idempotencyKey: key }));
      assert.equal(again.replayed, true, 'Idempotency-Replayed on the second submission');
      assert.equal(again.recordId, first.recordId, 'same recordId');
      assert.equal(again.overall, first.overall);
      t.diagnostic(`native REST overall ${first.overall}, recordId ${first.recordId}, replayed ${first.replayed} then ${again.replayed}`);
    } finally {
      await c.close();
    }
  });

  test('compat REST evaluate sent.eval.cn', async (t) => {
    const c = client();
    try {
      const r = await call('evaluateCompat', () => c.evaluateCompat('sent.eval.cn', COMPAT, WAV));
      assert.equal(typeof r.overall, 'number', 'numeric overall');
      assert.equal(r.mode, 'compat');
      assert.match(r.recordId, /\S/);
      t.diagnostic(`compat REST sent.eval.cn overall ${r.overall}, recordId ${r.recordId}`);
    } finally {
      await c.close();
    }
  });

  test('native streaming evaluation end to end', async (t) => {
    const c = client();
    try {
      const r = await stream((l) => c.streamEvaluate(SENTENCE, l));
      assert.equal(r.mode, 'native');
      t.diagnostic(`native streaming overall ${r.overall}, recordId ${r.recordId}`);
    } finally {
      await c.close();
    }
  });

  test('compat streaming evaluation sent.eval.cn end to end', async (t) => {
    const c = client();
    try {
      const r = await stream((l) => c.streamEvaluateCompat('sent.eval.cn', COMPAT, l));
      assert.equal(r.mode, 'compat');
      t.diagnostic(`compat streaming sent.eval.cn overall ${r.overall}, recordId ${r.recordId}`);
    } finally {
      await c.close();
    }
  });
});
