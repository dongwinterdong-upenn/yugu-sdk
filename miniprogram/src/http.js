// One logical REST call: attempts, retry decisions, backoff, total deadline, events and logs
// (DESIGN 2.2, 2.3, 2.7). Retries reuse the idempotency key; only timestamp, nonce and signature
// headers are rebuilt per attempt.
import { asYuguError, fromHttpResponse, isRetryable, localError } from './errors.js';
import { computeRetryDelay } from './retry.js';
import { wxRequest } from './transport.js';
import { onAbort } from './abort.js';

const TAG = 'YuguSDK:http';

export function describeError(err) {
  if (err.httpStatus) return 'HTTP ' + err.httpStatus + ' code=' + err.code;
  return err.category + ' code=' + err.code;
}

function decorate(err, spec, attempt) {
  err.attempts = attempt;
  if (!err.idempotencyKey && spec.key) err.idempotencyKey = spec.key;
  if (!err.op) err.op = spec.op;
  return err;
}

/** Sleeps ms, rejecting early with cancelError() when the signal aborts. */
export function sleep(ms, signal, cancelError) {
  return new Promise((resolve, reject) => {
    let off = null;
    const t = setTimeout(() => {
      if (off) off();
      resolve();
    }, ms);
    off = onAbort(signal, () => {
      clearTimeout(t);
      reject(cancelError());
    });
  });
}

/**
 * ctx:  {wx, logger, events, random}
 * spec: {op, method, path, url, key, retryAllowed, policy, attemptTimeoutMs, totalTimeoutMs, signal,
 *        cancelError(), build(attempt) -> {header, data}, parse(response, attempt) -> result}
 */
export async function executeCall(ctx, spec) {
  const logger = ctx.logger;
  const events = ctx.events;
  const startedAt = Date.now();
  const deadline = startedAt + spec.totalTimeoutMs;
  const policy = spec.policy;
  if (!spec.retryAllowed && policy.maxRetries > 0) {
    logger.debug(TAG, spec.op + ': retries disabled because the call carries no idempotency key');
  }
  let attempt = 0;
  for (;;) {
    attempt += 1;
    let err;
    try {
      if (spec.signal && spec.signal.aborted) throw spec.cancelError();
      const remaining = deadline - Date.now();
      if (remaining <= 0) throw localError(90002, 'total timeout of ' + spec.totalTimeoutMs + ' ms exceeded');
      const req = spec.build(attempt);
      events.emit('onRequestStart', spec.op, spec.method, spec.path, attempt);
      logger.debug(TAG, spec.method + ' ' + spec.path + ' attempt ' + attempt + (spec.key ? ' idempotencyKey=' + spec.key : ''));
      const res = await wxRequest(ctx.wx, {
        url: spec.url,
        method: spec.method,
        header: req.header,
        data: req.data,
        timeoutMs: Math.max(1, Math.min(spec.attemptTimeoutMs, remaining)),
        signal: spec.signal,
        cancelError: spec.cancelError,
      });
      if (res.statusCode >= 200 && res.statusCode < 300) {
        const out = spec.parse(res, attempt);
        events.emit('onRequestEnd', spec.op, res.statusCode, Date.now() - startedAt, attempt, undefined);
        logger.debug(TAG, spec.op + ' done: HTTP ' + res.statusCode + ' after ' + attempt + ' attempt(s)');
        return out;
      }
      throw fromHttpResponse(res.statusCode, res.text, { headers: res.header, idempotencyKey: spec.key, op: spec.op });
    } catch (e) {
      err = decorate(asYuguError(e), spec, attempt);
    }
    const cancelled = err.category === 'CANCELLED' || err.code === 90004;
    if (!cancelled && spec.retryAllowed && attempt <= policy.maxRetries && isRetryable(err)) {
      const delay = computeRetryDelay(attempt, policy, ctx.random, err.retryAfterMs);
      if (Date.now() + delay <= deadline) {
        logger.warn(TAG, 'retry ' + attempt + '/' + policy.maxRetries + ' in ' + delay + ' ms: ' + describeError(err));
        events.emit('onRetry', spec.op, attempt, delay, err);
        try {
          await sleep(delay, spec.signal, spec.cancelError);
          continue;
        } catch (e2) {
          err = decorate(asYuguError(e2), spec, attempt);
        }
      } else {
        logger.warn(TAG, spec.op + ': no retry, the next attempt would pass the total timeout of ' + spec.totalTimeoutMs + ' ms');
      }
    }
    events.emit('onRequestEnd', spec.op, err.httpStatus, Date.now() - startedAt, attempt, err);
    logger.info(TAG, spec.op + ' failed after ' + attempt + ' attempt(s): ' + describeError(err) + ' ' + err.message);
    throw err;
  }
}
