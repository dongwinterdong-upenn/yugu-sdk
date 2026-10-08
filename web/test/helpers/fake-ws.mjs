// In-memory WebSocket with the browser API surface. Tests play the server side.

export function createFakeWebSocket() {
  const sockets = [];

  class FakeWebSocket {
    constructor(url) {
      this.url = url;
      this.readyState = 0;
      this.binaryType = 'blob';
      this.sent = [];
      this.closeCalls = [];
      this.onopen = null;
      this.onmessage = null;
      this.onclose = null;
      this.onerror = null;
      sockets.push(this);
    }

    send(data) {
      if (this.readyState !== 1) throw new Error(`send while readyState ${this.readyState}`);
      if (typeof data === 'string') this.sent.push(data);
      else {
        const view = data instanceof Uint8Array ? data : new Uint8Array(data.buffer || data, data.byteOffset || 0, data.byteLength);
        this.sent.push(view.slice());
      }
    }

    close(code, reason) {
      this.closeCalls.push([code, reason]);
      if (this.readyState >= 2) return;
      this.readyState = 2;
      queueMicrotask(() => this._closed(code == null ? 1005 : code, reason || ''));
    }

    // ---- server side
    accept() {
      this.readyState = 1;
      if (this.onopen) this.onopen({ type: 'open' });
    }

    serverSend(frame) {
      if (this.readyState !== 1) return;
      if (this.onmessage) this.onmessage({ data: typeof frame === 'string' ? frame : JSON.stringify(frame) });
    }

    serverClose(code = 1000, reason = '') {
      this._closed(code, reason);
    }

    fail(message = 'connection reset') {
      if (this.readyState === 3) return;
      if (this.onerror) this.onerror({ type: 'error', message });
      this._closed(1006, '');
    }

    _closed(code, reason) {
      if (this.readyState === 3) return;
      this.readyState = 3;
      if (this.onclose) this.onclose({ code, reason });
    }

    get json() {
      return this.sent.filter((x) => typeof x === 'string').map((x) => JSON.parse(x));
    }

    get audio() {
      return this.sent.filter((x) => typeof x !== 'string');
    }

    get audioBytes() {
      return this.audio.reduce((n, b) => n + b.length, 0);
    }

    get pings() {
      return this.json.filter((f) => f.cmd === 'ping').length;
    }
  }

  /** Plays a well-behaved server on a socket: open, connected, started on start, result on end. */
  function serve(ws, { mode = 'native', result = { recordId: 'eval_x', eof: 1, result: { overall: 90 } } } = {}) {
    const origSend = ws.send.bind(ws);
    ws.send = (data) => {
      origSend(data);
      if (typeof data !== 'string') return;
      const f = JSON.parse(data);
      if (f.cmd === 'ping') queueMicrotask(() => ws.serverSend({ event: 'pong', ts: Date.now() }));
      else if (f.cmd === 'end') queueMicrotask(() => ws.serverSend(mode === 'native' ? { event: 'result', ...result } : result));
      else if (f.cmd === 'start' || (mode === 'compat' && f.cmd === undefined)) queueMicrotask(() => ws.serverSend({ event: 'started' }));
    };
    ws.accept();
    ws.serverSend(mode === 'native' ? { event: 'connected', message: 'stream channel ready' } : { event: 'connected', coreType: 'sent.eval.cn' });
  }

  return { FakeWebSocket, sockets, serve };
}
