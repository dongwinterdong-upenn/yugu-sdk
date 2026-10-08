// In-memory `wx` for deterministic unit tests. wx.request answers from a script; wx.connectSocket
// returns FakeSocketTasks that a test (or a scripted server) drives frame by frame.
import { createWxMock } from './wx-mock.mjs';

const isAB = (x) => Object.prototype.toString.call(x) === '[object ArrayBuffer]';

/**
 * responses: array consumed one per request, or a function (req, index) -> response.
 * response: {status, body (string|object), headers} | {fail: 'request:fail ...'} | {hang: true}
 */
export function scriptedWx(responses = [], options = {}) {
  const base = createWxMock(options);
  const requests = [];
  const sockets = [];
  let serverFactory = null;

  base.request = (o) => {
    const idx = requests.length;
    const req = { url: o.url, method: o.method, header: { ...(o.header || {}) }, data: o.data, timeout: o.timeout, dataType: o.dataType, responseType: o.responseType, aborted: false, t: Date.now() };
    requests.push(req);
    const r = typeof responses === 'function' ? responses(req, idx) : responses[idx] || responses[responses.length - 1] || { status: 200, body: {} };
    let done = false;
    const task = {
      abort() {
        req.aborted = true;
        if (done) return;
        done = true;
        setImmediate(() => o.fail && o.fail({ errMsg: 'request:fail abort' }));
      },
    };
    if (r.hang) return task;
    const delay = r.delayMs || 0;
    setTimeout(() => {
      if (done) return;
      done = true;
      if (r.fail) {
        if (o.fail) o.fail({ errMsg: r.fail });
        return;
      }
      const body = typeof r.body === 'string' ? r.body : JSON.stringify(r.body === undefined ? {} : r.body);
      if (o.success) o.success({ statusCode: r.status || 200, header: r.headers || {}, data: o.dataType === 'json' ? safeJson(body) : body });
    }, delay);
    return task;
  };

  base.connectSocket = (o) => {
    const task = new FakeSocketTask(o, sockets.length);
    sockets.push(task);
    if (base.__connectThrows) {
      base.__connectThrows = false;
      throw new Error('connectSocket:fail too many sockets');
    }
    if (serverFactory) {
      const server = serverFactory(task, sockets.length - 1);
      task.server = server;
      setImmediate(() => server && server.onOpen && server.onOpen());
    }
    return task;
  };

  base.__scripted = {
    requests,
    sockets,
    /** factory(task, index) -> {onOpen(), onFrame(text|ArrayBuffer)}; called for every new socket */
    setServer(factory) {
      serverFactory = factory;
    },
    lastSocket: () => sockets[sockets.length - 1],
  };
  return base;
}

function safeJson(s) {
  try {
    return JSON.parse(s);
  } catch (e) {
    return s;
  }
}

export class FakeSocketTask {
  constructor(o, index) {
    this.options = o;
    this.url = o.url;
    this.index = index;
    this.listeners = { open: [], close: [], error: [], message: [] };
    this.sent = [];
    this.closed = false;
    this.closeArgs = null;
    this.failSends = false;
    this.server = null;
  }

  // ---- SocketTask API used by the SDK
  send(p) {
    if (this.closed) {
      setImmediate(() => p.fail && p.fail({ errMsg: 'sendSocketMessage:fail socket closed' }));
      return;
    }
    if (this.failSends) {
      setImmediate(() => p.fail && p.fail({ errMsg: 'sendSocketMessage:fail broken pipe' }));
      return;
    }
    const d = p.data;
    if (typeof d === 'string') this.sent.push({ text: d, json: safeJson(d) });
    else if (isAB(d)) this.sent.push({ binary: new Uint8Array(d.slice(0)) });
    else throw new TypeError('bad data');
    if (this.server && this.server.onFrame) {
      const s = this.server;
      setImmediate(() => s.onFrame(typeof d === 'string' ? safeJson(d) : d));
    }
  }

  close(p = {}) {
    this.closeArgs = p;
    if (this.closed) return;
    this.closed = true;
    setImmediate(() => this._emit('close', { code: p.code || 1000, reason: p.reason || '' }));
  }

  onOpen(fn) { this.listeners.open.push(fn); }
  onClose(fn) { this.listeners.close.push(fn); }
  onError(fn) { this.listeners.error.push(fn); }
  onMessage(fn) { this.listeners.message.push(fn); }

  // ---- test controls
  _emit(type, payload) {
    for (const fn of this.listeners[type].slice()) fn(payload);
  }

  open() {
    this._emit('open', {});
  }

  frame(obj) {
    if (this.closed) return;
    this._emit('message', { data: typeof obj === 'string' ? obj : JSON.stringify(obj) });
  }

  binary(ab) {
    this._emit('message', { data: ab });
  }

  serverClose(code, reason = '') {
    if (this.closed) return;
    this.closed = true;
    this._emit('close', { code, reason });
  }

  error(msg = 'socket error') {
    this._emit('error', { errMsg: msg });
  }

  texts() {
    return this.sent.filter((s) => s.text !== undefined).map((s) => s.json);
  }

  cmds() {
    return this.sent.map((s) => (s.binary ? 'audio:' + s.binary.length : (s.json && (s.json.cmd || (s.json.refText !== undefined ? 'params' : 'json')))));
  }

  audioBytes() {
    return this.sent.filter((s) => s.binary).reduce((n, s) => n + s.binary.length, 0);
  }

  query() {
    return Object.fromEntries(new URL(this.url).searchParams.entries());
  }
}

/**
 * Scripted platform for one socket. mode 'native' or 'compat'.
 * opts: {pong: true, answerPing: 'pong'|'unknown'|'none'|'restart', onEnd: 'result'|'none'|frame, result: {...},
 *        replayed: false, connected: true}
 */
export function scriptedServer(task, opts = {}) {
  const mode = opts.mode || 'native';
  const state = { audio: 0, started: 0, ended: false, pings: 0, frames: [] };
  const server = {
    state,
    onOpen() {
      task.open();
      if (opts.connected !== false) task.frame(mode === 'native' ? { event: 'connected', message: 'stream channel ready' } : { event: 'connected', coreType: 'sent.eval.cn' });
    },
    onFrame(f) {
      state.frames.push(f);
      if (isAB(f)) {
        state.audio += f.byteLength;
        return;
      }
      if (!f || typeof f !== 'object') return;
      if (f.cmd === 'ping') {
        state.pings += 1;
        const a = opts.answerPing || 'pong';
        if (a === 'pong') task.frame({ event: 'pong', ts: Date.now() });
        else if (a === 'unknown') task.frame({ event: 'error', message: 'unknown cmd' });
        else if (a === 'restart') {
          state.started += 1;
          state.audio = 0;
          task.frame({ event: 'started' });
        }
        return;
      }
      if (f.cmd === 'end') {
        state.ended = true;
        const onEnd = opts.onEnd === undefined ? 'result' : opts.onEnd;
        if (onEnd === 'result') {
          const body = opts.result || { recordId: 'eval_scripted', eof: 1, result: { overall: 88, pronunciation: 90, fluency: 85, integrity: 100, words: [] }, warnings: [] };
          const frame = mode === 'native' ? { event: 'result', ...body } : { ...body };
          if (opts.replayed) frame.replayed = true;
          setTimeout(() => task.frame(frame), opts.resultDelayMs || 0);
        } else if (onEnd && typeof onEnd === 'object') {
          task.frame(onEnd);
        }
        return;
      }
      if (f.cmd === 'start' || mode === 'compat') {
        state.started += 1;
        state.audio = 0;
        task.frame(mode === 'native' ? { event: 'started' } : { event: 'started', coreType: 'sent.eval.cn' });
      }
    },
  };
  return server;
}
