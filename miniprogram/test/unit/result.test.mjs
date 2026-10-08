// Result parsing for every evaluation mode, from real platform responses (acceptance A-05).
import test from 'node:test';
import assert from 'node:assert/strict';
import { fixtureJson } from '../helpers/common.mjs';
import {
  parseEvalResult, extractWarnings, isReplayedHeader, parseJsonBody, unwrapEnvelope, parseTtsData, resolveAudioUrl,
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
