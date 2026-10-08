// Shared test utilities.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const PKG_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const REPO_ROOT = path.resolve(PKG_ROOT, '..');
export const SPEC = path.join(REPO_ROOT, 'spec');
export const FIXTURES = path.join(SPEC, 'fixtures');

export function fixtureJson(rel) {
  return JSON.parse(fs.readFileSync(path.join(FIXTURES, rel), 'utf8'));
}

export function fixtureBytes(rel) {
  return fs.readFileSync(path.join(FIXTURES, rel));
}

export function toArrayBuffer(buf) {
  return buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
}

/** PCM samples (the data chunk) of a fixture WAV file. */
export function wavPcm(rel) {
  const b = fixtureBytes(rel);
  let off = 12;
  while (off + 8 <= b.length) {
    const id = b.toString('ascii', off, off + 4);
    const size = b.readUInt32LE(off + 4);
    if (id === 'data') return b.subarray(off + 8, off + 8 + size);
    off += 8 + size + (size & 1);
  }
  throw new Error('no data chunk in ' + rel);
}

/** Deterministic PRNG (mulberry32) returning [0, 1). */
export function seeded(seed) {
  let a = seed >>> 0;
  return function rnd() {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function waitFor(cond, timeoutMs = 5000, what = 'condition') {
  const t0 = Date.now();
  while (!cond()) {
    if (Date.now() - t0 > timeoutMs) throw new Error('timed out waiting for ' + what);
    await sleep(5);
  }
}

/** Listener that records every callback with a timestamp. */
export function recordingListener(extra = {}) {
  const events = [];
  const at = Date.now();
  const push = (name, args) => events.push({ name, args, t: Date.now() - at });
  const names = ['onStateChanged', 'onConnected', 'onStarted', 'onPartial', 'onReconnecting', 'onReconnected',
    'onResult', 'onError', 'onClosed', 'onWarning'];
  const listener = {};
  for (const n of names) {
    listener[n] = (...args) => {
      push(n, args);
      if (extra[n]) extra[n](...args);
    };
  }
  const done = new Promise((resolve) => {
    const prev = listener.onClosed;
    listener.onClosed = (...args) => {
      prev(...args);
      resolve(events);
    };
  });
  return {
    listener,
    events,
    done,
    names: () => events.map((e) => e.name),
    states: () => events.filter((e) => e.name === 'onStateChanged').map((e) => e.args[1]),
    first: (n) => events.find((e) => e.name === n),
    count: (n) => events.filter((e) => e.name === n).length,
  };
}

/** Collects log lines from the SDK logger option. */
export function captureLogs() {
  const lines = [];
  const sink = (level, tag, message, error) => lines.push({ level, tag, message, error });
  return { lines, sink, text: () => lines.map((l) => `${l.level} ${l.tag} ${l.message} ${l.error ? l.error.message : ''}`).join('\n') };
}
