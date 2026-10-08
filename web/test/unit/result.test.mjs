import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  COMPAT_CORE_TYPES,
  NATIVE_CORE_TYPES,
  normalizeEvalResult,
  normalizeTtsResult,
  normalizeWarnings,
  resolveTtsUrl,
  WARNING_MESSAGES,
} from '../../src/result.js';
import { readJson } from '../helpers/common.mjs';

const parse = (name, meta) => {
  const raw = readJson(`platform/${name}`);
  return { raw, r: normalizeEvalResult(raw, meta) };
};

function assertCommon(r, raw) {
  assert.equal(r.recordId, raw.recordId);
  assert.equal(r.eof, 1);
  assert.equal(r.raw, raw, 'raw JSON stays accessible');
  assert.deepEqual(r.warnings, []);
  assert.deepEqual(r.localWarnings, []);
}

describe('result parsing per mode (real platform responses)', () => {
  test('word, English (native_word_en.json)', () => {
    const { raw, r } = parse('native_word_en.json', { coreType: 'word' });
    assertCommon(r, raw);
    assert.equal(r.overall, 65);
    assert.deepEqual(r.dims, { integrity: 38.64, accuracy: 73.73, pronunciation: 73.73, fluency: 77.28, tone: 0, rhythm: 79, emotion: 100, readingSkill: 60.74 });
    assert.equal(r.speed, 29);
    assert.equal(r.durationSeconds, 2.3);
    assert.equal(r.words.length, 1);
    assert.equal(r.words[0].word, 'apple');
    assert.equal(r.words[0].scores.overall, 99);
    assert.equal(r.words[0].scores.stress[0].phonetic, 'AE');
    assert.equal(r.words[0].phonemes.length, 4);
    assert.equal(r.asrText.overreadDetected, true);
    assert.equal(r.asrText.text, 'i want to eat an apple');
    assert.equal(r.report.dimensionScores.accuracy, 73.73);
    assert.equal(r.standardAudio, null, 'empty standardAudio object becomes null');
    assert.equal(r.connected, null);
    assert.equal(r.open, null);
    assert.equal(r.coreType, 'word');
    assert.equal(r.language, 'en');
    assert.equal(r.yuguScores.overall, raw.result.yuguScores.overall);
  });

  test('sentence, English (native_sentence_en.json)', () => {
    const { raw, r } = parse('native_sentence_en.json');
    assertCommon(r, raw);
    assert.equal(r.overall, 93.9);
    assert.equal(r.dims.pronunciation, 91.08);
    assert.equal(r.dims.fluency, 97);
    assert.equal(r.dims.integrity, 100);
    assert.equal(r.dims.rhythm, 89);
    assert.equal(r.dims.emotion, 98, 'emotion from yuguScores when result.emotion is absent');
    assert.equal(r.dims.readingSkill, 89.03);
    assert.equal(r.words.length, 9);
    assert.equal(r.sentences.length, 1);
    assert.equal(r.sentences[0].scores.overall, 94);
    assert.equal(r.sentences[0].details.length, 9);
    assert.equal(r.asrText.alignment[0].read_status, 'correct');
    assert.equal(r.compositeReport.compositeScore, raw.result.compositeReport.compositeScore);
  });

  test('sentence, Chinese (native_evaluate_sentence_zh.json)', () => {
    const { raw, r } = parse('native_evaluate_sentence_zh.json');
    assertCommon(r, raw);
    assert.equal(r.overall, 93.7);
    assert.deepEqual(r.dims, { integrity: 100, accuracy: 100, pronunciation: 100, fluency: 96, tone: 83, rhythm: 78, emotion: 100, readingSkill: 78 });
    assert.equal(r.words[0].word, '今');
    assert.equal(r.words[0].symbolpinyin, 'jīn');
    assert.equal(r.words[0].phonemes[1].phoneme, 'IN');
    assert.equal(r.asrText.text, '今 天 天 气 很 好');
    assert.equal(r.report.dimensionScores.affect, 100);
    assert.equal(r.report.dimensionEvidence.accuracy[0].type, 'pitchLow');
    assert.equal(raw.result.rear_tone, 'fall');
  });

  test('passage, Chinese with sentence details (native_passage_zh.json)', () => {
    const { raw, r } = parse('native_passage_zh.json', { coreType: 'passage' });
    assertCommon(r, raw);
    assert.equal(r.overall, 89.1);
    assert.equal(r.dims.tone, 87);
    assert.equal(r.dims.fluency, 84);
    assert.equal(r.dims.rhythm, 69);
    assert.equal(r.speed, 257);
    assert.equal(r.words.length, 15);
    assert.equal(r.sentences.length, 2);
    assert.equal(r.sentences[0].overall, 81);
    assert.equal(r.sentences[0].details.length, 6, 'paragraphNeedWordScore=1 fills details');
    assert.equal(r.sentences[0].details[0].word, '今');
    assert.equal(r.asrText, null, 'empty asrText object becomes null');
    assert.equal(raw.result.rear_tone, 'rise');
  });

  test('connected speech, English: overall falls back to connected_overall (native_connected_en.json)', () => {
    const { raw, r } = parse('native_connected_en.json', { coreType: 'connected' });
    assertCommon(r, raw);
    assert.equal(raw.result.overall, undefined);
    assert.equal(r.overall, 60);
    assert.equal(r.connected.overall, 60);
    assert.equal(r.connected.linking, 40);
    assert.equal(r.connected.rhythm, 44);
    assert.equal(r.connected.elision, 0);
    assert.equal(r.connected.reduction, 95);
    assert.equal(r.connected.nBoundaries, 4);
    assert.equal(r.connected.boundaries.length, 4);
    assert.deepEqual(r.connected.boundaries[0].between, ['want', 'to']);
    assert.deepEqual(r.connected.boundaries[0].tags, ['elision', 'reduction']);
    assert.equal(r.connected.boundaries[0].realized, 0.82);
    assert.equal(r.connected.boundaries[0].start_ms, 770);
    assert.equal(r.connected.boundaries[0].end_ms, 1070);
    assert.equal(r.connected.coverage.ratio, 1);
    assert.equal(r.connected.metrics.nPVI_V, 59.5);
    assert.equal(r.connected.report.suggestions.length, 3);
    assert.equal(r.dims.rhythm, 44);
    assert.equal(r.dims.fluency, null);
    assert.equal(r.open, null);
    assert.deepEqual(r.words, []);
  });

  test('open speaking, Chinese: content, languageUse, delivery, feedback (native_open_zh.json)', () => {
    const { raw, r } = parse('native_open_zh.json', { coreType: 'open' });
    assertCommon(r, raw);
    assert.equal(r.recordId, 'open_7d6567a9860d');
    assert.equal(r.overall, 92);
    assert.equal(r.durationSeconds, 14.22, 'duration_s');
    assert.equal(r.language, 'zh');
    const o = r.open;
    assert.equal(o.taskType, 'free');
    assert.match(o.transcript, /^大家好/);
    assert.equal(o.hasSpeech, true);
    assert.deepEqual(o.content, { overall: 98, relevance: 100, coherence: 95, task_achievement: 98 });
    assert.deepEqual(o.languageUse, { overall: 90, grammar: 96, vocabulary: 85 });
    assert.equal(o.delivery.overall, 89);
    assert.equal(o.delivery.fluency, 100);
    assert.equal(o.delivery.pronunciation, 72);
    assert.equal(o.delivery.speech_rate, 3.897);
    assert.equal(o.delivery.speech_rate_label, '233.8 字/分');
    assert.equal(o.delivery.n_pauses, 0);
    assert.equal(o.feedback.suggestions.length, 2);
    assert.equal(typeof o.feedback.strengths, 'string');
    assert.equal(o.openTaskAudit.promptEcho, false);
    assert.equal(o.openTaskAudit.answerUnits, 53);
    assert.equal(o.audioQuality.mos, 4.66);
    assert.match(o.aggregation.formula, /content/);
    assert.equal(o.rubricVersion, 'v20260705a');
    assert.deepEqual(r.report.dimensionScores, {}, 'open mode sends no dimension scores');
    assert.equal(r.report.rubricBackfilled, true);
    assert.equal(r.connected, null);
  });

  test('alpha, English (native_alpha_en.json)', () => {
    const { raw, r } = parse('native_alpha_en.json', { coreType: 'alpha' });
    assertCommon(r, raw);
    assert.equal(r.overall, 47);
    assert.equal(r.dims.pronunciation, 37.51);
    assert.equal(r.dims.integrity, 26.34);
    assert.equal(r.words.length, 3);
    assert.equal(r.words[0].word, 'A');
    assert.ok('error_type' in r.words[0]);
    assert.equal(r.asrText.substitutionDetected, true);
    assert.deepEqual(r.asrText.mismatches[0], { index: 2, expected: 'c', recognized: 'g' });
  });

  test('pinyin, Chinese (native_pinyin_zh.json): requested coreType wins over the routed one', () => {
    const { raw, r } = parse('native_pinyin_zh.json', { coreType: 'pinyin' });
    assertCommon(r, raw);
    assert.equal(raw.result._coreType, 'sentence');
    assert.equal(r.coreType, 'pinyin');
    assert.equal(r.overall, 88.9);
    assert.equal(r.dims.tone, 83);
    assert.equal(r.dims.emotion, 76);
    assert.equal(r.words[0].pinyin, 'jin');
    assert.equal(r.sentences[0].details.length, 6);
    assert.equal(normalizeEvalResult(raw).coreType, 'sentence');
  });

  for (const [file, coreType, overall, words, sentences] of [
    ['compat_word.eval.json', 'word.eval', 65, 1, 0],
    ['compat_sent.eval.json', 'sent.eval', 93.9, 9, 1],
    ['compat_sent.eval.cn.json', 'sent.eval.cn', 94.6, 6, 1],
    ['compat_sent_eval_cn.json', 'sent.eval.cn', 94.6, 6, 1],
    ['compat_para.eval.cn.json', 'para.eval.cn', 89.8, 0, 2],
  ]) {
    test(`compat ${coreType} (${file})`, () => {
      const { raw, r } = parse(file, { mode: 'compat', coreType });
      assert.equal(r.mode, 'compat');
      assert.equal(r.coreType, coreType);
      assert.equal(r.overall, overall);
      assert.equal(r.words.length, words);
      assert.equal(r.sentences.length, sentences);
      assert.equal(r.recordId, raw.recordId);
      assert.equal(r.dims.fluency, raw.result.fluency);
      assert.equal(r.eof, 1);
    });
  }
});

describe('WebSocket result frames', () => {
  test('native frame with event result (ws_native_sentence_frames.json)', () => {
    const frames = readJson('platform/ws_native_sentence_frames.json');
    const resultFrame = frames.find((f) => f.frame.event === 'result').frame;
    const r = normalizeEvalResult(resultFrame, { mode: 'native', coreType: 'sentence', idempotencyKey: 'k', replayed: false });
    assert.equal(r.overall, 93.7);
    assert.equal(r.idempotencyKey, 'k');
    assert.equal(r.replayed, false);
    assert.equal(frames.filter((f) => f.frame.event === 'error' && f.frame.message === 'unknown cmd').length, 1);
  });
  test('compat frame (ws_compat_sent_eval_cn_frames.json)', () => {
    const frames = readJson('platform/ws_compat_sent_eval_cn_frames.json');
    const final = frames.find((f) => f.frame.eof === 1).frame;
    const r = normalizeEvalResult({ ...final, replayed: true }, { mode: 'compat' });
    assert.equal(r.overall, 94.6);
    assert.equal(r.replayed, true);
  });
});

describe('warnings and helpers', () => {
  test('numbers, numeric strings, objects; deduplicated; unknown codes kept', () => {
    const w = normalizeWarnings([1002, '1005', { code: 1001, message: 'srv' }], [1002, { code: 4242 }, { nope: 1 }, null], null);
    assert.deepEqual(w.map((x) => [x.code, x.message]), [[1002, 'Audio volume too low!'], [1005, 'Audio not complete!'], [1001, 'srv'], [4242, '']]);
    const r = normalizeEvalResult({ result: { overall: '88', warning: [1002] }, warnings: [1009] });
    assert.deepEqual(r.warnings.map((x) => x.code), [1002, 1009]);
    assert.equal(r.overall, 88);
  });
  test('missing or odd input never throws', () => {
    const r = normalizeEvalResult(null);
    assert.equal(r.overall, null);
    assert.deepEqual(r.words, []);
    assert.equal(r.mode, 'native');
    assert.equal(normalizeEvalResult({ result: { overall: true, duration: 'x' } }).overall, null);
    assert.equal(normalizeEvalResult({ recordId: 5 }).recordId, '5');
    assert.equal(normalizeEvalResult({ standardAudio: { url: '/audio/a.wav' } }).standardAudio.url, '/audio/a.wav');
    assert.equal(normalizeEvalResult({ result: { yuguScores: { reading_skill: 70 } } }).dims.readingSkill, 70);
  });
  test('TTS envelope and URL resolution (tts_generate.json)', () => {
    const raw = readJson('platform/tts_generate.json');
    const t = normalizeTtsResult(raw, { baseUrl: 'https://open.shengzhiai.com', idempotencyKey: 'k', replayed: true });
    assert.equal(t.audioUrl, raw.data.audioUrl);
    assert.equal(t.absoluteUrl, raw.data.audioUrl);
    assert.equal(t.duration, '1.348');
    assert.equal(t.durationSeconds, 1.348);
    assert.equal(t.format, 'mp3');
    assert.equal(t.replayed, true);
    assert.equal(resolveTtsUrl('https://h', '/audio/x.mp3'), 'https://h/tts/audio/x.mp3');
    assert.equal(resolveTtsUrl('https://h', '/media/x.mp3'), 'https://h/media/x.mp3');
    assert.equal(resolveTtsUrl('https://h', 'x.mp3'), 'https://h/x.mp3');
    assert.equal(resolveTtsUrl('https://h', ''), '');
    assert.equal(normalizeTtsResult(null).audioUrl, '');
  });
  test('constants', () => {
    assert.equal(NATIVE_CORE_TYPES.length, 7);
    assert.equal(COMPAT_CORE_TYPES.length, 10);
    assert.equal(WARNING_MESSAGES[1001], 'No valid audio detected!');
  });
});
