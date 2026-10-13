// Result parsing for every evaluation mode, from real platform responses (acceptance A-05).
import test from 'node:test';
import assert from 'node:assert/strict';
import { fixtureJson } from '../helpers/common.mjs';
import {
  parseEvalResult, parsePartialResult, extractWarnings, isReplayedHeader, parseJsonBody, unwrapEnvelope, parseTtsData,
  resolveAudioUrl,
} from '../../src/result.js';
import { NotFoundException, ProtocolViolationException, ServerException } from '../../src/errors.js';

function checkReadAloud(file, mode, extra = {}) {
  const raw = fixtureJson('platform/' + file);
  const r = parseEvalResult(raw);
  const res = raw.result;
  assert.equal(r.raw, raw, file);
  assert.equal(r.recordId, raw.recordId);
  assert.equal(r.eof, 1);
  assert.equal(r.coreType, mode, file + ' coreType');
  assert.equal(r.overall, res.overall, file + ' overall');
  assert.equal(r.dims.pronunciation, res.pronunciation);
  assert.equal(r.dims.accuracy, res.accuracy);
  assert.equal(r.dims.fluency, res.fluency);
  assert.equal(r.dims.integrity, res.integrity);
  assert.equal(r.dims.rhythm, res.rhythm);
  assert.equal(r.dims.tone, res.tone);
  assert.equal(r.dims.emotion, res.emotion === undefined ? null : res.emotion);
  assert.equal(r.speed, res.speed);
  assert.equal(r.rearTone, res.rear_tone);
  assert.equal(r.duration, res.duration);
  assert.equal(r.durationSec, res.numeric_duration);
  assert.equal(r.words.length, res.words.length);
  assert.equal(r.sentences.length, (res.sentences || []).length);
  assert.equal(r.connected, null, file + ' connected');
  assert.equal(r.open, null, file + ' open');
  assert.deepEqual(r.warnings, []);
  assert.equal(r.replayed, false);
  if (extra.words) assert.deepEqual(r.words.map((w) => w.word), extra.words);
  return r;
}

test('word mode: native_word_en.json', () => {
  const r = checkReadAloud('native_word_en.json', 'word', { words: ['apple'] });
  assert.equal(r.overall, 65);
  assert.equal(r.language, 'en');
  assert.equal(r.words[0].scores.overall, 99);
  assert.ok(Array.isArray(r.words[0].phonemes));
});

test('sentence mode, English and Chinese', () => {
  const en = checkReadAloud('native_sentence_en.json', 'sentence');
  assert.equal(en.overall, 93.9);
  assert.equal(en.dims.tone, 0);
  assert.equal(en.sentences[0].details.length, 9);
  const zh = checkReadAloud('native_evaluate_sentence_zh.json', 'sentence', { words: ['今', '天', '天', '气', '很', '好'] });
  assert.equal(zh.overall, 93.7);
  assert.equal(zh.dims.tone, 83);
  assert.equal(zh.dims.emotion, 100);
  assert.equal(zh.words[0].symbolpinyin, 'jīn');
  assert.ok(zh.asrText && zh.asrText.text);
  assert.ok(zh.report && zh.report.dimensionScores);
  assert.ok(zh.yuguScores && zh.yuguScores.accuracy);
});

test('passage mode: sentences carry details when paragraphNeedWordScore is 1', () => {
  const r = checkReadAloud('native_passage_zh.json', 'passage');
  assert.equal(r.overall, 89.1);
  assert.equal(r.sentences.length, 2);
  assert.deepEqual(r.sentences.map((s) => s.details.length), [6, 9]);
  assert.deepEqual(r.sentences.map((s) => s.overall), [81, 85]);
  assert.equal(r.words.length, 15);
});

test('alpha and pinyin modes', () => {
  const a = checkReadAloud('native_alpha_en.json', 'alpha');
  assert.equal(a.overall, 47);
  assert.equal(a.words.length, 3);
  assert.equal(a.words[0].word, 'A');
  // the pinyin route is scored by the sentence engine and reports coreType sentence
  const p = checkReadAloud('native_pinyin_zh.json', 'sentence');
  assert.equal(p.overall, 88.9);
  assert.equal(p.dims.tone, 83);
});

test('connected mode: overall falls back to connected_overall', () => {
  const raw = fixtureJson('platform/native_connected_en.json');
  assert.equal(raw.result.overall, undefined);
  const r = parseEvalResult(raw);
  assert.equal(r.coreType, 'connected');
  assert.equal(r.overall, 60);
  assert.equal(r.dims.rhythm, 44);
  assert.equal(r.open, null);
  const c = r.connected;
  assert.equal(c.overall, 60);
  assert.equal(c.linking, 40);
  assert.equal(c.rhythm, 44);
  assert.equal(c.elision, 0);
  assert.equal(c.reduction, 95);
  assert.equal(c.nBoundaries, 4);
  assert.equal(c.boundaries.length, 4);
  const b = c.boundaries[0];
  assert.deepEqual(b.between, ['want', 'to']);
  assert.deepEqual(b.tags, ['elision', 'reduction']);
  assert.equal(b.realized, 0.82);
  assert.equal(b.start_ms, 770);
  assert.equal(b.end_ms, 1070);
  assert.equal(c.coverage.ratio, 1);
  assert.equal(c.metrics.linking_rate, 0.704);
  assert.equal(r.words.length, 0);
  assert.equal(r.raw.result.report.source, 'llm');
});

test('open mode: content, languageUse, delivery, transcript, feedback, openTaskAudit', () => {
  const raw = fixtureJson('platform/native_open_zh.json');
  const r = parseEvalResult(raw);
  assert.equal(r.recordId, 'open_7d6567a9860d');
  assert.equal(r.coreType, 'open');
  assert.equal(r.overall, 92);
  assert.equal(r.connected, null);
  const o = r.open;
  assert.equal(o.taskType, 'free');
  assert.equal(o.hasSpeech, true);
  assert.equal(o.durationSec, 14.22);
  assert.match(o.transcript, /李明/);
  assert.deepEqual(o.content, { overall: 98, relevance: 100, coherence: 95, task_achievement: 98 });
  assert.equal(o.languageUse.overall, 90);
  assert.equal(o.languageUse.grammar, 96);
  assert.equal(o.delivery.overall, 89);
  assert.equal(o.delivery.fluency, 100);
  assert.equal(o.delivery.pronunciation, 72);
  assert.equal(o.delivery.speech_rate, 3.897);
  assert.equal(o.delivery.speech_rate_label, '233.8 字/分');
  assert.equal(o.delivery.n_pauses, 0);
  assert.equal(o.feedback.strengths.length > 0, true);
  assert.equal(o.feedback.suggestions.length, 2);
  assert.equal(o.openTaskAudit.promptEcho, false);
  assert.equal(o.openTaskAudit.answerUnits, 53);
  assert.equal(o.audioQuality.mos, 4.66);
  assert.match(o.aggregation.formula, /content/);
  assert.equal(o.rubricVersion, 'v20260705a');
  assert.equal(r.dims.fluency, null);
  assert.equal(r.report.rubricBackfilled, true);
});

test('compat REST results of every captured coreType', () => {
  for (const [file, overall, words] of [
    ['compat_word.eval.json', 65, 1],
    ['compat_sent.eval.json', 93.9, 9],
    ['compat_sent.eval.cn.json', 94.6, 6],
    ['compat_sent_eval_cn.json', 94.6, 6],
    ['compat_para.eval.cn.json', 89.8, 0],
  ]) {
    const raw = fixtureJson('platform/' + file);
    const r = parseEvalResult(raw);
    assert.equal(r.overall, overall, file);
    assert.equal(r.words.length, words, file);
    assert.equal(r.eof, 1);
    assert.equal(r.raw, raw);
  }
  const para = parseEvalResult(fixtureJson('platform/compat_para.eval.cn.json'));
  assert.equal(para.coreType, 'para.eval.cn');
  assert.equal(para.sentences.length, 2);
});

test('compat open.eval.cn (2.1.1): open question scores plus the Shengtong flat fields', () => {
  const raw = fixtureJson('platform/compat_open.eval.cn.json');
  const r = parseEvalResult(raw);
  assert.equal(r.raw, raw);
  assert.equal(r.recordId, 'open_00289cd06bd0');
  assert.equal(r.coreType, 'open', 'the platform reports the scoring mode');
  assert.equal(r.language, 'zh');
  assert.equal(r.eof, 1);
  assert.equal(r.overall, 96);
  assert.deepEqual([r.dims.pronunciation, r.dims.accuracy, r.dims.fluency, r.dims.integrity], [71, 71, 100, 100]);
  assert.equal(r.speed, 267);
  assert.deepEqual([r.words.length, r.sentences.length], [0, 0]);
  assert.equal(r.connected, null);
  assert.equal(r.open.taskType, 'situational');
  assert.equal(r.open.transcript, '我是学生。');
  assert.equal(r.open.hasSpeech, true);
  assert.equal(r.open.durationSec, 1.64);
  assert.equal(r.open.content.overall, 100);
  assert.equal(r.open.languageUse.overall, 100);
  assert.equal(r.open.delivery.overall, 88);
  assert.equal(r.open.feedback.strengths, '回答完全符合参考答案，表达清晰准确。');
  assert.equal(r.open.openTaskAudit.refKind, 'answers');
  assert.equal(r.raw.result.coherence, 100);
});

test('WebSocket result frames captured from the platform', () => {
  const native = fixtureJson('platform/ws_native_sentence_frames.json');
  const nr = parseEvalResult(native.find((f) => f.frame.event === 'result').frame);
  assert.equal(nr.overall, 93.7);
  assert.equal(nr.recordId, 'eval_3fb45f4c8e71');
  const compat = fixtureJson('platform/ws_compat_sent_eval_cn_frames.json');
  const cr = parseEvalResult(compat.find((f) => f.frame.eof === 1).frame);
  assert.equal(cr.overall, 94.6);
  assert.equal(parseEvalResult({ ...compat.at(-1).frame, replayed: true }).replayed, true);
});

// Reading progress (2.1.0, CONTRACT 5.5): rounds recorded from production on 2026-10-12. The per-unit
// state is result.progress on both channels; compat frames also carry recordId "" and the Shengtong
// style result.words with the units read since the previous frame, which the SDK never uses for it.
const CUSTOMER_UNITS = Array.from('我是中国人我是中国人我们都是中国人中华民族是一家人');
const EN_UNITS = ['Nice', 'to', 'see', 'you', 'again', 'I', 'am', 'a', 'student', 'and', 'I', 'like', 'reading', 'books', 'every', 'day'];
const PROGRESS_ROUNDS = [
  { file: 'ws_compat_sent_eval_cn_progress_frames.json', compat: true, units: CUSTOMER_UNITS, last: [25, 25], misread: [], overall: 87.3 },
  // the second sentence's 中 was read as 外: read, not matched
  { file: 'ws_compat_sent_eval_cn_progress_misread_frames.json', compat: true, units: CUSTOMER_UNITS, last: [25, 24], misread: [7], overall: 82 },
  // "Nice" was never matched by the partial recognitions; the final result scores it 97
  { file: 'ws_compat_sent_eval_progress_frames.json', compat: true, units: EN_UNITS, last: [16, 15], misread: [0], overall: 94.4 },
  { file: 'ws_native_sentence_progress_frames.json', compat: false, units: CUSTOMER_UNITS, last: [25, 25], misread: [], overall: 88 },
];

test('reading progress: every partial frame of the four recorded rounds', () => {
  for (const round of PROGRESS_ROUNDS) {
    const frames = fixtureJson('platform/' + round.file).map((e) => e.frame);
    const partials = frames.filter((f) => f.eof === 0);
    assert.ok(partials.length >= 15, round.file);
    let prev = null;
    for (const f of partials) {
      const p = parsePartialResult(f);
      const r = f.result;
      assert.equal(p.raw, f);
      assert.equal(p.bytes, r.bytes);
      assert.equal(p.hasProgress, true, round.file);
      assert.deepEqual([p.readUnits, p.matchedUnits, p.totalUnits], [r.readUnits, r.matchedUnits, r.totalUnits]);
      assert.deepEqual(p.progress, r.progress, 'items are exactly {word, read, matched}');
      assert.notEqual(p.progress, r.progress, 'a copy, not the frame array');
      assert.deepEqual(p.progress.map((w) => w.word), round.units);
      assert.equal(p.totalUnits, round.units.length);
      assert.equal(p.readUnits, p.progress.filter((w) => w.read).length);
      assert.equal(p.matchedUnits, p.progress.filter((w) => w.matched).length);
      assert.ok(p.progress.every((w) => w.read || !w.matched), 'matched implies read');
      assert.equal(p.words, undefined, 'the model has no words field');
      if (round.compat) {
        // Shengtong style increment: the units read since the previous frame, [readUnits - words.length, readUnits)
        assert.equal(f.recordId, '');
        assert.ok(Array.isArray(r.words), round.file);
        const from = r.readUnits - r.words.length;
        assert.equal(from, prev ? prev.readUnits : 0, round.file + ': increments follow each other');
        assert.deepEqual(r.words.map((w) => w.word), round.units.slice(from, r.readUnits));
      } else {
        assert.equal(r.words, undefined, 'native progress frames have no words');
      }
      if (prev) {
        const before = prev;
        assert.ok(p.progress.every((w, i) => (w.read || !before.progress[i].read) && (w.matched || !before.progress[i].matched)), 'never back to false');
        assert.ok(p.readUnits >= prev.readUnits && p.matchedUnits >= prev.matchedUnits && p.bytes >= prev.bytes);
      }
      prev = p;
    }
    assert.deepEqual([prev.readUnits, prev.matchedUnits], round.last, round.file);
    assert.deepEqual(prev.progress.flatMap((w, i) => (w.read && !w.matched ? [i] : [])), round.misread, round.file);
    const final = frames.at(-1);
    assert.equal(final.eof, 1, 'the final frame is the last frame of the round');
    assert.equal(parseEvalResult(final).overall, round.overall);
  }
  // the English round: the partial recognitions misrecognized "Nice", the reading passed it; the final decides
  const enFrames = fixtureJson('platform/ws_compat_sent_eval_progress_frames.json').map((e) => e.frame);
  const en = enFrames.filter((f) => f.eof === 0).map(parsePartialResult);
  assert.ok(en.filter((p) => p.progress[0].read).every((p) => p.progress[0].matched === false));
  assert.deepEqual(en.at(-1).progress[0], { word: 'Nice', read: true, matched: false });
  const enFinal = parseEvalResult(enFrames.at(-1));
  assert.deepEqual([enFinal.words[0].word, enFinal.words[0].scores.overall], ['Nice', 97]);
});

test('partial frames without reading progress: bytes as in 2.0.0, -1 counts, empty progress', () => {
  const bytesOnly = { eof: 0, result: { bytes: 16000 } };
  assert.deepEqual(parsePartialResult(bytesOnly), { bytes: 16000, readUnits: -1, matchedUnits: -1, totalUnits: -1, progress: [], hasProgress: false, raw: bytesOnly });
  const noResult = { eof: '0' };
  assert.deepEqual(parsePartialResult(noResult), { bytes: 0, readUnits: -1, matchedUnits: -1, totalUnits: -1, progress: [], hasProgress: false, raw: noResult });
  assert.equal(parsePartialResult(null).hasProgress, false);
  assert.equal(parsePartialResult({ eof: 0, result: [16000] }).bytes, 0);
  assert.equal(parsePartialResult({ eof: 0, result: { bytes: 8000, progress: { 0: '我' } } }).hasProgress, false, 'progress must be an array');
  // damaged items keep their index; counts sent as text are read, missing counts stay -1
  const odd = parsePartialResult({ eof: 0, result: { bytes: '32000', readUnits: '2', progress: [null, { word: 5, read: true, matched: 1 }, { word: '人' }] } });
  assert.deepEqual(odd.progress, [{ word: '', read: false, matched: false }, { word: '5', read: true, matched: false }, { word: '人', read: false, matched: false }]);
  assert.deepEqual([odd.bytes, odd.readUnits, odd.matchedUnits, odd.totalUnits, odd.hasProgress], [32000, 2, -1, -1, true]);
  const coerced = parsePartialResult({ eof: 0, result: { bytes: 1, readUnits: 3.9, matchedUnits: 'x', totalUnits: Infinity, progress: [] } });
  assert.deepEqual([coerced.readUnits, coerced.matchedUnits, coerced.totalUnits], [3, -1, -1]);
  const empty = parsePartialResult({ eof: 0, result: { bytes: 1, readUnits: 0, matchedUnits: 0, totalUnits: 0, progress: [] } });
  assert.deepEqual([empty.readUnits, empty.matchedUnits, empty.totalUnits, empty.hasProgress], [0, 0, 0, true]);
});

test('Shengtong style result.words of compat frames never feeds the per-unit state', () => {
  const item = (word, ok) => ({ charType: 0, scores: { pronunciation: ok ? 100 : 0, skipped: ok ? 0 : 1, tone: ok ? 100 : 0, overall: ok ? 100 : 0, overall_pron: ok ? 100 : 0 }, word, word_parts: [{ beginIndex: 0, charType: 0, endIndex: 0, part: word }] });
  const units = ['北', '京', '你', '好'];
  const frame = {
    eof: 0, recordId: '',
    result: {
      kernel_version: '1.0.0', resource_version: '1.0.0', bytes: 32000, readUnits: 3, matchedUnits: 2, totalUnits: 4,
      progress: units.map((word, i) => ({ word, read: i < 3, matched: i < 2 })),
      words: [item('你', false)],
    },
  };
  const p = parsePartialResult(frame);
  assert.deepEqual(p.progress, frame.result.progress);
  assert.deepEqual([p.readUnits, p.matchedUnits, p.totalUnits, p.hasProgress], [3, 2, 4, true]);
  assert.equal(p.raw.result.words[0].word, '你', 'the increment stays in raw');
  // words without progress, as a Shengtong server would send them: no reading progress for the model
  const shengtongOnly = parsePartialResult({ eof: 0, recordId: '', result: { kernel_version: '2.4.1', resource_version: '1.8.7', words: [item('北', true)] } });
  assert.deepEqual([shengtongOnly.hasProgress, shengtongOnly.progress, shengtongOnly.readUnits, shengtongOnly.bytes], [false, [], -1, 0]);
  // the draft format with the per-unit list in words is not read either
  const draft = parsePartialResult({ eof: 0, result: { bytes: 16000, readUnits: 1, matchedUnits: 1, totalUnits: 4, words: units.map((word, i) => ({ word, read: i < 1, matched: i < 1 })) } });
  assert.deepEqual([draft.hasProgress, draft.progress], [false, []]);
});

test('warnings merge result.warning and top level warnings, numbers or objects', () => {
  const w = extractWarnings({ result: { warning: [1002, { code: 1001, message: 'No valid audio detected!' }] }, warnings: [1002, 1009, '1003', 0, null, { code: 7777 }] });
  assert.deepEqual(w.map((x) => x.code), [1002, 1001, 1009, 1003, 7777]);
  assert.equal(w[0].message, 'Audio volume too low!');
  assert.equal(w[4].message, 'warning 7777');
  assert.deepEqual(parseEvalResult({ result: { warning: [{ code: 1001, message: 'silence' }] } }).warnings, [{ code: 1001, message: 'silence' }]);
  const empty = parseEvalResult(null);
  assert.equal(empty.overall, null);
  assert.equal(empty.recordId, null);
  assert.deepEqual(empty.words, []);
});

test('replay header is read case-insensitively', () => {
  assert.equal(isReplayedHeader({ 'Idempotency-Replayed': 'true' }), true);
  assert.equal(isReplayedHeader({ 'idempotency-replayed': 'TRUE' }), true);
  assert.equal(isReplayedHeader({ 'IDEMPOTENCY-REPLAYED': ['true'] }), true);
  assert.equal(isReplayedHeader({ 'Idempotency-Replayed': 'false' }), false);
  assert.equal(isReplayedHeader({}), false);
  assert.equal(isReplayedHeader(undefined), false);
});

test('envelopes, TTS data and protocol errors', () => {
  const tts = fixtureJson('platform/tts_generate.json');
  const t = parseTtsData(unwrapEnvelope(tts), 'https://open.shengzhiai.com');
  assert.equal(t.fullUrl, tts.data.audioUrl);
  assert.equal(t.duration, 1.348);
  assert.equal(t.format, 'mp3');
  assert.deepEqual(t.warnings, []);
  assert.equal(resolveAudioUrl('https://h', '/audio/x.mp3'), 'https://h/tts/audio/x.mp3');
  assert.equal(resolveAudioUrl('https://h', '/files/x.mp3'), 'https://h/files/x.mp3');
  assert.equal(resolveAudioUrl('https://h', 'rel.mp3'), 'rel.mp3');
  assert.equal(resolveAudioUrl('https://h', ''), '');
  assert.throws(() => unwrapEnvelope({ code: 40400, message: 'gone' }), NotFoundException);
  assert.throws(() => unwrapEnvelope({ code: 50000 }), ServerException);
  assert.deepEqual(unwrapEnvelope({ code: 0 }), {});
  assert.throws(() => parseJsonBody('<html>'), (e) => e instanceof ProtocolViolationException && e.code === 90005 && e.rawBody === '<html>');
  assert.throws(() => parseJsonBody('42'), ProtocolViolationException);
  assert.deepEqual(parseJsonBody('{"a":1}'), { a: 1 });
});
