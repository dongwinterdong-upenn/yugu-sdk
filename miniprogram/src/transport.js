// Promise wrapper over wx.request with an SDK side timer, abort support and error mapping.
import { utf8Decode } from './codec.js';
import { fromWxFail, localError } from './errors.js';
import { onAbort } from './abort.js';
import { isArrayBuffer } from './util.js';

// wx.request gets a slightly longer timeout than the SDK timer, so a timeout is always reported by
// the SDK timer with one consistent error.
const WX_TIMEOUT_MARGIN_MS = 1000;

function responseText(data) {
  if (typeof data === 'string') return data;
  if (data === undefined || data === null) return '';
  if (isArrayBuffer(data)) return utf8Decode(data);
  try {
    return JSON.stringify(data);
  } catch (e) {
    return String(data);
  }
}

/**
 * opts: {url, method, header, data, timeoutMs, signal, cancelError: () => YuguError}
 * Resolves {statusCode, header, text} for every HTTP status, rejects with a YuguError on transport
 * failure, timeout (90002) or cancellation.
 */
export function wxRequest(wxApi, opts) {
  return new Promise((resolve, reject) => {
    let settled = false;
    let task = null;
    let timer = null;
    let off = null;
    const finish = (fn, value) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      if (off) off();
      fn(value);
    };
    const abortTask = () => {
      try {
        if (task && typeof task.abort === 'function') task.abort();
      } catch (e) {
        // the task may already be finished
      }
    };
    if (opts.signal && opts.signal.aborted) {
      reject(opts.cancelError());
      return;
    }
    try {
      task = wxApi.request({
        url: opts.url,
        method: opts.method,
        header: opts.header,
        data: opts.data,
        timeout: opts.timeoutMs + WX_TIMEOUT_MARGIN_MS,
        dataType: 'text',
        responseType: 'text',
        success: (res) => {
          finish(resolve, {
            statusCode: res && typeof res.statusCode === 'number' ? res.statusCode : 0,
            header: (res && res.header) || {},
            text: responseText(res && res.data),
          });
        },
        fail: (err) => finish(reject, fromWxFail(err, 'request')),
      });
    } catch (e) {
      finish(reject, fromWxFail(e, 'request'));
      return;
    }
    if (settled) return;
    timer = setTimeout(() => {
      abortTask();
      finish(reject, localError(90002, 'request timed out after ' + opts.timeoutMs + ' ms'));
    }, opts.timeoutMs);
    off = onAbort(opts.signal, () => {
      abortTask();
      finish(reject, opts.cancelError());
    });
  });
}
