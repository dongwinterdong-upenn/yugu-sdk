// A `wx` object for Node tests that behaves like the WeChat mini program APIs the SDK uses:
// - wx.request performs a real HTTP request with node:http (Referer and User-Agent are dropped like
//   WeChat does, dataType json parses the body, timeout fails with "request:fail timeout");
// - wx.connectSocket returns a SocketTask backed by the `ws` client;
// - wx.getRecorderManager returns one global fake recorder that emits PCM frames of
//   spec/fixtures/audio/zh_short.wav and writes the recording to a temp file;
// - wx.getFileSystemManager reads real files.
// Test controls live under wx.__mock.
import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';
import { wavPcm, toArrayBuffer } from './common.mjs';

const isAB = (x) => Object.prototype.toString.call(x) === '[object ArrayBuffer]';

// Temp directories are created on first use and always removed when the test process exits.
const tempDirs = new Set();
process.once('exit', () => {
  for (const d of tempDirs) fs.rmSync(d, { recursive: true, force: true });
});

export function createWxMock(options = {}) {
  let tmpDir = null;
  const state = {
    offline: false,
    requests: [],
    sockets: [],
    recorder: null,
    get tmpDir() {
      if (!tmpDir) {
        tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'yugu-wx-'));
        tempDirs.add(tmpDir);
      }
      return tmpDir;
    },
  };

  function later(fn) {
    setImmediate(fn);
  }

  function request(o) {
    const rec = { url: o.url, method: (o.method || 'GET').toUpperCase(), header: { ...(o.header || {}) }, timeout: o.timeout, aborted: false, dataType: o.dataType, responseType: o.responseType };
    state.requests.push(rec);
    let done = false;
    let req = null;
    let timer = null;
    const complete = (res) => {
      if (typeof o.complete === 'function') o.complete(res);
    };
    const fail = (errMsg) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      rec.failed = errMsg;
      later(() => {
        const res = { errMsg, errno: 600001 };
        if (typeof o.fail === 'function') o.fail(res);
        complete(res);
      });
    };
    const task = {
      abort() {
        if (done) return;
        rec.aborted = true;
        if (req) req.destroy();
        fail('request:fail abort');
      },
      onHeadersReceived() {},
      offHeadersReceived() {},
    };
    if (state.offline) {
      fail('request:fail -1009 network is offline');
      return task;
    }
    const u = new URL(o.url);
    const headers = {};
    for (const k of Object.keys(rec.header)) {
      if (/^(referer|user-agent)$/i.test(k)) continue;
      headers[k] = String(rec.header[k]);
    }
    let body = null;
    if (o.data !== undefined && o.data !== null) {
      if (isAB(o.data)) body = Buffer.from(o.data);
      else if (ArrayBuffer.isView(o.data)) body = Buffer.from(o.data.buffer, o.data.byteOffset, o.data.byteLength);
      else if (typeof o.data === 'string') body = Buffer.from(o.data, 'utf8');
      else body = Buffer.from(JSON.stringify(o.data), 'utf8');
    }
    if (!Object.keys(headers).some((k) => k.toLowerCase() === 'content-type')) headers['content-type'] = 'application/json';
    if (body) headers['content-length'] = String(body.length);
    rec.body = body;
    const lib = u.protocol === 'https:' ? https : http;
    req = lib.request({ hostname: u.hostname, port: u.port, path: u.pathname + u.search, method: rec.method, headers, agent: false }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        const buf = Buffer.concat(chunks);
        const header = {};
        for (let i = 0; i < res.rawHeaders.length; i += 2) {
          const k = res.rawHeaders[i];
          header[k] = header[k] !== undefined ? header[k] + ', ' + res.rawHeaders[i + 1] : res.rawHeaders[i + 1];
        }
        let data;
        if (o.responseType === 'arraybuffer') {
          data = toArrayBuffer(buf);
        } else {
          data = buf.toString('utf8');
          if ((o.dataType || 'json') === 'json') {
            try {
              data = JSON.parse(data);
            } catch (e) {
              // WeChat hands back the raw string when JSON.parse fails
            }
          }
        }
        rec.statusCode = res.statusCode;
        later(() => {
          const r = { data, statusCode: res.statusCode, header, cookies: [], errMsg: 'request:ok' };
          if (typeof o.success === 'function') o.success(r);
          complete(r);
        });
      });
      res.on('error', (e) => fail('request:fail ' + e.message));
    });
    req.on('error', (e) => fail('request:fail ' + (e.code || e.message)));
    timer = setTimeout(() => {
      if (req) req.destroy();
      fail('request:fail timeout');
    }, o.timeout || 60000);
    if (body) req.write(body);
    req.end();
    return task;
  }

  function connectSocket(o) {
    const listeners = { open: [], close: [], error: [], message: [] };
    let ws = null;
    let closed = false;
    const emit = (type, payload) => {
      for (const fn of listeners[type].slice()) fn(payload);
    };
    const finishClose = (code, reason) => {
      if (closed) return;
      closed = true;
      task.closeCode = code;
      emit('close', { code, reason: reason || '' });
    };
    const task = {
      url: o.url,
      header: o.header,
      sent: [],
      closeCode: null,
      send(p) {
        if (!ws || ws.readyState !== WebSocket.OPEN) {
          later(() => p && typeof p.fail === 'function' && p.fail({ errMsg: 'sendSocketMessage:fail WebSocket is not connected' }));
          return;
        }
        const data = p.data;
        if (typeof data === 'string') task.sent.push(data);
        else if (isAB(data)) task.sent.push({ binary: data.byteLength });
        else throw new TypeError('SocketTask.send data must be a string or an ArrayBuffer');
        ws.send(typeof data === 'string' ? data : Buffer.from(data), (err) => {
          if (err && typeof p.fail === 'function') p.fail({ errMsg: 'sendSocketMessage:fail ' + err.message });
          else if (!err && typeof p.success === 'function') p.success({ errMsg: 'sendSocketMessage:ok' });
        });
      },
      close(p = {}) {
        task.closedByClient = { code: p.code, reason: p.reason };
        if (closed) return;
        if (!ws) {
          finishClose(p.code || 1000, p.reason);
          return;
        }
        try {
          if (ws.readyState === WebSocket.CONNECTING) ws.terminate();
          else ws.close(p.code || 1000, p.reason || '');
        } catch (e) {
          finishClose(1006, e.message);
        }
      },
      onOpen(fn) { listeners.open.push(fn); },
      onClose(fn) { listeners.close.push(fn); },
      onError(fn) { listeners.error.push(fn); },
      onMessage(fn) { listeners.message.push(fn); },
      // test control: network switch, the TCP connection disappears without a close frame
      __reset() {
        if (ws) ws.terminate();
      },
      __ws: () => ws,
    };
    state.sockets.push(task);
    later(() => {
      if (state.offline) {
        emit('error', { errMsg: 'connectSocket:fail network is offline' });
        finishClose(1006, 'abnormal closure');
        return;
      }
      ws = new WebSocket(o.url, o.protocols, { headers: o.header || {}, handshakeTimeout: o.timeout || 60000 });
      ws.binaryType = 'arraybuffer';
      ws.on('open', () => emit('open', { header: {} }));
      ws.on('message', (data, isBinary) => {
        if (closed) return;
        emit('message', { data: isBinary ? (isAB(data) ? data : toArrayBuffer(Buffer.from(data))) : Buffer.from(data).toString('utf8') });
      });
      ws.on('error', (e) => {
        if (!closed) emit('error', { errMsg: e.message });
      });
      ws.on('close', (code, reason) => finishClose(code, Buffer.from(reason || '').toString('utf8')));
    });
    return task;
  }

  function getRecorderManager() {
    if (!state.recorder) state.recorder = createFakeRecorder(state, options);
    return state.recorder;
  }

  function getFileSystemManager() {
    return {
      readFile(o) {
        fs.readFile(o.filePath, (err, buf) => {
          if (err) {
            if (typeof o.fail === 'function') o.fail({ errMsg: 'readFile:fail no such file or directory, open "' + o.filePath + '"' });
            return;
          }
          const data = o.encoding ? buf.toString(o.encoding) : toArrayBuffer(buf);
          if (typeof o.success === 'function') o.success({ data, errMsg: 'readFile:ok' });
        });
      },
    };
  }

  const wx = {
    request,
    connectSocket,
    getRecorderManager,
    getFileSystemManager,
    __mock: {
      state,
      setOffline(v) {
        state.offline = !!v;
        if (state.offline) {
          for (const s of state.sockets) {
            const ws = s.__ws();
            if (ws && ws.readyState !== WebSocket.CLOSED) ws.terminate();
          }
        }
      },
      writeTemp(name, bytes) {
        const file = path.join(state.tmpDir, name);
        fs.writeFileSync(file, bytes);
        return file;
      },
      openSockets() {
        return state.sockets.filter((s) => s.closeCode === null);
      },
      cleanup() {
        if (!tmpDir) return;
        fs.rmSync(tmpDir, { recursive: true, force: true });
        tempDirs.delete(tmpDir);
        tmpDir = null;
      },
    },
  };
  return wx;
}

/**
 * Fake RecorderManager. One instance per wx mock, like the global manager of WeChat. Registering a
 * handler replaces the previous one (the RecorderManager has no off* methods).
 * Controls: micOpen, registrations (count per event), denyPermission, injectError(msg), interrupt().
 */
function createFakeRecorder(state, options) {
  const pcm = options.recorderPcm || wavPcm('audio/zh_short.wav');
  const frameIntervalMs = options.frameIntervalMs === undefined ? 2 : options.frameIntervalMs;
  const handlers = {};
  const registrations = {};
  let rec = null;
  let seq = 0;
  const emit = (name, payload) => {
    const fn = handlers[name];
    if (fn) fn(payload);
  };
  const reg = (name) => (fn) => {
    handlers[name] = fn;
    registrations[name] = (registrations[name] || 0) + 1;
  };
  const fake = {
    micOpen: false,
    startCount: 0,
    stopCount: 0,
    lastOptions: null,
    registrations,
    denyPermission: false,
    start(o) {
      if (rec) {
        setImmediate(() => emit('error', { errMsg: 'operateRecorder:fail recorder is recording' }));
        return;
      }
      if (fake.denyPermission) {
        setImmediate(() => emit('error', { errMsg: 'operateRecorder:fail auth deny' }));
        return;
      }
      fake.lastOptions = { ...o };
      fake.micOpen = true;
      fake.startCount += 1;
      rec = { o, pos: 0, chunks: [], paused: false, frameBytes: Math.round((o.frameSize || 0) * 1024), timer: null };
      setImmediate(() => emit('start', {}));
      schedule();
    },
    pause() {
      if (!rec || rec.paused) return;
      rec.paused = true;
      clearTimeout(rec.timer);
      setImmediate(() => emit('pause', {}));
    },
    resume() {
      if (!rec || !rec.paused) return;
      rec.paused = false;
      setImmediate(() => emit('resume', {}));
      schedule();
    },
    stop() {
      if (!rec) {
        setImmediate(() => emit('error', { errMsg: 'operateRecorder:fail recorder not start' }));
        return;
      }
      // the platform delivers one more frame flagged isLastFrame before onStop
      tick(true);
    },
    onStart: reg('start'),
    onPause: reg('pause'),
    onResume: reg('resume'),
    onStop: reg('stop'),
    onError: reg('error'),
    onFrameRecorded: reg('frameRecorded'),
    onInterruptionBegin: reg('interruptionBegin'),
    onInterruptionEnd: reg('interruptionEnd'),
    injectError(msg) {
      if (rec) {
        clearTimeout(rec.timer);
        rec = null;
        fake.micOpen = false;
      }
      emit('error', { errMsg: msg });
    },
    interrupt() {
      emit('interruptionBegin', {});
      fake.pause();
    },
    isRecording: () => !!rec,
  };
  function schedule() {
    rec.timer = setTimeout(() => tick(false), frameIntervalMs);
  }
  function tick(stopping) {
    if (!rec || (rec.paused && !stopping)) return;
    clearTimeout(rec.timer);
    const n = rec.frameBytes || 3200;
    const chunk = pcm.subarray(rec.pos, rec.pos + n);
    rec.pos += chunk.length;
    const exhausted = rec.pos >= pcm.length;
    if (chunk.length) rec.chunks.push(Buffer.from(chunk));
    const last = stopping || exhausted;
    if (rec.frameBytes && chunk.length) emit('frameRecorded', { frameBuffer: toArrayBuffer(Buffer.from(chunk)), isLastFrame: last });
    if (last) {
      finish();
      return;
    }
    schedule();
  }
  function finish() {
    const r = rec;
    rec = null;
    fake.micOpen = false;
    fake.stopCount += 1;
    seq += 1;
    const data = Buffer.concat(r.chunks);
    const ext = String(r.o.format || 'aac').toLowerCase();
    const file = path.join(state.tmpDir, `recording_${seq}.${ext}`);
    fs.writeFileSync(file, data);
    const bytesPerMs = ((r.o.sampleRate || 8000) * (r.o.numberOfChannels || 2) * 2) / 1000;
    setImmediate(() => emit('stop', { tempFilePath: file, duration: Math.round(data.length / bytesPerMs), fileSize: data.length }));
  }
  return fake;
}
