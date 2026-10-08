import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { describe, test } from 'node:test';
import { buildSignPayload, importHmacKey, signHmacSha256 } from '../../src/signer.js';
import { InvalidParameterException } from '../../src/errors.js';
import { readJson } from '../helpers/common.mjs';

const vectors = readJson('sign/vectors.json');

describe('signer: every case of spec/fixtures/sign/vectors.json', () => {
  assert.ok(vectors.cases.length >= 7);
  for (const c of vectors.cases) {
    test(`${c.name}: payload`, () => {
      assert.equal(buildSignPayload(c.params), c.payload);
    });
    test(`${c.name}: signature`, async () => {
      assert.equal(await signHmacSha256(c.params, c.secret), c.signature);
    });
    test(`${c.name}: signature with an injected crypto`, async () => {
      assert.equal(await signHmacSha256(c.params, c.secret, webcrypto), c.signature);
    });
  }
});

describe('signer: payload rules', () => {
  test('drops null, undefined and empty strings, keeps 0 and false', () => {
    assert.equal(buildSignPayload({ b: 0, a: false, c: null, d: undefined, e: '' }), 'a=false&b=0');
  });
  test('sorts by UTF-16 code units like Java TreeMap, no URL encoding', () => {
    assert.equal(buildSignPayload({ b: '2', B: '1', a: 'x y&z' }), 'B=1&a=x y&z&b=2');
  });
  test('null params give the empty payload', () => {
    assert.equal(buildSignPayload(null), '');
  });
});

describe('signer: errors', () => {
  test('empty secret is InvalidParameterException 90010', async () => {
    await assert.rejects(signHmacSha256({ a: '1' }, ''), (e) => e instanceof InvalidParameterException && e.code === 90010);
  });
  test('missing Web Crypto is reported clearly', async () => {
    await assert.rejects(importHmacKey('s', { getRandomValues() {} }), (e) => e.code === 90010 && /Web Crypto/.test(e.message));
  });
});
