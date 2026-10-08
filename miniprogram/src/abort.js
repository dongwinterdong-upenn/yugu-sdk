// The mini program runtime has no AbortController. createAbortController returns an object with the
// same shape (signal.aborted, signal.addEventListener('abort', fn), abort(reason)). Any standard
// AbortSignal is accepted as well.

export function createAbortController() {
  const listeners = [];
  const signal = {
    aborted: false,
    reason: undefined,
    onabort: null,
    addEventListener(type, fn) {
      if (type === 'abort' && typeof fn === 'function' && listeners.indexOf(fn) < 0) listeners.push(fn);
    },
    removeEventListener(type, fn) {
      const i = listeners.indexOf(fn);
      if (type === 'abort' && i >= 0) listeners.splice(i, 1);
    },
  };
  return {
    signal,
    abort(reason) {
      if (signal.aborted) return;
      signal.aborted = true;
      signal.reason = reason;
      const event = { type: 'abort' };
      const all = listeners.slice();
      listeners.length = 0;
      if (typeof signal.onabort === 'function') {
        try {
          signal.onabort(event);
        } catch (e) {
          // listener errors do not stop the abort
        }
      }
      for (let i = 0; i < all.length; i++) {
        try {
          all[i](event);
        } catch (e) {
          // listener errors do not stop the abort
        }
      }
    },
  };
}

/** Subscribes to abort of any signal shape. Returns the unsubscribe function. */
export function onAbort(signal, fn) {
  if (!signal || typeof signal !== 'object') return () => {};
  if (signal.aborted) {
    fn();
    return () => {};
  }
  let done = false;
  const handler = () => {
    if (done) return;
    done = true;
    fn();
  };
  if (typeof signal.addEventListener === 'function') {
    signal.addEventListener('abort', handler);
    return () => {
      done = true;
      if (typeof signal.removeEventListener === 'function') signal.removeEventListener('abort', handler);
    };
  }
  return () => {
    done = true;
  };
}

/** Signal that aborts when either input signal aborts. */
export function linkSignals(a, b) {
  const ctl = createAbortController();
  const offA = onAbort(a, () => ctl.abort(a && a.reason));
  const offB = onAbort(b, () => ctl.abort(b && b.reason));
  return {
    signal: ctl.signal,
    abort: (r) => ctl.abort(r),
    dispose() {
      offA();
      offB();
    },
  };
}
