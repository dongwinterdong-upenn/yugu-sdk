// Shared test helpers: paths, fixtures, waiting, seeded random.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseWav } from '../../src/audio.js';

export const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const REPO = path.resolve(WEB, '..');
export const FIXTURES = path.join(REPO, 'spec/fixtures');

export function readJson(rel) {
  return JSON.parse(fs.readFileSync(path.join(FIXTURES, rel), 'utf8'));
}

export function readAudio(name) {
  return new Uint8Array(fs.readFileSync(path.join(FIXTURES, 'audio', name)));
}

/** PCM data chunk of a WAV fixture. */
export function pcmOf(wavBytes) {
  const info = parseWav(wavBytes);
  return wavBytes.slice(info.dataOffset, info.dataOffset + info.dataLength);
}

/** 640-byte frames of PCM. */
export function frames(pcm, size = 640) {
  const out = [];
  for (let i = 0; i < pcm.length; i += size) out.push(pcm.subarray(i, Math.min(pcm.length, i + size)));
  return out;
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Poll until cond() is truthy; rejects after timeoutMs. */
export async function waitFor(cond, timeoutMs = 3000, label = 'condition') {
  const start = Date.now();
  for (;;) {
    const v = cond();
    if (v) return v;
    if (Date.now() - start > timeoutMs) throw new Error(`timed out after ${timeoutMs} ms waiting for ${label}`);
    await new Promise((r) => setTimeout(r, 2));
  }
}

/** mulberry32: small deterministic PRNG returning numbers in [0, 1). */
export function seededRandom(seed) {
  let a = seed >>> 0;
  return function random() {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Collects every callback of a stream listener in order. */
export function recordingListener() {
  const events = [];
  let resolveDone;
  const done = new Promise((r) => {
    resolveDone = r;
  });
  const listener = {
    events,
    done,
    onStateChanged: (a, b) => events.push(['state', a, b]),
    onConnected: () => events.push(['connected']),
    onStarted: () => events.push(['started']),
    onPartial: (p) => events.push(['partial', p.bytes]),
    onReconnecting: (n, d, cause) => {
      if (!listener.firstReconnectAt) listener.firstReconnectAt = Date.now();
      events.push(['reconnecting', n, d, cause]);
    },
    onReconnected: (n, info) => events.push(['reconnected', n, info]),
    onWarning: (w) => events.push(['warning', w.code]),
    onResult: (r) => events.push(['result', r]),
    onError: (e) => events.push(['error', e]),
    onClosed: (code, reason) => {
      events.push(['closed', code, reason]);
      resolveDone(events);
    },
  };
  return listener;
}

export const kinds = (events) => events.map((e) => e[0]);
export const states = (events) => events.filter((e) => e[0] === 'state').map((e) => e[2]);

/** Asserts the listener guarantees: one terminal callback (or none after cancel), onClosed last. */
export function assertGuarantees(assert, events, { cancelled = false } = {}) {
  const terminal = events.filter((e) => e[0] === 'result' || e[0] === 'error');
  if (cancelled) assert.equal(terminal.length, 0, 'no onResult or onError after cancel');
  else assert.equal(terminal.length, 1, `exactly one terminal callback, got ${terminal.map((e) => e[0])}`);
  assert.equal(events[events.length - 1][0], 'closed', 'onClosed is the last callback');
  assert.equal(events.filter((e) => e[0] === 'closed').length, 1, 'onClosed once');
}
