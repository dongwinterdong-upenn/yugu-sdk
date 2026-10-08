import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { buildMultipart, CONFIG_PART_CONTENT_TYPE, escapeFormName, makeBoundary } from '../../src/multipart.js';
import { utf8, utf8Decode } from '../../src/util.js';

const B = '----YuguFormBoundaryTEST';

describe('multipart byte layout (DESIGN 5.2)', () => {
  test('config part has no filename and Content-Type application/json; charset=utf-8', () => {
    const cfg = '{"coreType":"sentence","referenceText":"今天天气很好"}';
    const audio = new Uint8Array([0, 1, 2, 255]);
    const mp = buildMultipart(
      [
        { name: 'config', value: cfg, contentType: CONFIG_PART_CONTENT_TYPE },
        { name: 'audio', data: audio, filename: 'a.wav', contentType: 'audio/wav' },
      ],
      { boundary: B },
    );
    assert.equal(mp.contentType, `multipart/form-data; boundary=${B}`);
    const expected = new Uint8Array([
      ...utf8(`--${B}\r\nContent-Disposition: form-data; name="config"\r\nContent-Type: application/json; charset=utf-8\r\n\r\n${cfg}\r\n`),
      ...utf8(`--${B}\r\nContent-Disposition: form-data; name="audio"; filename="a.wav"\r\nContent-Type: audio/wav\r\n\r\n`),
      ...audio,
      ...utf8(`\r\n--${B}--\r\n`),
    ]);
    assert.deepEqual(mp.body, expected);
    assert.equal(CONFIG_PART_CONTENT_TYPE, 'application/json; charset=utf-8');
  });
  test('text fields carry only Content-Disposition (compat form fields)', () => {
    const mp = buildMultipart([{ name: 'refText', value: 'How are you' }, { name: 'n', value: 1 }], { boundary: B });
    assert.equal(
      utf8Decode(mp.body),
      `--${B}\r\nContent-Disposition: form-data; name="refText"\r\n\r\nHow are you\r\n` +
        `--${B}\r\nContent-Disposition: form-data; name="n"\r\n\r\n1\r\n--${B}--\r\n`,
    );
  });
  test('names and filenames are escaped like browsers do', () => {
    assert.equal(escapeFormName('a"b\r\nc'), 'a%22b%0D%0Ac');
    const mp = buildMultipart([{ name: 'audio', data: new ArrayBuffer(1), filename: 'x"y.wav' }], { boundary: B });
    assert.match(utf8Decode(mp.body), /filename="x%22y.wav"/);
  });
  test('random boundary, regenerated when it occurs in the content', () => {
    const b1 = makeBoundary();
    assert.match(b1, /^----YuguFormBoundary[0-9a-f]{24}$/);
    let calls = 0;
    const crypto = {
      getRandomValues(a) {
        calls += 1;
        return a.fill(calls === 1 ? 0 : 1);
      },
    };
    const clash = `--${'----YuguFormBoundary'}${'00'.repeat(12)}`;
    const mp = buildMultipart([{ name: 'audio', data: utf8(`xx${clash}yy`), filename: 'a' }], { crypto });
    assert.equal(mp.boundary, `----YuguFormBoundary${'01'.repeat(12)}`);
  });
  test('unsupported data is rejected', () => {
    assert.throws(() => buildMultipart([{ name: 'x', data: 12 }]), TypeError);
    const empty = buildMultipart([{ name: 'n', value: null }], { boundary: B });
    assert.match(utf8Decode(empty.body), /name="n"\r\n\r\n\r\n/);
  });
});
