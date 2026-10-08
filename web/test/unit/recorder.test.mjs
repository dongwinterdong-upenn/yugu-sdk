import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { parseWav } from '../../src/audio.js';
import { IllegalSessionStateException, InvalidParameterException, PermissionException } from '../../src/errors.js';
import { PCM_WORKLET_SOURCE, RecorderState, StreamResampler, YuguRecorder } from '../../src/recorder.js';
import { createAudioEnvironment, domError, tone } from '../helpers/fake-audio.mjs';
import { sleep } from '../helpers/common.mjs';

function recorder(envOpts = {}, opts = {}) {
  const { env, environment } = createAudioEnvironment(envOpts);
  const events = [];
  const rec = new YuguRecorder({
    environment,
    listener: {
      onStateChanged: (a, b) => events.push(['state', a, b]),
      onFrame: (f) => events.push(['frame', f]),
      onLevel: (v) => events.push(['level', v]),
      onError: (e) => events.push(['error', e]),
    },
    ...opts,
  });
  return { rec, env, events, frames: () => events.filter((e) => e[0] === 'frame').map((e) => e[1]) };
}

describe('YuguRecorder (B-05)', () => {
  test('AudioWorklet path: 48 kHz input becomes 16 kHz PCM16 in 640-byte frames', async () => {
    const { rec, env, events, frames } = recorder();
    assert.equal(rec.getState(), 'IDLE');
    await rec.start();
    assert.equal(rec.getState(), 'RECORDING');
    assert.equal(rec.isUsingAudioWorklet(), true);
    assert.deepEqual(env.moduleUrls, ['blob:fake-worklet']);
    const ctx = env.contexts[0];
    assert.equal(ctx.workletNode.name, 'yugu-pcm-processor');
    assert.deepEqual(env.lastConstraints, { audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
    for (let i = 0; i < 10; i++) ctx.emit(tone(4800, 48000));
    const got = frames();
    assert.equal(got.length, 50, '48000 samples at 48 kHz = 1 s = 16000 samples = 50 frames of 320 samples');
    for (const f of got) assert.equal(f.byteLength, 640);
    assert.ok(events.some((e) => e[0] === 'level' && e[1] > 0.3));
    await rec.stop();
    assert.equal(rec.getState(), 'STOPPED');
    assert.ok(ctx.workletNode.port.posted.includes('flush'));
    assert.equal(ctx.state, 'closed');
    assert.ok(env.tracks.every((t) => t.readyState === 'ended'), 'microphone released on stop');
    assert.ok(env.nodes.filter((n) => n.kind !== 'destination').every((n) => n.disconnected));
    const wav = rec.exportWavBytes();
    const info = parseWav(wav);
    assert.equal(info.sampleRate, 16000);
    assert.equal(info.channels, 1);
    assert.equal(info.bitsPerSample, 16);
    assert.equal(info.dataLength, 32000);
    assert.ok(Math.abs(rec.getDurationMs() - 1000) < 1);
    const blob = rec.exportWav();
    assert.equal(blob.type, 'audio/wav');
    assert.deepEqual(events.filter((e) => e[0] === 'state').map((e) => e[2]), ['RECORDING', 'STOPPED']);
  });

  test('stop() pads the last partial frame and delivers it', async () => {
    const { rec, env, frames } = recorder({ sampleRate: 16000 });
    await rec.start();
    env.contexts[0].emit(new Float32Array(500).fill(0.25));
    assert.equal(frames().length, 1);
    await rec.stop();
    const all = frames();
    assert.equal(all.length, 2);
    const last = new Int16Array(all[1].buffer);
    assert.equal(last[179], Math.trunc(0.25 * 0x7fff), '500 samples: 320 in the first frame, 180 in the last');
    assert.equal(last[180], 0, 'zero padded');
  });

  test('ScriptProcessor fallback when addModule fails (for example CSP) or no AudioWorklet', async () => {
    for (const envOpts of [{ addModuleFails: true }, { worklet: false }]) {
      const { rec, env, frames } = recorder(envOpts);
      await rec.start();
      assert.equal(rec.isUsingAudioWorklet(), false);
      const ctx = env.contexts[0];
      assert.equal(ctx.scriptNode.bufferSize, 4096);
      ctx.emit(tone(4800));
      assert.equal(frames().length, 5);
      await rec.stop();
      assert.equal(ctx.state, 'closed');
      assert.equal(ctx.scriptNode.onaudioprocess, null);
    }
    const forced = recorder({}, { useAudioWorklet: false });
    await forced.rec.start();
    assert.equal(forced.rec.isUsingAudioWorklet(), false);
    await forced.rec.release();
    const hosted = recorder({}, { workletModuleUrl: '/static/yugu-pcm-worklet.js' });
    await hosted.rec.start();
    assert.deepEqual(hosted.env.moduleUrls, ['/static/yugu-pcm-worklet.js']);
    await hosted.rec.release();
  });

  test('pause discards audio, resume continues; invalid transitions are IllegalSessionStateException', async () => {
    const { rec, env, frames } = recorder({ sampleRate: 16000 });
    await assert.rejects(rec.pause(), IllegalSessionStateException);
    await assert.rejects(rec.resume(), IllegalSessionStateException);
    await rec.start();
    await rec.start();
    const ctx = env.contexts[0];
    ctx.emit(new Float32Array(320).fill(0.1));
    await rec.pause();
    await rec.pause();
    assert.equal(rec.getState(), 'PAUSED');
    assert.equal(ctx.state, 'suspended');
    await assert.rejects(rec.start(), IllegalSessionStateException);
    ctx.emit(new Float32Array(3200).fill(0.1));
    assert.equal(frames().length, 1, 'paused audio is dropped');
    await rec.resume();
    await rec.resume();
    assert.equal(ctx.state, 'running');
    ctx.emit(new Float32Array(320).fill(0.1));
    assert.equal(frames().length, 2);
    await rec.pause();
    await rec.stop();
    assert.equal(rec.getState(), 'STOPPED');
    assert.equal(env.gumCalls, 1);
  });

  test('release() is idempotent, stops tracks, closes the context, drops listener and audio', async () => {
    const { rec, env, events } = recorder();
    await rec.start();
    env.contexts[0].emit(tone(4800));
    const p1 = rec.release();
    const p2 = rec.release();
    assert.equal(p1, p2);
    await p1;
    await rec.release();
    await rec.stop();
    assert.equal(rec.getState(), 'RELEASED');
    assert.equal(env.contexts[0].state, 'closed');
    assert.ok(env.tracks.every((t) => t.readyState === 'ended'));
    assert.equal(rec.exportWavBytes().length, 44, 'recorded audio dropped');
    const n = events.length;
    env.contexts[0].emit(tone(4800));
    assert.equal(events.length, n, 'no callbacks after release');
    await assert.rejects(rec.start(), (e) => e instanceof IllegalSessionStateException && e.code === 90009);
    rec.setListener({});
  });

  test('100 create, start, release cycles leave no live track, open context or growth', async () => {
    const { env, environment } = createAudioEnvironment();
    for (let i = 0; i < 100; i++) {
      const rec = new YuguRecorder({ environment });
      await rec.start();
      env.contexts[env.contexts.length - 1].emit(tone(960));
      await rec.release();
    }
    assert.equal(env.gumCalls, 100);
    assert.equal(env.tracks.filter((t) => t.readyState === 'live').length, 0);
    assert.equal(env.contexts.filter((c) => c.state !== 'closed').length, 0);
    assert.equal(env.tracks.reduce((n, t) => n + (t.listeners.ended || []).length, 0), 0, 'track listeners removed');
  });

  test('permission denied, missing device and unsupported environment; resources released on every error path', async () => {
    const denied = recorder({ gumError: domError('NotAllowedError', 'Permission denied') });
    await assert.rejects(denied.rec.start(), (e) => e instanceof PermissionException && e.code === 90201);
    assert.equal(denied.rec.getState(), 'IDLE');
    for (const name of ['NotFoundError', 'NotReadableError', 'OverconstrainedError']) {
      const r = recorder({ gumError: domError(name) });
      await assert.rejects(r.rec.start(), (e) => e instanceof IllegalSessionStateException && e.code === 90202, name);
    }
    const other = recorder({ gumError: new TypeError('bad constraints') });
    await assert.rejects(other.rec.start(), (e) => e.code === 90203);
    const none = new YuguRecorder({ environment: { mediaDevices: { getUserMedia: 'x' }, AudioContext: null } });
    await assert.rejects(none.start(), (e) => e.code === 90202);
    assert.equal(YuguRecorder.isSupported(), false, 'Node has no getUserMedia');
    const { env, environment } = createAudioEnvironment();
    const Broken = class extends environment.AudioContext {
      createMediaStreamSource() {
        throw new Error('graph error');
      }
    };
    const rec = new YuguRecorder({ environment: { ...environment, AudioContext: Broken } });
    await assert.rejects(rec.start(), (e) => e.code === 90203);
    assert.ok(env.tracks.every((t) => t.readyState === 'ended'), 'microphone released after a failed start');
    assert.equal(env.contexts[0].state, 'closed');
  });

  test('microphone unplugged while recording: STOPPED, onError 90202, resources released', async () => {
    const { rec, env, events } = recorder();
    await rec.start();
    env.tracks[0].fire('ended');
    await sleep(1);
    assert.equal(rec.getState(), 'STOPPED');
    const err = events.find((e) => e[0] === 'error')[1];
    assert.equal(err.code, 90202);
    assert.equal(env.contexts[0].state, 'closed');
    env.tracks[0].fire('ended');
    assert.equal(events.filter((e) => e[0] === 'error').length, 1);
  });

  test('start() never blocks on AudioContext.resume() without a user gesture', async () => {
    const { rec, env } = recorder({ startSuspended: true, resumeHangs: true });
    const t0 = Date.now();
    await rec.start();
    assert.ok(Date.now() - t0 < 2000, 'start returned although resume() is still pending');
    assert.equal(rec.getState(), 'RECORDING');
    assert.equal(env.contexts[0].resumeCalls, 1);
    await rec.pause();
    await rec.resume();
    assert.equal(rec.getState(), 'RECORDING');
    await rec.release();
    const ok = recorder({ startSuspended: true });
    await ok.rec.start();
    assert.equal(ok.env.contexts[0].state, 'running');
    await ok.rec.release();
  });

  test('release during start keeps nothing open', async () => {
    const { env, environment } = createAudioEnvironment();
    let resolveGum;
    const slow = { getUserMedia: () => new Promise((r) => (resolveGum = r)) };
    const rec = new YuguRecorder({ environment: { ...environment, mediaDevices: slow } });
    const starting = rec.start();
    assert.equal(rec.start(), starting, 'concurrent start shares the promise');
    const rel = rec.release();
    resolveGum(await environment.mediaDevices.getUserMedia({}));
    await assert.rejects(starting, (e) => e.code === 90009);
    await rel;
    assert.ok(env.tracks.every((t) => t.readyState === 'ended'));
  });

  test('frames stream into a session; listener can be replaced and removed', async () => {
    const { rec, env } = recorder({ sampleRate: 16000 });
    const sent = [];
    const session = { sendAudio: (f) => sent.push(f.byteLength) };
    await rec.start({ session });
    env.contexts[0].emit(new Float32Array(960).fill(0.2));
    assert.deepEqual(sent, [640, 640, 640]);
    rec.setListener(null);
    env.contexts[0].emit(new Float32Array(320).fill(0.2));
    assert.equal(sent.length, 4, 'the session keeps receiving without a listener');
    assert.throws(() => rec.setListener(5), InvalidParameterException);
    const throwing = { sendAudio: () => { throw new Error('closed'); } };
    await rec.stop();
    const r2 = recorder({ sampleRate: 16000 });
    await r2.rec.start({ session: throwing });
    r2.env.contexts[0].emit(new Float32Array(320).fill(0.2));
    await r2.rec.release();
    await assert.rejects(r2.rec.start({ session: {} }), (e) => e.code === 90009);
    const r3 = recorder();
    await assert.rejects(r3.rec.start({ session: {} }), InvalidParameterException);
  });

  test('legacy option callbacks and validation', async () => {
    const got = [];
    const { environment } = createAudioEnvironment({ sampleRate: 16000 });
    const rec = new YuguRecorder({ environment, onFrame: (f) => got.push(f.length), onLevel: () => {} });
    await rec.start();
    rec._ctx.emit(new Float32Array(320));
    assert.deepEqual(got, [640]);
    await rec.stopAndGetWav();
    for (const bad of [5, { targetSampleRate: 100 }, { frameBytes: 3 }]) assert.throws(() => new YuguRecorder(bad), InvalidParameterException);
    assert.equal(new YuguRecorder({ frameBytes: 320 }).getState(), RecorderState.IDLE);
  });

  test('processing errors are reported and stop the recorder', async () => {
    const { rec, env, events } = recorder();
    await rec.start();
    rec._resampler = { process() { throw new Error('bad samples'); } };
    env.contexts[0].emit(tone(480));
    await sleep(1);
    assert.equal(events.find((e) => e[0] === 'error')[1].code, 90203);
    assert.equal(rec.getState(), 'STOPPED');
  });

  test('worklet source registers the processor', () => {
    assert.match(PCM_WORKLET_SOURCE, /registerProcessor\('yugu-pcm-processor'/);
    assert.match(PCM_WORKLET_SOURCE, /flushed/);
  });
});

describe('StreamResampler', () => {
  test('lengths for common rates and continuity across chunk boundaries', () => {
    for (const rate of [48000, 44100, 32000, 22050, 16000, 8000]) {
      const r = new StreamResampler(rate, 16000);
      let out = 0;
      let input = 0;
      for (let i = 0; i < 100; i++) {
        const n = Math.round(rate / 100) + (i % 3);
        input += n;
        out += r.process(tone(n, rate)).length;
      }
      const expected = (input * 16000) / rate;
      assert.ok(Math.abs(out - expected) <= 2, `${rate}: ${out} vs ${expected}`);
    }
    const a = new StreamResampler(48000, 16000);
    const whole = a.process(tone(4800));
    const b = new StreamResampler(48000, 16000);
    const parts = [...b.process(tone(4800).subarray(0, 1000)), ...b.process(tone(4800).subarray(1000))];
    assert.equal(parts.length, whole.length);
    for (let i = 0; i < whole.length; i++) assert.ok(Math.abs(parts[i] - whole[i]) < 1e-6);
    assert.equal(new StreamResampler(48000, 16000).process(new Float32Array(0)).length, 0);
  });
  test('low-pass removes a tone above the target Nyquist', () => {
    const r = new StreamResampler(48000, 16000);
    const out = r.process(tone(48000, 48000, 16000, 0.8));
    const rms = Math.sqrt(out.reduce((s, x) => s + x * x, 0) / out.length);
    assert.ok(rms < 0.05, `aliasing rms ${rms}`);
  });
});
