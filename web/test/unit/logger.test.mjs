import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { callHook, consoleLogSink, isLogLevel, Logger, LogLevel, maskAppKey, redactHeaders, redactUrl } from '../../src/logger.js';

describe('logging and redaction (C-01, DESIGN 2.7)', () => {
  test('levels filter messages', () => {
    const out = [];
    const log = new Logger('WARN', (...a) => out.push(a));
    log.debug('t', 'd');
    log.info('t', 'i');
    log.warn('t', 'w');
    log.error('t', 'e', new Error('x'));
    assert.deepEqual(out.map((x) => x[0]), ['WARN', 'ERROR']);
    assert.equal(out[1][3].message, 'x');
    const off = [];
    new Logger('OFF', (...a) => off.push(a)).error('t', 'nothing');
    assert.equal(off.length, 0);
    const all = [];
    const dbg = new Logger('DEBUG', (...a) => all.push(a));
    dbg.debug('t', 'd');
    assert.equal(all.length, 1);
    assert.equal(isLogLevel('INFO'), true);
    assert.equal(isLogLevel('TRACE'), false);
    assert.deepEqual(Object.keys(LogLevel), ['OFF', 'ERROR', 'WARN', 'INFO', 'DEBUG']);
  });
  test('URLs, headers and known secrets are masked', () => {
    const url = 'wss://h/api/v1/ws/evaluate?idempotencyKey=k1&appKey=abcdefgh&timestamp=1&nonce=n&signature=SIG%2B%3D&token=eyJ.x.y';
    const red = redactUrl(url);
    assert.equal(red, 'wss://h/api/v1/ws/evaluate?idempotencyKey=k1&appKey=abcd***&timestamp=1&nonce=n&signature=***&token=***');
    assert.equal(redactUrl('Authorization: Bearer abc.def-ghi'), 'Authorization: Bearer ***');
    assert.deepEqual(redactHeaders({ 'X-Signature': 's', Authorization: 'Bearer t', 'X-App-Key': 'abcdefgh', 'Idempotency-Key': 'k' }), {
      'X-Signature': '***',
      Authorization: 'Bearer ***',
      'X-App-Key': 'abcd***',
      'Idempotency-Key': 'k',
    });
    assert.equal(maskAppKey('ak_1234567'), 'ak_1***');
    assert.equal(maskAppKey(''), '');
    const out = [];
    const log = new Logger('DEBUG', (...a) => out.push(a), ['super-secret-key', 'abc']);
    log.info('t', 'the secret is super-secret-key, short abc stays');
    assert.equal(out[0][2], 'the secret is ***, short abc stays');
  });
  test('a failing sink or listener never throws', () => {
    const log = new Logger('DEBUG', () => {
      throw new Error('sink down');
    });
    log.error('t', 'x');
    const errors = [];
    const l2 = new Logger('ERROR', (...a) => errors.push(a));
    callHook({ onRetry() { throw new Error('boom'); } }, 'onRetry', [], l2);
    assert.equal(errors.length, 1);
    callHook(null, 'onRetry', []);
    callHook({}, 'onRetry', []);
    callHook({ onRetry() { throw new Error('no logger'); } }, 'onRetry', []);
  });
  test('console sink picks the console method by level', () => {
    const orig = { error: console.error, warn: console.warn, info: console.info, debug: console.debug };
    const calls = [];
    for (const k of Object.keys(orig)) console[k] = (...a) => calls.push([k, ...a]);
    try {
      consoleLogSink('ERROR', 'http', 'e', new Error('x'));
      consoleLogSink('WARN', 'http', 'w', null);
      consoleLogSink('INFO', 'http', 'i');
      consoleLogSink('DEBUG', 'http', 'd');
    } finally {
      Object.assign(console, orig);
    }
    assert.deepEqual(calls.map((c) => c[0]), ['error', 'warn', 'info', 'debug']);
    assert.equal(calls[0][1], '[yugu-web-sdk] ERROR http: e');
    assert.equal(calls[0].length, 3);
    assert.equal(calls[1].length, 2);
  });
});
