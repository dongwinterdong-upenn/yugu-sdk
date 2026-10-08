// Logging levels, redaction, event hooks and the AbortController replacement.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Logger, EventHub, LogLevel, maskAppKey, redactUrl, isLogLevel } from '../../src/logger.js';
import { createAbortController, onAbort, linkSignals } from '../../src/abort.js';

test('levels filter messages, WARN is the default', () => {
  const lines = [];
  const sink = (level, tag, msg) => lines.push(level + ':' + msg);
  const all = ['ERROR', 'WARN', 'INFO', 'DEBUG'];
  const run = (level) => {
    lines.length = 0;
    const l = new Logger(level, sink);
    l.error('t', 'e');
    l.warn('t', 'w');
    l.info('t', 'i');
    l.debug('t', 'd');
    return lines.slice();
  };
  assert.deepEqual(run(undefined), ['ERROR:e', 'WARN:w']);
  assert.deepEqual(run('OFF'), []);
  assert.deepEqual(run('ERROR'), ['ERROR:e']);
  assert.deepEqual(run('INFO'), ['ERROR:e', 'WARN:w', 'INFO:i']);
  assert.deepEqual(run('DEBUG'), all.map((x) => x + ':' + x[0].toLowerCase()));
  assert.deepEqual(Object.keys(LogLevel), ['OFF', 'ERROR', 'WARN', 'INFO', 'DEBUG']);
  assert.equal(isLogLevel('TRACE'), false);
});

test('appKey is masked, credential query parameters are redacted, secrets are scrubbed', () => {
  assert.equal(maskAppKey('mock-app-key'), 'mock***');
  assert.equal(maskAppKey(''), '');
  const url = 'wss://h/api/v1/ws/evaluate?idempotencyKey=k&appKey=ak_live_123&timestamp=1&nonce=n&signature=abc%2B%3D&token=jwt.x.y';
  assert.equal(redactUrl(url), 'wss://h/api/v1/ws/evaluate?idempotencyKey=k&appKey=ak_l***&timestamp=1&nonce=n&signature=***&token=***');
  assert.equal(redactUrl('x?appKey=%E4%B8%AD%zz'), 'x?appKey=%E4%***', 'undecodable values are masked as they are');
  const lines = [];
  const l = new Logger('DEBUG', (lv, t, m) => lines.push(m), ['super-secret', 'abc', null]);
  l.debug('t', 'value super-secret and abc');
  assert.deepEqual(lines, ['value *** and abc']);
});

test('a failing sink never breaks the caller; the console sink is the default', (t) => {
  const l = new Logger('DEBUG', () => {
    throw new Error('sink down');
  });
  l.error('t', 'x');
  const warn = t.mock.method(console, 'warn', () => {});
  const log = t.mock.method(console, 'log', () => {});
  const err = t.mock.method(console, 'error', () => {});
  const info = t.mock.method(console, 'info', () => {});
  const c = new Logger('DEBUG');
  c.warn('tag', 'hello');
  c.debug('tag', 'dbg');
  c.error('tag', 'bad', new Error('e'));
  c.info('tag', 'inf');
  assert.equal(warn.mock.calls[0].arguments[0], '[tag] hello');
  assert.equal(log.mock.calls[0].arguments[0], '[tag] dbg');
  assert.equal(err.mock.calls[0].arguments[1].message, 'e');
  assert.equal(info.mock.calls.length, 1);
});

test('EventHub calls optional hooks and isolates their exceptions', () => {
  const lines = [];
  const logger = new Logger('ERROR', (lv, t, m) => lines.push(m));
  const calls = [];
  const hub = new EventHub({ onRetry: (...a) => calls.push(a), onRequestEnd: () => { throw new Error('x'); } }, logger);
  hub.emit('onRetry', 'evaluate', 1, 200, null);
  hub.emit('onRequestEnd', 'evaluate');
  hub.emit('onMissing', 1);
  new EventHub(null, logger).emit('onRetry');
  assert.deepEqual(calls, [['evaluate', 1, 200, null]]);
  assert.deepEqual(lines, ['eventListener.onRequestEnd threw']);
});

test('createAbortController behaves like the standard one', () => {
  const ctl = createAbortController();
  const seen = [];
  const fn = () => seen.push('a');
  ctl.signal.addEventListener('abort', fn);
  ctl.signal.addEventListener('abort', fn);
  ctl.signal.addEventListener('abort', () => { throw new Error('ignored'); });
  ctl.signal.onabort = () => seen.push('on');
  const removed = () => seen.push('removed');
  ctl.signal.addEventListener('abort', removed);
  ctl.signal.removeEventListener('abort', removed);
  ctl.signal.removeEventListener('other', fn);
  ctl.abort('why');
  ctl.abort('again');
  assert.equal(ctl.signal.aborted, true);
  assert.equal(ctl.signal.reason, 'why');
  assert.deepEqual(seen, ['on', 'a']);
  let n = 0;
  onAbort(ctl.signal, () => n++);
  assert.equal(n, 1, 'an aborted signal calls back at once');
  onAbort(null, () => n++)();
  const bare = { aborted: false };
  onAbort(bare, () => n++)();
  const throwing = createAbortController();
  throwing.signal.onabort = () => { throw new Error('x'); };
  throwing.abort();
  assert.equal(throwing.signal.aborted, true);
});

test('linkSignals follows standard AbortSignals', () => {
  const std = new AbortController();
  const link = linkSignals(std.signal, null);
  let fired = 0;
  onAbort(link.signal, () => fired++);
  std.abort('r');
  assert.equal(link.signal.aborted, true);
  assert.equal(fired, 1);
  link.dispose();
  const own = linkSignals(null, null);
  own.abort('x');
  assert.equal(own.signal.reason, 'x');
  own.dispose();
});
