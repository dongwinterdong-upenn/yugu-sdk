// Multipart assembly: exact byte layout and part Content-Types (DESIGN 5.1, 5.2, acceptance A-05).
import test from 'node:test';
import assert from 'node:assert/strict';
import { buildMultipart, generateBoundary } from '../../src/multipart.js';
import { InvalidParameterException } from '../../src/errors.js';

// Same parsing rules as tools/mock-server: a part with filename is a file, without is a field.
function parse(bytes, boundary) {
  const body = Buffer.from(bytes);
  const b = Buffer.from('--' + boundary);
  const parts = [];
  let pos = body.indexOf(b);
  while (pos >= 0) {
    pos += b.length;
    if (body.subarray(pos, pos + 2).toString() === '--') break;
    pos += 2;
    const headerEnd = body.indexOf('\r\n\r\n', pos);
    const next = body.indexOf(b, headerEnd + 4);
    const headers = {};
    for (const line of body.subarray(pos, headerEnd).toString('utf8').split('\r\n')) {
      const k = line.indexOf(':');
      headers[line.slice(0, k).toLowerCase()] = line.slice(k + 1).trim();
    }
    const cd = headers['content-disposition'];
    parts.push({
      name: /name="([^"]*)"/.exec(cd)[1],
      filename: (/filename="([^"]*)"/.exec(cd) || [])[1] ?? null,
      contentType: headers['content-type'] ?? null,
      data: body.subarray(headerEnd + 4, next - 2),
    });
    pos = next;
  }
  return parts;
}

test('exact byte layout of the native evaluate body', () => {
  const config = '{"coreType":"sentence","referenceText":"今天天气很好","language":"zh-CN"}';
  const audio = new Uint8Array([0x52, 0x49, 0x46, 0x46, 0, 1, 2, 255]);
  const mp = buildMultipart([
    { name: 'config', contentType: 'application/json; charset=utf-8', data: config },
    { name: 'audio', filename: 'audio.wav', contentType: 'audio/wav', data: audio },
  ], 'XyZ');
  const expected = Buffer.concat([
    Buffer.from('--XyZ\r\nContent-Disposition: form-data; name="config"\r\nContent-Type: application/json; charset=utf-8\r\n\r\n'),
    Buffer.from(config, 'utf8'),
    Buffer.from('\r\n--XyZ\r\nContent-Disposition: form-data; name="audio"; filename="audio.wav"\r\nContent-Type: audio/wav\r\n\r\n'),
    Buffer.from(audio),
    Buffer.from('\r\n--XyZ--\r\n'),
  ]);
  assert.deepEqual(Buffer.from(mp.bytes), expected);
  assert.deepEqual(Buffer.from(mp.body), expected);
  assert.equal(mp.body.byteLength, expected.length);
  assert.equal(mp.contentType, 'multipart/form-data; boundary=XyZ');
});

test('the config part has no filename and is application/json; the audio part is a file', () => {
  const config = JSON.stringify({ coreType: 'word', referenceText: 'apple', slack: 0.2 });
  const audio = new Uint8Array(5000).map((_, i) => i % 251);
  const mp = buildMultipart([
    { name: 'config', contentType: 'application/json; charset=utf-8', data: config },
    { name: 'audio', filename: 'audio.wav', contentType: 'audio/wav', data: audio.buffer },
    { name: 'image', filename: 'image.png', contentType: 'image/png', data: new Uint8Array([1, 2, 3]) },
  ]);
  const parts = parse(mp.bytes, mp.boundary);
  assert.equal(parts.length, 3);
  assert.deepEqual(parts.map((p) => [p.name, p.filename, p.contentType]), [
    ['config', null, 'application/json; charset=utf-8'],
    ['audio', 'audio.wav', 'audio/wav'],
    ['image', 'image.png', 'image/png'],
  ]);
  assert.equal(parts[0].data.toString('utf8'), config);
  assert.deepEqual(new Uint8Array(parts[1].data), audio);
});

test('compat text fields are plain form fields with UTF-8 values', () => {
  const mp = buildMultipart([
    { name: 'refText', data: '北京你好' },
    { name: 'language', data: 'zh-CN' },
    { name: 'audio', filename: 'audio.wav', contentType: 'audio/wav', data: new Uint8Array([9]) },
  ]);
  const parts = parse(mp.bytes, mp.boundary);
  assert.equal(parts[0].contentType, null);
  assert.equal(parts[0].filename, null);
  assert.equal(parts[0].data.toString('utf8'), '北京你好');
  assert.match(Buffer.from(mp.bytes).toString('latin1'), /Content-Disposition: form-data; name="refText"\r\n\r\n/);
});

test('boundaries are random tokens and unsafe names are rejected', () => {
  const a = generateBoundary();
  assert.match(a, /^----YuguFormBoundary[A-Za-z0-9]{24}$/);
  assert.notEqual(a, generateBoundary());
  for (const bad of [{ name: 'a"b', data: 'x' }, { name: '', data: 'x' }, { name: 'a\r\nb', data: 'x' },
    { name: 'audio', filename: 'x"y.wav', data: 'x' }, { name: 'audio', data: 42 }]) {
    assert.throws(() => buildMultipart([bad]), InvalidParameterException, JSON.stringify(bad));
  }
  assert.throws(() => buildMultipart([], 'bad"boundary'), InvalidParameterException);
});
