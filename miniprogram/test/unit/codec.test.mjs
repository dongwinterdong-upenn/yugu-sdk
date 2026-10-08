import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { sha256, hmacSha256, base64Encode, utf8Encode, utf8Decode } from '../../src/codec.js';

const hex = (u8) => Buffer.from(u8).toString('hex');

test('SHA-256 matches the FIPS 180-2 vectors', () => {
  assert.equal(hex(sha256('')), 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  assert.equal(hex(sha256('abc')), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  assert.equal(hex(sha256('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')),
    '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1');
  assert.equal(hex(sha256('a'.repeat(1000000))), 'cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0');
});

test('SHA-256, HMAC-SHA256 and Base64 agree with node:crypto on random input', () => {
  for (let n = 0; n < 260; n += 3) {
    const data = crypto.randomBytes(n);
    const key = crypto.randomBytes(n % 150);
    assert.equal(hex(sha256(data)), crypto.createHash('sha256').update(data).digest('hex'));
    assert.equal(base64Encode(hmacSha256(key, data)), crypto.createHmac('sha256', key).update(data).digest('base64'));
    assert.equal(base64Encode(data), data.toString('base64'));
  }
});

test('HMAC-SHA256 RFC 4231 cases, including keys longer than the block size', () => {
  const cases = [
    [Buffer.alloc(20, 0x0b), Buffer.from('Hi There'), 'b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7'],
    [Buffer.from('Jefe'), Buffer.from('what do ya want for nothing?'), '5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843'],
    [Buffer.alloc(131, 0xaa), Buffer.from('Test Using Larger Than Block-Size Key - Hash Key First'), '60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54'],
  ];
  for (const [k, m, want] of cases) assert.equal(hex(hmacSha256(k, m)), want);
  assert.equal(base64Encode(hmacSha256('test_secret_key_123', 'coreType=sent.eval.cn&language=zh-CN&refText=北京你好')),
    'A+6uVB/D7khxQEt8tzgCNjMUC1QtQQd1UF+NCYVYZqE=');
});

test('UTF-8 encoding equals TextEncoder, lone surrogates become U+FFFD', () => {
  const samples = ['', 'abc', '北京你好', '中文，标点。', '😀 emoji', '\ud800', 'a\udc00b', '\ud83d', 'x'.repeat(5000) + '语'];
  for (const s of samples) assert.deepEqual(Buffer.from(utf8Encode(s)), Buffer.from(new TextEncoder().encode(s)), JSON.stringify(s).slice(0, 40));
  assert.equal(utf8Encode('é').length, 2);
});

test('UTF-8 decoding equals TextDecoder on random bytes and long text', () => {
  const td = new TextDecoder();
  for (let i = 0; i < 400; i++) {
    const b = crypto.randomBytes(i % 64);
    assert.equal(utf8Decode(b), td.decode(b));
  }
  const long = '语音评测'.repeat(5000) + '😀';
  assert.equal(utf8Decode(new TextEncoder().encode(long)), long);
  assert.equal(utf8Decode(new Uint8Array([0xe4, 0xbd])), '�');
  assert.equal(utf8Decode(new Uint8Array([0xf0, 0x9f, 0x98, 0x80]).buffer), '😀');
});

test('codec functions reject values that are neither strings nor bytes', () => {
  assert.throws(() => sha256(42), TypeError);
  assert.throws(() => base64Encode({}), TypeError);
});
