// Mocked getUserMedia, MediaStream, AudioContext and AudioWorkletNode for recorder tests.

export function createAudioEnvironment({ sampleRate = 48000, worklet = true, addModuleFails = false, gumError = null, startSuspended = false, resumeHangs = false } = {}) {
  const env = { contexts: [], tracks: [], nodes: [], streams: [], gumCalls: 0, moduleUrls: [] };

  class FakeTrack {
    constructor() {
      this.readyState = 'live';
      this.listeners = {};
      env.tracks.push(this);
    }
    stop() {
      this.readyState = 'ended';
    }
    addEventListener(type, fn) {
      (this.listeners[type] = this.listeners[type] || []).push(fn);
    }
    removeEventListener(type, fn) {
      this.listeners[type] = (this.listeners[type] || []).filter((f) => f !== fn);
    }
    fire(type) {
      for (const f of this.listeners[type] || []) f({ type });
    }
  }

  class FakeStream {
    constructor() {
      this.tracks = [new FakeTrack()];
      env.streams.push(this);
    }
    getTracks() {
      return this.tracks;
    }
  }

  class FakeNode {
    constructor(kind) {
      this.kind = kind;
      this.connections = [];
      this.disconnected = false;
      env.nodes.push(this);
    }
    connect(n) {
      this.connections.push(n);
      return n;
    }
    disconnect() {
      this.disconnected = true;
    }
  }

  class FakeAudioContext {
    constructor() {
      this.sampleRate = sampleRate;
      this.state = startSuspended ? 'suspended' : 'running';
      this.resumeCalls = 0;
      this.destination = new FakeNode('destination');
      this.workletNode = null;
      this.scriptNode = null;
      env.contexts.push(this);
      if (worklet) {
        this.audioWorklet = {
          addModule: async (url) => {
            env.moduleUrls.push(url);
            if (addModuleFails) throw new Error('refused by Content-Security-Policy');
          },
        };
      }
    }
    createMediaStreamSource(stream) {
      const n = new FakeNode('source');
      n.stream = stream;
      return n;
    }
    createScriptProcessor(size) {
      const n = new FakeNode('script');
      n.bufferSize = size;
      n.onaudioprocess = null;
      this.scriptNode = n;
      return n;
    }
    createGain() {
      const n = new FakeNode('gain');
      n.gain = { value: 1 };
      return n;
    }
    async suspend() {
      this.state = 'suspended';
    }
    resume() {
      this.resumeCalls += 1;
      if (resumeHangs) return new Promise(() => {}); // no user gesture yet
      this.state = 'running';
      return Promise.resolve();
    }
    async close() {
      this.state = 'closed';
    }
    /** Test helper: deliver samples through whichever node is active. */
    emit(float32) {
      if (this.workletNode && this.workletNode.port.onmessage) this.workletNode.port.onmessage({ data: float32 });
      else if (this.scriptNode && this.scriptNode.onaudioprocess) {
        this.scriptNode.onaudioprocess({ inputBuffer: { getChannelData: () => float32 } });
      }
    }
  }

  class FakeAudioWorkletNode extends FakeNode {
    constructor(ctx, name, options) {
      super('worklet');
      this.name = name;
      this.options = options;
      const port = {
        onmessage: null,
        posted: [],
        postMessage: (m) => {
          port.posted.push(m);
          if (m === 'flush') queueMicrotask(() => port.onmessage && port.onmessage({ data: 'flushed' }));
        },
      };
      this.port = port;
      ctx.workletNode = this;
    }
  }

  const mediaDevices = {
    async getUserMedia(constraints) {
      env.gumCalls += 1;
      env.lastConstraints = constraints;
      if (gumError) throw gumError;
      return new FakeStream();
    },
  };

  return {
    env,
    environment: {
      mediaDevices,
      AudioContext: FakeAudioContext,
      AudioWorkletNode: FakeAudioWorkletNode,
      createObjectURL: () => 'blob:fake-worklet',
    },
  };
}

/** A sine tone as Float32 samples. */
export function tone(n, sampleRate = 48000, freq = 440, amp = 0.5) {
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) out[i] = amp * Math.sin((2 * Math.PI * freq * i) / sampleRate);
  return out;
}

export function domError(name, message = name) {
  const e = new Error(message);
  e.name = name;
  return e;
}
